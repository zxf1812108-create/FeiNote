package com.feiji.bulletnotes.data

/**
 * 飞记轻量级纯本地 Markdown 解析器（零第三方依赖）：
 * - 支持标题（H1–H3，# 后必须有空格）、粗体/斜体/删除线、行内代码、围栏代码块、引用块、
 *   无序列表（保留圆点）、有序列表、任务复选框（- [ ] / - [x]）、分割线、安全链接（http/https/mailto）。
 * - 为每个可见字符维护到原文的精确偏移映射（rawOffsets），确保浏览态点击进入编辑态时准确落光标。
 * - 提供 stripMarkdownForSearch 供全文检索忽略 Markdown 标记字符。
 */
object MarkdownParser {

    data class InlineStyleRun(
        val start: Int,
        val end: Int,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
        val code: Boolean = false,
        val linkUrl: String? = null,
        val isImageLink: Boolean = false
    )

    data class ParsedInline(
        val displayText: String,
        val rawOffsets: IntArray,
        val runs: List<InlineStyleRun>,
        val rawStart: Int,
        val rawEnd: Int
    ) {
        fun mapDisplayOffsetToRaw(displayOffset: Int): Int {
            if (rawOffsets.isEmpty()) return rawStart
            if (displayOffset <= 0) return rawOffsets[0]
            if (displayOffset >= rawOffsets.size) return rawEnd
            return rawOffsets[displayOffset]
        }

        fun findLinkAt(displayOffset: Int): String? {
            return runs.firstOrNull { run ->
                run.linkUrl != null && displayOffset in run.start until run.end
            }?.linkUrl
        }
    }

    sealed class MarkdownBlock {
        abstract val rawStart: Int
        abstract val rawEnd: Int

        data class Heading(
            val level: Int,
            val inline: ParsedInline,
            override val rawStart: Int,
            override val rawEnd: Int
        ) : MarkdownBlock()

        data class Quote(
            val lines: List<ParsedInline>,
            override val rawStart: Int,
            override val rawEnd: Int
        ) : MarkdownBlock()

        data class CodeBlock(
            val language: String,
            val code: String,
            val codeRawStart: Int,
            override val rawStart: Int,
            override val rawEnd: Int
        ) : MarkdownBlock()

        data class UnorderedListItem(
            val indentLevel: Int,
            val inline: ParsedInline,
            override val rawStart: Int,
            override val rawEnd: Int
        ) : MarkdownBlock()

        data class OrderedListItem(
            val indentLevel: Int,
            val marker: String,
            val inline: ParsedInline,
            override val rawStart: Int,
            override val rawEnd: Int
        ) : MarkdownBlock()

        data class TaskListItem(
            val indentLevel: Int,
            val checked: Boolean,
            val checkboxCharRawIndex: Int,
            val inline: ParsedInline,
            override val rawStart: Int,
            override val rawEnd: Int
        ) : MarkdownBlock()

        data class HorizontalRule(
            override val rawStart: Int,
            override val rawEnd: Int
        ) : MarkdownBlock()

        data class Paragraph(
            val inline: ParsedInline,
            override val rawStart: Int,
            override val rawEnd: Int
        ) : MarkdownBlock()

        data class BlankLine(
            override val rawStart: Int,
            override val rawEnd: Int
        ) : MarkdownBlock()
    }

    private data class RawLine(
        val text: String,
        val start: Int,
        val end: Int
    )

    private val headingRegex = Regex("""^(#{1,3})[ \t]+(.*)$""")
    private val hrRegex = Regex("""^[ \t]*(?:-[ \t]*-[ \t]*-[- \t]*|\*[ \t]*\*[ \t]*\*[\* \t]*|_[ \t]*_[ \t]*_[_ \t]*)$""")
    private val fenceRegex = Regex("""^[ \t]*(`{3,}|~{3,})[ \t]*([A-Za-z0-9_+#.-]*)[ \t]*$""")
    private val taskItemRegex = Regex("""^([ \t]*)[-*+][ \t]+\[([ xX])\](?:[ \t]+(.*)|)$""")
    private val unorderedItemRegex = Regex("""^([ \t]*)[-*+][ \t]+(.*)$""")
    private val orderedItemRegex = Regex("""^([ \t]*)(\d+\.)[ \t]+(.*)$""")
    private val quoteRegex = Regex("""^[ \t]*>[ \t]?(.*)$""")

