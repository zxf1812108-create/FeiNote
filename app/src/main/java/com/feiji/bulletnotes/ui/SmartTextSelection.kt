package com.feiji.bulletnotes.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import java.text.BreakIterator

/**
 * 编辑框中英文智能长按选区控制器：
 * - 长按中文汉字时，跳过系统默认分词，精准选中当前按住的 1 个汉字；
 * - 长按英文时，精准选中当前按住的完整英文单词；
 * - 长按不松手直接拖动连选时，经过中文逐个汉字扩展/回缩，经过英文按整个单词吸附扩展/回缩；
 * - 全程以非消费的 Initial 通道被动观察触点，完整保留系统原生放大镜、水滴手柄和复制/粘贴/全选菜单。
 */
class SmartSelectionController {
    var layout: TextLayoutResult? = null
    var containerWidthPx: Int = 0

    private var isPointerDown: Boolean = false
    private var pressOriginPos: Offset = Offset.Zero
    private var currentPointerPos: Offset = Offset.Zero

    private var isSmartSelecting: Boolean = false
    private var anchorUnitRange: TextRange? = null

    fun onPointerDown(position: Offset) {
        isPointerDown = true
        pressOriginPos = position
        currentPointerPos = position
        isSmartSelecting = false
        anchorUnitRange = null
    }

    fun onPointerUp() {
        isPointerDown = false
        isSmartSelecting = false
        anchorUnitRange = null
    }

    fun onPointerMove(position: Offset, currentValue: TextFieldValue): TextRange? {
        currentPointerPos = position
        if (!isPointerDown || !isSmartSelecting) return null
        val anchor = anchorUnitRange ?: return null
        val text = currentValue.text
        if (text.isEmpty()) return null
        val currentLayout = layout ?: return null
        if (!canUseDirectLayoutHitTest(currentLayout)) return null

        val dragCharIdx = findCharIndexAtPosition(text, currentLayout, position) ?: return null
        val dragUnit = resolveSmartUnitRange(text, dragCharIdx)
        return mergeSmartRanges(anchor, dragUnit)
    }

    /**
     * 在 BasicTextField.onValueChange 中拦截并修正长按触发/长按拖动产生的选区，
     * 不影响正常打字、IME 组词、全选菜单及松手后拖动选区水滴手柄。
     */
    fun adjustIncomingValue(
        previous: TextFieldValue,
        incoming: TextFieldValue
    ): TextFieldValue {
        // 文本或输入法组词状态发生变化时，立即退出长按选区会话并原样放行。
        if (incoming.text != previous.text || incoming.composition != previous.composition) {
            isSmartSelecting = false
            anchorUnitRange = null
            return incoming
        }

        // 手指未按在正文区域（如点击“全选”菜单、拖动弹出层中的水滴手柄、方向键移动）时原样放行。
        if (!isPointerDown) {
            return incoming
        }

        // 选区折叠（普通单击落光标）时原样放行。
        if (incoming.selection.collapsed) {
            isSmartSelecting = false
            anchorUnitRange = null
            return incoming
        }

        val text = incoming.text
        if (text.isEmpty()) return incoming

        val currentLayout = layout
        if (!isSmartSelecting) {
            val anchorCharIdx = if (canUseDirectLayoutHitTest(currentLayout) && currentLayout != null) {
                findCharIndexAtPosition(text, currentLayout, pressOriginPos)
                    ?: incoming.selection.min.coerceIn(0, text.lastIndex)
            } else {
                incoming.selection.min.coerceIn(0, text.lastIndex)
            }
            val anchorUnit = resolveSmartUnitRange(text, anchorCharIdx)
            isSmartSelecting = true
            anchorUnitRange = anchorUnit
            return incoming.copy(selection = anchorUnit)
        }

        val anchor = anchorUnitRange ?: return incoming
        val dragCharIdx = if (canUseDirectLayoutHitTest(currentLayout) && currentLayout != null) {
            findCharIndexAtPosition(text, currentLayout, currentPointerPos)
        } else {
            val activeEnd = incoming.selection.end
            val fallbackIdx = if (activeEnd < anchor.min) activeEnd else (activeEnd - 1)
            fallbackIdx.coerceIn(0, text.lastIndex)
        } ?: return incoming

        val dragUnit = resolveSmartUnitRange(text, dragCharIdx)
        val merged = mergeSmartRanges(anchor, dragUnit)
        return incoming.copy(selection = merged)
    }

