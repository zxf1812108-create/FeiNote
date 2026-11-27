package com.feiji.bulletnotes.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.feiji.bulletnotes.data.MdxDictionaryManager
import com.feiji.bulletnotes.ui.theme.LocalIsDarkTheme
import kotlinx.coroutines.launch

/**
 * 极简本地 MDX 词典查词弹窗：
 * - 支持直接展示 .mdx 词条 HTML 与配套 .css 样式；
 * - 支持多本词典切换与词条内 entry:// 跳转及返回；
 * - 尚未导入词典时可直接在此弹窗内一键导入 .mdx 与 .css 文件。
 */
@Composable
fun DictionaryLookupDialog(
    initialQuery: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDark = LocalIsDarkTheme.current

    LaunchedEffect(Unit) {
        MdxDictionaryManager.ensureLoaded(context)
    }

    val dictionaries by MdxDictionaryManager.dictionaries.collectAsState()
    val enabledDicts = remember(dictionaries) { dictionaries.filter { it.enabled } }

    val historyStack = remember(initialQuery) { mutableStateListOf<String>() }
    var currentQuery by remember(initialQuery) { mutableStateOf(initialQuery.trim()) }
    var lookupVersion by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var results by remember { mutableStateOf<List<MdxDictionaryManager.DictionaryLookupResult>>(emptyList()) }
    var selectedResultIndex by remember { mutableIntStateOf(0) }
    var isImporting by remember { mutableStateOf(false) }

    val mdxPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            isImporting = true
            scope.launch {
                val res = MdxDictionaryManager.importMdx(context, uri)
                isImporting = false
                res.onSuccess { entry ->
                    Toast.makeText(
                        context,
                        "已导入「${entry.title}」（${entry.entryCount} 词条）",
                        Toast.LENGTH_SHORT
                    ).show()
                    lookupVersion++
                }.onFailure { err ->
                    Toast.makeText(
                        context,
                        err.message ?: "导入 MDX 词典失败",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    val cssPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            isImporting = true
            scope.launch {
                val res = MdxDictionaryManager.importCss(context, uri)
                isImporting = false
                res.onSuccess { msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    lookupVersion++
                }.onFailure { err ->
                    Toast.makeText(
                        context,
                        err.message ?: "导入 CSS 样式失败",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    LaunchedEffect(currentQuery, enabledDicts, lookupVersion) {
        if (enabledDicts.isEmpty() || currentQuery.isBlank()) {
            results = emptyList()
            selectedResultIndex = 0
            isLoading = false
            return@LaunchedEffect
        }
        isLoading = true
        val found = MdxDictionaryManager.lookupAcrossDictionaries(context, currentQuery)
        results = found
        selectedResultIndex = 0
        isLoading = false
    }

    val contentColor = MaterialTheme.colorScheme.onSurface
    val mutedText = MaterialTheme.colorScheme.onSurfaceVariant
    val hairlineBorder = MaterialTheme.colorScheme.outlineVariant
    val dialogSurfaceColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val webBgColor = MaterialTheme.colorScheme.surface

    val activeResult = results.getOrNull(selectedResultIndex)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.76f)
                .border(1.dp, hairlineBorder, RoundedCornerShape(18.dp)),
            shape = RoundedCornerShape(18.dp),
            color = dialogSurfaceColor,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // 顶栏：返回（若有内部跳转） + 词头 + 导入快捷入口 + 关闭
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (historyStack.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    val prev = historyStack.removeAt(historyStack.lastIndex)
                                    currentQuery = prev
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "‹",
                                fontSize = 20.sp,
                                color = contentColor
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentQuery.ifBlank { "查词" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = contentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (
                            activeResult != null &&
                            !activeResult.matchedWord.equals(currentQuery, ignoreCase = true)
                        ) {
                            Text(
                                text = "→ ${activeResult.matchedWord}",
                                style = MaterialTheme.typography.labelSmall,
                                color = mutedText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = contentColor.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // 多本词典命中时展示极简切换条
                if (results.size > 1) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        results.forEachIndexed { index, item ->
                            val selected = index == selectedResultIndex
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.surfaceContainerHighest
                                        else Color.Transparent
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (selected) hairlineBorder else Color.Transparent,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable { selectedResultIndex = index }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = item.dictionaryTitle,
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) contentColor else mutedText,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = hairlineBorder)
                Spacer(modifier = Modifier.height(8.dp))

                // 正文区域
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(webBgColor)
                        .border(1.dp, hairlineBorder, RoundedCornerShape(12.dp)),
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
                                    .padding(20.dp),
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
                                    text = "导入 .mdx 词典文件及配套 .css 样式文件后，即可离线查词。也可随时在「设置 → 词典」中管理。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = mutedText
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(9.dp))
                                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                            .border(1.dp, hairlineBorder, RoundedCornerShape(9.dp))
                                            .clickable { mdxPicker.launch(arrayOf("*/*")) }
                                            .padding(horizontal = 14.dp, vertical = 9.dp)
                                    ) {
                                        Text(
                                            text = "导入 MDX",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = contentColor
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(9.dp))
                                            .border(1.dp, hairlineBorder, RoundedCornerShape(9.dp))
                                            .clickable { cssPicker.launch(arrayOf("*/*")) }
                                            .padding(horizontal = 14.dp, vertical = 9.dp)
                                    ) {
                                        Text(
                                            text = "导入 CSS",
                                            fontSize = 13.sp,
                                            color = mutedText
                                        )
                                    }
                                }
                            }
                        }

                        isLoading -> {
                            Text(
                                text = "正在查词…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = mutedText
                            )
                        }

                        activeResult == null -> {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "未查到「$currentQuery」",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = contentColor
                                )
                                Text(
                                    text = "已检索 ${enabledDicts.size} 本本地词典",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = mutedText
                                )
                            }
                        }

                        else -> {
                            val bgHex = colorToCssHex(webBgColor)
                            val fgHex = colorToCssHex(contentColor)
                            val mutedHex = colorToCssHex(mutedText)
                            val borderHex = colorToCssHex(hairlineBorder)
                            val documentHtml = remember(activeResult, isDark, bgHex, fgHex) {
                                buildMdxHtmlDocument(
                                    bodyHtml = activeResult.html,
                                    userCss = activeResult.cssText,
                                    isDark = isDark,
                                    bgHex = bgHex,
                                    fgHex = fgHex,
                                    mutedHex = mutedHex,
                                    borderHex = borderHex
                                )
                            }

                            MdxDefinitionWebView(
                                htmlDocument = documentHtml,
                                backgroundArgb = webBgColor.toArgb(),
                                onNavigateEntry = { targetWord ->
                                    val cleaned = targetWord.trim()
                                    if (cleaned.isNotEmpty() && !cleaned.equals(currentQuery, ignoreCase = true)) {
                                        historyStack.add(currentQuery)
                                        currentQuery = cleaned
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                // 底部词典来源提示（单本词典时显示）
                if (activeResult != null && results.size == 1) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = activeResult.dictionaryTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = mutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MdxDefinitionWebView(
    htmlDocument: String,
    backgroundArgb: Int,
    onNavigateEntry: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val latestOnNavigate by androidx.compose.runtime.rememberUpdatedState(onNavigateEntry)

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                settings.apply {
                    javaScriptEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    blockNetworkLoads = true
                    loadsImagesAutomatically = false
                    builtInZoomControls = false
                    displayZoomControls = false
                    useWideViewPort = false
                    loadWithOverviewMode = false
                    defaultTextEncodingName = "UTF-8"
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val url = request?.url?.toString().orEmpty()
                        if (url.startsWith("entry://", ignoreCase = true)) {
                            val rawWord = url.substring(8).substringBefore('#')
                            val decoded = runCatching { Uri.decode(rawWord) }.getOrDefault(rawWord)
                            if (decoded.isNotBlank()) {
                                latestOnNavigate(decoded)
                            }
                            return true
                        }
                        // 离线沙盒模式：拦截外部网络跳转
                        return true
                    }
                }
            }
        },
        update = { webView ->
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
            webView.stopLoading()
            webView.destroy()
        }
    )
}

private fun colorToCssHex(color: Color): String {
    val argb = color.toArgb()
    return String.format("#%06X", 0xFFFFFF and argb)
}

private fun buildMdxHtmlDocument(
    bodyHtml: String,
    userCss: String,
    isDark: Boolean,
    bgHex: String,
    fgHex: String,
    mutedHex: String,
    borderHex: String
): String {
    val linkColor = if (isDark) "#8AB4F8" else "#1F5FAD"
    val darkPatch = if (isDark) {
        """
        html, body { background-color: $bgHex !important; color: $fgHex !important; }
        body * { border-color: $borderHex !important; }
        """.trimIndent()
    } else {
        ""
    }

    // 剥离 MDX 词条内部指向外部本地 CSS 的 <link> 标签，由我们直接内联用户导入的 CSS 样式。
    val cleanedBody = bodyHtml
        .replace(Regex("""<link\b[^>]*rel\s*=\s*["']?stylesheet["']?[^>]*>""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""<script\b[^>]*>[\s\S]*?</script>""", RegexOption.IGNORE_CASE), "")

    return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
        <style>
        html, body {
            margin: 0;
            padding: 12px 14px 20px 14px;
            background-color: $bgHex;
            color: $fgHex;
            font-family: -apple-system, BlinkMacSystemFont, "Noto Sans CJK SC", sans-serif;
            font-size: 15px;
            line-height: 1.65;
            word-wrap: break-word;
            overflow-wrap: break-word;
        }
        a { color: $linkColor; text-decoration: none; }
        img { display: none; }
        table { max-width: 100%; border-collapse: collapse; }
        hr { border: 0; border-top: 1px solid $borderHex; margin: 10px 0; }
        .muted { color: $mutedHex; }
        </style>
        <style>
        $userCss
        </style>
        <style>
        $darkPatch
        </style>
        </head>
        <body>
        $cleanedBody
        </body>
        </html>
    """.trimIndent()
}
