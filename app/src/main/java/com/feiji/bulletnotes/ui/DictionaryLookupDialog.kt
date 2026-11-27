package com.feiji.bulletnotes.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.feiji.bulletnotes.data.MdxDictionaryEngine
import com.feiji.bulletnotes.data.MdxDictionaryManager
import com.feiji.bulletnotes.ui.theme.LocalIsDarkTheme
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 音量键翻页分发器：
 * 当离线词典弹窗打开时接管音量上/下键，同步翻阅词典释义页面。
 */
object DictionaryVolumeKeyDispatcher {
    @Volatile
    var onVolumePage: ((Int) -> Boolean)? = null
}

private data class LookupHistoryItem(
    val query: String,
    val dictionaryId: String?,
    val scrollY: Int
)

private class MdxWebViewController {
    var webView: WebView? = null
    var lastScrollY: Int = 0

    fun pageUp(): Boolean {
        val wv = webView ?: return false
        wv.post { wv.evaluateJavascript("window.__mdxPageUp && window.__mdxPageUp();", null) }
        return true
    }

    fun pageDown(): Boolean {
        val wv = webView ?: return false
        wv.post { wv.evaluateJavascript("window.__mdxPageDown && window.__mdxPageDown();", null) }
        return true
    }

    fun setEditing(editing: Boolean) {
        val wv = webView ?: return
        wv.post { wv.evaluateJavascript("window.__mdxSetEditing && window.__mdxSetEditing($editing);", null) }
    }

    fun refreshLayout() {
        val wv = webView ?: return
        wv.post { wv.evaluateJavascript("window.__mdxRefreshLayout && window.__mdxRefreshLayout();", null) }
    }
}

/**
 * 极简离线 MDX 词典查词弹窗（完整复刻飞文/feiartical 体验）：
 * - 顶部栏：返回箭头/词典图标 + 无边框行内单词输入框（聚焦显底线）+ 词典名（多本命中带下划线可循环切换）+ 关闭按钮；
 * - 释义区：Shadow DOM 样式隔离、HTML/CSS 净化、强制统一字号行高、深色模式反色护眼、相关候选词横向条、entry:///bword:///#anchor 跳转及历史栈滚动恢复；
 * - 精准翻页：左半屏上翻 / 右半屏下翻 + 视觉行计算 + 上下边缘半行遮罩 + 末页动态占位 + 音量键接管；
 * - 高度与编辑态：查好词直接拉长；点击左上角收缩为完整屏幕 50%（防 adjustResize 二次缩水并避让键盘），150ms 防抖查词不打断输入；
 *   4 种退出编辑态拉长时机：回车/搜索键、点选候选/跳转/返回/切换词典、手动收起软键盘、编辑态点击释义区不翻页仅退出拉长。
 */
