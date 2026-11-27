package com.feiji.bulletnotes.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.feiji.bulletnotes.data.MarkdownParser
import com.feiji.bulletnotes.ui.theme.LocalIsDarkTheme

private data class MarkdownRenderPalette(
    val textColor: Color,
    val mutedColor: Color,
    val inlineCodeBg: Color,
    val codeBlockBg: Color,
    val codeBlockBorder: Color,
    val quoteBarColor: Color,
    val quoteTextColor: Color,
    val linkColor: Color,
    val bulletColor: Color,
    val ruleColor: Color
)

@Composable
private fun rememberMarkdownPalette(): MarkdownRenderPalette {
    val isDark = LocalIsDarkTheme.current
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant
    val primary = MaterialTheme.colorScheme.primary
    return remember(isDark, onSurface, onSurfaceVariant, outlineVariant, primary) {
        MarkdownRenderPalette(
            textColor = onSurface,
            mutedColor = onSurfaceVariant,
            inlineCodeBg = onSurface.copy(alpha = if (isDark) 0.12f else 0.065f),
            codeBlockBg = onSurface.copy(alpha = if (isDark) 0.07f else 0.038f),
            codeBlockBorder = outlineVariant,
            quoteBarColor = onSurface.copy(alpha = if (isDark) 0.28f else 0.22f),
            quoteTextColor = onSurface.copy(alpha = 0.78f),
            linkColor = if (isDark) Color(0xFF8AB4F8) else Color(0xFF1F5FAD),
            bulletColor = onSurface.copy(alpha = 0.55f),
            ruleColor = outlineVariant
        )
    }
}

private fun buildMarkdownAnnotatedString(
    parsed: MarkdownParser.ParsedInline,
    palette: MarkdownRenderPalette,
    baseTextDecoration: TextDecoration? = null
): AnnotatedString {
    if (parsed.displayText.isEmpty()) return AnnotatedString(" ")
    return buildAnnotatedString {
        append(parsed.displayText)
        for (run in parsed.runs) {
            val decorations = mutableListOf<TextDecoration>()
            if (baseTextDecoration != null && baseTextDecoration != TextDecoration.None) {
                decorations.add(baseTextDecoration)
            }
            if (run.strike) decorations.add(TextDecoration.LineThrough)
            if (run.linkUrl != null) decorations.add(TextDecoration.Underline)

            val combinedDeco = when (decorations.size) {
                0 -> null
                1 -> decorations[0]
                else -> TextDecoration.combine(decorations)
            }

            addStyle(
                style = SpanStyle(
                    fontWeight = if (run.bold) FontWeight.Bold else null,
                    fontStyle = if (run.italic) FontStyle.Italic else null,
                    fontFamily = if (run.code) FontFamily.Monospace else null,
                    background = if (run.code) palette.inlineCodeBg else Color.Unspecified,
                    color = when {
                        run.linkUrl != null -> palette.linkColor
                        run.isImageLink -> palette.mutedColor
                        else -> Color.Unspecified
                    },
                    textDecoration = combinedDeco
                ),
                start = run.start.coerceIn(0, parsed.displayText.length),
                end = run.end.coerceIn(0, parsed.displayText.length)
            )
        }
    }
}

/**
 * 收起单行浏览态 Markdown 渲染：
 * - 保留正文正常字号、无序列表圆点与行内样式；
 * - 精准回报第一行高度和溢出状态，非折叠单行点击时通过 rawOffsets 还原原文光标位置。
 */
