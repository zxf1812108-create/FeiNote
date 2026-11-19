package com.feiji.bulletnotes.data

import java.util.Locale

data class KeywordHintEntry(
    val displayKeyword: String,
    val normalizedKeyword: String,
    val notes: List<NoteEntity>
)

data class KeywordHintIndex(
    val byNormalizedKeyword: Map<String, KeywordHintEntry>,
    val sortedKeywordsDesc: List<String>
) {
    companion object {
        val EMPTY = KeywordHintIndex(emptyMap(), emptyList())
    }
}

data class KeywordMatch(
    val displayKeyword: String,
    val normalizedKeyword: String,
    val notes: List<NoteEntity>
)

/**
 * 关键字即时提示索引与匹配引擎：
 * - 同时支持 @关键字@ 与 #关键字#（同一对需首尾符号一致，不跨换行，长度 1..20）
 * - 对 @ 自动过滤常见邮箱地址，避免将两段邮箱中间正文误判为关键字
 * - 匹配时聚焦本次新输入区间与光标前窗口，兼顾逐字输入与中文输入法整词上屏，
 *   并按“最靠近光标优先、同位置最长关键字优先”收敛结果。
 */
object KeywordHintEngine {
    const val MAX_KEYWORD_LENGTH = 20

    private val emailDomainPrefixRegex = Regex("^[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+")

    fun extractKeywords(content: String): List<String> {
        if (content.isEmpty() || (!content.contains('@') && !content.contains('#'))) {
            return emptyList()
        }
        val results = ArrayList<String>()
        val seenNormalized = HashSet<String>()

        content.lineSequence().forEach { line ->
            var i = 0
            val len = line.length
            while (i < len) {
                val ch = line[i]
                if (ch == '@' || ch == '#') {
                    if (ch == '@' && isEmailAt(line, i)) {
                        i++
                        continue
                    }
                    val closingIndex = findClosingDelimiter(line, i + 1, ch)
                    if (closingIndex > i + 1) {
                        val raw = line.substring(i + 1, closingIndex)
                        val trimmed = raw.trim()
                        if (trimmed.isNotEmpty() && trimmed.length <= MAX_KEYWORD_LENGTH) {
                            val normalized = trimmed.lowercase(Locale.ROOT)
                            if (seenNormalized.add(normalized)) {
                                results.add(trimmed)
                            }
                            i = closingIndex + 1
                            continue
                        }
                    }
                }
                i++
            }
        }
        return results
    }

    private fun findClosingDelimiter(line: String, startIndex: Int, delimiter: Char): Int {
        var idx = startIndex
        val len = line.length
        while (idx < len) {
            if (line[idx] == delimiter) {
                if (delimiter == '@' && isEmailAt(line, idx)) {
                    idx++
                    continue
                }
                return idx
            }
            idx++
        }
        return -1
    }

    private fun isEmailAt(line: String, atIndex: Int): Boolean {
        if (atIndex <= 0 || atIndex + 3 >= line.length) return false
        val prev = line[atIndex - 1]
        val isLocalChar = prev in 'A'..'Z' || prev in 'a'..'z' || prev in '0'..'9' ||
            prev == '.' || prev == '_' || prev == '-' || prev == '+'
        if (!isLocalChar) return false
        val after = line.substring(atIndex + 1)
        return emailDomainPrefixRegex.containsMatchIn(after)
    }