    private fun splitRawLines(source: String): List<RawLine> {
        if (source.isEmpty()) return emptyList()
        val result = ArrayList<RawLine>()
        var lineStart = 0
        var i = 0
        val len = source.length
        while (i < len) {
            if (source[i] == '\n') {
                result.add(RawLine(source.substring(lineStart, i), lineStart, i))
                lineStart = i + 1
            }
            i++
        }
        result.add(RawLine(source.substring(lineStart, len), lineStart, len))
        return result
    }

    private fun computeIndentLevel(indent: String): Int {
        var spaces = 0
        for (ch in indent) {
            spaces += if (ch == '\t') 4 else 1
        }
        return (spaces / 2).coerceIn(0, 4)
    }

    private fun isSafeUrl(rawUrl: String): Boolean {
        val trimmed = rawUrl.trim().lowercase()
        return trimmed.startsWith("https://") ||
            trimmed.startsWith("http://") ||
            trimmed.startsWith("mailto:")
    }

    fun parseBlocks(source: String): List<MarkdownBlock> {
        val lines = splitRawLines(source)
        if (lines.isEmpty()) return emptyList()

        val blocks = ArrayList<MarkdownBlock>()
        var idx = 0
        while (idx < lines.size) {
            val line = lines[idx]
            val text = line.text

            // 1. 空行
            if (text.isBlank()) {
                blocks.add(MarkdownBlock.BlankLine(line.start, line.end))
                idx++
                continue
            }

            // 2. 围栏代码块（``` 或 ~~~）
            val fenceMatch = fenceRegex.matchEntire(text)
            if (fenceMatch != null) {
                val markerChar = fenceMatch.groupValues[1][0]
                val markerLen = fenceMatch.groupValues[1].length
                val lang = fenceMatch.groupValues[2]
                val blockStart = line.start
                var closeIdx = idx + 1
                while (closeIdx < lines.size) {
                    val trimmed = lines[closeIdx].text.trim()
                    if (trimmed.length >= markerLen && trimmed.all { it == markerChar }) {
                        break
                    }
                    closeIdx++
                }
                val codeLines = if (idx + 1 < closeIdx) {
                    lines.subList(idx + 1, closeIdx)
                } else {
                    emptyList()
                }
                val codeText = codeLines.joinToString("\n") { it.text }
                val codeRawStart = codeLines.firstOrNull()?.start ?: line.end
                val blockEnd = if (closeIdx < lines.size) lines[closeIdx].end else lines.last().end
                blocks.add(
                    MarkdownBlock.CodeBlock(
                        language = lang,
                        code = codeText,
                        codeRawStart = codeRawStart,
                        rawStart = blockStart,
                        rawEnd = blockEnd
                    )
                )
                idx = if (closeIdx < lines.size) closeIdx + 1 else lines.size
                continue
            }

            // 3. 分割线
            if (hrRegex.matches(text)) {
                blocks.add(MarkdownBlock.HorizontalRule(line.start, line.end))
                idx++
                continue
            }

            // 4. 标题（# / ## / ### 后必须跟空格）
            val headingMatch = headingRegex.matchEntire(text)
            if (headingMatch != null) {
                val level = headingMatch.groupValues[1].length
                val contentGroup = headingMatch.groups[2]!!
                val inline = parseInline(
                    source = source,
                    startIndex = line.start + contentGroup.range.first,
                    endIndex = line.start + contentGroup.range.last + 1
                )
                blocks.add(MarkdownBlock.Heading(level, inline, line.start, line.end))
                idx++
                continue
            }

            // 5. 引用块（合并连续的 > 行）
            val quoteMatch = quoteRegex.matchEntire(text)
            if (quoteMatch != null) {
                val quoteLines = ArrayList<ParsedInline>()
                val blockStart = line.start
                var blockEnd = line.end
                while (idx < lines.size) {
                    val cur = lines[idx]
                    val m = quoteRegex.matchEntire(cur.text) ?: break
                    val g = m.groups[1]!!
                    val contentStart = if (g.range.isEmpty()) cur.end else cur.start + g.range.first
                    val contentEnd = if (g.range.isEmpty()) cur.end else cur.start + g.range.last + 1
                    quoteLines.add(parseInline(source, contentStart, contentEnd))
                    blockEnd = cur.end
                    idx++
                }
                blocks.add(MarkdownBlock.Quote(quoteLines, blockStart, blockEnd))
                continue
            }

            // 6. 任务复选框列表（- [ ] / - [x]）
            val taskMatch = taskItemRegex.matchEntire(text)
            if (taskMatch != null) {
                val indent = computeIndentLevel(taskMatch.groupValues[1])
                val checkGroup = taskMatch.groups[2]!!
                val checked = checkGroup.value.equals("x", ignoreCase = true)
                val checkCharIndex = line.start + checkGroup.range.first
                val contentGroup = taskMatch.groups[3]
                val inline = if (contentGroup != null && !contentGroup.range.isEmpty()) {
                    parseInline(
                        source = source,
                        startIndex = line.start + contentGroup.range.first,
                        endIndex = line.start + contentGroup.range.last + 1
                    )
                } else {
                    ParsedInline("", IntArray(0), emptyList(), line.end, line.end)
                }
                blocks.add(
                    MarkdownBlock.TaskListItem(
                        indentLevel = indent,
                        checked = checked,
                        checkboxCharRawIndex = checkCharIndex,
                        inline = inline,
                        rawStart = line.start,
                        rawEnd = line.end
                    )
                )
                idx++
                continue
            }

            // 7. 无序列表（保留圆点）
            val ulMatch = unorderedItemRegex.matchEntire(text)
            if (ulMatch != null) {
                val indent = computeIndentLevel(ulMatch.groupValues[1])
                val contentGroup = ulMatch.groups[2]!!
                val inline = parseInline(
                    source = source,
                    startIndex = line.start + contentGroup.range.first,
                    endIndex = line.start + contentGroup.range.last + 1
                )
                blocks.add(MarkdownBlock.UnorderedListItem(indent, inline, line.start, line.end))
                idx++
                continue
            }

            // 8. 有序列表
            val olMatch = orderedItemRegex.matchEntire(text)
            if (olMatch != null) {
                val indent = computeIndentLevel(olMatch.groupValues[1])
                val marker = olMatch.groupValues[2]
                val contentGroup = olMatch.groups[3]!!
                val inline = parseInline(
                    source = source,
                    startIndex = line.start + contentGroup.range.first,
                    endIndex = line.start + contentGroup.range.last + 1
                )
                blocks.add(MarkdownBlock.OrderedListItem(indent, marker, inline, line.start, line.end))
                idx++
                continue
            }

            // 9. 普通段落行
            val inline = parseInline(source, line.start, line.end)
            blocks.add(MarkdownBlock.Paragraph(inline, line.start, line.end))
            idx++
        }
        return blocks
    }