    private fun canUseDirectLayoutHitTest(currentLayout: TextLayoutResult?): Boolean {
        if (currentLayout == null || currentLayout.lineCount == 0) return false
        return containerWidthPx <= 0 ||
            currentLayout.lineCount > 1 ||
            currentLayout.size.width <= containerWidthPx + 2
    }
}

/**
 * 为 BasicTextField 内部文本区挂载被动触点监听器（不消费任何事件）。
 * 建议直接挂在包裹 innerTextField() 的最内层 Box 上，使触点坐标与 TextLayoutResult 严格一致。
 */
@Composable
fun Modifier.smartSelectionObserver(
    key: Any,
    controller: SmartSelectionController,
    currentValue: TextFieldValue,
    onSelectionChange: (TextRange) -> Unit
): Modifier {
    val latestController by rememberUpdatedState(controller)
    val latestValue by rememberUpdatedState(currentValue)
    val latestOnSelectionChange by rememberUpdatedState(onSelectionChange)

    return this
        .onGloballyPositioned { coords ->
            latestController.containerWidthPx = coords.size.width
        }
        .pointerInput(key) {
            awaitPointerEventScope {
                var activePointerId: PointerId? = null
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val changes = event.changes
                    if (activePointerId == null) {
                        val down = changes.firstOrNull { it.pressed }
                        if (down != null) {
                            activePointerId = down.id
                            latestController.onPointerDown(down.position)
                        }
                    } else {
                        val current = changes.firstOrNull { it.id == activePointerId }
                        if (current == null || !current.pressed) {
                            activePointerId = null
                            latestController.onPointerUp()
                        } else {
                            val updatedSelection = latestController.onPointerMove(
                                position = current.position,
                                currentValue = latestValue
                            )
                            if (updatedSelection != null && updatedSelection != latestValue.selection) {
                                latestOnSelectionChange(updatedSelection)
                            }
                        }
                    }
                }
            }
        }
}

/**
 * 带中英文智能长按选区的 OutlinedTextField 封装，保持 Material3 原生样式与交互不变。
 */
@Composable
fun SmartOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    textStyle: TextStyle = LocalTextStyle.current,
    shape: Shape = OutlinedTextFieldDefaults.shape,
    singleLine: Boolean = false,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null
) {
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(value, TextRange(value.length)))
    }
    if (fieldValue.text != value) {
        fieldValue = TextFieldValue(value, TextRange(value.length))
    }
    val smartSelection = remember { SmartSelectionController() }
    val enableSmart = visualTransformation == VisualTransformation.None

    OutlinedTextField(
        value = fieldValue,
        onValueChange = { incoming ->
            val adjusted = if (enableSmart) {
                smartSelection.adjustIncomingValue(fieldValue, incoming)
            } else {
                incoming
            }
            val textChanged = adjusted.text != fieldValue.text
            fieldValue = adjusted
            if (textChanged) {
                onValueChange(adjusted.text)
            }
        },
        label = label,
        textStyle = textStyle,
        shape = shape,
        singleLine = singleLine,
        isError = isError,
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
        modifier = modifier.then(
            if (enableSmart) {
                Modifier.smartSelectionObserver(
                    key = Unit,
                    controller = smartSelection,
                    currentValue = fieldValue,
                    onSelectionChange = { newSelection ->
                        fieldValue = fieldValue.copy(selection = newSelection)
                    }
                )
            } else {
                Modifier
            }
        )
    )
}