    fun buildIndex(notes: List<NoteEntity>): KeywordHintIndex {
        if (notes.isEmpty()) return KeywordHintIndex.EMPTY

        val buckets = LinkedHashMap<String, MutableList<Pair<String, NoteEntity>>>()
        for (note in notes) {
            if (note.isDeleted) continue
            val keywords = extractKeywords(note.content)
            for (keyword in keywords) {
                val normalized = keyword.lowercase(Locale.ROOT)
                buckets.getOrPut(normalized) { ArrayList() }.add(keyword to note)
            }
        }
        if (buckets.isEmpty()) return KeywordHintIndex.EMPTY

        val noteComparator = compareByDescending<NoteEntity> { it.dateString }
            .thenBy { it.orderIndex }
            .thenByDescending { it.updatedAt }
            .thenByDescending { it.id }

        val entryMap = HashMap<String, KeywordHintEntry>(buckets.size)
        for ((normalized, pairs) in buckets) {
            val uniqueNotes = pairs
                .map { it.second }
                .distinctBy { it.id }
                .sortedWith(noteComparator)
            if (uniqueNotes.isEmpty()) continue
            val primaryNoteId = uniqueNotes.first().id
            val displayKeyword = pairs.firstOrNull { it.second.id == primaryNoteId }?.first
                ?: pairs.first().first
            entryMap[normalized] = KeywordHintEntry(
                displayKeyword = displayKeyword,
                normalizedKeyword = normalized,
                notes = uniqueNotes
            )
        }

        val sortedKeywordsDesc = entryMap.keys.sortedByDescending { it.length }
        return KeywordHintIndex(
            byNormalizedKeyword = entryMap,
            sortedKeywordsDesc = sortedKeywordsDesc
        )
    }

    /**
     * 在当前输入的文本中查找由本次新增内容触发的关联关键字提示。
     * - 仅在文本增长（输入/上屏/粘贴）时触发，删除字符不反复打扰；
     * - 同时支持逐字补全与输入法整句上屏；
     * - 优先返回最靠近光标的命中项，同结束位置时优先最长关键字。
     */
    fun findMatchOnEdit(
        previousText: String,
        currentText: String,
        cursorPosition: Int,
        index: KeywordHintIndex,
        excludeNoteId: Long,
        dismissedKeywords: Set<String>
    ): KeywordMatch? {
        if (index.sortedKeywordsDesc.isEmpty()) return null
        if (currentText.length <= previousText.length) return null

        val safeCursor = cursorPosition.coerceIn(0, currentText.length)
        if (safeCursor == 0) return null

        var commonPrefix = 0
        val prefixLimit = minOf(previousText.length, safeCursor)
        while (commonPrefix < prefixLimit && previousText[commonPrefix] == currentText[commonPrefix]) {
            commonPrefix++
        }
        val insertStart = commonPrefix
        if (safeCursor <= insertStart) return null

        val windowStart = (insertStart - MAX_KEYWORD_LENGTH + 1).coerceAtLeast(0)
        val windowLower = currentText.substring(windowStart, safeCursor).lowercase(Locale.ROOT)
        if (windowLower.isEmpty()) return null

        var bestEntry: KeywordHintEntry? = null
        var bestFilteredNotes: List<NoteEntity>? = null
        var bestEndGlobal = -1
        var bestLength = -1

        for (normalizedKeyword in index.sortedKeywordsDesc) {
            if (normalizedKeyword in dismissedKeywords) continue
            val kwLen = normalizedKeyword.length
            if (kwLen > windowLower.length) continue

            val localStart = windowLower.lastIndexOf(normalizedKeyword)
            if (localStart < 0) continue

            val globalEnd = windowStart + localStart + kwLen
            // 仅当命中的关键字至少包含本次新输入的最后一个字符区间时才触发，避免编辑别处时反复弹出旧词。
            if (globalEnd <= insertStart) continue

            if (globalEnd < bestEndGlobal || (globalEnd == bestEndGlobal && kwLen <= bestLength)) {
                continue
            }

            val entry = index.byNormalizedKeyword[normalizedKeyword] ?: continue
            val filtered = entry.notes.filter { it.id != excludeNoteId }
            if (filtered.isEmpty()) continue

            bestEntry = entry
            bestFilteredNotes = filtered
            bestEndGlobal = globalEnd
            bestLength = kwLen
        }

        val matchedEntry = bestEntry ?: return null
        val matchedNotes = bestFilteredNotes ?: return null
        return KeywordMatch(
            displayKeyword = matchedEntry.displayKeyword,
            normalizedKeyword = matchedEntry.normalizedKeyword,
            notes = matchedNotes
        )
    }
}