    /**
     * 收起单行预览解析：
     * - 去掉标题 `# `、引用 `> ` 等块级符号但保持正文正常字号，确保左侧项目符号对齐不跳动；
     * - 无序列表保留圆点 `• `；有序列表保留编号；任务列表显示 `☐ `/`☑ `；
     * - 若第一行是围栏代码开头（```），自动预览下一行有效代码或显示 `[代码]`。
     */
    fun parseCollapsedFirstLine(source: String): ParsedInline {
        if (source.isEmpty()) {
            return ParsedInline("", IntArray(0), emptyList(), 0, 0)
        }
        val lines = splitRawLines(source)
        val first = lines.first()
        val text = first.text

        // 第一行是围栏代码开头
        val fenceMatch = fenceRegex.matchEntire(text)
        if (fenceMatch != null) {
            val markerChar = fenceMatch.groupValues[1][0]
            val markerLen = fenceMatch.groupValues[1].length
            val lang = fenceMatch.groupValues[2]
            var previewLine: RawLine? = null
            for (k in 1 until lines.size) {
                val trimmed = lines[k].text.trim()
                if (trimmed.length >= markerLen && trimmed.all { it == markerChar }) break
                if (trimmed.isNotEmpty()) {
                    previewLine = lines[k]
                    break
                }
            }
            return if (previewLine != null) {
                val trimmedCode = previewLine.text.trim()
                val trimOffset = previewLine.text.indexOf(trimmedCode).coerceAtLeast(0)
                val baseRaw = previewLine.start + trimOffset
                val offsets = IntArray(trimmedCode.length) { baseRaw + it }
                ParsedInline(
                    displayText = trimmedCode,
                    rawOffsets = offsets,
                    runs = listOf(InlineStyleRun(0, trimmedCode.length, code = true)),
                    rawStart = baseRaw,
                    rawEnd = baseRaw + trimmedCode.length
                )
            } else {
                val label = if (lang.isNotEmpty()) "[$lang 代码]" else "[代码]"
                val offsets = IntArray(label.length) { first.start }
                ParsedInline(
                    displayText = label,
                    rawOffsets = offsets,
                    runs = listOf(InlineStyleRun(0, label.length, code = true)),
                    rawStart = first.start,
                    rawEnd = first.end
                )
            }
        }

        return parseSingleLineForPreview(source, first)
    }