private fun mergeSmartRanges(anchor: TextRange, current: TextRange): TextRange {
    return if (current.min < anchor.min) {
        // 向左/向上扩展：锚点留在右端，活动端向左跟随手指
        TextRange(anchor.max, current.min)
    } else {
        // 向右/向下扩展（或在锚点内）：锚点留在左端，活动端向右跟随手指
        TextRange(anchor.min, maxOf(anchor.max, current.max))
    }
}

private fun findCharIndexAtPosition(
    text: String,
    layout: TextLayoutResult,
    position: Offset
): Int? {
    if (text.isEmpty() || layout.lineCount == 0) return null
    val line = layout.getLineForVerticalPosition(position.y)
        .coerceIn(0, layout.lineCount - 1)
    val lineStart = layout.getLineStart(line)
    val lineEndExclusive = layout.getLineEnd(line, visibleEnd = true)

    if (lineStart >= lineEndExclusive) {
        return lineStart.coerceIn(0, text.lastIndex)
    }

    val lineLeft = layout.getLineLeft(line)
    val lineRight = layout.getLineRight(line)
    val clampedX = if (lineRight >= lineLeft) {
        position.x.coerceIn(lineLeft, lineRight)
    } else {
        position.x
    }
    val lineCenterY = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
    val raw = layout.getOffsetForPosition(Offset(clampedX, lineCenterY))
        .coerceIn(lineStart, lineEndExclusive)

    // 修正 getOffsetForPosition 按字符中心就近取光标缝隙导致的“按右半边偏选下一字”问题
    val candidate = when {
        raw >= lineEndExclusive -> lineEndExclusive - 1
        raw > lineStart -> {
            val box = runCatching { layout.getBoundingBox(raw) }.getOrNull()
            if (box != null && clampedX < box.left) raw - 1 else raw
        }
        else -> raw
    }
    return candidate.coerceIn(0, text.lastIndex)
}

private fun resolveSmartUnitRange(text: String, charIndex: Int): TextRange {
    if (text.isEmpty()) return TextRange.Zero
    val idx = charIndex.coerceIn(0, text.lastIndex)
    if (isEnglishWordCharAt(text, idx)) {
        var start = idx
        while (start > 0 && isEnglishWordCharAt(text, start - 1)) {
            start--
        }
        var end = idx + 1
        while (end < text.length && isEnglishWordCharAt(text, end)) {
            end++
        }
        return TextRange(start, end)
    }
    return findSingleGraphemeRange(text, idx)
}

private fun isAsciiLetter(ch: Char): Boolean {
    return (ch in 'a'..'z') || (ch in 'A'..'Z')
}

private fun isEnglishWordCoreChar(ch: Char): Boolean {
    return (ch in 'a'..'z') ||
        (ch in 'A'..'Z') ||
        (ch in '0'..'9') ||
        ch == '_' ||
        (ch in '\u00C0'..'\u024F' && ch != '\u00D7' && ch != '\u00F7')
}

private fun isEnglishWordCharAt(text: String, idx: Int): Boolean {
    if (idx !in text.indices) return false
    val ch = text[idx]
    if (isEnglishWordCoreChar(ch)) return true
    // 英文缩略词内部撇号（如 don't / it's）：仅左右两侧均为英文字母时计入同一单词
    if (ch == '\'' || ch == '\u2019') {
        val left = idx - 1
        val right = idx + 1
        return left in text.indices &&
            right in text.indices &&
            isAsciiLetter(text[left]) &&
            isAsciiLetter(text[right])
    }
    return false
}

private fun findSingleGraphemeRange(text: String, index: Int): TextRange {
    if (text.isEmpty()) return TextRange.Zero
    val safeIdx = index.coerceIn(0, text.lastIndex)
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(text)
    val start = if (iterator.isBoundary(safeIdx)) {
        safeIdx
    } else {
        iterator.preceding(safeIdx).let { if (it == BreakIterator.DONE) 0 else it }
    }
    val end = iterator.following(start).let { if (it == BreakIterator.DONE) text.length else it }
    return TextRange(start.coerceIn(0, text.length), end.coerceIn(0, text.length))
}
