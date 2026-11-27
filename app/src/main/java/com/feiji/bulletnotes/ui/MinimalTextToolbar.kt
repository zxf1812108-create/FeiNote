package com.feiji.bulletnotes.ui

import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuDropdownProvider
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.feiji.bulletnotes.data.MdxDictionaryEngine
import kotlinx.coroutines.CompletableDeferred
import kotlin.math.roundToInt

/**
 * 全局选中文本追踪器：
 * 供极简悬浮菜单在点击「翻译」时直接读取当前选区原文，避免污染系统剪贴板。
 */
object ActiveSelectionTracker {
    var selectedText by mutableStateOf("")
        private set

    fun updateFromValue(value: TextFieldValue) {
        val sel = value.selection
        selectedText = if (!sel.collapsed && value.text.isNotEmpty()) {
            val start = sel.min.coerceIn(0, value.text.length)
            val end = sel.max.coerceIn(start, value.text.length)
            if (end > start) value.text.substring(start, end) else ""
        } else {
            ""
        }
    }

    fun clear() {
        selectedText = ""
    }
}

/**
 * 兼容旧版 LocalTextToolbar 的状态容器（与 Compose 1.9 新版 TextContextMenuProvider 双轨接管）。
 */
class MinimalTextToolbarState : TextToolbar {
    var isVisible by mutableStateOf(false)
        private set
    var anchorRect by mutableStateOf(Rect.Zero)
        private set
    var onCopyRequested by mutableStateOf<(() -> Unit)?>(null)
        private set
    var onPasteRequested by mutableStateOf<(() -> Unit)?>(null)
        private set
    var onCutRequested by mutableStateOf<(() -> Unit)?>(null)
        private set
    var onSelectAllRequested by mutableStateOf<(() -> Unit)?>(null)
        private set

    override val status: TextToolbarStatus
        get() = if (isVisible) TextToolbarStatus.Shown else TextToolbarStatus.Hidden

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?
    ) {
        this.anchorRect = rect
        this.onCopyRequested = onCopyRequested
        this.onPasteRequested = onPasteRequested
        this.onCutRequested = onCutRequested
        this.onSelectAllRequested = onSelectAllRequested
        this.isVisible = true
    }

    override fun hide() {
        this.isVisible = false
        this.onCopyRequested = null
        this.onPasteRequested = null
        this.onCutRequested = null
        this.onSelectAllRequested = null
    }
}

private data class ActiveContextMenuRequest(
    val session: TextContextMenuSession,
    val dataProvider: TextContextMenuDataProvider
)

/**
 * Compose Foundation 1.9+ 官方新版文本菜单通道的自定义实现：
 * 彻底接管 LocalTextContextMenuToolbarProvider / DropdownProvider，
 * 阻止系统默认 AndroidTextContextMenuToolbarProvider 弹出原生 ActionMode 悬浮菜单。
 */
private class MinimalTextContextMenuProvider : TextContextMenuProvider {
    private val mutatorMutex = MutatorMutex()
    var activeRequest by mutableStateOf<ActiveContextMenuRequest?>(null)
        private set

    override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
        val closedSignal = CompletableDeferred<Unit>()
        val session = object : TextContextMenuSession {
            override fun close() {
                closedSignal.complete(Unit)
            }
        }
        val request = ActiveContextMenuRequest(session = session, dataProvider = dataProvider)
        mutatorMutex.mutate {
            try {
                activeRequest = request
                closedSignal.await()
            } finally {
                if (activeRequest === request) {
                    activeRequest = null
                }
            }
        }
    }
}

private data class ToolbarActionItem(
    val label: String,
    val onClick: () -> Unit
)

/**
 * 极简 5 项选区菜单宿主（仅保留：剪切、复制、粘贴、全选、翻译）：
 * - 同时接管 Compose 1.9 默认启用的 LocalTextContextMenuToolbarProvider / DropdownProvider
 *   以及旧版 LocalTextToolbar，彻底屏蔽 Android 系统 ActionMode 注入的冗杂第三方菜单项。
 */
@Composable
fun MinimalTextToolbarHost(
    onTranslateRequest: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
    content: @Composable () -> Unit
) {
    val contextMenuProvider = remember { MinimalTextContextMenuProvider() }
    val legacyToolbarState = remember { MinimalTextToolbarState() }
    var hostCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    CompositionLocalProvider(
        LocalTextContextMenuToolbarProvider provides contextMenuProvider,
        LocalTextContextMenuDropdownProvider provides contextMenuProvider,
        LocalTextToolbar provides legacyToolbarState
    ) {
        Box(
            modifier = modifier.onGloballyPositioned { hostCoordinates = it }
        ) {
            content()

            val activeRequest = contextMenuProvider.activeRequest
            val coords = hostCoordinates
            if (activeRequest != null && coords != null && coords.isAttached) {
                ContextMenuToolbarPopup(
                    session = activeRequest.session,
                    dataProvider = activeRequest.dataProvider,
                    anchorCoordinates = coords,
                    onTranslateRequest = onTranslateRequest
                )
            } else {
                LegacyTextToolbarOverlay(
                    state = legacyToolbarState,
                    onTranslateRequest = onTranslateRequest
                )
            }
        }
    }
}