    /**
     * 多行精简预览（用于关键词联想卡片最多 3 行、回收站摘要最多 2 行）：
     * 保留无序列表圆点、有序编号与行内样式，跳过代码块围栏行。
     */
    fun parsePreviewLines(source: String, maxLines: Int): Pair<ParsedInline, Boolean> {
        if (source.isEmpty()) {
            return ParsedInline("", IntArray(0), emptyList(), 0, 0) to false
        }
        val lines = splitRawLines(source)
        val outText = StringBuilder()
        val outOffsets = ArrayList<Int>()
        val outRuns = ArrayList<InlineStyleRun>()

        var taken = 0
        var inCodeFence = false
        var fenceChar = '`'
        var fenceLen = 3
        var hasMore = false
        var lastRawEnd = 0

        for (i in lines.indices) {
            val line = lines[i]
            val trimmed = line.text.trim()
            if (inCodeFence) {
                if (trimmed.length >= fenceLen && trimmed.all { it == fenceChar }) {
                    inCodeFence = false
                    continue
                }
            } else {
                val fenceMatch = fenceRegex.matchEntire(line.text)
                if (fenceMatch != null) {
                    inCodeFence = true
                    fenceChar = fenceMatch.groupValues[1][0]
                    fenceLen = fenceMatch.groupValues[1].length
                    continue
                }
            }

            if (taken >= maxLines) {
                hasMore = true
                break
            }

            if (taken > 0) {
                outText.append('\n')
                outOffsets.add(line.start)
            }

            val parsedLine = if (inCodeFence) {
                val codeText = line.text
                val offsets = IntArray(codeText.length) { line.start + it }
                ParsedInline(
                    displayText = codeText,
                    rawOffsets = offsets,
                    runs = if (codeText.isNotEmpty()) listOf(InlineStyleRun(0, codeText.length, code = true)) else emptyList(),
                    rawStart = line.start,
                    rawEnd = line.end
                )
            } else {
                parseSingleLineForPreview(source, line)
            }

            val baseDisplayOffset = outText.length
            outText.append(parsedLine.displayText)
            for (o in parsedLine.rawOffsets) outOffsets.add(o)
            for (r in parsedLine.runs) {
                outRuns.add(
                    r.copy(
                        start = r.start + baseDisplayOffset,
                        end = r.end + baseDisplayOffset
                    )
                )
            }
            lastRawEnd = line.end
            taken++
        }

        if (hasMore) {
            outText.append('…')
            outOffsets.add(lastRawEnd)
        }

        val combined = ParsedInline(
            displayText = outText.toString(),
            rawOffsets = outOffsets.toIntArray(),
            runs = outRuns,
            rawStart = 0,
            rawEnd = lastRawEnd
        )
        return combined to hasMore
    }

