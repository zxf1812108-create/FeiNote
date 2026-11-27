package com.feiji.bulletnotes.data

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.Inflater

/**
 * 纯本地离线 MDict (.mdx) 词典解析与极速查词引擎（零第三方依赖）：
 * - 完整支持 MDict v1.2 与 v2.0 格式；
 * - 支持 v2.0 索引块加密（RIPEMD-128 + fast_decrypt）、zlib / LZO1X / 未压缩块；
 * - 支持 UTF-8 / UTF-16LE / GB18030 / Big5 编码；
 * - 启动时仅加载词头块索引与记录块偏移表（常驻内存 < 2MB），查词按需随机读取单块解压；
 * - 支持 @@@LINK= 自动重定向与英文常见词形还原回退（复数、过去式、进行时、比较级等）。
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
    private val stripKey: Boolean
) : Closeable {

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
        val lastStripped: String
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

    private val lock = Any()
    private var raf: RandomAccessFile? = RandomAccessFile(file, "r")

    // 最近访问的少量词头块缓存，避免连续查词重复解压
    private val keyBlockCache = object : LinkedHashMap<Int, List<KeyEntry>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<KeyEntry>>?): Boolean {
            return size > 12
        }
    }

    override fun close() {
        synchronized(lock) {
            runCatching { raf?.close() }
            raf = null
            keyBlockCache.clear()
        }
    }

    fun lookup(rawQuery: String): LookupMatch? {
        val cleaned = rawQuery.trim().trim('\uFEFF', '\u200B')
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

    private fun lookupExactWithRedirect(word: String, maxHops: Int): Pair<String, String>? {
        var current = word
        val visited = HashSet<String>()
        repeat(maxHops) {
            val norm = current.lowercase(Locale.ROOT)
            if (!visited.add(norm)) return null
            val hit = lookupSinglePass(current) ?: return null
            val html = hit.second.trim()
            if (html.startsWith("@@@LINK=", ignoreCase = true)) {
                val target = html.substring(8)
                    .lineSequence()
                    .firstOrNull()
                    ?.trim()
                    ?.trimEnd('\u0000')
                    .orEmpty()
                if (target.isEmpty()) return null
                current = target
            } else {
                return hit.first to html
            }
        }
        return null
    }

    private fun lookupSinglePass(word: String): Pair<String, String>? {
        if (keyBlocks.isEmpty()) return null
        val targetLower = word.lowercase(Locale.ROOT)
        val targetStripped = stripForKey(word)

        val candidateBlocks = findCandidateKeyBlocks(targetLower, targetStripped)
        for (block in candidateBlocks) {
            val entries = readKeyBlockEntries(block)
            // 先精确匹配（区分大小写），再忽略大小写匹配，最后按 stripKey 匹配
            var matchIdx = entries.indexOfFirst { it.word == word }
            if (matchIdx < 0) {
                matchIdx = entries.indexOfFirst { it.word.equals(word, ignoreCase = true) }
            }
            if (matchIdx < 0 && targetStripped.isNotEmpty() && stripKey) {
                matchIdx = entries.indexOfFirst { stripForKey(it.word) == targetStripped }
            }
            if (matchIdx >= 0) {
                val entry = entries[matchIdx]
                val nextOffset = if (matchIdx + 1 < entries.size) {
                    entries[matchIdx + 1].recordOffset
                } else {
                    resolveNextBlockStartRecordOffset(block.index)
                }
                val html = readRecordSlice(entry.recordOffset, nextOffset)
                return entry.word to html
            }
        }
        return null
    }

    private fun findCandidateKeyBlocks(
        targetLower: String,
        targetStripped: String
    ): List<KeyBlockMeta> {
        val result = ArrayList<KeyBlockMeta>(4)
        val added = HashSet<Int>(8)

        fun addBlock(idx: Int) {
            if (idx in keyBlocks.indices && added.add(idx)) {
                result.add(keyBlocks[idx])
            }
        }

        // 1. 按标准 stripKey 范围快速扫描（通常仅数百至两千块，耗时 < 0.5ms）
        if (targetStripped.isNotEmpty()) {
            for (block in keyBlocks) {
                if (targetStripped >= block.firstStripped && targetStripped <= block.lastStripped) {
                    addBlock(block.index)
                }
            }
        }

        // 2. 按普通小写字典序范围匹配（覆盖未 stripKey 的词典）
        for (block in keyBlocks) {
            if (targetLower >= block.firstLower && targetLower <= block.lastLower) {
                addBlock(block.index)
            }
        }

        // 3. 若命中边界块，顺带检查紧邻前一块（兼容大小写/重音字母跨块边界）
        if (result.isEmpty()) {
            var lo = 0
            var hi = keyBlocks.lastIndex
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val b = keyBlocks[mid]
                val cmp = if (stripKey && targetStripped.isNotEmpty()) {
                    targetStripped.compareTo(b.firstStripped)
                } else {
                    targetLower.compareTo(b.firstLower)
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

    private fun resolveNextBlockStartRecordOffset(blockIndex: Int): Long {
        val nextBlockIndex = blockIndex + 1
        if (nextBlockIndex !in keyBlocks.indices) {
            return totalRecordDecompSize
        }
        val nextEntries = readKeyBlockEntries(keyBlocks[nextBlockIndex])
        return nextEntries.firstOrNull()?.recordOffset ?: totalRecordDecompSize
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

            return String(out.toByteArray(), charset).trimEnd('\u0000')
        }
    }

    companion object {
        private val headerAttrRegex = Regex("""(\w+)\s*=\s*"([^"]*)"""")
        private val stripKeyRegex = Regex("""[\s\p{Punct}]+""")

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
                    attrs[m.groupValues[1]] = unescapeHtmlAttr(m.groupValues[2])
                }

                val versionStr = attrs["GeneratedByEngineVersion"]
                    ?: attrs["RequiredEngineVersion"]
                    ?: "2.0"
                val version = versionStr.toFloatOrNull() ?: 2.0f
                val isV2 = version >= 2.0f
                val numberWidth = if (isV2) 8 else 4

                val encodingName = attrs["Encoding"].orEmpty().trim().uppercase(Locale.ROOT)
                val charset = when {
                    encodingName.isEmpty() || encodingName == "UTF-8" || encodingName == "UTF8" -> Charsets.UTF_8
                    encodingName == "UTF-16" || encodingName == "UTF-16LE" || encodingName == "UTF16" -> Charsets.UTF_16LE
                    encodingName == "GBK" || encodingName == "GB2312" || encodingName == "GB18030" -> {
                        runCatching { Charset.forName("GB18030") }.getOrDefault(Charsets.UTF_8)
                    }
                    encodingName == "BIG5" -> {
                        runCatching { Charset.forName("Big5") }.getOrDefault(Charsets.UTF_8)
                    }
                    else -> runCatching { Charset.forName(encodingName) }.getOrDefault(Charsets.UTF_8)
                }

                val encryptedRaw = attrs["Encrypted"].orEmpty().trim()
                val encryptedFlags = when {
                    encryptedRaw.equals("Yes", ignoreCase = true) -> 1
                    encryptedRaw.equals("No", ignoreCase = true) || encryptedRaw.isEmpty() -> 0
                    else -> encryptedRaw.toIntOrNull() ?: 0
                }

                val stripKey = !attrs["StripKey"].equals("No", ignoreCase = true)
                val rawTitle = attrs["Title"].orEmpty().trim()
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
                    stripKey = stripKey
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
                val firstWord = String(firstBytes, charset).trimEnd('\u0000')

                val lastLen = if (isV2) (buf.short.toInt() and 0xFFFF) else (buf.get().toInt() and 0xFF)
                val lastByteCount = (lastLen + textTerm) * charUnitWidth
                val lastBytes = ByteArray(lastByteCount)
                buf.get(lastBytes)
                val lastWord = String(lastBytes, charset).trimEnd('\u0000')

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
                        lastStripped = stripForKey(lastWord)
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
                    val word = String(raw, textStart, (pos - textStart).coerceAtLeast(0), charset)
                    pos += 2
                    entries.add(KeyEntry(offset, word))
                } else {
                    while (pos < raw.size && raw[pos] != 0.toByte()) {
                        pos++
                    }
                    val word = String(raw, textStart, (pos - textStart).coerceAtLeast(0), charset)
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
         * 纯 Kotlin 实现 LZO1X 解压，兼容早期使用 LZO 压缩的 MDX 文件。
         */
        private fun lzo1xDecompress(
            src: ByteArray,
            srcOffset: Int,
            srcLen: Int,
            expectedSize: Int
        ): ByteArray {
            val end = srcOffset + srcLen
            var ip = srcOffset
            var out = ByteArray(expectedSize.coerceAtLeast(4096))
            var op = 0

            fun ensureCapacity(extra: Int) {
                val needed = op + extra
                if (needed > out.size) {
                    var newSize = out.size * 2
                    while (newSize < needed) newSize *= 2
                    out = out.copyOf(newSize)
                }
            }

            fun copyLiterals(count: Int) {
                ensureCapacity(count)
                System.arraycopy(src, ip, out, op, count)
                ip += count
                op += count
            }

            fun copyMatch(mPosStart: Int, count: Int) {
                ensureCapacity(count)
                var mp = mPosStart
                repeat(count) {
                    out[op++] = out[mp++]
                }
            }

            if (ip >= end) return ByteArray(0)

            var t = src[ip].toInt() and 0xFF
            var skipFirstLiteralRun = false
            if (t > 17) {
                ip++
                t -= 17
                if (t < 4) {
                    copyLiterals(t)
                    t = src[ip++].toInt() and 0xFF
                    skipFirstLiteralRun = true
                } else {
                    copyLiterals(t)
                    skipFirstLiteralRun = true
                    t = src[ip++].toInt() and 0xFF
                }
            } else {
                t = src[ip++].toInt() and 0xFF
            }

            mainLoop@ while (true) {
                if (!skipFirstLiteralRun) {
                    if (t < 16) {
                        var litLen = t
                        if (litLen == 0) {
                            while (ip < end && src[ip] == 0.toByte()) {
                                litLen += 255
                                ip++
                            }
                            litLen += 15 + (src[ip++].toInt() and 0xFF)
                        }
                        copyLiterals(litLen + 3)
                        if (ip >= end) break@mainLoop
                        t = src[ip++].toInt() and 0xFF
                        if (t < 16) {
                            var mPos = op - (1 + 0x0800)
                            mPos -= (t ushr 2)
                            mPos -= ((src[ip++].toInt() and 0xFF) shl 2)
                            copyMatch(mPos, 3)
                            val trail = t and 3
                            if (trail > 0) copyLiterals(trail)
                            if (ip >= end) break@mainLoop
                            t = src[ip++].toInt() and 0xFF
                            continue@mainLoop
                        }
                    }
                }
                skipFirstLiteralRun = false

                while (true) {
                    val mPos: Int
                    val mLen: Int
                    when {
                        t >= 64 -> {
                            mPos = op - 1 - ((t ushr 2) and 7) - ((src[ip++].toInt() and 0xFF) shl 3)
                            mLen = (t ushr 5) - 1 + 2
                        }
                        t >= 32 -> {
                            var len = t and 31
                            if (len == 0) {
                                while (ip < end && src[ip] == 0.toByte()) {
                                    len += 255
                                    ip++
                                }
                                len += 31 + (src[ip++].toInt() and 0xFF)
                            }
                            val b0 = src[ip++].toInt() and 0xFF
                            val b1 = src[ip++].toInt() and 0xFF
                            mPos = op - 1 - ((b0 ushr 2) + (b1 shl 6))
                            mLen = len + 2
                            t = b0
                        }
                        t >= 16 -> {
                            var pos = op - ((t and 8) shl 11)
                            var len = t and 7
                            if (len == 0) {
                                while (ip < end && src[ip] == 0.toByte()) {
                                    len += 255
                                    ip++
                                }
                                len += 7 + (src[ip++].toInt() and 0xFF)
                            }
                            val b0 = src[ip++].toInt() and 0xFF
                            val b1 = src[ip++].toInt() and 0xFF
                            pos -= (b0 ushr 2) + (b1 shl 6)
                            if (pos == op) {
                                break@mainLoop
                            }
                            mPos = pos - 0x4000
                            mLen = len + 2
                            t = b0
                        }
                        else -> {
                            mPos = op - 1 - (t ushr 2) - ((src[ip++].toInt() and 0xFF) shl 2)
                            mLen = 2
                        }
                    }

                    copyMatch(mPos, mLen)
                    val litTrail = t and 3
                    if (litTrail > 0) {
                        copyLiterals(litTrail)
                        if (ip >= end) break@mainLoop
                        t = src[ip++].toInt() and 0xFF
                        continue
                    } else {
                        if (ip >= end) break@mainLoop
                        t = src[ip++].toInt() and 0xFF
                        break
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

        private fun stripForKey(text: String): String {
            return stripKeyRegex.replace(text, "").lowercase(Locale.ROOT)
        }

        private fun unescapeHtmlAttr(text: String): String {
            return text
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
        }

        private fun buildLookupCandidates(query: String): List<String> {
            val out = LinkedHashSet<String>()
            fun add(v: String) {
                val trimmed = v.trim()
                if (trimmed.isNotEmpty()) out.add(trimmed)
            }

            add(query)
            val lower = query.lowercase(Locale.ROOT)
            add(lower)
            if (lower.length > 1) {
                add(lower.replaceFirstChar { it.uppercase(Locale.ROOT) })
            }

            // 仅对纯英文单词生成常见词形还原候选（复数、三单、过去式、现在分词、比较级等）
            if (lower.length >= 3 && lower.all { it in 'a'..'z' || it == '\'' || it == '-' }) {
                if (lower.endsWith("'s") || lower.endsWith("’s")) {
                    add(lower.dropLast(2))
                }
                if (lower.endsWith("ies") && lower.length > 4) {
                    add(lower.dropLast(3) + "y")
                }
                if (lower.endsWith("es") && lower.length > 3) {
                    add(lower.dropLast(2))
                }
                if (lower.endsWith("s") && !lower.endsWith("ss") && lower.length > 3) {
                    add(lower.dropLast(1))
                }
                if (lower.endsWith("ied") && lower.length > 4) {
                    add(lower.dropLast(3) + "y")
                }
                if (lower.endsWith("ed") && lower.length > 3) {
                    val stem = lower.dropLast(2)
                    add(stem)
                    add(stem + "e")
                    if (stem.length >= 3 && stem[stem.lastIndex] == stem[stem.lastIndex - 1]) {
                        add(stem.dropLast(1))
                    }
                }
                if (lower.endsWith("ing") && lower.length > 4) {
                    val stem = lower.dropLast(3)
                    add(stem)
                    add(stem + "e")
                    if (stem.length >= 3 && stem[stem.lastIndex] == stem[stem.lastIndex - 1]) {
                        add(stem.dropLast(1))
                    }
                }
                if (lower.endsWith("ier") && lower.length > 4) {
                    add(lower.dropLast(3) + "y")
                }
                if (lower.endsWith("er") && lower.length > 4) {
                    val stem = lower.dropLast(2)
                    add(stem)
                    add(stem + "e")
                }
                if (lower.endsWith("iest") && lower.length > 5) {
                    add(lower.dropLast(4) + "y")
                }
                if (lower.endsWith("est") && lower.length > 5) {
                    val stem = lower.dropLast(3)
                    add(stem)
                    add(stem + "e")
                }
            }
            return out.toList()
        }
    }
}
