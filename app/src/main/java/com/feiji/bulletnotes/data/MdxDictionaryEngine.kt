package com.feiji.bulletnotes.data

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.text.Collator
import java.text.Normalizer
import java.util.Locale
import java.util.zip.Inflater

/**
 * 纯本地离线 MDict (.mdx) 词典解析与极速查词引擎（零第三方依赖）：
 * - 完整支持 MDict v1.2 与 v2.0 格式；
 * - 支持 v2.0 索引块加密（RIPEMD-128 + fast_decrypt）、zlib / LZO1X / 未压缩块；
 * - 支持 UTF-8 / UTF-16LE / GB18030 / GBK / Big5 编码与 StyleSheet 样式宏展开；
 * - 启动时仅加载词头块索引与记录块偏移表（常驻内存 < 2MB），查词按需随机读取单块解压；
 * - 支持 @@@LINK= 自动重定向、中文词头规范化与多音字/同形词合并、英文常见词形还原回退。
 */
class MdxDictionaryEngine private constructor(
    private val file: File,
    val title: String,
    val version: Float,
    val numEntries: Long,
    private val charset: Charset,
    private val numberWidth: Int,
    private val keyBlocksStartOffset: Long,
    private val keyBlocks: List<KeyBlockMeta>,
    private val recordDataStartOffset: Long,
    private val recordBlocks: List<RecordBlockMeta>,
    private val totalRecordDecompSize: Long,
    private val stripKey: Boolean,
    private val styleSheet: Map<String, Pair<String, String>>
) : Closeable {

    private enum class BlockSortOrder {
        UNICODE_STRIPPED,
        UNICODE_LOWER,
        CHARSET_BYTES,
        UTF8_BYTES,
        ZH_PINYIN,
        UNKNOWN
    }

    data class LookupMatch(
        val queriedWord: String,
        val matchedWord: String,
        val html: String
    )

    private data class KeyBlockMeta(
        val index: Int,
        val compOffset: Long,
        val compSize: Long,
        val decompSize: Long,
        val entryCount: Long,
        val firstWord: String,
        val lastWord: String,
        val firstLower: String,
        val lastLower: String,
        val firstStripped: String,
        val lastStripped: String,
        val firstRawBytes: ByteArray,
        val lastRawBytes: ByteArray,
        val firstUtf8Bytes: ByteArray,
        val lastUtf8Bytes: ByteArray
    )

    private data class RecordBlockMeta(
        val compOffset: Long,
        val compSize: Long,
        val decompOffset: Long,
        val decompSize: Long
    )

    private data class KeyEntry(
        val recordOffset: Long,
        val word: String
    )

    private class FullKeyIndex(
        val lowerToBlocks: HashMap<String, IntArray>,
        val strippedToBlocks: HashMap<String, IntArray>,
        val cjkBaseToBlocks: HashMap<String, IntArray>,
        val sortedHeadwords: Array<String>,
        val sortedHeadwordsLower: Array<String>
    )

    private val lock = Any()
    private var raf: RandomAccessFile? = RandomAccessFile(file, "r")
    private val zhCollator: Collator = Collator.getInstance(Locale.CHINA).apply {
        strength = Collator.PRIMARY
    }

    private val totalKeyDecompSize: Long = keyBlocks.sumOf { it.decompSize }
    private val hasCjkHeadwords: Boolean = containsCjk(title) || keyBlocks.any {
        containsCjk(it.firstWord) || containsCjk(it.lastWord)
    }
    private val sortOrderInfo: Pair<BlockSortOrder, List<Int>> = detectSortOrder(keyBlocks, zhCollator)
    private val primarySortOrder: BlockSortOrder = sortOrderInfo.first
    private val outOfOrderBlocks: List<Int> = sortOrderInfo.second

    @Volatile
    private var fullKeyIndex: FullKeyIndex? = null

    // 最近访问的少量词头块缓存，避免连续查词重复解压
    private val keyBlockCache = object : LinkedHashMap<Int, List<KeyEntry>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<KeyEntry>>?): Boolean {
            return size > 16
        }
    }

    override fun close() {
        synchronized(lock) {
            runCatching { raf?.close() }
            raf = null
            keyBlockCache.clear()
            fullKeyIndex = null
        }
    }

    fun lookup(rawQuery: String): LookupMatch? {
        val cleaned = cleanBasicQuery(rawQuery)
        if (cleaned.isEmpty()) return null

        val candidates = buildLookupCandidates(cleaned)
        for (candidate in candidates) {
            val resolved = lookupExactWithRedirect(candidate, maxHops = 5)
            if (resolved != null) {
                return LookupMatch(
                    queriedWord = cleaned,
                    matchedWord = resolved.first,
                    html = resolved.second
                )
            }
        }
        return null
    }

    /**
     * 前缀联想搜索：
     * - 若已建立全量词头索引（中文/非标准排序词典），直接 O(log N) 二分检索；
     * - 否则按检测到的真实排序规则（GBK 字节序 / 拼音序 / UTF-8 / Unicode）定位起始块并扫描。
     */
    fun suggestPrefix(rawPrefix: String, limit: Int = 12): List<String> {
        val cleaned = cleanBasicQuery(rawPrefix)
        if (cleaned.isEmpty() || keyBlocks.isEmpty() || limit <= 0) return emptyList()

        val prefixLower = cleaned.lowercase(Locale.ROOT)
        val prefixStripped = stripForKey(cleaned)

        // 若已构建全量索引，或当前为非单调排序/中文词典且索引体量适中，直接二分全量词头
        val existingIdx = fullKeyIndex ?: if (
            (primarySortOrder == BlockSortOrder.UNKNOWN || containsCjk(cleaned)) &&
            totalKeyDecompSize <= 96L * 1024 * 1024
        ) {
            ensureFullKeyIndex()
        } else {
            null
        }

        if (existingIdx != null) {
            return suggestFromFullIndex(existingIdx, prefixLower, limit)
        }

        val startBlockIdx = findPrefixStartBlockIndex(cleaned, prefixLower, prefixStripped)
        if (startBlockIdx !in keyBlocks.indices) return emptyList()

        val exactPrefixHits = LinkedHashSet<String>(limit)
        val strippedPrefixHits = LinkedHashSet<String>(limit)
        val seenLower = HashSet<String>(limit * 2)

        val firstScan = (startBlockIdx - 1).coerceAtLeast(0)
        val lastScan = (startBlockIdx + 5).coerceAtMost(keyBlocks.lastIndex)

        for (bIdx in firstScan..lastScan) {
            val block = keyBlocks[bIdx]
            val entries = readKeyBlockEntries(block)
            var passedBlock = false
            for (entry in entries) {
                val word = entry.word.trim()
                if (word.isEmpty()) continue
                val wordLower = word.lowercase(Locale.ROOT)
                val wordStripped = if (stripKey && prefixStripped.isNotEmpty()) stripForKey(word) else ""

                val isExactPrefix = wordLower.startsWith(prefixLower)
                val isStrippedPrefix = !isExactPrefix &&
                    prefixStripped.isNotEmpty() &&
                    wordStripped.startsWith(prefixStripped)

                if (isExactPrefix || isStrippedPrefix) {
                    if (seenLower.add(wordLower)) {
                        if (isExactPrefix) {
                            exactPrefixHits.add(word)
                        } else {
                            strippedPrefixHits.add(word)
                        }
                        if (exactPrefixHits.size >= limit) break
                    }
                } else if (exactPrefixHits.isNotEmpty() || strippedPrefixHits.isNotEmpty()) {
                    val pastLower = wordLower > prefixLower && !wordLower.startsWith(prefixLower)
                    val pastStripped = prefixStripped.isEmpty() ||
                        (wordStripped > prefixStripped && !wordStripped.startsWith(prefixStripped))
                    if (pastLower && pastStripped &&
                        (primarySortOrder == BlockSortOrder.UNICODE_LOWER || primarySortOrder == BlockSortOrder.UNICODE_STRIPPED)
                    ) {
                        passedBlock = true
                        break
                    }
                }
            }
            if (exactPrefixHits.size >= limit || passedBlock) break
        }

        val combined = ArrayList<String>(limit)
        for (w in exactPrefixHits) {
            if (combined.size < limit) combined.add(w)
        }
        for (w in strippedPrefixHits) {
            if (combined.size < limit) combined.add(w)
        }
        return combined
    }

    private fun suggestFromFullIndex(
        index: FullKeyIndex,
        prefixLower: String,
        limit: Int
    ): List<String> {
        val lowers = index.sortedHeadwordsLower
        val words = index.sortedHeadwords
        if (lowers.isEmpty()) return emptyList()

        // 二分查找首个 >= prefixLower 的位置
        var lo = 0
        var hi = lowers.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (lowers[mid] < prefixLower) {
                lo = mid + 1
            } else {
                hi = mid
            }
        }

        val out = ArrayList<String>(limit)
        var i = lo
        while (i < lowers.size && out.size < limit) {
            if (!lowers[i].startsWith(prefixLower)) break
            out.add(words[i])
            i++
        }
        return out
    }

    private fun findPrefixStartBlockIndex(
        rawPrefix: String,
        prefixLower: String,
        prefixStripped: String
    ): Int {
        if (keyBlocks.isEmpty()) return -1
        val prefixCharsetBytes = runCatching { rawPrefix.toByteArray(charset) }.getOrNull()
        val prefixUtf8Bytes = rawPrefix.toByteArray(Charsets.UTF_8)

        when (primarySortOrder) {
            BlockSortOrder.CHARSET_BYTES -> {
                if (prefixCharsetBytes != null) {
                    for (i in keyBlocks.indices) {
                        val b = keyBlocks[i]
                        if (compareUnsignedBytes(b.lastRawBytes, prefixCharsetBytes) >= 0 ||
                            b.lastLower.startsWith(prefixLower)
                        ) {
                            return i
                        }
                    }
                }
            }
            BlockSortOrder.UTF8_BYTES -> {
                for (i in keyBlocks.indices) {
                    val b = keyBlocks[i]
                    if (compareUnsignedBytes(b.lastUtf8Bytes, prefixUtf8Bytes) >= 0 ||
                        b.lastLower.startsWith(prefixLower)
                    ) {
                        return i
                    }
                }
            }
            BlockSortOrder.ZH_PINYIN -> {
                synchronized(zhCollator) {
                    for (i in keyBlocks.indices) {
                        val b = keyBlocks[i]
                        if (zhCollator.compare(b.lastWord, rawPrefix) >= 0 ||
                            b.lastLower.startsWith(prefixLower)
                        ) {
                            return i
                        }
                    }
                }
            }
            else -> {
                if (stripKey && prefixStripped.isNotEmpty()) {
                    for (i in keyBlocks.indices) {
                        val b = keyBlocks[i]
                        if (b.lastStripped >= prefixStripped || b.lastStripped.startsWith(prefixStripped)) {
                            return i
                        }
                    }
                }
                for (i in keyBlocks.indices) {
                    val b = keyBlocks[i]
                    if (b.lastLower >= prefixLower || b.lastLower.startsWith(prefixLower)) {
                        return i
                    }
                }
            }
        }
        return -1
    }

    private fun lookupExactWithRedirect(word: String, maxHops: Int): Pair<String, String>? {
        val hits = lookupAllMatchingEntries(word)
        if (hits.isEmpty()) return null

        val primaryWord = hits.first().first
        val resolvedHtmls = LinkedHashSet<String>()
        for ((_, rawHtml) in hits) {
            val resolvedList = resolveRedirectIfNeeded(rawHtml, word, maxHops)
            for (item in resolvedList) {
                if (item.isNotBlank()) {
                    resolvedHtmls.add(item)
                }
            }
        }
        if (resolvedHtmls.isEmpty()) return null
        val combinedHtml = resolvedHtmls.joinToString("\n<hr class=\"mdx-multi-sep\"/>\n")
        return primaryWord to combinedHtml
    }

    private fun resolveRedirectIfNeeded(
        initialHtml: String,
        originWord: String,
        maxHops: Int
    ): List<String> {
        var currentList = listOf(stripLeadingBomAndNull(initialHtml))
        val visited = HashSet<String>()
        visited.add(originWord.lowercase(Locale.ROOT))

        repeat(maxHops) {
            val nextList = ArrayList<String>(currentList.size)
            var followedAny = false
            for (html in currentList) {
                val trimmed = stripLeadingBomAndNull(html)
                if (!trimmed.startsWith("@@@LINK=", ignoreCase = true)) {
                    if (trimmed.isNotEmpty()) nextList.add(trimmed)
                    continue
                }
                val target = trimmed.substring(8)
                    .lineSequence()
                    .firstOrNull()
                    ?.let { trimHeadword(it) }
                    .orEmpty()
                if (target.isEmpty()) continue
                val norm = target.lowercase(Locale.ROOT)
                if (!visited.add(norm)) continue
                val nextHits = lookupAllMatchingEntries(target)
                if (nextHits.isNotEmpty()) {
                    followedAny = true
                    for ((_, nextHtml) in nextHits) {
                        val cleanedNext = stripLeadingBomAndNull(nextHtml)
                        if (cleanedNext.isNotEmpty()) nextList.add(cleanedNext)
                    }
                }
            }
            if (!followedAny) {
                return nextList.filterNot { it.startsWith("@@@LINK=", ignoreCase = true) }
            }
            currentList = nextList
        }
        return currentList.filterNot { it.startsWith("@@@LINK=", ignoreCase = true) }
    }

    private fun lookupAllMatchingEntries(word: String): List<Pair<String, String>> {
        if (keyBlocks.isEmpty()) return emptyList()
        val normalizedWord = Normalizer.normalize(word, Normalizer.Form.NFC)
        val targetLower = normalizedWord.lowercase(Locale.ROOT)
        val targetStripped = stripForKey(normalizedWord)
        val isCjkQuery = containsCjk(normalizedWord)
        val targetCjkBase = if (isCjkQuery) extractCjkBaseKey(normalizedWord) else ""

        // 1. 若已存在全量索引，直接 O(1) 定位词头块（依次尝试精确/忽略大小写 -> 去标点空白 -> 中文同形词/编号词头）
        fullKeyIndex?.let { idx ->
            val directBlocks = idx.lowerToBlocks[targetLower]
            if (directBlocks != null) {
                val found = extractMatchesFromBlocks(directBlocks.map { keyBlocks[it] }, normalizedWord, targetStripped, targetCjkBase)
                if (found.isNotEmpty()) return found
            }
            if (targetStripped.isNotEmpty()) {
                val strippedBlocks = idx.strippedToBlocks[targetStripped]
                if (strippedBlocks != null) {
                    val found = extractMatchesFromBlocks(strippedBlocks.map { keyBlocks[it] }, normalizedWord, targetStripped, targetCjkBase)
                    if (found.isNotEmpty()) return found
                }
            }
            if (targetCjkBase.isNotEmpty()) {
                val baseBlocks = idx.cjkBaseToBlocks[targetCjkBase]
                if (baseBlocks != null) {
                    val found = extractMatchesFromBlocks(baseBlocks.map { keyBlocks[it] }, normalizedWord, targetStripped, targetCjkBase)
                    if (found.isNotEmpty()) return found
                }
            }
            return emptyList()
        }

        // 2. 快速多排序通道（Unicode / stripKey / 原编码字节序[GBK等] / UTF-8字节序 / 中文拼音序）
        val candidateBlocks = findCandidateKeyBlocks(normalizedWord, targetLower, targetStripped)
        val fastHits = extractMatchesFromBlocks(candidateBlocks, normalizedWord, targetStripped, targetCjkBase)
        if (fastHits.isNotEmpty()) return fastHits

        // 3. 兜底：若为中文查询或非单调排序词典（如部首/笔画/多音字拼音/多分区/带编号词头），
        //    懒加载构建一次 O(1) 全量词头索引（超大词典则流式顺序扫描），确保任何中文单字与词语 100% 查得到！
        val shouldBuildFallback = primarySortOrder == BlockSortOrder.UNKNOWN ||
            isCjkQuery ||
            keyBlocks.size <= 32

        if (shouldBuildFallback) {
            if (totalKeyDecompSize <= 96L * 1024 * 1024) {
                val idx = ensureFullKeyIndex() ?: return emptyList()
                val blockIds = idx.lowerToBlocks[targetLower]
                    ?: targetStripped.takeIf { it.isNotEmpty() }?.let { idx.strippedToBlocks[it] }
                    ?: targetCjkBase.takeIf { it.isNotEmpty() }?.let { idx.cjkBaseToBlocks[it] }
                if (blockIds != null) {
                    return extractMatchesFromBlocks(blockIds.map { keyBlocks[it] }, normalizedWord, targetStripped, targetCjkBase)
                }
            } else {
                // 超大词典兜底：逐块顺序扫描（流式不常驻内存）
                return scanAllBlocksStreaming(normalizedWord, targetStripped, targetCjkBase)
            }
        }
        return emptyList()
    }

    private fun scanAllBlocksStreaming(
        word: String,
        targetStripped: String,
        targetCjkBase: String
    ): List<Pair<String, String>> {
        val exactMatches = ArrayList<Pair<String, String>>(2)
        val fallbackMatches = ArrayList<Pair<String, String>>(2)
        for (block in keyBlocks) {
            val hits = extractMatchesFromBlocks(listOf(block), word, targetStripped, targetCjkBase)
            for (hit in hits) {
                if (hit.first == word || hit.first.equals(word, ignoreCase = true)) {
                    if (exactMatches.size < 6) exactMatches.add(hit)
                } else {
                    if (fallbackMatches.size < 6) fallbackMatches.add(hit)
                }
            }
            if (exactMatches.size >= 6) break
        }
        return if (exactMatches.isNotEmpty()) exactMatches else fallbackMatches
    }

    private fun extractMatchesFromBlocks(
        blocks: List<KeyBlockMeta>,
        word: String,
        targetStripped: String,
        targetCjkBase: String
    ): List<Pair<String, String>> {
        val exactMatches = ArrayList<Pair<String, String>>(2)
        val ciMatches = ArrayList<Pair<String, String>>(2)
        val strippedMatches = ArrayList<Pair<String, String>>(2)
        val cjkBaseMatches = ArrayList<Pair<String, String>>(2)

        for (block in blocks) {
            val entries = runCatching { readKeyBlockEntries(block) }.getOrNull() ?: continue
            for (i in entries.indices) {
                val entry = entries[i]
                val w = entry.word
                if (w.isEmpty()) continue
                val matchTier = when {
                    w == word -> 0
                    w.equals(word, ignoreCase = true) -> 1
                    targetStripped.isNotEmpty() && (stripKey || containsCjk(word)) && stripForKey(w) == targetStripped -> 2
                    targetCjkBase.isNotEmpty() && extractCjkBaseKey(w) == targetCjkBase -> 3
                    else -> -1
                }
                if (matchTier >= 0) {
                    val nextOffset = resolveEntryEndRecordOffset(block.index, entries, i)
                    val html = runCatching { readRecordSlice(entry.recordOffset, nextOffset) }.getOrDefault("")
                    if (html.isBlank()) continue
                    val pair = w to html
                    when (matchTier) {
                        0 -> if (exactMatches.size < 6) exactMatches.add(pair)
                        1 -> if (ciMatches.size < 6) ciMatches.add(pair)
                        2 -> if (strippedMatches.size < 6) strippedMatches.add(pair)
                        3 -> if (cjkBaseMatches.size < 6) cjkBaseMatches.add(pair)
                    }
                }
            }
        }

        return when {
            exactMatches.isNotEmpty() -> exactMatches
            ciMatches.isNotEmpty() -> ciMatches
            strippedMatches.isNotEmpty() -> strippedMatches
            else -> cjkBaseMatches
        }
    }

    private fun ensureFullKeyIndex(): FullKeyIndex? {
        fullKeyIndex?.let { return it }
        synchronized(lock) {
            fullKeyIndex?.let { return it }
            val reader = raf ?: return null
            val initCap = numEntries.toInt().coerceIn(1024, 250_000)
            val lowerMap = HashMap<String, MutableList<Int>>(initCap)
            val strippedMap = HashMap<String, MutableList<Int>>()
            val cjkBaseMap = HashMap<String, MutableList<Int>>()
            val uniqueHeadwords = LinkedHashMap<String, String>(initCap)

            for (block in keyBlocks) {
                val entries = keyBlockCache[block.index] ?: runCatching {
                    val compBytes = ByteArray(block.compSize.toInt())
                    reader.seek(keyBlocksStartOffset + block.compOffset)
                    reader.readFully(compBytes)
                    val decomp = decompressBlock(compBytes, block.decompSize.toInt())
                    parseKeyBlockEntries(decomp, numberWidth, charset)
                }.getOrNull() ?: continue

                for (entry in entries) {
                    val w = entry.word
                    if (w.isEmpty()) continue
                    val low = w.lowercase(Locale.ROOT)
                    val list = lowerMap.getOrPut(low) { ArrayList(1) }
                    if (list.lastOrNull() != block.index && list.size < 6) {
                        list.add(block.index)
                    }
                    if (!uniqueHeadwords.containsKey(low)) {
                        uniqueHeadwords[low] = w
                    }

                    val stripped = stripForKey(w)
                    if (stripped.isNotEmpty() && stripped != low && (stripKey || containsCjk(w))) {
                        val sList = strippedMap.getOrPut(stripped) { ArrayList(1) }
                        if (sList.lastOrNull() != block.index && sList.size < 6) {
                            sList.add(block.index)
                        }
                    }

                    if (containsCjk(w)) {
                        val cjkBase = extractCjkBaseKey(w)
                        if (cjkBase.isNotEmpty() && cjkBase != low && cjkBase != stripped) {
                            val bList = cjkBaseMap.getOrPut(cjkBase) { ArrayList(1) }
                            if (bList.lastOrNull() != block.index && bList.size < 6) {
                                bList.add(block.index)
                            }
                        }
                    }
                }
            }

            val lowerArrMap = HashMap<String, IntArray>(lowerMap.size)
            for ((k, v) in lowerMap) {
                lowerArrMap[k] = v.toIntArray()
            }
            val strippedArrMap = HashMap<String, IntArray>(strippedMap.size)
            for ((k, v) in strippedMap) {
                strippedArrMap[k] = v.toIntArray()
            }
            val cjkBaseArrMap = HashMap<String, IntArray>(cjkBaseMap.size)
            for ((k, v) in cjkBaseMap) {
                cjkBaseArrMap[k] = v.toIntArray()
            }

            val pairs = uniqueHeadwords.entries.sortedBy { it.key }
            val sortedWords = Array(pairs.size) { pairs[it].value }
            val sortedLowers = Array(pairs.size) { pairs[it].key }

            val built = FullKeyIndex(
                lowerToBlocks = lowerArrMap,
                strippedToBlocks = strippedArrMap,
                cjkBaseToBlocks = cjkBaseArrMap,
                sortedHeadwords = sortedWords,
                sortedHeadwordsLower = sortedLowers
            )
            fullKeyIndex = built
            return built
        }
    }

    private fun findCandidateKeyBlocks(
        rawWord: String,
        targetLower: String,
        targetStripped: String
    ): List<KeyBlockMeta> {
        val result = ArrayList<KeyBlockMeta>(6)
        val added = HashSet<Int>(12)

        fun addBlock(idx: Int) {
            if (idx in keyBlocks.indices && added.add(idx)) {
                result.add(keyBlocks[idx])
            }
        }

        // 1. 按标准 stripKey 范围快速扫描
        if (targetStripped.isNotEmpty()) {
            for (block in keyBlocks) {
                if (targetStripped >= block.firstStripped && targetStripped <= block.lastStripped) {
                    addBlock(block.index)
                }
            }
        }

        // 2. 按普通小写 Unicode 范围匹配
        for (block in keyBlocks) {
            if (targetLower >= block.firstLower && targetLower <= block.lastLower) {
                addBlock(block.index)
            }
        }

        // 3. 按词典原生编码（GBK / Big5 / UTF-8 / UTF-16LE）无符号字节序匹配（修复 GBK 排序中文词典）
        val targetCharsetBytes = runCatching { rawWord.toByteArray(charset) }.getOrNull()
        val targetLowerCharsetBytes = runCatching { targetLower.toByteArray(charset) }.getOrNull()
        if (targetCharsetBytes != null) {
            for (block in keyBlocks) {
                if (compareUnsignedBytes(targetCharsetBytes, block.firstRawBytes) >= 0 &&
                    compareUnsignedBytes(targetCharsetBytes, block.lastRawBytes) <= 0
                ) {
                    addBlock(block.index)
                } else if (
                    targetLowerCharsetBytes != null &&
                    compareUnsignedBytes(targetLowerCharsetBytes, block.firstRawBytes) >= 0 &&
                    compareUnsignedBytes(targetLowerCharsetBytes, block.lastRawBytes) <= 0
                ) {
                    addBlock(block.index)
                }
            }
        }

        // 4. 按 UTF-8 无符号字节序匹配
        val targetUtf8Bytes = rawWord.toByteArray(Charsets.UTF_8)
        for (block in keyBlocks) {
            if (compareUnsignedBytes(targetUtf8Bytes, block.firstUtf8Bytes) >= 0 &&
                compareUnsignedBytes(targetUtf8Bytes, block.lastUtf8Bytes) <= 0
            ) {
                addBlock(block.index)
            }
        }

        // 5. 若包含中文，按中文拼音 Collator 序匹配（修复 MdxBuilder 按拼音排序的中文词典）
        if (containsCjk(rawWord) && hasCjkHeadwords) {
            synchronized(zhCollator) {
                for (block in keyBlocks) {
                    if (zhCollator.compare(rawWord, block.firstWord) >= 0 &&
                        zhCollator.compare(rawWord, block.lastWord) <= 0
                    ) {
                        addBlock(block.index)
                    }
                }
            }
        }

        // 6. 顺带纳入少量分区边界/乱序块（如前言/凡例所在块）
        if (outOfOrderBlocks.size <= 12) {
            for (idx in outOfOrderBlocks) {
                addBlock(idx)
            }
        }

        // 7. 若仍未命中，按检测到的主排序规则做二分定位并检查邻近块
        if (result.isEmpty() && primarySortOrder != BlockSortOrder.UNKNOWN) {
            var lo = 0
            var hi = keyBlocks.lastIndex
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val b = keyBlocks[mid]
                val cmp = when (primarySortOrder) {
                    BlockSortOrder.UNICODE_STRIPPED -> targetStripped.compareTo(b.firstStripped)
                    BlockSortOrder.UNICODE_LOWER -> targetLower.compareTo(b.firstLower)
                    BlockSortOrder.CHARSET_BYTES -> {
                        if (targetCharsetBytes != null) {
                            compareUnsignedBytes(targetCharsetBytes, b.firstRawBytes)
                        } else {
                            targetLower.compareTo(b.firstLower)
                        }
                    }
                    BlockSortOrder.UTF8_BYTES -> compareUnsignedBytes(targetUtf8Bytes, b.firstUtf8Bytes)
                    BlockSortOrder.ZH_PINYIN -> synchronized(zhCollator) {
                        zhCollator.compare(rawWord, b.firstWord)
                    }
                    BlockSortOrder.UNKNOWN -> 0
                }
                if (cmp < 0) {
                    hi = mid - 1
                } else {
                    lo = mid + 1
                }
            }
            addBlock(hi)
            addBlock(hi + 1)
            addBlock(hi - 1)
        }

        return result
    }

    /**
     * 查找当前词条对应的真实记录结束偏移：
     * 兼容同形/别名词条共享同一 recordOffset 的 MDX（跳过相等偏移直到找到下一个更大的偏移）。
     */
    private fun resolveEntryEndRecordOffset(
        blockIndex: Int,
        entries: List<KeyEntry>,
        entryIndex: Int
    ): Long {
        val currentOffset = entries[entryIndex].recordOffset
        for (j in (entryIndex + 1) until entries.size) {
            if (entries[j].recordOffset > currentOffset) {
                return entries[j].recordOffset
            }
        }
        var nextBlockIdx = blockIndex + 1
        while (nextBlockIdx in keyBlocks.indices) {
            val nextEntries = runCatching { readKeyBlockEntries(keyBlocks[nextBlockIdx]) }.getOrNull()
                ?: break
            for (nextEntry in nextEntries) {
                if (nextEntry.recordOffset > currentOffset) {
                    return nextEntry.recordOffset
                }
            }
            nextBlockIdx++
        }
        return totalRecordDecompSize
    }

    private fun readKeyBlockEntries(block: KeyBlockMeta): List<KeyEntry> {
        synchronized(lock) {
            keyBlockCache[block.index]?.let { return it }
            val reader = raf ?: return emptyList()
            val compBytes = ByteArray(block.compSize.toInt())
            reader.seek(keyBlocksStartOffset + block.compOffset)
            reader.readFully(compBytes)
            val decomp = decompressBlock(compBytes, block.decompSize.toInt())
            val entries = parseKeyBlockEntries(decomp, numberWidth, charset)
            keyBlockCache[block.index] = entries
            return entries
        }
    }

    private fun readRecordSlice(startOffset: Long, endOffset: Long): String {
        val safeEnd = endOffset.coerceAtMost(totalRecordDecompSize)
        val totalLen = (safeEnd - startOffset).toInt()
        if (totalLen <= 0 || recordBlocks.isEmpty()) return ""

        synchronized(lock) {
            val reader = raf ?: return ""
            // 二分查找包含 startOffset 的记录块
            var lo = 0
            var hi = recordBlocks.lastIndex
            var blockIdx = 0
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val rb = recordBlocks[mid]
                if (startOffset < rb.decompOffset) {
                    hi = mid - 1
                } else if (startOffset >= rb.decompOffset + rb.decompSize) {
                    lo = mid + 1
                } else {
                    blockIdx = mid
                    break
                }
            }

            val out = ByteArrayOutputStream(totalLen)
            var cursor = startOffset
            while (cursor < safeEnd && blockIdx in recordBlocks.indices) {
                val rb = recordBlocks[blockIdx]
                val compBytes = ByteArray(rb.compSize.toInt())
                reader.seek(recordDataStartOffset + rb.compOffset)
                reader.readFully(compBytes)
                val decomp = decompressBlock(compBytes, rb.decompSize.toInt())

                val localStart = (cursor - rb.decompOffset).toInt().coerceIn(0, decomp.size)
                val needed = (safeEnd - cursor).toInt()
                val available = (decomp.size - localStart).coerceAtLeast(0)
                val toCopy = minOf(needed, available)
                if (toCopy <= 0) break
                out.write(decomp, localStart, toCopy)
                cursor += toCopy
                blockIdx++
            }

            val rawText = String(out.toByteArray(), charset).trim('\u0000', '\uFEFF')
            return if (styleSheet.isNotEmpty() && rawText.contains('`') && !rawText.trimStart().startsWith("@@@LINK=", ignoreCase = true)) {
                substituteStylesheet(rawText, styleSheet)
            } else {
                rawText
            }
        }
    }

    companion object {
        private val headerAttrRegex = Regex("""(\w+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
        private val stripKeyRegex = Regex("""[\s\u00A0\u3000\u200B-\u200D\uFEFF\p{P}]+""")
        private val cjkTrailingDisambigRegex = Regex(
            """(?:\s*(?:\([^)]{1,24}\)|（[^）]{1,24}）|\[[^\]]{1,24}]|【[^】]{1,24}】|[0-9０-９①-⑳⑴-⒇¹²³⁴⁵⁶⁷⁸⁹⁰]+|\s+[a-zA-Zāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜü]+))+$"""
        )

        fun open(file: File): MdxDictionaryEngine {
            require(file.exists() && file.isFile && file.length() > 16) {
                "无效的 MDX 文件"
            }
            RandomAccessFile(file, "r").use { raf ->
                // 1. 解析头部 XML
                val headerByteLen = raf.readInt()
                require(headerByteLen in 2..(4 * 1024 * 1024)) {
                    "MDX 文件头格式异常"
                }
                val headerBytes = ByteArray(headerByteLen)
                raf.readFully(headerBytes)
                raf.skipBytes(4) // header adler32

                val headerXml = String(headerBytes, Charsets.UTF_16LE).trimEnd('\u0000')
                val attrs = HashMap<String, String>()
                headerAttrRegex.findAll(headerXml).forEach { m ->
                    val attrKey = m.groupValues[1].lowercase(Locale.ROOT)
                    val rawVal = if (m.groups[2] != null) m.groupValues[2] else m.groupValues[3]
                    attrs[attrKey] = unescapeHtmlAttr(rawVal)
                }

                val versionStr = attrs["generatedbyengineversion"]
                    ?: attrs["requiredengineversion"]
                    ?: "2.0"
                val version = versionStr.toFloatOrNull() ?: 2.0f
                val isV2 = version >= 2.0f
                val numberWidth = if (isV2) 8 else 4

                val encodingName = attrs["encoding"].orEmpty().trim().uppercase(Locale.ROOT)
                val charset = resolveMdxCharset(encodingName)

                val encryptedRaw = attrs["encrypted"].orEmpty().trim()
                val encryptedFlags = when {
                    encryptedRaw.equals("Yes", ignoreCase = true) -> 1
                    encryptedRaw.equals("No", ignoreCase = true) || encryptedRaw.isEmpty() -> 0
                    else -> encryptedRaw.toIntOrNull() ?: 0
                }

                val stripKey = !attrs["stripkey"].equals("No", ignoreCase = true)
                val styleSheet = parseStyleSheet(attrs["stylesheet"].orEmpty())
                val rawTitle = attrs["title"].orEmpty().trim()
                val title = if (
                    rawTitle.isBlank() ||
                    rawTitle.equals("Title (No HTML code allowed)", ignoreCase = true)
                ) {
                    file.nameWithoutExtension
                } else {
                    rawTitle
                }

                // 2. 解析词头区头部
                val keyHeaderLen = if (isV2) 40 else 16
                val keyHeaderBytes = ByteArray(keyHeaderLen)
                raf.readFully(keyHeaderBytes)
                if (isV2) {
                    raf.skipBytes(4) // key header adler32
                }

                val khBuf = ByteBuffer.wrap(keyHeaderBytes).order(ByteOrder.BIG_ENDIAN)
                val numKeyBlocks = readNumber(khBuf, numberWidth)
                val numEntries = readNumber(khBuf, numberWidth)
                val keyInfoDecompSize = if (isV2) readNumber(khBuf, numberWidth) else -1L
                val keyInfoCompSize = readNumber(khBuf, numberWidth)
                val keyBlocksTotalSize = readNumber(khBuf, numberWidth)

                require(numKeyBlocks in 0..5_000_000 && keyInfoCompSize in 0..(64L * 1024 * 1024)) {
                    "MDX 词头索引损坏或不受支持"
                }

                val keyInfoCompBytes = ByteArray(keyInfoCompSize.toInt())
                raf.readFully(keyInfoCompBytes)

                val keyInfoBytes = if (isV2) {
                    if ((encryptedFlags and 2) != 0) {
                        require(keyInfoCompBytes.size >= 8) { "MDX 加密索引块长度异常" }
                        val keySeed = byteArrayOf(
                            keyInfoCompBytes[4],
                            keyInfoCompBytes[5],
                            keyInfoCompBytes[6],
                            keyInfoCompBytes[7],
                            0x95.toByte(),
                            0x36.toByte(),
                            0x00.toByte(),
                            0x00.toByte()
                        )
                        val key = ripemd128(keySeed)
                        fastDecryptInPlace(keyInfoCompBytes, 8, key)
                    }
                    decompressBlock(
                        keyInfoCompBytes,
                        keyInfoDecompSize.toInt().coerceAtLeast(0)
                    )
                } else {
                    keyInfoCompBytes
                }

                val keyBlocks = parseKeyBlockInfoList(
                    infoBytes = keyInfoBytes,
                    numBlocks = numKeyBlocks.toInt(),
                    isV2 = isV2,
                    numberWidth = numberWidth,
                    charset = charset
                )

                val keyBlocksStartOffset = raf.filePointer
                val recordSectionStartOffset = keyBlocksStartOffset + keyBlocksTotalSize

                // 3. 解析记录区偏移表
                raf.seek(recordSectionStartOffset)
                val recordHeaderBuf = ByteArray(numberWidth * 4)
                raf.readFully(recordHeaderBuf)
                val rhBuf = ByteBuffer.wrap(recordHeaderBuf).order(ByteOrder.BIG_ENDIAN)
                val numRecordBlocks = readNumber(rhBuf, numberWidth)
                /* numRecordEntries */ readNumber(rhBuf, numberWidth)
                val recordInfoSize = readNumber(rhBuf, numberWidth)
                /* recordBlocksTotalCompSize */ readNumber(rhBuf, numberWidth)

                require(numRecordBlocks in 0..10_000_000 && recordInfoSize in 0..(64L * 1024 * 1024)) {
                    "MDX 记录索引区格式异常"
                }

                val recordInfoBytes = ByteArray(recordInfoSize.toInt())
                raf.readFully(recordInfoBytes)
                val riBuf = ByteBuffer.wrap(recordInfoBytes).order(ByteOrder.BIG_ENDIAN)

                val recordBlocks = ArrayList<RecordBlockMeta>(numRecordBlocks.toInt())
                var runComp = 0L
                var runDecomp = 0L
                for (i in 0 until numRecordBlocks.toInt()) {
                    val compSz = readNumber(riBuf, numberWidth)
                    val decompSz = readNumber(riBuf, numberWidth)
                    recordBlocks.add(
                        RecordBlockMeta(
                            compOffset = runComp,
                            compSize = compSz,
                            decompOffset = runDecomp,
                            decompSize = decompSz
                        )
                    )
                    runComp += compSz
                    runDecomp += decompSz
                }

                val recordDataStartOffset = raf.filePointer

                return MdxDictionaryEngine(
                    file = file,
                    title = title,
                    version = version,
                    numEntries = numEntries,
                    charset = charset,
                    numberWidth = numberWidth,
                    keyBlocksStartOffset = keyBlocksStartOffset,
                    keyBlocks = keyBlocks,
                    recordDataStartOffset = recordDataStartOffset,
                    recordBlocks = recordBlocks,
                    totalRecordDecompSize = runDecomp,
                    stripKey = stripKey,
                    styleSheet = styleSheet
                )
            }
        }

        private fun readNumber(buf: ByteBuffer, numberWidth: Int): Long {
            return if (numberWidth == 8) {
                buf.long
            } else {
                buf.int.toLong() and 0xFFFFFFFFL
            }
        }

        private fun parseKeyBlockInfoList(
            infoBytes: ByteArray,
            numBlocks: Int,
            isV2: Boolean,
            numberWidth: Int,
            charset: Charset
        ): List<KeyBlockMeta> {
            val buf = ByteBuffer.wrap(infoBytes).order(ByteOrder.BIG_ENDIAN)
            val charUnitWidth = if (charset == Charsets.UTF_16LE) 2 else 1
            val textTerm = if (isV2) 1 else 0
            val list = ArrayList<KeyBlockMeta>(numBlocks)
            var runningCompOffset = 0L

            for (i in 0 until numBlocks) {
                val entryCount = readNumber(buf, numberWidth)
                val firstLen = if (isV2) (buf.short.toInt() and 0xFFFF) else (buf.get().toInt() and 0xFF)
                val firstByteCount = (firstLen + textTerm) * charUnitWidth
                val firstBytes = ByteArray(firstByteCount)
                buf.get(firstBytes)
                val firstPayloadBytes = firstBytes.copyOf((firstLen * charUnitWidth).coerceAtMost(firstBytes.size))
                val firstWord = trimHeadword(String(firstPayloadBytes, charset))

                val lastLen = if (isV2) (buf.short.toInt() and 0xFFFF) else (buf.get().toInt() and 0xFF)
                val lastByteCount = (lastLen + textTerm) * charUnitWidth
                val lastBytes = ByteArray(lastByteCount)
                buf.get(lastBytes)
                val lastPayloadBytes = lastBytes.copyOf((lastLen * charUnitWidth).coerceAtMost(lastBytes.size))
                val lastWord = trimHeadword(String(lastPayloadBytes, charset))

                val compSize = readNumber(buf, numberWidth)
                val decompSize = readNumber(buf, numberWidth)

                list.add(
                    KeyBlockMeta(
                        index = i,
                        compOffset = runningCompOffset,
                        compSize = compSize,
                        decompSize = decompSize,
                        entryCount = entryCount,
                        firstWord = firstWord,
                        lastWord = lastWord,
                        firstLower = firstWord.lowercase(Locale.ROOT),
                        lastLower = lastWord.lowercase(Locale.ROOT),
                        firstStripped = stripForKey(firstWord),
                        lastStripped = stripForKey(lastWord),
                        firstRawBytes = firstPayloadBytes,
                        lastRawBytes = lastPayloadBytes,
                        firstUtf8Bytes = firstWord.toByteArray(Charsets.UTF_8),
                        lastUtf8Bytes = lastWord.toByteArray(Charsets.UTF_8)
                    )
                )
                runningCompOffset += compSize
            }
            return list
        }

        private fun parseKeyBlockEntries(
            raw: ByteArray,
            numberWidth: Int,
            charset: Charset
        ): List<KeyEntry> {
            val entries = ArrayList<KeyEntry>(256)
            val isUtf16Le = charset == Charsets.UTF_16LE
            var pos = 0
            while (pos + numberWidth <= raw.size) {
                var offset = 0L
                for (b in 0 until numberWidth) {
                    offset = (offset shl 8) or (raw[pos + b].toLong() and 0xFFL)
                }
                pos += numberWidth

                val textStart = pos
                if (isUtf16Le) {
                    while (pos + 1 < raw.size && !(raw[pos] == 0.toByte() && raw[pos + 1] == 0.toByte())) {
                        pos += 2
                    }
                    val word = trimHeadword(
                        String(raw, textStart, (pos - textStart).coerceAtLeast(0), charset)
                    )
                    pos += 2
                    entries.add(KeyEntry(offset, word))
                } else {
                    while (pos < raw.size && raw[pos] != 0.toByte()) {
                        pos++
                    }
                    val word = trimHeadword(
                        String(raw, textStart, (pos - textStart).coerceAtLeast(0), charset)
                    )
                    pos += 1
                    entries.add(KeyEntry(offset, word))
                }
            }
            return entries
        }

        private fun decompressBlock(compBytes: ByteArray, expectedSize: Int = 0): ByteArray {
            if (compBytes.size < 8) return compBytes
            val compType = compBytes[0].toInt() and 0xFF
            return when (compType) {
                0 -> compBytes.copyOfRange(8, compBytes.size)
                1 -> lzo1xDecompress(compBytes, 8, compBytes.size - 8, expectedSize)
                2 -> zlibDecompress(compBytes, 8, compBytes.size - 8, expectedSize)
                else -> throw IllegalStateException("不支持的 MDX 压缩格式: $compType")
            }
        }

        private fun zlibDecompress(
            data: ByteArray,
            offset: Int,
            length: Int,
            expectedSize: Int
        ): ByteArray {
            val inflater = Inflater()
            return try {
                inflater.setInput(data, offset, length)
                val out = ByteArrayOutputStream(expectedSize.coerceIn(1024, 2 * 1024 * 1024))
                val buffer = ByteArray(8192)
                while (!inflater.finished()) {
                    val count = inflater.inflate(buffer)
                    if (count == 0) {
                        if (inflater.needsInput() || inflater.needsDictionary()) break
                    } else {
                        out.write(buffer, 0, count)
                    }
                }
                out.toByteArray()
            } finally {
                inflater.end()
            }
        }

        /**
         * 纯 Kotlin 精确实现标准 minilzo LZO1X 解压状态机，兼容所有采用 LZO 压缩的 MDX 词典。
         */
        private fun lzo1xDecompress(
            src: ByteArray,
            srcOffset: Int,
            srcLen: Int,
            expectedSize: Int
        ): ByteArray {
            val end = (srcOffset + srcLen).coerceAtMost(src.size)
            var ip = srcOffset
            if (ip >= end) return ByteArray(0)

            var out = ByteArray(expectedSize.coerceIn(4096, 4 * 1024 * 1024))
            var op = 0

            fun ensureCapacity(extra: Int) {
                val needed = op + extra
                if (needed > out.size) {
                    var newSize = out.size * 2
                    while (newSize < needed) newSize *= 2
                    out = out.copyOf(newSize)
                }
            }

            fun copyLiterals(count: Int): Boolean {
                if (count <= 0) return true
                val safeCount = minOf(count, end - ip)
                if (safeCount <= 0) return false
                ensureCapacity(safeCount)
                System.arraycopy(src, ip, out, op, safeCount)
                ip += safeCount
                op += safeCount
                return safeCount == count
            }

            fun copyMatch(mPosStart: Int, count: Int): Boolean {
                if (count <= 0) return true
                if (mPosStart < 0 || mPosStart >= op) return false
                ensureCapacity(count)
                var mp = mPosStart
                repeat(count) {
                    out[op++] = out[mp++]
                }
                return true
            }

            // 严格对齐 minilzo.c lzo1x_decompress 的 4 个核心状态
            val stateLiteralRun = 0
            val stateFirstLiteralRun = 1
            val stateMatch = 2
            val stateMatchDone = 3

            var state: Int
            var t = src[ip].toInt() and 0xFF
            var matchTrail = 0

            if (t > 17) {
                ip++
                t -= 17
                if (t < 4) {
                    if (!copyLiterals(t) || ip >= end) return out.copyOf(op)
                    t = src[ip++].toInt() and 0xFF
                    state = stateMatch
                } else {
                    if (!copyLiterals(t)) return out.copyOf(op)
                    state = stateFirstLiteralRun
                }
            } else {
                state = stateLiteralRun
            }

            loop@ while (true) {
                when (state) {
                    stateLiteralRun -> {
                        if (ip >= end) break@loop
                        t = src[ip++].toInt() and 0xFF
                        if (t >= 16) {
                            state = stateMatch
                            continue@loop
                        }
                        var litLen = t
                        if (litLen == 0) {
                            while (ip < end && src[ip] == 0.toByte()) {
                                litLen += 255
                                ip++
                            }
                            if (ip >= end) break@loop
                            litLen += 15 + (src[ip++].toInt() and 0xFF)
                        }
                        if (!copyLiterals(litLen + 3)) break@loop
                        state = stateFirstLiteralRun
                    }

                    stateFirstLiteralRun -> {
                        if (ip >= end) break@loop
                        t = src[ip++].toInt() and 0xFF
                        if (t >= 16) {
                            state = stateMatch
                            continue@loop
                        }
                        if (ip >= end) break@loop
                        val b = src[ip++].toInt() and 0xFF
                        val mPos = op - (1 + 0x0800) - (t ushr 2) - (b shl 2)
                        if (!copyMatch(mPos, 3)) break@loop
                        matchTrail = t and 3
                        state = stateMatchDone
                    }

                    stateMatch -> {
                        when {
                            t >= 64 -> {
                                if (ip >= end) break@loop
                                val b = src[ip++].toInt() and 0xFF
                                val mPos = op - 1 - ((t ushr 2) and 7) - (b shl 3)
                                val mLen = (t ushr 5) - 1 + 2
                                if (!copyMatch(mPos, mLen)) break@loop
                                matchTrail = t and 3
                            }
                            t >= 32 -> {
                                var len = t and 31
                                if (len == 0) {
                                    while (ip < end && src[ip] == 0.toByte()) {
                                        len += 255
                                        ip++
                                    }
                                    if (ip >= end) break@loop
                                    len += 31 + (src[ip++].toInt() and 0xFF)
                                }
                                if (ip + 1 >= end) break@loop
                                val b0 = src[ip++].toInt() and 0xFF
                                val b1 = src[ip++].toInt() and 0xFF
                                val mPos = op - 1 - ((b0 ushr 2) + (b1 shl 6))
                                if (!copyMatch(mPos, len + 2)) break@loop
                                matchTrail = b0 and 3
                            }
                            t >= 16 -> {
                                var pos = op - ((t and 8) shl 11)
                                var len = t and 7
                                if (len == 0) {
                                    while (ip < end && src[ip] == 0.toByte()) {
                                        len += 255
                                        ip++
                                    }
                                    if (ip >= end) break@loop
                                    len += 7 + (src[ip++].toInt() and 0xFF)
                                }
                                if (ip + 1 >= end) break@loop
                                val b0 = src[ip++].toInt() and 0xFF
                                val b1 = src[ip++].toInt() and 0xFF
                                pos -= (b0 ushr 2) + (b1 shl 6)
                                if (pos == op) {
                                    break@loop
                                }
                                val mPos = pos - 0x4000
                                if (!copyMatch(mPos, len + 2)) break@loop
                                matchTrail = b0 and 3
                            }
                            else -> {
                                if (ip >= end) break@loop
                                val b = src[ip++].toInt() and 0xFF
                                val mPos = op - 1 - (t ushr 2) - (b shl 2)
                                if (!copyMatch(mPos, 2)) break@loop
                                matchTrail = t and 3
                            }
                        }
                        state = stateMatchDone
                    }

                    stateMatchDone -> {
                        if (matchTrail == 0) {
                            state = stateLiteralRun
                        } else {
                            if (!copyLiterals(matchTrail) || ip >= end) break@loop
                            t = src[ip++].toInt() and 0xFF
                            state = stateMatch
                        }
                    }
                }
            }

            return out.copyOf(op)
        }

        private fun fastDecryptInPlace(data: ByteArray, offset: Int, key: ByteArray) {
            var previous = 0x36
            val keyLen = key.size
            for (i in offset until data.size) {
                val idx = i - offset
                val orig = data[i].toInt() and 0xFF
                var t = ((orig ushr 4) or (orig shl 4)) and 0xFF
                t = t xor previous xor (idx and 0xFF) xor (key[idx % keyLen].toInt() and 0xFF)
                previous = orig
                data[i] = t.toByte()
            }
        }

        /**
         * 标准 RIPEMD-128 摘要算法，用于解密 MDict v2.0 Encrypted=2 的词头索引块。
         */
        private fun ripemd128(message: ByteArray): ByteArray {
            val bitLen = message.size.toLong() * 8L
            val padLen = ((56 - (message.size + 1) % 64) + 64) % 64
            val padded = ByteArray(message.size + 1 + padLen + 8)
            System.arraycopy(message, 0, padded, 0, message.size)
            padded[message.size] = 0x80.toByte()
            for (i in 0 until 8) {
                padded[padded.size - 8 + i] = ((bitLen ushr (8 * i)) and 0xFFL).toByte()
            }

            var h0 = 0x67452301
            var h1 = -0x10325477 // 0xEFCDAB89
            var h2 = -0x67452302 // 0x98BADCFE
            var h3 = 0x10325476

            val r1 = intArrayOf(
                0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
                7, 4, 13, 1, 10, 6, 15, 3, 12, 0, 9, 5, 2, 14, 11, 8,
                3, 10, 14, 4, 9, 15, 8, 1, 2, 7, 0, 6, 13, 11, 5, 12,
                1, 9, 11, 10, 0, 8, 12, 4, 13, 3, 7, 15, 14, 5, 6, 2
            )
            val r2 = intArrayOf(
                5, 14, 7, 0, 9, 2, 11, 4, 13, 6, 15, 8, 1, 10, 3, 12,
                6, 11, 3, 7, 0, 13, 5, 10, 14, 15, 8, 12, 4, 9, 1, 2,
                15, 5, 1, 3, 7, 14, 6, 9, 11, 8, 12, 2, 10, 0, 4, 13,
                8, 6, 4, 1, 3, 11, 15, 0, 5, 12, 2, 13, 9, 7, 10, 14
            )
            val s1 = intArrayOf(
                11, 14, 15, 12, 5, 8, 7, 9, 11, 13, 14, 15, 6, 7, 9, 8,
                7, 6, 8, 13, 11, 9, 7, 15, 7, 12, 15, 9, 11, 7, 13, 12,
                11, 13, 6, 7, 14, 9, 13, 15, 14, 8, 13, 6, 5, 12, 7, 5,
                11, 12, 14, 15, 14, 15, 9, 8, 9, 14, 5, 6, 8, 6, 5, 12
            )
            val s2 = intArrayOf(
                8, 9, 9, 11, 13, 15, 15, 5, 7, 7, 8, 11, 14, 14, 12, 6,
                9, 13, 15, 7, 12, 8, 9, 11, 7, 7, 12, 7, 6, 15, 13, 11,
                9, 7, 15, 11, 8, 6, 6, 14, 12, 13, 5, 14, 13, 13, 7, 5,
                15, 5, 8, 11, 14, 14, 6, 14, 6, 9, 12, 9, 12, 5, 15, 8
            )

            val x = IntArray(16)
            var blockStart = 0
            while (blockStart < padded.size) {
                for (i in 0 until 16) {
                    val idx = blockStart + i * 4
                    x[i] = (padded[idx].toInt() and 0xFF) or
                        ((padded[idx + 1].toInt() and 0xFF) shl 8) or
                        ((padded[idx + 2].toInt() and 0xFF) shl 16) or
                        ((padded[idx + 3].toInt() and 0xFF) shl 24)
                }

                var a = h0
                var b = h1
                var c = h2
                var d = h3
                var aa = h0
                var bb = h1
                var cc = h2
                var dd = h3

                for (j in 0 until 64) {
                    val f1: Int
                    val k1: Int
                    val f2: Int
                    val k2: Int
                    when (j / 16) {
                        0 -> {
                            f1 = b xor c xor d
                            k1 = 0x00000000
                            f2 = (bb and dd) or (cc and dd.inv())
                            k2 = 0x50A28BE6
                        }
                        1 -> {
                            f1 = (b and c) or (b.inv() and d)
                            k1 = 0x5A827999
                            f2 = (bb or cc.inv()) xor dd
                            k2 = 0x5C4DD124
                        }
                        2 -> {
                            f1 = (b or c.inv()) xor d
                            k1 = 0x6ED9EBA1
                            f2 = (bb and cc) or (bb.inv() and dd)
                            k2 = 0x6D703EF3
                        }
                        else -> {
                            f1 = (b and d) or (c and d.inv())
                            k1 = -0x70E44324 // 0x8F1BBCDC
                            f2 = bb xor cc xor dd
                            k2 = 0x00000000
                        }
                    }

                    val t1 = Integer.rotateLeft(a + f1 + x[r1[j]] + k1, s1[j])
                    a = d
                    d = c
                    c = b
                    b = t1

                    val t2 = Integer.rotateLeft(aa + f2 + x[r2[j]] + k2, s2[j])
                    aa = dd
                    dd = cc
                    cc = bb
                    bb = t2
                }

                val t = h1 + c + dd
                h1 = h2 + d + aa
                h2 = h3 + a + bb
                h3 = h0 + b + cc
                h0 = t
                blockStart += 64
            }

            val digest = ByteArray(16)
            val words = intArrayOf(h0, h1, h2, h3)
            for (i in 0 until 4) {
                val w = words[i]
                digest[i * 4] = (w and 0xFF).toByte()
                digest[i * 4 + 1] = ((w ushr 8) and 0xFF).toByte()
                digest[i * 4 + 2] = ((w ushr 16) and 0xFF).toByte()
                digest[i * 4 + 3] = ((w ushr 24) and 0xFF).toByte()
            }
            return digest
        }

        private fun resolveMdxCharset(encodingName: String): Charset {
            return when {
                encodingName.isEmpty() || encodingName == "UTF-8" || encodingName == "UTF8" -> Charsets.UTF_8
                encodingName == "UTF-16" || encodingName == "UTF-16LE" || encodingName == "UTF16" || encodingName == "UNICODE" -> Charsets.UTF_16LE
                encodingName.startsWith("GB") || encodingName == "CP936" || encodingName == "MS936" -> {
                    listOf("GB18030", "GBK", "GB2312").firstNotNullOfOrNull { name ->
                        runCatching { Charset.forName(name) }.getOrNull()
                    } ?: Charsets.UTF_8
                }
                encodingName == "BIG5" || encodingName == "BIG-5" || encodingName == "CP950" -> {
                    listOf("Big5", "Big5-HKSCS").firstNotNullOfOrNull { name ->
                        runCatching { Charset.forName(name) }.getOrNull()
                    } ?: Charsets.UTF_8
                }
                else -> runCatching { Charset.forName(encodingName) }.getOrDefault(Charsets.UTF_8)
            }
        }

        private fun parseStyleSheet(raw: String): Map<String, Pair<String, String>> {
            if (raw.isBlank()) return emptyMap()
            val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            val map = HashMap<String, Pair<String, String>>()
            var i = 0
            while (i + 2 < lines.size) {
                val id = lines[i].trim()
                val begin = lines[i + 1]
                val end = lines[i + 2]
                if (id.isNotEmpty()) {
                    map[id] = begin to end
                }
                i += 3
            }
            return map
        }

        private fun substituteStylesheet(
            txt: String,
            styleSheet: Map<String, Pair<String, String>>
        ): String {
            val sb = StringBuilder(txt.length + 64)
            var lastEnd = ""
            var i = 0
            val n = txt.length
            while (i < n) {
                if (txt[i] == '`') {
                    val close = txt.indexOf('`', i + 1)
                    if (close > i + 1 && close - i <= 8) {
                        val key = txt.substring(i + 1, close)
                        val style = styleSheet[key]
                        if (style != null) {
                            sb.append(lastEnd)
                            sb.append(style.first)
                            lastEnd = style.second
                            i = close + 1
                            continue
                        }
                    }
                }
                sb.append(txt[i])
                i++
            }
            sb.append(lastEnd)
            return sb.toString()
        }

        private fun stripLeadingBomAndNull(raw: String): String {
            return raw.trim('\u0000', '\uFEFF', '\u200B', '\r', '\n', ' ', '\t', '\u3000', '\u00A0')
        }

        private fun trimHeadword(raw: String): String {
            val cleaned = raw.trim('\u0000', '\uFEFF', '\u200B', '\u200C', '\u200D', '\r', '\n', ' ', '\t', '\u3000', '\u00A0')
            return if (cleaned.isEmpty()) "" else Normalizer.normalize(cleaned, Normalizer.Form.NFC)
        }

        private fun stripForKey(text: String): String {
            val nfc = Normalizer.normalize(text, Normalizer.Form.NFC)
            return stripKeyRegex.replace(nfc, "").lowercase(Locale.ROOT)
        }

        private fun extractCjkBaseKey(text: String): String {
            val trimmed = trimHeadword(text)
            if (trimmed.isEmpty()) return ""
            val unwrapped = edgePunctRegex.replace(trimmed, "").trim()
            val withoutSuffix = cjkTrailingDisambigRegex.replace(unwrapped, "").trim()
            val cleaned = stripKeyRegex.replace(withoutSuffix, "")
            return if (cleaned.isNotEmpty() && isCjkWord(cleaned)) cleaned else ""
        }

        private fun unescapeHtmlAttr(text: String): String {
            return text
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
        }

        private fun compareUnsignedBytes(a: ByteArray, b: ByteArray): Int {
            val minLen = minOf(a.size, b.size)
            for (i in 0 until minLen) {
                val av = a[i].toInt() and 0xFF
                val bv = b[i].toInt() and 0xFF
                if (av != bv) return av - bv
            }
            return a.size - b.size
        }

        private fun containsCjk(text: String): Boolean {
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                if (Character.isIdeographic(cp)) return true
                i += Character.charCount(cp)
            }
            return false
        }

        private fun isCjkWord(text: String): Boolean {
            if (text.isEmpty()) return false
            var count = 0
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                if (!Character.isIdeographic(cp)) return false
                count++
                if (count > 8) return false
                i += Character.charCount(cp)
            }
            return count in 1..8
        }

        private fun detectSortOrder(
            blocks: List<KeyBlockMeta>,
            zhCollator: Collator
        ): Pair<BlockSortOrder, List<Int>> {
            if (blocks.size <= 1) return BlockSortOrder.UNICODE_LOWER to emptyList()

            fun scoreOrder(compare: (KeyBlockMeta, KeyBlockMeta) -> Int, selfCompare: (KeyBlockMeta) -> Int): Pair<Int, List<Int>> {
                var inversions = 0
                val badBlocks = ArrayList<Int>()
                for (i in blocks.indices) {
                    val b = blocks[i]
                    if (selfCompare(b) > 0) {
                        inversions++
                        badBlocks.add(i)
                    }
                    if (i > 0 && compare(blocks[i - 1], b) > 0) {
                        inversions++
                        if (badBlocks.lastOrNull() != i) badBlocks.add(i)
                    }
                }
                return inversions to badBlocks
            }

            val candidates = listOf(
                BlockSortOrder.UNICODE_STRIPPED to scoreOrder(
                    compare = { a, b -> a.lastStripped.compareTo(b.firstStripped) },
                    selfCompare = { b -> b.firstStripped.compareTo(b.lastStripped) }
                ),
                BlockSortOrder.UNICODE_LOWER to scoreOrder(
                    compare = { a, b -> a.lastLower.compareTo(b.firstLower) },
                    selfCompare = { b -> b.firstLower.compareTo(b.lastLower) }
                ),
                BlockSortOrder.CHARSET_BYTES to scoreOrder(
                    compare = { a, b -> compareUnsignedBytes(a.lastRawBytes, b.firstRawBytes) },
                    selfCompare = { b -> compareUnsignedBytes(b.firstRawBytes, b.lastRawBytes) }
                ),
                BlockSortOrder.UTF8_BYTES to scoreOrder(
                    compare = { a, b -> compareUnsignedBytes(a.lastUtf8Bytes, b.firstUtf8Bytes) },
                    selfCompare = { b -> compareUnsignedBytes(b.firstUtf8Bytes, b.lastUtf8Bytes) }
                ),
                BlockSortOrder.ZH_PINYIN to synchronized(zhCollator) {
                    scoreOrder(
                        compare = { a, b -> zhCollator.compare(a.lastWord, b.firstWord) },
                        selfCompare = { b -> zhCollator.compare(b.firstWord, b.lastWord) }
                    )
                }
            )

            val best = candidates.minBy { it.second.first }
            val maxAllowed = maxOf(2, (blocks.size * 0.03).toInt())
            return if (best.second.first <= maxAllowed) {
                best.first to best.second.second
            } else {
                BlockSortOrder.UNKNOWN to emptyList()
            }
        }

        private val edgePunctRegex = Regex(
            """^[\s\p{Punct}《》「」『』【】（）()、，。！？；：""''—–…·•·]+|[\s\p{Punct}《》「」『』【】（）()、，。！？；：""''—–…·•·]+$"""
        )
        private val softCharsRegex = Regex("""[\u00AD\u200B\u200C\u200D\uFEFF]""")
        private val multiSpaceRegex = Regex("""\s+""")
        private val englishWordTokenRegex = Regex("""^[A-Za-z]+(?:[-'’][A-Za-z]+)*$""")

        fun cleanBasicQuery(raw: String): String {
            val cleaned = softCharsRegex.replace(raw, "")
                .replace('’', '\'')
                .replace('‘', '\'')
                .replace('\u3000', ' ')
                .replace('\u00A0', ' ')
                .trim()
                .let { multiSpaceRegex.replace(it, " ") }
            return if (cleaned.isEmpty()) "" else Normalizer.normalize(cleaned, Normalizer.Form.NFC)
        }

        /**
         * 选词过滤：
         * - 清理软连字符与首尾标点，拒绝换行或整句；
         * - 支持 1~8 个汉字的中文单字/词语/成语（含扩展区生僻字）；
         * - 支持英文单词、连字符词或最多 3 词短语。
         */
        fun sanitizeSelectionQuery(raw: String): String? {
            if (raw.contains('\n') || raw.contains('\r')) return null
            val strippedSoft = softCharsRegex.replace(raw, "")
                .replace('’', '\'')
                .replace('‘', '\'')
                .trim()
            if (strippedSoft.isEmpty()) return null
            val cleaned = edgePunctRegex.replace(strippedSoft, "")
                .let { multiSpaceRegex.replace(it, " ") }
                .trim()
            if (cleaned.isEmpty() || cleaned.length > 64) return null

            // 中文：1~8 个纯汉字（覆盖基本区与全部 CJK 扩展区）
            if (isCjkWord(cleaned)) {
                return cleaned
            }

            // 英文：单词、连字符词或最多 3 词短语
            val tokens = cleaned.split(' ').filter { it.isNotEmpty() }
            if (tokens.size in 1..3 && tokens.all { englishWordTokenRegex.matches(it) }) {
                return tokens.joinToString(" ")
            }

            return null
        }

        private fun buildLookupCandidates(query: String): List<String> {
            val out = LinkedHashSet<String>()
            fun add(v: String) {
                val trimmed = v.trim()
                if (trimmed.isNotEmpty()) out.add(trimmed)
            }

            val normalized = edgePunctRegex.replace(cleanBasicQuery(query), "").trim()
            val effective = normalized.ifEmpty { cleanBasicQuery(query) }
            add(effective)
            val lower = effective.lowercase(Locale.ROOT)
            add(lower)
            if (lower.length > 1) {
                add(lower.replaceFirstChar { it.uppercase(Locale.ROOT) })
            }

            // 仅对纯英文单词（或含撇号/连字符词）生成完整词形还原候选
            if (lower.length >= 2 && lower.all { it in 'a'..'z' || it == '\'' || it == '-' }) {
                // 所有格 's / 末尾撇号
                val baseWord = when {
                    lower.endsWith("'s") -> lower.dropLast(2).also { add(it) }
                    lower.endsWith("'") -> lower.dropLast(1).also { add(it) }
                    else -> lower
                }

                // 复数 / 第三人称单数 (-ies / -ves / -es / -s)
                if (baseWord.endsWith("ies") && baseWord.length > 4) {
                    add(baseWord.dropLast(3) + "y")
                }
                if (baseWord.endsWith("ves") && baseWord.length > 4) {
                    val stem = baseWord.dropLast(3)
                    add(stem + "f")
                    add(stem + "fe")
                }
                if (baseWord.endsWith("es") && baseWord.length > 3) {
                    add(baseWord.dropLast(2))
                }
                if (baseWord.endsWith("s") && !baseWord.endsWith("ss") && baseWord.length > 3) {
                    add(baseWord.dropLast(1))
                }

                // 过去式 / 过去分词 (-ied / -ed / 双写辅音还原)
                if (baseWord.endsWith("ied") && baseWord.length > 4) {
                    add(baseWord.dropLast(3) + "y")
                }
                if (baseWord.endsWith("ed") && baseWord.length > 3) {
                    val stem = baseWord.dropLast(2)
                    add(stem)
                    add(stem + "e")
                    if (stem.length >= 3 && stem[stem.lastIndex] == stem[stem.lastIndex - 1]) {
                        add(stem.dropLast(1))
                    }
                }

                // 进行时 (-ying -> -ie / -ing / 去 e / 双写辅音还原)
                if (baseWord.endsWith("ying") && baseWord.length > 5) {
                    add(baseWord.dropLast(4) + "ie")
                }
                if (baseWord.endsWith("ing") && baseWord.length > 4) {
                    val stem = baseWord.dropLast(3)
                    add(stem)
                    add(stem + "e")
                    if (stem.length >= 3 && stem[stem.lastIndex] == stem[stem.lastIndex - 1]) {
                        add(stem.dropLast(1))
                    }
                }

                // 比较级 / 最高级 (-ier / -er / -iest / -est / 双写辅音还原)
                if (baseWord.endsWith("ier") && baseWord.length > 4) {
                    add(baseWord.dropLast(3) + "y")
                }
                if (baseWord.endsWith("er") && baseWord.length > 4) {
                    val stem = baseWord.dropLast(2)
                    add(stem)
                    add(stem + "e")
                    if (stem.length >= 3 && stem[stem.lastIndex] == stem[stem.lastIndex - 1]) {
                        add(stem.dropLast(1))
                    }
                }
                if (baseWord.endsWith("iest") && baseWord.length > 5) {
                    add(baseWord.dropLast(4) + "y")
                }
                if (baseWord.endsWith("est") && baseWord.length > 5) {
                    val stem = baseWord.dropLast(3)
                    add(stem)
                    add(stem + "e")
                    if (stem.length >= 3 && stem[stem.lastIndex] == stem[stem.lastIndex - 1]) {
                        add(stem.dropLast(1))
                    }
                }
            }
            return out.toList()
        }
    }
}