    private fun parseSingleLineForPreview(source: String, line: RawLine): ParsedInline {
        val text = line.text
        if (hrRegex.matches(text)) {
            val rule = "———"
            return ParsedInline(
                displayText = rule,
                rawOffsets = IntArray(rule.length) { line.start },
                runs = emptyList(),
                rawStart = line.start,
                rawEnd = line.end
            )
        }

        val headingMatch = headingRegex.matchEntire(text)
        if (headingMatch != null) {
            val g = headingMatch.groups[2]!!
            return parseInline(source, line.start + g.range.first, line.start + g.range.last + 1)
        }

        val quoteMatch = quoteRegex.matchEntire(text)
        if (quoteMatch != null) {
            val g = quoteMatch.groups[1]!!
            val s = if (g.range.isEmpty()) line.end else line.start + g.range.first
            val e = if (g.range.isEmpty()) line.end else line.start + g.range.last + 1
            return parseInline(source, s, e)
        }

        val taskMatch = taskItemRegex.matchEntire(text)
        if (taskMatch != null) {
            val checked = taskMatch.groupValues[2].equals("x", ignoreCase = true)
            val prefix = if (checked) "☑ " else "☐ "
            val contentGroup = taskMatch.groups[3]
            val body = if (contentGroup != null && !contentGroup.range.isEmpty()) {
                parseInline(source, line.start + contentGroup.range.first, line.start + contentGroup.range.last + 1)
            } else {
                ParsedInline("", IntArray(0), emptyList(), line.end, line.end)
            }
            return prependPrefix(prefix, line.start, body)
        }

        // 无序列表：保留前面的圆点
        val ulMatch = unorderedItemRegex.matchEntire(text)
        if (ulMatch != null) {
            val g = ulMatch.groups[2]!!
            val body = parseInline(source, line.start + g.range.first, line.start + g.range.last + 1)
            return prependPrefix("• ", line.start, body)
        }

        val olMatch = orderedItemRegex.matchEntire(text)
        if (olMatch != null) {
            val marker = olMatch.groupValues[2]
            val g = olMatch.groups[3]!!
            val body = parseInline(source, line.start + g.range.first, line.start + g.range.last + 1)
            return prependPrefix("$marker ", line.start, body)
        }

        return parseInline(source, line.start, line.end)
    }

    private fun prependPrefix(prefix: String, prefixRawOffset: Int, body: ParsedInline): ParsedInline {
        val shift = prefix.length
        val offsets = IntArray(shift + body.rawOffsets.size)
        for (i in 0 until shift) offsets[i] = prefixRawOffset
        body.rawOffsets.copyInto(offsets, destinationOffset = shift)
        val shiftedRuns = body.runs.map { it.copy(start = it.start + shift, end = it.end + shift) }
        return ParsedInline(
            displayText = prefix + body.displayText,
            rawOffsets = offsets,
            runs = shiftedRuns,
            rawStart = prefixRawOffset,
            rawEnd = body.rawEnd
        )
    }

    /** 切换原文中指定位置的任务复选框（[ ] <-> [x]）。 */
    fun toggleTaskCheckbox(source: String, checkboxCharRawIndex: Int): String {
        if (checkboxCharRawIndex <= 0 || checkboxCharRawIndex >= source.length - 1) return source
        if (source[checkboxCharRawIndex - 1] != '[' || source[checkboxCharRawIndex + 1] != ']') return source
        val current = source[checkboxCharRawIndex]
        val replacement = when (current) {
            ' ' -> 'x'
            'x', 'X' -> ' '
            else -> return source
        }
        return buildString(source.length) {
            append(source, 0, checkboxCharRawIndex)
            append(replacement)
            append(source, checkboxCharRawIndex + 1, source.length)
        }
    }