@Composable
fun MarkdownCollapsedLine(
    noteId: Long,
    content: String,
    textStyle: TextStyle,
    isCollapsedMultiLine: Boolean,
    onFirstLineHeightMeasured: (Float) -> Unit,
    onEllipsizedChanged: (Boolean) -> Unit,
    onTap: (rawCursorOffset: Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = rememberMarkdownPalette()
    val parsed = remember(content) { MarkdownParser.parseCollapsedFirstLine(content) }
    val annotated = remember(parsed, palette) { buildMarkdownAnnotatedString(parsed, palette) }

    var textLayout by remember(noteId, content) { mutableStateOf<TextLayoutResult?>(null) }
    val latestCollapsed = rememberUpdatedState(isCollapsedMultiLine)
    val latestParsed = rememberUpdatedState(parsed)
    val latestOnTap = rememberUpdatedState(onTap)

    Text(
        text = annotated,
        style = textStyle,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(noteId) {
                detectTapGestures(
                    onTap = { tapOffset ->
                        if (!latestCollapsed.value) {
                            val displayOff = textLayout?.getOffsetForPosition(tapOffset)
                            val rawOff = displayOff?.let { latestParsed.value.mapDisplayOffsetToRaw(it) }
                            latestOnTap.value(rawOff)
                        } else {
                            latestOnTap.value(null)
                        }
                    }
                )
            },
        onTextLayout = { layout ->
            textLayout = layout
            if (layout.lineCount > 0) {
                onFirstLineHeightMeasured(layout.getLineBottom(0) - layout.getLineTop(0))
            }
            onEllipsizedChanged(layout.hasVisualOverflow)
        }
    )
}

/**
 * 精简多行预览态 Markdown 渲染（用于关键词联想卡片 3 行预览、回收站 2 行摘要）。
 */
@Composable
fun MarkdownPreviewText(
    content: String,
    maxLines: Int,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    emptyPlaceholder: String = " ",
    onFirstLineHeightMeasured: ((Float) -> Unit)? = null,
    onOverflowChanged: ((Boolean) -> Unit)? = null,
    onTap: ((rawCursorOffset: Int?) -> Unit)? = null,
    isTruncated: Boolean = false
) {
    val palette = rememberMarkdownPalette()
    val previewPair = remember(content, maxLines) {
        if (content.isBlank()) {
            MarkdownParser.ParsedInline(emptyPlaceholder, IntArray(0), emptyList(), 0, 0) to false
        } else {
            MarkdownParser.parsePreviewLines(content, maxLines)
        }
    }
    val parsed = previewPair.first
    val annotated = remember(parsed, palette) { buildMarkdownAnnotatedString(parsed, palette) }

    var textLayout by remember(content, maxLines) { mutableStateOf<TextLayoutResult?>(null) }
    val latestTruncated = rememberUpdatedState(isTruncated)
    val latestParsed = rememberUpdatedState(parsed)
    val latestOnTap = rememberUpdatedState(onTap)

    val tapMod = if (onTap != null) {
        Modifier.pointerInput(content, maxLines) {
            detectTapGestures(
                onTap = { tapOffset ->
                    val handler = latestOnTap.value ?: return@detectTapGestures
                    if (!latestTruncated.value) {
                        val displayOff = textLayout?.getOffsetForPosition(tapOffset)
                        val rawOff = displayOff?.let { latestParsed.value.mapDisplayOffsetToRaw(it) }
                        handler(rawOff)
                    } else {
                        handler(null)
                    }
                }
            )
        }
    } else {
        Modifier
    }

    Text(
        text = annotated,
        style = textStyle,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .then(tapMod),
        onTextLayout = { layout ->
            textLayout = layout
            if (layout.lineCount > 0) {
                onFirstLineHeightMeasured?.invoke(layout.getLineBottom(0) - layout.getLineTop(0))
            }
            onOverflowChanged?.invoke(layout.hasVisualOverflow || previewPair.second)
        }
    )
}

/**
 * 展开浏览态完整 Markdown 块级渲染：
 * - 支持 H1–H3、粗/斜/删除线、行内代码、代码块、引用块、无序列表（保留圆点）、有序列表、任务复选框、分割线、安全链接；
 * - 点击链接直接打开浏览器；点击任务复选框直接切换并写回原文；点击其他文字精确映射原文光标进入编辑。
 */
@Composable
fun MarkdownExpandedContent(
    noteId: Long,
    content: String,
    baseTextStyle: TextStyle,
    onFirstLineHeightMeasured: (Float) -> Unit,
    onTapToEdit: (rawCursorOffset: Int) -> Unit,
    onToggleTask: ((updatedContent: String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val palette = rememberMarkdownPalette()
    val blocks = remember(content) { MarkdownParser.parseBlocks(content) }
    val latestContent = rememberUpdatedState(content)
    val latestOnTapToEdit = rememberUpdatedState(onTapToEdit)
    val latestOnToggleTask = rememberUpdatedState(onToggleTask)

    fun openSafeUrl(url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    if (blocks.isEmpty()) {
        Text(
            text = " ",
            style = baseTextStyle,
            modifier = modifier
                .fillMaxWidth()
                .pointerInput(noteId) {
                    detectTapGestures(onTap = { latestOnTapToEdit.value(0) })
                },
            onTextLayout = { layout ->
                if (layout.lineCount > 0) {
                    onFirstLineHeightMeasured(layout.getLineBottom(0) - layout.getLineTop(0))
                }
            }
        )
        return
    }

    fun MarkdownParser.MarkdownBlock.isListItem(): Boolean =
        this is MarkdownParser.MarkdownBlock.UnorderedListItem ||
            this is MarkdownParser.MarkdownBlock.OrderedListItem ||
            this is MarkdownParser.MarkdownBlock.TaskListItem

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(noteId, content) {
                detectTapGestures(
                    onTap = { latestOnTapToEdit.value(latestContent.value.length) }
                )
            }
    ) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) {
                val prev = blocks[index - 1]
                val gapDp = when {
                    prev is MarkdownParser.MarkdownBlock.BlankLine ||
                        block is MarkdownParser.MarkdownBlock.BlankLine -> 0.dp
                    block is MarkdownParser.MarkdownBlock.Heading -> 14.dp
                    prev is MarkdownParser.MarkdownBlock.Heading -> 6.dp
                    prev.isListItem() && block.isListItem() -> 5.dp
                    else -> 10.dp
                }
                if (gapDp > 0.dp) {
                    Spacer(modifier = Modifier.height(gapDp))
                }
            }

            val measureFirst: ((TextLayoutResult) -> Unit)? = if (index == 0) {
                { layout ->
                    if (layout.lineCount > 0) {
                        onFirstLineHeightMeasured(layout.getLineBottom(0) - layout.getLineTop(0))
                    }
                }
            } else {
                null
            }

            when (block) {
                is MarkdownParser.MarkdownBlock.BlankLine -> {
                    Spacer(modifier = Modifier.height(16.dp))
                }

                is MarkdownParser.MarkdownBlock.HorizontalRule -> {
                    HorizontalDivider(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        thickness = 1.dp,
                        color = palette.ruleColor
                    )
                }

                is MarkdownParser.MarkdownBlock.Heading -> {
                    val baseSize = if (baseTextStyle.fontSize.value > 0f) baseTextStyle.fontSize else 16.sp
                    val (scale, weight) = when (block.level) {
                        1 -> 1.24f to FontWeight.Bold
                        2 -> 1.14f to FontWeight.SemiBold
                        else -> 1.07f to FontWeight.SemiBold
                    }
                    val headingStyle = baseTextStyle.copy(
                        fontSize = baseSize * scale,
                        lineHeight = if (baseTextStyle.lineHeight.value > 0f) baseTextStyle.lineHeight * scale else (baseSize * scale * 1.4f),
                        fontWeight = weight
                    )
                    MarkdownInlineText(
                        parsed = block.inline,
                        style = headingStyle,
                        palette = palette,
                        onOpenUrl = ::openSafeUrl,
                        onTapCursor = { rawOffset -> latestOnTapToEdit.value(rawOffset) },
                        onFirstLineLayout = measureFirst
                    )
                }

                is MarkdownParser.MarkdownBlock.Quote -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(2.dp))
                                .background(palette.quoteBarColor)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            block.lines.forEachIndexed { lineIdx, lineInline ->
                                MarkdownInlineText(
                                    parsed = lineInline,
                                    style = baseTextStyle.copy(color = palette.quoteTextColor),
                                    palette = palette,
                                    onOpenUrl = ::openSafeUrl,
                                    onTapCursor = { rawOffset -> latestOnTapToEdit.value(rawOffset) },
                                    onFirstLineLayout = if (index == 0 && lineIdx == 0) measureFirst else null
                                )
                            }
                        }
                    }
                }

                is MarkdownParser.MarkdownBlock.CodeBlock -> {
                    val codeTextStyle = baseTextStyle.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.5.sp,
                        lineHeight = 20.sp
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(palette.codeBlockBg)
                            .border(1.dp, palette.codeBlockBorder, RoundedCornerShape(8.dp))
                            .pointerInput(block.rawStart, block.rawEnd) {
                                detectTapGestures(
                                    onTap = { latestOnTapToEdit.value(block.codeRawStart) }
                                )
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        if (block.language.isNotEmpty()) {
                            Text(
                                text = block.language,
                                fontSize = 11.sp,
                                color = palette.mutedColor,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = block.code.ifEmpty { " " },
                                style = codeTextStyle,
                                onTextLayout = { layout -> measureFirst?.invoke(layout) }
                            )
                        }
                    }
                }

                is MarkdownParser.MarkdownBlock.UnorderedListItem -> {
                    val indentDp = (block.indentLevel * 16).dp
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = indentDp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "•",
                            style = baseTextStyle.copy(
                                color = palette.bulletColor,
                                fontWeight = FontWeight.Bold
                            ),
                            modifier = Modifier.padding(end = 7.dp)
                        )
                        MarkdownInlineText(
                            parsed = block.inline,
                            style = baseTextStyle,
                            palette = palette,
                            onOpenUrl = ::openSafeUrl,
                            onTapCursor = { rawOffset -> latestOnTapToEdit.value(rawOffset) },
                            onFirstLineLayout = measureFirst,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                is MarkdownParser.MarkdownBlock.OrderedListItem -> {
                    val indentDp = (block.indentLevel * 16).dp
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = indentDp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = block.marker,
                            style = baseTextStyle.copy(color = palette.mutedColor),
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        MarkdownInlineText(
                            parsed = block.inline,
                            style = baseTextStyle,
                            palette = palette,
                            onOpenUrl = ::openSafeUrl,
                            onTapCursor = { rawOffset -> latestOnTapToEdit.value(rawOffset) },
                            onFirstLineLayout = measureFirst,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                is MarkdownParser.MarkdownBlock.TaskListItem -> {
                    val indentDp = (block.indentLevel * 16).dp
                    val taskTextStyle = if (block.checked) {
                        baseTextStyle.copy(
                            color = palette.mutedColor,
                            textDecoration = TextDecoration.LineThrough
                        )
                    } else {
                        baseTextStyle
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = indentDp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 3.dp, end = 7.dp)
                                .size(20.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .clickable(enabled = latestOnToggleTask.value != null) {
                                    val updated = MarkdownParser.toggleTaskCheckbox(
                                        latestContent.value,
                                        block.checkboxCharRawIndex
                                    )
                                    if (updated != latestContent.value) {
                                        latestOnToggleTask.value?.invoke(updated)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            TaskCheckboxGlyph(
                                checked = block.checked,
                                color = if (block.checked) palette.mutedColor else palette.bulletColor
                            )
                        }
                        MarkdownInlineText(
                            parsed = block.inline,
                            style = taskTextStyle,
                            palette = palette,
                            baseTextDecoration = if (block.checked) TextDecoration.LineThrough else null,
                            onOpenUrl = ::openSafeUrl,
                            onTapCursor = { rawOffset -> latestOnTapToEdit.value(rawOffset) },
                            onFirstLineLayout = measureFirst,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                is MarkdownParser.MarkdownBlock.Paragraph -> {
                    MarkdownInlineText(
                        parsed = block.inline,
                        style = baseTextStyle,
                        palette = palette,
                        onOpenUrl = ::openSafeUrl,
                        onTapCursor = { rawOffset -> latestOnTapToEdit.value(rawOffset) },
                        onFirstLineLayout = measureFirst
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkdownInlineText(
    parsed: MarkdownParser.ParsedInline,
    style: TextStyle,
    palette: MarkdownRenderPalette,
    baseTextDecoration: TextDecoration? = null,
    onOpenUrl: (String) -> Unit,
    onTapCursor: (Int) -> Unit,
    onFirstLineLayout: ((TextLayoutResult) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val annotated = remember(parsed, palette, baseTextDecoration) {
        buildMarkdownAnnotatedString(parsed, palette, baseTextDecoration)
    }
    var textLayout by remember(parsed) { mutableStateOf<TextLayoutResult?>(null) }
    val latestParsed = rememberUpdatedState(parsed)
    val latestOnOpenUrl = rememberUpdatedState(onOpenUrl)
    val latestOnTapCursor = rememberUpdatedState(onTapCursor)

    Text(
        text = annotated,
        style = style,
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(parsed) {
                detectTapGestures(
                    onTap = { tapOffset ->
                        val layout = textLayout
                        if (layout != null) {
                            val displayOffset = layout.getOffsetForPosition(tapOffset)
                            val url = latestParsed.value.findLinkAt(displayOffset)
                            if (url != null) {
                                latestOnOpenUrl.value(url)
                                return@detectTapGestures
                            }
                            latestOnTapCursor.value(latestParsed.value.mapDisplayOffsetToRaw(displayOffset))
                        } else {
                            latestOnTapCursor.value(latestParsed.value.rawEnd)
                        }
                    }
                )
            },
        onTextLayout = { layout ->
            textLayout = layout
            onFirstLineLayout?.invoke(layout)
        }
    )
}

@Composable
private fun TaskCheckboxGlyph(checked: Boolean, color: Color) {
    Canvas(modifier = Modifier.size(14.dp)) {
        val strokePx = 1.5.dp.toPx()
        val inset = strokePx / 2f
        val boxSize = size.width - strokePx
        drawRoundRect(
            color = color,
            topLeft = Offset(inset, inset),
            size = Size(boxSize, boxSize),
            cornerRadius = CornerRadius(2.6.dp.toPx(), 2.6.dp.toPx()),
            style = Stroke(width = strokePx)
        )
        if (checked) {
            val path = Path().apply {
                moveTo(size.width * 0.25f, size.height * 0.52f)
                lineTo(size.width * 0.44f, size.height * 0.72f)
                lineTo(size.width * 0.76f, size.height * 0.30f)
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(
                    width = strokePx * 1.05f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
    }
}