@Composable
fun DictionaryLookupDialog(
    initialQuery: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val isDark = LocalIsDarkTheme.current

    LaunchedEffect(Unit) {
        MdxDictionaryManager.ensureLoaded(context)
    }

    val dictionaries by MdxDictionaryManager.dictionaries.collectAsState()
    val enabledDicts = remember(dictionaries) { dictionaries.filter { it.enabled } }

    val cleanedInitial = remember(initialQuery) { MdxDictionaryEngine.cleanBasicQuery(initialQuery) }

    val historyStack = remember(initialQuery) { mutableStateListOf<LookupHistoryItem>() }
    var inputValue by remember(initialQuery) {
        mutableStateOf(TextFieldValue(cleanedInitial, TextRange(cleanedInitial.length)))
    }
    var activeQuery by remember(initialQuery) { mutableStateOf(cleanedInitial) }
    var lookupVersion by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var results by remember {
        mutableStateOf<List<MdxDictionaryManager.DictionaryLookupResult>>(emptyList())
    }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedResultIndex by remember { mutableIntStateOf(0) }
    var isImporting by remember { mutableStateOf(false) }

    // 历史返回或锚点跳转的待恢复状态
    var pendingRestoreScrollY by remember { mutableIntStateOf(0) }
    var pendingRestoreDictId by remember { mutableStateOf<String?>(null) }
    var pendingAnchor by remember { mutableStateOf<String?>(null) }

    // 编辑态与键盘高度追踪
    var isEditing by remember { mutableStateOf(false) }
    var imeShownDuringEdit by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val webController = remember { MdxWebViewController() }

    // 预先记录完整屏幕高度（避免 adjustResize 模式下被软键盘压扁后二次缩水）
    val displayHeightPx = remember(context) { context.resources.displayMetrics.heightPixels }
    var fullScreenHeightPx by remember { mutableIntStateOf(displayHeightPx) }

    // 动态内容高度追踪（用于拉长态按实际词条内容自适应高度，消除底部大片空白）
    var headerHeightPx by remember { mutableIntStateOf(0) }
    var relatedBarHeightPx by remember { mutableIntStateOf(0) }
    var measuredContentHeightDp by remember { mutableStateOf<Float?>(null) }
    var measuredContentToken by remember { mutableStateOf("") }

    fun exitEditModeAndExpand() {
        isEditing = false
        imeShownDuringEdit = false
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        webController.setEditing(false)
    }

    fun jumpToWord(
        targetWord: String,
        anchor: String? = null,
        pushHistory: Boolean = true
    ) {
        val cleaned = MdxDictionaryEngine.cleanBasicQuery(targetWord)
        if (cleaned.isEmpty()) return
        if (pushHistory && activeQuery.isNotBlank() && !cleaned.equals(activeQuery, ignoreCase = true)) {
            historyStack.add(
                LookupHistoryItem(
                    query = activeQuery,
                    dictionaryId = results.getOrNull(selectedResultIndex)?.dictionaryId,
                    scrollY = webController.lastScrollY
                )
            )
        }
        pendingRestoreScrollY = 0
        pendingRestoreDictId = null
        pendingAnchor = anchor?.takeIf { it.isNotBlank() }
        inputValue = TextFieldValue(cleaned, TextRange(cleaned.length))
        activeQuery = cleaned
        lookupVersion++
        exitEditModeAndExpand()
    }

    fun navigateBackHistory() {
        if (historyStack.isEmpty()) return
        val prev = historyStack.removeAt(historyStack.lastIndex)
        pendingRestoreScrollY = prev.scrollY
        pendingRestoreDictId = prev.dictionaryId
        pendingAnchor = null
        inputValue = TextFieldValue(prev.query, TextRange(prev.query.length))
        activeQuery = prev.query
        lookupVersion++
        exitEditModeAndExpand()
    }

    fun cycleMatchedDictionary() {
        if (results.size <= 1) return
        pendingRestoreScrollY = 0
        pendingAnchor = null
        selectedResultIndex = (selectedResultIndex + 1) % results.size
        exitEditModeAndExpand()
    }

    fun commitManualSearchAndExpand() {
        val cleaned = MdxDictionaryEngine.cleanBasicQuery(inputValue.text)
        if (cleaned.isNotEmpty()) {
            pendingRestoreScrollY = 0
            pendingRestoreDictId = null
            pendingAnchor = null
            activeQuery = cleaned
            lookupVersion++
        }
        exitEditModeAndExpand()
    }

    // 同步编辑态给 WebView（控制点击释义区时是否拦截翻页）
    LaunchedEffect(isEditing) {
        webController.setEditing(isEditing)
        // 高度切换后通知 WebView 重算上下边缘遮罩与末页占位
        delay(80L)
        webController.refreshLayout()
    }

    // 注册全局音量键翻页接管
    DisposableEffect(webController) {
        DictionaryVolumeKeyDispatcher.onVolumePage = { direction ->
            if (isEditing) {
                exitEditModeAndExpand()
                true
            } else if (direction < 0) {
                webController.pageUp()
            } else {
                webController.pageDown()
            }
        }
        onDispose {
            DictionaryVolumeKeyDispatcher.onVolumePage = null
        }
    }

    // 150ms 防抖实时查词与前缀联想（打字期间绝不自动失焦或收起键盘）
    LaunchedEffect(inputValue.text, enabledDicts, lookupVersion) {
        val raw = inputValue.text
        val query = MdxDictionaryEngine.cleanBasicQuery(raw)
        if (enabledDicts.isEmpty() || query.isEmpty()) {
            results = emptyList()
            suggestions = emptyList()
            selectedResultIndex = 0
            isLoading = false
            return@LaunchedEffect
        }

        if (isEditing) {
            delay(150L)
        }
        activeQuery = query
        isLoading = true

        val (found, cands) = coroutineScope {
            val lookupDef = async { MdxDictionaryManager.lookupAcrossDictionaries(context, query) }
            val suggestDef = async { MdxDictionaryManager.suggestAcrossDictionaries(context, query, limit = 16) }
            lookupDef.await() to suggestDef.await()
        }

        results = found
        suggestions = cands
        val restoreDictId = pendingRestoreDictId
        pendingRestoreDictId = null
        selectedResultIndex = if (restoreDictId != null) {
            found.indexOfFirst { it.dictionaryId == restoreDictId }.takeIf { it >= 0 } ?: 0
        } else {
            0
        }
        isLoading = false
    }

    val libraryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            isImporting = true
            scope.launch {
                val res = MdxDictionaryManager.importFiles(context, uris)
                isImporting = false
                res.onSuccess { msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    lookupVersion++
                }.onFailure { err ->
                    Toast.makeText(context, err.message ?: "导入词库失败", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val contentColor = MaterialTheme.colorScheme.onSurface
    val mutedText = MaterialTheme.colorScheme.onSurfaceVariant
    val hairlineBorder = MaterialTheme.colorScheme.outlineVariant
    val cardSurfaceColor = MaterialTheme.colorScheme.surface
    val headerSurfaceColor = MaterialTheme.colorScheme.surfaceContainerHigh

    val activeResult = results.getOrNull(selectedResultIndex)
    val relatedWords = remember(suggestions, activeResult, activeQuery) {
        val excludeA = activeQuery.lowercase()
        val excludeB = activeResult?.matchedWord?.lowercase().orEmpty()
        suggestions.filter { word ->
            val low = word.lowercase()
            low != excludeA && low != excludeB
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        // 确保弹窗窗口占满全屏且支持 adjustResize，方便精确获取 IME 高度并防止外部点击穿透正文
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.let { win ->
                win.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT
                )
                win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                win.setDimAmount(0.36f)
            }
        }

        val statusTopPx = WindowInsets.statusBars.getTop(density)
        val navBottomPx = WindowInsets.navigationBars.getBottom(density)
        val imeBottomPx = WindowInsets.ime.getBottom(density)

        // 退出编辑态时机 3：用户手动收起系统软键盘时，立即失焦并直接拉长弹窗
        LaunchedEffect(isEditing, imeBottomPx) {
            if (!isEditing) {
                imeShownDuringEdit = false
            } else if (imeBottomPx > 0) {
                imeShownDuringEdit = true
            } else if (imeShownDuringEdit && imeBottomPx == 0) {
                exitEditModeAndExpand()
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { keyEvent ->
                    val native = keyEvent.nativeKeyEvent
                    if (native.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                        native.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                    ) {
                        if (keyEvent.type == KeyEventType.KeyDown) {
                            if (isEditing) {
                                exitEditModeAndExpand()
                            } else if (native.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                                webController.pageUp()
                            } else {
                                webController.pageDown()
                            }
                        }
                        true
                    } else {
                        false
                    }
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        ) {
            val currentViewportHeightPx = with(density) { maxHeight.toPx().toInt() }
            LaunchedEffect(currentViewportHeightPx, imeBottomPx) {
                if (imeBottomPx == 0 && currentViewportHeightPx > 0) {
                    fullScreenHeightPx = maxOf(fullScreenHeightPx, currentViewportHeightPx)
                }
            }

            val topMarginPx = statusTopPx + with(density) { 10.dp.toPx() }
            val toolbarClearancePx = navBottomPx + with(density) { 58.dp.toPx() }
            val minEditCardPx = with(density) { 200.dp.toPx() }
            val minContentCardPx = with(density) { 132.dp.toPx() }

            val maxExpandedHeightPx = (fullScreenHeightPx - topMarginPx - toolbarClearancePx)
                .coerceAtLeast(minEditCardPx)

            val effectiveHeaderPx = if (headerHeightPx > 0) headerHeightPx.toFloat() else with(density) { 48.dp.toPx() }
            val effectiveRelatedPx = if (activeResult != null && relatedWords.isNotEmpty()) {
                if (relatedBarHeightPx > 0) relatedBarHeightPx.toFloat() else with(density) { 32.dp.toPx() }
            } else {
                0f
            }
            val chromeHeightPx = effectiveHeaderPx + effectiveRelatedPx + with(density) { 4.dp.toPx() }

            // 拉长态根据实际显示内容自适应高度（不超过最大拉长上限，避免短词条底部大片空白）
            val currentContentToken = activeResult?.let {
                "${it.dictionaryId}|${it.matchedWord}|${it.html.hashCode()}"
            }.orEmpty()
            val validMeasuredDp = if (activeResult != null && measuredContentToken == currentContentToken) {
                measuredContentHeightDp
            } else {
                null
            }

            val desiredExpandedHeightPx = when {
                activeResult != null -> {
                    if (validMeasuredDp != null) {
                        val contentPx = with(density) { (validMeasuredDp + 4f).dp.toPx() }
                        (chromeHeightPx + contentPx).coerceIn(minContentCardPx, maxExpandedHeightPx)
                    } else {
                        // 尚未测量出新词条高度时，沿用上一次高度或温和默认高度，避免闪烁大片空白
                        val fallbackDp = measuredContentHeightDp ?: 220f
                        val contentPx = with(density) { (fallbackDp + 4f).dp.toPx() }
                        (chromeHeightPx + contentPx).coerceIn(minContentCardPx, maxExpandedHeightPx)
                    }
                }
                enabledDicts.isEmpty() -> {
                    minOf(maxExpandedHeightPx, with(density) { 248.dp.toPx() })
                }
                suggestions.isNotEmpty() -> {
                    val listPx = with(density) { (suggestions.size * 44f + 8f).dp.toPx() }
                    (chromeHeightPx + listPx).coerceIn(minContentCardPx, maxExpandedHeightPx)
                }
                else -> {
                    minOf(maxExpandedHeightPx, with(density) { 196.dp.toPx() })
                }
            }

            // 兼容 adjustResize：若容器本身已被系统压缩，取二者中准确的键盘上方剩余空间
            val effectiveAvailableAboveImePx = if (imeBottomPx > 0) {
                val fromFull = fullScreenHeightPx - imeBottomPx - topMarginPx - with(density) { 8.dp.toPx() }
                val fromCurrent = currentViewportHeightPx - topMarginPx - with(density) { 8.dp.toPx() }
                minOf(fromFull, if (currentViewportHeightPx < fullScreenHeightPx - 100) fromCurrent else fromFull)
            } else {
                maxExpandedHeightPx
            }.coerceAtLeast(minEditCardPx)

            val editHeightPx = minOf(fullScreenHeightPx * 0.50f, effectiveAvailableAboveImePx)
                .coerceAtLeast(minEditCardPx)

            val targetCardHeightDp = with(density) {
                (if (isEditing) editHeightPx else desiredExpandedHeightPx).toDp()
            }
            val animatedCardHeightDp by animateDpAsState(
                targetValue = targetCardHeightDp,
                animationSpec = tween(durationMillis = 120),
                label = "dictCardHeight"
            )
            val topPaddingDp = with(density) { topMarginPx.toDp() }

            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = topPaddingDp)
                    .fillMaxWidth(0.94f)
                    .height(animatedCardHeightDp)
                    .border(1.dp, hairlineBorder, RoundedCornerShape(16.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { /* 拦截卡片内点击，防止误触外部关闭 */ }
                    ),
                shape = RoundedCornerShape(16.dp),
                color = cardSurfaceColor,
                shadowElevation = 0.dp,
                tonalElevation = 0.dp
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // 1. 顶部标题栏：左(返回箭头/词典图标 + 无边框单词输入框) | 右(当前词典名[多本带下划线可循环切换] + ×)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { headerHeightPx = it.height }
                            .background(headerSurfaceColor)
                            .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 左侧图标：有历史显示返回箭头，无历史显示词典图标
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .then(
                                    if (historyStack.isNotEmpty()) {
                                        Modifier.clickable { navigateBackHistory() }
                                    } else {
                                        Modifier.clickable {
                                            inputFocusRequester.requestFocus()
                                            isEditing = true
                                        }
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (historyStack.isNotEmpty()) {
                                LookupBackArrowIcon(color = contentColor)
                            } else {
                                LookupBookIcon(color = mutedText)
                            }
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        // 可直接点击编辑的无边框单词输入框（聚焦时显示底线）
                        val underlineColor = contentColor.copy(alpha = 0.65f)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .drawBehind {
                                    if (isEditing) {
                                        val strokePx = 1.4.dp.toPx()
                                        drawLine(
                                            color = underlineColor,
                                            start = Offset(0f, size.height),
                                            end = Offset(size.width, size.height),
                                            strokeWidth = strokePx
                                        )
                                    }
                                }
                                .padding(vertical = 4.dp, horizontal = 2.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            BasicTextField(
                                value = inputValue,
                                onValueChange = { incoming ->
                                    inputValue = incoming
                                },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.titleMedium.copy(
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = contentColor
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(
                                    onSearch = {
                                        if (inputValue.composition == null) {
                                            commitManualSearchAndExpand()
                                        }
                                    },
                                    onDone = {
                                        if (inputValue.composition == null) {
                                            commitManualSearchAndExpand()
                                        }
                                    }
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(inputFocusRequester)
                                    .onFocusChanged { state ->
                                        if (state.isFocused && !isEditing) {
                                            isEditing = true
                                        }
                                    },
                                decorationBox = { innerTextField ->
                                    if (inputValue.text.isEmpty()) {
                                        Text(
                                            text = "输入单词或汉字…",
                                            fontSize = 15.sp,
                                            color = mutedText.copy(alpha = 0.65f),
                                            maxLines = 1
                                        )
                                    }
                                    innerTextField()
                                }
                            )
                        }

                        // 右侧当前词典名：多本命中时带下划线，点击循环切换
                        if (activeResult != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            val canCycle = results.size > 1
                            Text(
                                text = activeResult.dictionaryTitle,
                                fontSize = 12.sp,
                                color = if (canCycle) contentColor.copy(alpha = 0.85f) else mutedText,
                                textDecoration = if (canCycle) TextDecoration.Underline else TextDecoration.None,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .then(
                                        if (canCycle) {
                                            Modifier.clickable { cycleMatchedDictionary() }
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                        }

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "关闭",
                                tint = contentColor.copy(alpha = 0.72f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    HorizontalDivider(thickness = 0.5.dp, color = hairlineBorder)

                    // 2. 命中释义顶部的相关候选词单行横向条
                    if (activeResult != null && relatedWords.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onSizeChanged { relatedBarHeightPx = it.height }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(cardSurfaceColor)
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                relatedWords.forEach { candidate ->
                                    Text(
                                        text = candidate,
                                        fontSize = 12.sp,
                                        color = mutedText,
                                        maxLines = 1,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { jumpToWord(candidate, pushHistory = true) }
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = hairlineBorder.copy(alpha = 0.65f)
                            )
                        }
                    }

                    // 3. 释义正文 / 候选列表 / 空状态区域
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(cardSurfaceColor),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            isImporting -> {
                                Text(
                                    text = "正在导入词典文件…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = mutedText
                                )
                            }

                            enabledDicts.isEmpty() -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = if (dictionaries.isEmpty()) {
                                            "尚未导入本地 MDX 词典"
                                        } else {
                                            "已导入的词典均已停用"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = contentColor
                                    )
                                    Text(
                                        text = "导入 .mdx 词典文件（可配 .css 样式）后即可离线查词，也可在「设置 → 词典」管理排序与样式。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = mutedText
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(headerSurfaceColor)
                                            .border(1.dp, hairlineBorder, RoundedCornerShape(10.dp))
                                            .clickable { libraryPicker.launch(arrayOf("*/*")) }
                                            .padding(horizontal = 16.dp, vertical = 9.dp)
                                    ) {
                                        Text(
                                            text = "导入词库",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = contentColor
                                        )
                                    }
                                }
                            }

                            isLoading && activeResult == null -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) {
                                            if (isEditing) exitEditModeAndExpand()
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "正在查词…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = mutedText
                                    )
                                }
                            }

                            activeResult == null -> {
                                if (suggestions.isNotEmpty()) {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        items(suggestions, key = { it }) { word ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { jumpToWord(word, pushHistory = false) }
                                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = word,
                                                    fontSize = 15.sp,
                                                    color = contentColor,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                            HorizontalDivider(
                                                thickness = 0.5.dp,
                                                color = hairlineBorder.copy(alpha = 0.55f)
                                            )
                                        }
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null
                                            ) {
                                                if (isEditing) exitEditModeAndExpand()
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(24.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = if (activeQuery.isBlank()) {
                                                    "输入词语开始查询"
                                                } else {
                                                    "未查到「$activeQuery」"
                                                },
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                color = contentColor
                                            )
                                            if (activeQuery.isNotBlank()) {
                                                Text(
                                                    text = "已检索 ${enabledDicts.size} 本本地词典",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = mutedText
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            else -> {
                                val bgHex = colorToCssHex(cardSurfaceColor)
                                val fgHex = colorToCssHex(contentColor)
                                val mutedHex = colorToCssHex(mutedText)
                                val borderHex = colorToCssHex(hairlineBorder)
                                val initialScroll = pendingRestoreScrollY
                                val initialAnchor = pendingAnchor

                                val documentHtml = remember(
                                    activeResult,
                                    isDark,
                                    bgHex,
                                    fgHex,
                                    initialScroll,
                                    initialAnchor,
                                    currentContentToken
                                ) {
                                    buildIsolatedMdxDocument(
                                        rawBodyHtml = activeResult.html,
                                        rawUserCss = activeResult.cssText,
                                        hasCustomCss = activeResult.hasCustomCss,
                                        isDark = isDark,
                                        bgHex = bgHex,
                                        fgHex = fgHex,
                                        mutedHex = mutedHex,
                                        borderHex = borderHex,
                                        initialScrollY = initialScroll,
                                        initialAnchor = initialAnchor,
                                        initialEditing = isEditing,
                                        renderToken = currentContentToken
                                    )
                                }

                                MdxPagedShadowWebView(
                                    controller = webController,
                                    htmlDocument = documentHtml,
                                    backgroundArgb = cardSurfaceColor.toArgb(),
                                    onNavigateEntry = { word, anchor ->
                                        jumpToWord(word, anchor = anchor, pushHistory = true)
                                    },
                                    onTapWhileEditing = {
                                        exitEditModeAndExpand()
                                    },
                                    onContentHeightMeasured = { token, heightCssPx ->
                                        if (token == currentContentToken && heightCssPx > 0) {
                                            measuredContentToken = token
                                            measuredContentHeightDp = heightCssPx.toFloat()
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
private fun MdxPagedShadowWebView(
    controller: MdxWebViewController,
    htmlDocument: String,
    backgroundArgb: Int,
    onNavigateEntry: (word: String, anchor: String?) -> Unit,
    onTapWhileEditing: () -> Unit,
    onContentHeightMeasured: (token: String, heightCssPx: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val latestOnNavigate by rememberUpdatedState(onNavigateEntry)
    val latestOnTapWhileEditing by rememberUpdatedState(onTapWhileEditing)
    val latestOnContentHeight by rememberUpdatedState(onContentHeightMeasured)

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                overScrollMode = WebView.OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false

                settings.apply {
                    // 严格离线沙盒：完全屏蔽网络与本地文件访问，仅运行我们内置的 Shadow DOM 与视觉行翻页控制器
                    javaScriptEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    blockNetworkLoads = true
                    loadsImagesAutomatically = true
                    builtInZoomControls = false
                    displayZoomControls = false
                    useWideViewPort = true
                    loadWithOverviewMode = false
                    defaultTextEncodingName = "UTF-8"
                }

                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onNavigateEntry(word: String?, anchor: String?) {
                            val target = word?.trim().orEmpty()
                            if (target.isNotEmpty()) {
                                post {
                                    latestOnNavigate(target, anchor?.takeIf { it.isNotBlank() })
                                }
                            }
                        }

                        @JavascriptInterface
                        fun onTapWhileEditing() {
                            post { latestOnTapWhileEditing() }
                        }

                        @JavascriptInterface
                        fun onScrollChanged(scrollY: Int) {
                            controller.lastScrollY = scrollY.coerceAtLeast(0)
                        }

                        @JavascriptInterface
                        fun onContentHeightChanged(token: String?, heightCssPx: Int) {
                            if (!token.isNullOrEmpty() && heightCssPx > 0) {
                                post { latestOnContentHeight(token, heightCssPx) }
                            }
                        }
                    },
                    "AndroidBridge"
                )

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val url = request?.url?.toString().orEmpty()
                        val handled = parseMdxInternalLink(url)
                        if (handled != null) {
                            latestOnNavigate(handled.first, handled.second)
                        }
                        // 离线模式：拦截一切外部跳转
                        return true
                    }
                }

                controller.webView = this
            }
        },
        update = { webView ->
            controller.webView = webView
            webView.setBackgroundColor(backgroundArgb)
            val prevTag = webView.tag as? String
            if (prevTag != htmlDocument) {
                webView.tag = htmlDocument
                webView.loadDataWithBaseURL(
                    "https://local.mdx/",
                    htmlDocument,
                    "text/html",
                    "UTF-8",
                    null
                )
            }
        },
        onRelease = { webView ->
            if (controller.webView === webView) {
                controller.webView = null
            }
            webView.removeJavascriptInterface("AndroidBridge")
            webView.stopLoading()
            webView.destroy()
        }
    )
}

private fun parseMdxInternalLink(url: String): Pair<String, String?>? {
    val lower = url.lowercase()
    val payload = when {
        lower.startsWith("entry://") -> url.substring(8)
        lower.startsWith("bword://") -> url.substring(8)
        else -> return null
    }
    val rawWord = payload.substringBefore('#')
    val rawAnchor = payload.substringAfter('#', missingDelimiterValue = "").takeIf { it.isNotBlank() }
    val decodedWord = runCatching { Uri.decode(rawWord) }.getOrDefault(rawWord).trim()
    if (decodedWord.isEmpty()) return null
    val decodedAnchor = rawAnchor?.let { runCatching { Uri.decode(it) }.getOrDefault(it) }
    return decodedWord to decodedAnchor
}

private fun colorToCssHex(color: Color): String {
    val argb = color.toArgb()
    return String.format("#%06X", 0xFFFFFF and argb)
}

/**
 * 净化词典 HTML：
 * - 移除 <script>/<iframe>/<object>/<embed>/<link rel=stylesheet>/<base> 及所有内联 on*= 事件与 javascript: 协议；
 * - 屏蔽无效外链图片与词典内置冗余的“回到顶部”按钮；
 * - 修复双重转义或残留的 HTML 实体。
 */
private fun sanitizeMdxHtml(rawHtml: String): String {
    var html = rawHtml
    // 1. 移除脚本与危险嵌入式标签
    html = html.replace(Regex("""<script\b[^>]*>[\s\S]*?</script>""", RegexOption.IGNORE_CASE), "")
    html = html.replace(Regex("""<noscript\b[^>]*>[\s\S]*?</noscript>""", RegexOption.IGNORE_CASE), "")
    html = html.replace(Regex("""<iframe\b[^>]*>[\s\S]*?</iframe>""", RegexOption.IGNORE_CASE), "")
    html = html.replace(Regex("""<object\b[^>]*>[\s\S]*?</object>""", RegexOption.IGNORE_CASE), "")
    html = html.replace(Regex("""<embed\b[^>]*>""", RegexOption.IGNORE_CASE), "")
    html = html.replace(Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE), "")
    html = html.replace(Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE), "")
    html = html.replace(Regex("""<base\b[^>]*>""", RegexOption.IGNORE_CASE), "")

    // 2. 移除内联事件属性与危险协议
    html = html.replace(
        Regex("""\s+on[a-z]+\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+)""", RegexOption.IGNORE_CASE),
        ""
    )
    html = html.replace(
        Regex("""(href|src)\s*=\s*["']\s*(?:javascript|vbscript):[^"']*["']""", RegexOption.IGNORE_CASE),
        """$1="#""""
    )

    // 3. 移除无效外链图片与音视频外链
    html = html.replace(
        Regex("""<img\b[^>]*\bsrc\s*=\s*["'](?:https?:|//|sound://)[^"']*["'][^>]*>""", RegexOption.IGNORE_CASE),
        ""
    )

    // 4. 彻底清除词典内冗余的“回到顶部 / 返回顶部 / 回顶部 / 返回页首”链接与块元素
    html = html.replace(
        Regex(
            """<a\b[^>]*>(?:[^<]{0,16}|<[^>]+>)*?(?:回到顶部|返回顶部|回顶部|回到页首|返回页首|回到顶端|返回顶端|back\s*to\s*top)(?:[^<]{0,12}|<[^>]+>)*?</a>""",
            RegexOption.IGNORE_CASE
        ),
        ""
    )
    html = html.replace(
        Regex(
            """<a\b[^>]*href\s*=\s*["']#(?:top|_top|header|page_top)?["'][^>]*>(?:[^<]{0,16}|<[^>]+>)*?(?:top|▲|↑)(?:[^<]{0,12}|<[^>]+>)*?</a>""",
            RegexOption.IGNORE_CASE
        ),
        ""
    )
    html = html.replace(
        Regex(
            """<(?:div|span|p|button|center|small)\b[^>]*class\s*=\s*["'][^"']*(?:back[-_]?to[-_]?top|go[-_]?top|scroll[-_]?top|to[-_]?top)[^"']*["'][^>]*>[\s\S]*?</(?:div|span|p|button|center|small)>""",
            RegexOption.IGNORE_CASE
        ),
        ""
    )

    // 5. 修复双重转义与残留 HTML 实体
    html = html
        .replace("&amp;nbsp;", "&nbsp;")
        .replace("&amp;lt;", "&lt;")
        .replace("&amp;gt;", "&gt;")
        .replace("&amp;quot;", "&quot;")
        .replace("&amp;apos;", "&#39;")
        .replace("&amp;amp;", "&amp;")
        .replace(Regex("""&amp;(#\d+;|#x[0-9a-fA-F]+;)"""), "&$1")

    return html
}

/**
 * 净化词典 CSS：移除外部 @import、外链 url(http...)、expression() 及破坏容器的 fixed 定位。
 */
private fun sanitizeMdxCss(rawCss: String): String {
    if (rawCss.isBlank()) return ""
    var css = rawCss
    css = css.replace(Regex("""@import\b[^;]*;?""", RegexOption.IGNORE_CASE), "")
    css = css.replace(Regex("""expression\s*\([^)]*\)""", RegexOption.IGNORE_CASE), "")
    css = css.replace(Regex("""url\s*\(\s*["']?\s*(?:https?:|//)[^)]*\)""", RegexOption.IGNORE_CASE), "none")
    css = css.replace(Regex("""position\s*:\s*fixed\b""", RegexOption.IGNORE_CASE), "position: static")
    return css
}

private fun buildIsolatedMdxDocument(
    rawBodyHtml: String,
    rawUserCss: String,
    hasCustomCss: Boolean,
    isDark: Boolean,
    bgHex: String,
    fgHex: String,
    mutedHex: String,
    borderHex: String,
    initialScrollY: Int,
    initialAnchor: String?,
    initialEditing: Boolean,
    renderToken: String
): String {
    val cleanBody = sanitizeMdxHtml(rawBodyHtml)
    val cleanUserCss = sanitizeMdxCss(rawUserCss)
    val useDarkInvert = isDark && hasCustomCss
    val linkColor = if (useDarkInvert) "#1F5FAD" else if (isDark) "#8AB4F8" else "#1F5FAD"
    val shadowBg = if (useDarkInvert) "#FFFFFF" else bgHex
    val shadowFg = if (useDarkInvert) "#1A1A1A" else fgHex
    val shadowMuted = if (useDarkInvert) "#666666" else mutedHex
    val shadowBorder = if (useDarkInvert) "#DCDCDC" else borderHex

    // 强制统一释义区所有元素的字号与行高（比正文 16sp 小 2px -> 14px，行高统一 1.65）
    val shadowBaseCss = """
        :host {
            display: block;
            background: $shadowBg;
            color: $shadowFg;
            font-family: -apple-system, BlinkMacSystemFont, "Noto Sans CJK SC", sans-serif;
            padding: 12px 15px 12px 15px;
            word-wrap: break-word;
            overflow-wrap: break-word;
        }
        a { color: $linkColor; text-decoration: none; }
        table { max-width: 100%; border-collapse: collapse; }
        hr { border: 0; border-top: 1px solid $shadowBorder; margin: 8px 0; }
        img { max-width: 100%; height: auto; }
        img[src^="http"], img[src^="//"], img:not([src]), img[src=""] { display: none !important; }
        .muted { color: $shadowMuted; }
    """.trimIndent()

    val uniformOverrideCss = """
        :host, *, *::before, *::after {
            font-size: 14px !important;
            line-height: 1.65 !important;
            box-sizing: border-box !important;
            max-width: 100% !important;
        }
        ${if (useDarkInvert) "img { filter: invert(1) hue-rotate(180deg) !important; }" else ""}
        ${if (isDark && !hasCustomCss) ":host, body, div, p, span, li, td, th { background-color: transparent !important; color: $fgHex !important; border-color: $borderHex !important; } a { color: $linkColor !important; }" else ""}
    """.trimIndent()

    val bodyJson = JSONObject.quote(cleanBody)
    val baseCssJson = JSONObject.quote(shadowBaseCss)
    val userCssJson = JSONObject.quote(cleanUserCss)
    val overrideCssJson = JSONObject.quote(uniformOverrideCss)
    val anchorJson = JSONObject.quote(initialAnchor ?: "")
    val tokenJson = JSONObject.quote(renderToken)
    val hostFilterCss = if (useDarkInvert) {
        "filter: invert(0.91) hue-rotate(180deg); background: #FFFFFF;"
    } else {
        "background: $bgHex;"
    }

    return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
        <style>
        html, body {
            margin: 0;
            padding: 0;
            width: 100%;
            height: 100%;
            overflow: hidden;
            background: $bgHex;
            -webkit-tap-highlight-color: transparent;
        }
        #dict-viewport {
            position: fixed;
            inset: 0;
            overflow-y: auto;
            overflow-x: hidden;
            background: $bgHex;
            scrollbar-width: none;
        }
        #dict-viewport::-webkit-scrollbar { display: none; }
        #dict-host {
            display: block;
            width: 100%;
            min-height: 24px;
            $hostFilterCss
        }
        #bottom-spacer {
            width: 100%;
            height: 0px;
            pointer-events: none;
            background: $bgHex;
        }
        #mask-top, #mask-bottom {
            position: fixed;
            left: 0;
            right: 0;
            height: 0px;
            pointer-events: none;
            z-index: 30;
            background: $bgHex;
        }
        #mask-top { top: 0; }
        #mask-bottom { bottom: 0; }
        </style>
        </head>
        <body>
        <div id="dict-viewport">
            <div id="dict-host"></div>
            <div id="bottom-spacer"></div>
        </div>
        <div id="mask-top"></div>
        <div id="mask-bottom"></div>
        <script>
        (function() {
            const viewport = document.getElementById('dict-viewport');
            const host = document.getElementById('dict-host');
            const spacer = document.getElementById('bottom-spacer');
            const maskTop = document.getElementById('mask-top');
            const maskBottom = document.getElementById('mask-bottom');

            window.__mdxEditing = ${if (initialEditing) "true" else "false"};
            window.__mdxSetEditing = function(v) {
                window.__mdxEditing = !!v;
            };

            const shadow = host.attachShadow({ mode: 'open' });
            const baseStyle = document.createElement('style');
            baseStyle.textContent = $baseCssJson;
            const userStyle = document.createElement('style');
            userStyle.textContent = $userCssJson;
            const overrideStyle = document.createElement('style');
            overrideStyle.textContent = $overrideCssJson;

            const container = document.createElement('div');
            container.id = 'mdx-root';
            container.innerHTML = $bodyJson;

            // 隐藏加载失败的离线图片与空白占位图，彻底清理任何形式的“回到顶部”元素及残留空容器
            container.querySelectorAll('img').forEach(function(img) {
                img.addEventListener('error', function() { img.style.display = 'none'; reportContentHeight(); });
                const s = (img.getAttribute('src') || '').trim();
                if (!s || /^https?:/i.test(s) || /^\/\//.test(s) || /^sound:/i.test(s)) {
                    img.style.display = 'none';
                }
            });

            const backTopLabels = new Set([
                '回到顶部', '返回顶部', '回顶部', '回到页首', '返回页首', '回页首',
                '回到顶端', '返回顶端', '回顶端', 'backtotop', 'totop'
            ]);
            function normalizeTopLabel(text) {
                return (text || '')
                    .replace(/[\s▲△↑⬆⇧【】\[\]()（）<>《》·\-_=]+/g, '')
                    .toLowerCase();
            }

            // 自底向上移除所有“回到顶部”节点及其变为全空的包装元素
            Array.from(container.querySelectorAll('*')).reverse().forEach(function(el) {
                const tag = (el.tagName || '').toUpperCase();
                if (tag === 'STYLE' || tag === 'SCRIPT' || tag === 'IMG') return;
                const norm = normalizeTopLabel(el.textContent);
                const isBackTopText = backTopLabels.has(norm) ||
                    ((tag === 'A' || tag === 'BUTTON') && (norm === 'top' || norm === ''));
                if (isBackTopText && el.querySelectorAll('p, div, table, ul, ol').length <= 1) {
                    const href = (el.getAttribute && el.getAttribute('href')) || '';
                    if (backTopLabels.has(norm) || href === '#' || /^#(?:top|_top)/i.test(href)) {
                        el.remove();
                        return;
                    }
                }
                if ((tag === 'DIV' || tag === 'P' || tag === 'CENTER' || tag === 'FOOTER') &&
                    !(el.textContent || '').trim() &&
                    !el.querySelector('img, svg, table, hr')) {
                    el.remove();
                }
            });

            // 清除孤立的裸文本“回到顶部”节点
            const textWalker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT, null);
            const textsToRemove = [];
            let tn;
            while ((tn = textWalker.nextNode())) {
                if (backTopLabels.has(normalizeTopLabel(tn.nodeValue))) {
                    textsToRemove.push(tn);
                }
            }
            textsToRemove.forEach(function(n) { n.remove(); });

            // 清理词条最末尾多余的 <br> / <hr> / 空块，避免底部多余留白
            while (container.lastChild) {
                const last = container.lastChild;
                if (last.nodeType === 3 && !(last.nodeValue || '').trim()) {
                    last.remove();
                } else if (last.nodeType === 1) {
                    const t = (last.tagName || '').toUpperCase();
                    if (t === 'BR' || t === 'HR' || (!(last.textContent || '').trim() && !last.querySelector('img, svg, table'))) {
                        last.remove();
                    } else {
                        break;
                    }
                } else {
                    break;
                }
            }

            shadow.appendChild(baseStyle);
            shadow.appendChild(userStyle);
            shadow.appendChild(overrideStyle);
            shadow.appendChild(container);

            const renderToken = $tokenJson;
            let lastReportedHeight = 0;
            function reportContentHeight() {
                const h = Math.ceil(host.getBoundingClientRect().height);
                if (h > 0 && Math.abs(h - lastReportedHeight) >= 2) {
                    lastReportedHeight = h;
                    try { AndroidBridge.onContentHeightChanged(renderToken, h); } catch (e) {}
                }
            }
            if (typeof ResizeObserver !== 'undefined') {
                try {
                    const ro = new ResizeObserver(function() {
                        reportContentHeight();
                        collectVisualLines();
                        updateClipMasks();
                    });
                    ro.observe(host);
                } catch (e) {}
            }

            function scrollToAnchor(anchorName) {
                if (!anchorName) return false;
                let target = shadow.getElementById(anchorName);
                if (!target) {
                    const safe = anchorName.replace(/["\\]/g, '\\$&');
                    target = shadow.querySelector('[name="' + safe + '"], [id="' + safe + '"]');
                }
                if (target) {
                    const vpRect = viewport.getBoundingClientRect();
                    const tRect = target.getBoundingClientRect();
                    viewport.scrollTop = Math.max(0, Math.floor(viewport.scrollTop + tRect.top - vpRect.top - 8));
                    updateClipMasks();
                    return true;
                }
                return false;
            }

            // 拦截 Shadow DOM 内部链接：entry:// / bword:// 跨词跳转，#anchor 词条内锚点跳转
            shadow.addEventListener('click', function(ev) {
                const path = ev.composedPath ? ev.composedPath() : [];
                let link = null;
                for (let i = 0; i < path.length; i++) {
                    const n = path[i];
                    if (n && n.tagName && n.tagName.toUpperCase() === 'A') {
                        link = n;
                        break;
                    }
                }
                if (!link) return;
                const href = (link.getAttribute('href') || '').trim();
                if (!href) return;
                ev.preventDefault();
                ev.stopPropagation();

                const lower = href.toLowerCase();
                if (lower.startsWith('entry://') || lower.startsWith('bword://')) {
                    const payload = href.slice(8);
                    const hashIdx = payload.indexOf('#');
                    const rawWord = hashIdx >= 0 ? payload.slice(0, hashIdx) : payload;
                    const rawAnc = hashIdx >= 0 ? payload.slice(hashIdx + 1) : '';
                    let w = rawWord;
                    let a = rawAnc;
                    try { w = decodeURIComponent(rawWord); } catch (e) {}
                    try { a = decodeURIComponent(rawAnc); } catch (e) {}
                    if (w.trim()) {
                        AndroidBridge.onNavigateEntry(w.trim(), a);
                    } else if (a) {
                        scrollToAnchor(a);
                    }
                    return;
                }
                if (href.startsWith('#')) {
                    scrollToAnchor(href.slice(1));
                }
            }, true);

            let cachedLines = [];
            let cachedContentHeight = 0;

            function collectVisualLines() {
                const vpRect = viewport.getBoundingClientRect();
                const scrollTop = viewport.scrollTop;
                const rects = [];
                const range = document.createRange();
                const walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT, null);
                let node;
                while ((node = walker.nextNode())) {
                    if (!node.nodeValue || !node.nodeValue.trim()) continue;
                    try {
                        range.selectNodeContents(node);
                        const clientRects = range.getClientRects();
                        for (let i = 0; i < clientRects.length; i++) {
                            const r = clientRects[i];
                            if (r.width < 0.5 || r.height < 1) continue;
                            rects.push({
                                top: r.top - vpRect.top + scrollTop,
                                bottom: r.bottom - vpRect.top + scrollTop
                            });
                        }
                    } catch (e) {}
                }
                rects.sort(function(a, b) { return a.top - b.top; });

                const lines = [];
                for (let i = 0; i < rects.length; i++) {
                    const r = rects[i];
                    if (lines.length === 0) {
                        lines.push({ top: r.top, bottom: r.bottom });
                    } else {
                        const prev = lines[lines.length - 1];
                        if (Math.abs(r.top - prev.top) <= 4 || (r.top < prev.bottom - 2 && r.bottom > prev.top + 2)) {
                            prev.top = Math.min(prev.top, r.top);
                            prev.bottom = Math.max(prev.bottom, r.bottom);
                        } else {
                            lines.push({ top: r.top, bottom: r.bottom });
                        }
                    }
                }
                cachedLines = lines;
                cachedContentHeight = host.scrollHeight;
                updateDynamicSpacer();
                return lines;
            }

            function updateDynamicSpacer() {
                const vh = viewport.clientHeight;
                if (vh <= 0) return;
                const contentH = Math.ceil(host.getBoundingClientRect().height);
                if (contentH <= vh + 4) {
                    spacer.style.height = '0px';
                } else {
                    const lastLineH = cachedLines.length > 0
                        ? (cachedLines[cachedLines.length - 1].bottom - cachedLines[cachedLines.length - 1].top)
                        : 24;
                    spacer.style.height = Math.max(24, Math.floor(vh - lastLineH - 12)) + 'px';
                }
            }

            function updateClipMasks() {
                const vh = viewport.clientHeight;
                if (vh <= 0) return;
                if (cachedLines.length === 0 || Math.abs(host.scrollHeight - cachedContentHeight) > 4) {
                    collectVisualLines();
                }
                const viewTop = viewport.scrollTop;
                const viewBottom = viewTop + vh;
                let topMask = 0;
                let bottomMask = 0;

                for (let i = 0; i < cachedLines.length; i++) {
                    const line = cachedLines[i];
                    if (line.top < viewTop - 1.5 && line.bottom > viewTop + 1.5) {
                        topMask = Math.max(topMask, Math.ceil(line.bottom - viewTop) + 1);
                    }
                    if (line.top < viewBottom - 1.5 && line.bottom > viewBottom + 1.5) {
                        bottomMask = Math.max(bottomMask, Math.ceil(viewBottom - line.top) + 1);
                    }
                }

                maskTop.style.height = Math.min(topMask, Math.floor(vh * 0.25)) + 'px';
                maskBottom.style.height = Math.min(bottomMask, Math.floor(vh * 0.25)) + 'px';
            }

            function pageDown() {
                const vh = viewport.clientHeight;
                if (vh <= 0) return;
                const lines = cachedLines.length ? cachedLines : collectVisualLines();
                const viewTop = viewport.scrollTop;
                const viewBottom = viewTop + vh;

                // 寻找第一条未被完整显示在当前页底部的视觉行
                let nextLine = null;
                for (let i = 0; i < lines.length; i++) {
                    if (lines[i].bottom > viewBottom - 1.5) {
                        nextLine = lines[i];
                        break;
                    }
                }
                if (nextLine) {
                    const targetTop = Math.max(0, Math.floor(nextLine.top) - 2);
                    if (targetTop > viewTop + 1) {
                        viewport.scrollTop = targetTop;
                    } else {
                        viewport.scrollTop = viewTop + Math.max(40, vh - 28);
                    }
                } else {
                    const maxContentScroll = Math.max(0, host.scrollHeight - vh);
                    if (viewTop < maxContentScroll - 2) {
                        viewport.scrollTop = maxContentScroll;
                    }
                }
                updateClipMasks();
            }

            function pageUp() {
                const vh = viewport.clientHeight;
                if (vh <= 0) return;
                const lines = cachedLines.length ? cachedLines : collectVisualLines();
                const viewTop = viewport.scrollTop;
                if (viewTop <= 1) return;

                // 寻找当前页首条完整可见行之前的那一行
                let lastAboveIdx = -1;
                for (let i = 0; i < lines.length; i++) {
                    if (lines[i].top < viewTop + 1.5) {
                        lastAboveIdx = i;
                    } else {
                        break;
                    }
                }
                if (lastAboveIdx <= 0) {
                    viewport.scrollTop = 0;
                    updateClipMasks();
                    return;
                }
                const targetBottom = lines[lastAboveIdx].bottom;
                let startIdx = lastAboveIdx;
                while (startIdx > 0 && (targetBottom - lines[startIdx - 1].top) <= vh - 6) {
                    startIdx--;
                }
                const targetTop = Math.max(0, Math.floor(lines[startIdx].top) - 2);
                viewport.scrollTop = targetTop < viewTop ? targetTop : Math.max(0, viewTop - Math.max(40, vh - 28));
                updateClipMasks();
            }

            window.__mdxPageDown = pageDown;
            window.__mdxPageUp = pageUp;
            window.__mdxRefreshLayout = function() {
                collectVisualLines();
                updateClipMasks();
            };

            let scrollReportTimer = null;
            viewport.addEventListener('scroll', function() {
                updateClipMasks();
                if (scrollReportTimer) clearTimeout(scrollReportTimer);
                scrollReportTimer = setTimeout(function() {
                    try { AndroidBridge.onScrollChanged(Math.round(viewport.scrollTop)); } catch (e) {}
                }, 80);
            }, { passive: true });

            window.addEventListener('resize', function() {
                collectVisualLines();
                updateClipMasks();
            });

            // 点击左右半屏翻页（拖拽滚动、划词、点击链接/按钮时不翻页；编辑态下点击仅退出编辑并拉长，不翻页）
            let downX = 0, downY = 0, downTime = 0, moved = false;
            viewport.addEventListener('pointerdown', function(ev) {
                downX = ev.clientX;
                downY = ev.clientY;
                downTime = Date.now();
                moved = false;
            }, { passive: true });

            viewport.addEventListener('pointermove', function(ev) {
                if (Math.hypot(ev.clientX - downX, ev.clientY - downY) > 10) {
                    moved = true;
                }
            }, { passive: true });

            viewport.addEventListener('pointerup', function(ev) {
                if (moved || (Date.now() - downTime) > 420) return;

                // 检查是否存在划词选区
                const winSel = window.getSelection ? window.getSelection().toString().trim() : '';
                const shSel = (shadow.getSelection ? shadow.getSelection().toString().trim() : '');
                if (winSel.length > 0 || shSel.length > 0) return;

                // 检查是否点击了链接或交互元素
                const path = ev.composedPath ? ev.composedPath() : [];
                for (let i = 0; i < path.length; i++) {
                    const el = path[i];
                    if (!el || !el.tagName) continue;
                    const tag = el.tagName.toUpperCase();
                    if (tag === 'A' || tag === 'BUTTON' || tag === 'INPUT' || tag === 'SELECT' || tag === 'SUMMARY') {
                        return;
                    }
                    if (el.getAttribute && el.getAttribute('role') === 'button') {
                        return;
                    }
                }

                // 退出编辑态时机 4：编辑态点击释义区只退出编辑并拉长，停留在当前页绝不翻页
                if (window.__mdxEditing) {
                    try { AndroidBridge.onTapWhileEditing(); } catch (e) {}
                    return;
                }

                if (ev.clientX < window.innerWidth * 0.5) {
                    pageUp();
                } else {
                    pageDown();
                }
            });

            requestAnimationFrame(function() {
                reportContentHeight();
                collectVisualLines();
                const initAnchor = $anchorJson;
                const initScroll = $initialScrollY;
                if (initAnchor) {
                    scrollToAnchor(initAnchor);
                } else if (initScroll > 0) {
                    viewport.scrollTop = initScroll;
                }
                updateClipMasks();
                setTimeout(function() {
                    reportContentHeight();
                    collectVisualLines();
                    updateClipMasks();
                }, 120);
            });
        })();
        </script>
        </body>
        </html>
    """.trimIndent()
}

@Composable
private fun LookupBackArrowIcon(color: Color) {
    Canvas(modifier = Modifier.size(17.dp)) {
        val sw = 1.6.dp.toPx()
        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val w = size.width
        val h = size.height
        drawLine(
            color = color,
            start = Offset(w * 0.80f, h * 0.50f),
            end = Offset(w * 0.22f, h * 0.50f),
            strokeWidth = sw,
            cap = StrokeCap.Round
        )
        val head = Path().apply {
            moveTo(w * 0.46f, h * 0.24f)
            lineTo(w * 0.20f, h * 0.50f)
            lineTo(w * 0.46f, h * 0.76f)
        }
        drawPath(head, color = color, style = stroke)
    }
}

@Composable
private fun LookupBookIcon(color: Color) {
    Canvas(modifier = Modifier.size(17.dp)) {
        val sw = 1.3.dp.toPx()
        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.16f, h * 0.08f),
            size = Size(w * 0.68f, h * 0.84f),
            cornerRadius = CornerRadius(2.2.dp.toPx(), 2.2.dp.toPx()),
            style = stroke
        )
        val aPath = Path().apply {
            moveTo(w * 0.36f, h * 0.63f)
            lineTo(w * 0.50f, h * 0.27f)
            lineTo(w * 0.64f, h * 0.63f)
        }
        drawPath(aPath, color = color, style = stroke)
        drawLine(
            color = color,
            start = Offset(w * 0.41f, h * 0.52f),
            end = Offset(w * 0.59f, h * 0.52f),
            strokeWidth = sw,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(w * 0.28f, h * 0.78f),
            end = Offset(w * 0.72f, h * 0.78f),
            strokeWidth = sw * 0.85f,
            cap = StrokeCap.Round
        )
    }
}