    /**
     * 生成供全文搜索使用的纯文本：忽略所有 Markdown 标记字符，
     * 使得搜索“重要会议”可以命中“**重要**会议”，搜索“项目计划”可以命中“# 项目[计划](url)”。
     */
    fun stripMarkdownForSearch(source: String): String {
        if (source.isEmpty()) return ""
        val blocks = parseBlocks(source)
        if (blocks.isEmpty()) return ""
        val sb = StringBuilder(source.length)
        for (i in blocks.indices) {
            if (i > 0) sb.append('\n')
            when (val b = blocks[i]) {
                is MarkdownBlock.Heading -> sb.append(b.inline.displayText)
                is MarkdownBlock.Quote -> {
                    b.lines.forEachIndexed { idx, l ->
                        if (idx > 0) sb.append('\n')
                        sb.append(l.displayText)
                    }
                }
                is MarkdownBlock.CodeBlock -> sb.append(b.code)
                is MarkdownBlock.UnorderedListItem -> sb.append(b.inline.displayText)
                is MarkdownBlock.OrderedListItem -> sb.append(b.inline.displayText)
                is MarkdownBlock.TaskListItem -> sb.append(b.inline.displayText)
                is MarkdownBlock.HorizontalRule -> Unit
                is MarkdownBlock.Paragraph -> sb.append(b.inline.displayText)
                is MarkdownBlock.BlankLine -> Unit
            }
        }
        return sb.toString()
    }