@Composable
private fun ContextMenuToolbarPopup(
    session: TextContextMenuSession,
    dataProvider: TextContextMenuDataProvider,
    anchorCoordinates: LayoutCoordinates,
    onTranslateRequest: (String) -> Unit
) {
    val menuData = dataProvider.data()
    val itemsByKey = remember(menuData) {
        menuData.components
            .filterIsInstance<TextContextMenuItem>()
            .associateBy { it.key }
    }

    val lookupCandidate = MdxDictionaryEngine.sanitizeSelectionQuery(ActiveSelectionTracker.selectedText)
    val hasCopy = itemsByKey.containsKey(TextContextMenuKeys.CopyKey)

    val actions = buildList {
        if (hasCopy && lookupCandidate != null) {
            add(ToolbarActionItem("翻译") {
                session.close()
                onTranslateRequest(lookupCandidate)
            })
        }
        itemsByKey[TextContextMenuKeys.CutKey]?.let { item ->
            add(ToolbarActionItem("剪切") { item.onClick(session) })
        }
        itemsByKey[TextContextMenuKeys.CopyKey]?.let { item ->
            add(ToolbarActionItem("复制") { item.onClick(session) })
        }
        itemsByKey[TextContextMenuKeys.PasteKey]?.let { item ->
            add(ToolbarActionItem("粘贴") { item.onClick(session) })
        }
        itemsByKey[TextContextMenuKeys.SelectAllKey]?.let { item ->
            add(ToolbarActionItem("全选") { item.onClick(session) })
        }
    }

    if (actions.isEmpty()) return

    val localRect = runCatching {
        if (anchorCoordinates.isAttached) dataProvider.contentBounds(anchorCoordinates) else null
    }.getOrNull() ?: return

    MinimalToolbarPopupBar(
        actions = actions,
        selectionRect = localRect,
        rectIsRelativeToAnchor = true
    )
}

@Composable
private fun LegacyTextToolbarOverlay(
    state: MinimalTextToolbarState,
    onTranslateRequest: (String) -> Unit
) {
    if (!state.isVisible) return

    val lookupCandidate = MdxDictionaryEngine.sanitizeSelectionQuery(ActiveSelectionTracker.selectedText)
    val actions = buildList {
        if (state.onCopyRequested != null && lookupCandidate != null) {
            add(ToolbarActionItem("翻译") {
                state.hide()
                onTranslateRequest(lookupCandidate)
            })
        }
        state.onCutRequested?.let { action ->
            add(ToolbarActionItem("剪切") { action() })
        }
        state.onCopyRequested?.let { action ->
            add(ToolbarActionItem("复制") { action() })
        }
        state.onPasteRequested?.let { action ->
            add(ToolbarActionItem("粘贴") { action() })
        }
        state.onSelectAllRequested?.let { action ->
            add(ToolbarActionItem("全选") { action() })
        }
    }

    if (actions.isEmpty()) return

    MinimalToolbarPopupBar(
        actions = actions,
        selectionRect = state.anchorRect,
        rectIsRelativeToAnchor = false
    )
}

@Composable
private fun MinimalToolbarPopupBar(
    actions: List<ToolbarActionItem>,
    selectionRect: Rect,
    rectIsRelativeToAnchor: Boolean
) {
    val density = LocalDensity.current
    val marginPx = with(density) { 10.dp.roundToPx() }
    val aboveGapPx = with(density) { 10.dp.roundToPx() }
    val belowGapPx = with(density) { 14.dp.roundToPx() }
    val topSafePx = with(density) { 36.dp.roundToPx() }

    val positionProvider = remember(
        selectionRect,
        rectIsRelativeToAnchor,
        marginPx,
        aboveGapPx,
        belowGapPx,
        topSafePx
    ) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                val offsetX = if (rectIsRelativeToAnchor) anchorBounds.left else 0
                val offsetY = if (rectIsRelativeToAnchor) anchorBounds.top else 0

                val left = offsetX + selectionRect.left
                val right = offsetX + selectionRect.right
                val top = offsetY + selectionRect.top
                val bottom = offsetY + selectionRect.bottom

                val centerX = ((left + right) / 2f).roundToInt()
                val desiredX = centerX - popupContentSize.width / 2
                val maxX = (windowSize.width - popupContentSize.width - marginPx)
                    .coerceAtLeast(marginPx)
                val x = desiredX.coerceIn(marginPx, maxX)

                val aboveY = top.roundToInt() - popupContentSize.height - aboveGapPx
                val belowY = bottom.roundToInt() + belowGapPx
                val maxY = (windowSize.height - popupContentSize.height - marginPx)
                    .coerceAtLeast(marginPx)
                val y = if (aboveY >= topSafePx) {
                    aboveY.coerceIn(marginPx, maxY)
                } else {
                    belowY.coerceIn(marginPx, maxY)
                }
                return IntOffset(x, y)
            }
        }
    }

    val containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val textColor = MaterialTheme.colorScheme.onSurface
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)

    Popup(
        popupPositionProvider = positionProvider,
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            clippingEnabled = true
        )
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = containerColor,
            shadowElevation = 6.dp,
            tonalElevation = 0.dp,
            modifier = Modifier.border(1.dp, borderColor, RoundedCornerShape(10.dp))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp)
            ) {
                actions.forEachIndexed { index, item ->
                    if (index > 0) {
                        Box(
                            modifier = Modifier
                                .width(0.5.dp)
                                .height(16.dp)
                                .background(dividerColor)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .clickable(onClick = item.onClick)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = item.label,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = textColor,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}