    /**
     * 解析行内 Markdown：粗斜体、粗体、斜体、删除线、行内代码、链接/图片、反斜杠转义。
     * 严守词边界规则，防止 `file_name_v2`、`10*20*30`、`#标签` 被误渲染。
     */
    fun parseInline(source: String, startIndex: Int, endIndex: Int): ParsedInline {
        val safeStart = startIndex.coerceIn(0, source.length)
        val safeEnd = endIndex.coerceIn(safeStart, source.length)
        val display = StringBuilder(safeEnd - safeStart)
        val offsets = ArrayList<Int>(safeEnd - safeStart)
        val runs = ArrayList<InlineStyleRun>()

        fun emitRange(
            from: Int,
            to: Int,
            bold: Boolean,
            italic: Boolean,
            strike: Boolean
        ) {
            var i = from
            while (i < to) {
                val ch = source[i]

                // 1. 反斜杠转义（\* \_ \` \~ \[ \] \# \\）
                if (ch == '\\' && i + 1 < to && isEscapableChar(source[i + 1])) {
                    val runStart = display.length
                    display.append(source[i + 1])
                    offsets.add(i + 1)
                    if (bold || italic || strike) {
                        runs.add(InlineStyleRun(runStart, display.length, bold = bold, italic = italic, strike = strike))
                    }
                    i += 2
                    continue
                }

                // 2. 行内代码 `code`（内部不再递归解析其他标记）
                if (ch == '`') {
                    val close = source.indexOf('`', i + 1)
                    if (close in (i + 1) until to && close > i + 1) {
                        val runStart = display.length
                        for (k in (i + 1) until close) {
                            display.append(source[k])
                            offsets.add(k)
                        }
                        runs.add(
                            InlineStyleRun(
                                start = runStart,
                                end = display.length,
                                bold = bold,
                                italic = italic,
                                strike = strike,
                                code = true
                            )
                        )
                        i = close + 1
                        continue
                    }
                }

                // 3. 图片语法 ![alt](url) -> 不自动拉取外链图，安全渲染为可点击链接标签
                if (ch == '!' && i + 1 < to && source[i + 1] == '[') {
                    val linkMatch = matchLink(source, i + 1, to)
                    if (linkMatch != null) {
                        val altText = source.substring(linkMatch.labelStart, linkMatch.labelEnd).trim()
                        val shown = if (altText.isNotEmpty()) "[图片: $altText]" else "[图片]"
                        val safeUrl = linkMatch.url.takeIf { isSafeUrl(it) }
                        val runStart = display.length
                        for (cIdx in shown.indices) {
                            display.append(shown[cIdx])
                            offsets.add((linkMatch.labelStart + cIdx).coerceAtMost(linkMatch.rawEnd - 1))
                        }
                        runs.add(
                            InlineStyleRun(
                                start = runStart,
                                end = display.length,
                                bold = bold,
                                italic = italic,
                                strike = strike,
                                linkUrl = safeUrl,
                                isImageLink = true
                            )
                        )
                        i = linkMatch.rawEnd
                        continue
                    }
                }

                // 4. 链接语法 [text](url)
                if (ch == '[') {
                    val linkMatch = matchLink(source, i, to)
                    if (linkMatch != null) {
                        val safeUrl = linkMatch.url.takeIf { isSafeUrl(it) }
                        val runStart = display.length
                        // 支持链接文字内部包含加粗/斜体等样式
                        emitRange(linkMatch.labelStart, linkMatch.labelEnd, bold, italic, strike)
                        if (display.length > runStart && safeUrl != null) {
                            runs.add(
                                InlineStyleRun(
                                    start = runStart,
                                    end = display.length,
                                    bold = bold,
                                    italic = italic,
                                    strike = strike,
                                    linkUrl = safeUrl
                                )
                            )
                        }
                        i = linkMatch.rawEnd
                        continue
                    }
                }

                // 5. 粗斜体 ***text***
                if (ch == '*' && i + 2 < to && source[i + 1] == '*' && source[i + 2] == '*') {
                    val close = findClosingMarker(source, "***", i + 3, to, requireWordBoundary = false)
                    if (close != -1) {
                        emitRange(i + 3, close, bold = true, italic = true, strike = strike)
                        i = close + 3
                        continue
                    }
                }

                // 6. 粗体 **text**
                if (ch == '*' && i + 1 < to && source[i + 1] == '*') {
                    val close = findClosingMarker(source, "**", i + 2, to, requireWordBoundary = false)
                    if (close != -1) {
                        emitRange(i + 2, close, bold = true, italic = italic, strike = strike)
                        i = close + 2
                        continue
                    }
                }

                // 7. 粗体 __text__（仅词边界生效）
                if (ch == '_' && i + 1 < to && source[i + 1] == '_' && isLeftWordBoundary(source, i, from)) {
                    val close = findClosingMarker(source, "__", i + 2, to, requireWordBoundary = true)
                    if (close != -1) {
                        emitRange(i + 2, close, bold = true, italic = italic, strike = strike)
                        i = close + 2
                        continue
                    }
                }

                // 8. 删除线 ~~text~~
                if (ch == '~' && i + 1 < to && source[i + 1] == '~') {
                    val close = findClosingMarker(source, "~~", i + 2, to, requireWordBoundary = false)
                    if (close != -1) {
                        emitRange(i + 2, close, bold = bold, italic = italic, strike = true)
                        i = close + 2
                        continue
                    }
                }

                // 9. 斜体 *text*（避开 10*20*30 等纯数字乘法及空格包围）
                if (ch == '*' && canOpenSingleAsterisk(source, i, from, to)) {
                    val close = findClosingSingleAsterisk(source, i + 1, to)
                    if (close != -1) {
                        emitRange(i + 1, close, bold = bold, italic = true, strike = strike)
                        i = close + 1
                        continue
                    }
                }

                // 10. 斜体 _text_（仅词边界生效，避免误伤 file_name_v2）
                if (ch == '_' && isLeftWordBoundary(source, i, from) && i + 1 < to && !source[i + 1].isWhitespace()) {
                    val close = findClosingMarker(source, "_", i + 1, to, requireWordBoundary = true)
                    if (close != -1) {
                        emitRange(i + 1, close, bold = bold, italic = true, strike = strike)
                        i = close + 1
                        continue
                    }
                }

                // 普通字符
                val runStart = display.length
                display.append(ch)
                offsets.add(i)
                if (bold || italic || strike) {
                    runs.add(InlineStyleRun(runStart, display.length, bold = bold, italic = italic, strike = strike))
                }
                i++
            }
        }

        emitRange(safeStart, safeEnd, bold = false, italic = false, strike = false)
        return ParsedInline(
            displayText = display.toString(),
            rawOffsets = offsets.toIntArray(),
            runs = mergeAdjacentRuns(runs),
            rawStart = safeStart,
            rawEnd = safeEnd
        )
    }

    private data class LinkMatch(
        val labelStart: Int,
        val labelEnd: Int,
        val url: String,
        val rawEnd: Int
    )

    private fun matchLink(source: String, openBracket: Int, limit: Int): LinkMatch? {
        var i = openBracket + 1
        var closeBracket = -1
        while (i < limit) {
            if (source[i] == '\\' && i + 1 < limit) {
                i += 2
                continue
            }
            if (source[i] == ']') {
                closeBracket = i
                break
            }
            i++
        }
        if (closeBracket == -1 || closeBracket + 1 >= limit || source[closeBracket + 1] != '(') {
            return null
        }
        val urlStart = closeBracket + 2
        var depth = 1
        var j = urlStart
        while (j < limit && depth > 0) {
            when (source[j]) {
                '(' -> depth++
                ')' -> depth--
            }
            if (depth == 0) break
            j++
        }
        if (depth != 0) return null
        val url = source.substring(urlStart, j).trim()
        if (url.isEmpty()) return null
        return LinkMatch(
            labelStart = openBracket + 1,
            labelEnd = closeBracket,
            url = url,
            rawEnd = j + 1
        )
    }

    private fun isEscapableChar(c: Char): Boolean {
        return c in "\\`*_{}[]()#+-.!~"
    }

    private fun isAsciiWord(c: Char): Boolean {
        return (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c == '_'
    }

    private fun isLeftWordBoundary(source: String, index: Int, minIndex: Int): Boolean {
        if (index <= minIndex) return true
        return !isAsciiWord(source[index - 1])
    }

    private fun isRightWordBoundary(source: String, afterIndex: Int, limit: Int): Boolean {
        if (afterIndex >= limit) return true
        return !isAsciiWord(source[afterIndex])
    }

    private fun canOpenSingleAsterisk(source: String, index: Int, minIndex: Int, limit: Int): Boolean {
        if (index + 1 >= limit) return false
        val next = source[index + 1]
        if (next.isWhitespace() || next == '*') return false
        if (index > minIndex && source[index - 1].isDigit() && next.isDigit()) return false
        return true
    }

    private fun findClosingSingleAsterisk(source: String, from: Int, limit: Int): Int {
        var i = from
        while (i < limit) {
            if (source[i] == '\\' && i + 1 < limit) {
                i += 2
                continue
            }
            if (source[i] == '`') {
                val endCode = source.indexOf('`', i + 1)
                if (endCode in (i + 1) until limit) {
                    i = endCode + 1
                    continue
                }
            }
            if (source[i] == '*') {
                val prevIsStar = i > from && source[i - 1] == '*'
                val nextIsStar = i + 1 < limit && source[i + 1] == '*'
                val prev = source[i - 1]
                val nextIsDigit = i + 1 < limit && source[i + 1].isDigit()
                if (!prevIsStar && !nextIsStar && i > from && !prev.isWhitespace() && !(prev.isDigit() && nextIsDigit)) {
                    return i
                }
            }
            i++
        }
        return -1
    }

    private fun findClosingMarker(
        source: String,
        marker: String,
        from: Int,
        limit: Int,
        requireWordBoundary: Boolean
    ): Int {
        val mLen = marker.length
        if (from >= limit || source[from].isWhitespace()) return -1
        var i = from
        while (i + mLen <= limit) {
            if (source[i] == '\\' && i + 1 < limit) {
                i += 2
                continue
            }
            if (source[i] == '`') {
                val endCode = source.indexOf('`', i + 1)
                if (endCode in (i + 1) until limit) {
                    i = endCode + 1
                    continue
                }
            }
            if (source.regionMatches(i, marker, 0, mLen)) {
                val contentNonEmpty = i > from
                val prevNotSpace = contentNonEmpty && !source[i - 1].isWhitespace()
                val boundaryOk = !requireWordBoundary || isRightWordBoundary(source, i + mLen, limit)
                if (contentNonEmpty && prevNotSpace && boundaryOk) {
                    return i
                }
            }
            i++
        }
        return -1
    }

    private fun mergeAdjacentRuns(runs: List<InlineStyleRun>): List<InlineStyleRun> {
        if (runs.size <= 1) return runs
        val merged = ArrayList<InlineStyleRun>(runs.size)
        for (run in runs) {
            if (run.end <= run.start) continue
            val prev = merged.lastOrNull()
            if (prev != null &&
                prev.end == run.start &&
                prev.bold == run.bold &&
                prev.italic == run.italic &&
                prev.strike == run.strike &&
                prev.code == run.code &&
                prev.linkUrl == run.linkUrl &&
                prev.isImageLink == run.isImageLink
            ) {
                merged[merged.lastIndex] = prev.copy(end = run.end)
            } else {
                merged.add(run)
            }
        }
        return merged
    }
}
