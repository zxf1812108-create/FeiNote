package com.feiji.bulletnotes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.feiji.bulletnotes.data.BackupManager
import com.feiji.bulletnotes.data.LanWebServer
import com.feiji.bulletnotes.data.MdxDictionaryManager
import com.feiji.bulletnotes.data.MemoryCurveConfig
import com.feiji.bulletnotes.data.ReviewFamiliarity
import com.feiji.bulletnotes.data.SpacedRepetitionEngine
import com.feiji.bulletnotes.data.WebDavBackupManager
import com.feiji.bulletnotes.ui.theme.DarkOnlineGreen
import com.feiji.bulletnotes.ui.theme.LightOnlineGreen
import com.feiji.bulletnotes.ui.theme.LocalIsDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

private enum class SettingsTab(val label: String) {
    LAN("直连"),
    SYNC("同步"),
    RESTORE("备份"),
    CURVE("曲线"),
    DICTIONARY("词典")
}

/**
 * 长按回收站弹出的统一「设置」弹窗：
 * - 顶部提供浅色/深色主题即时切换与关闭按钮；
 * - 内部通过 4 段极简标签平级整合「电脑直连（默认）」「同步设置」「备份恢复」「记忆曲线」，消除多层弹窗跳转。
 */
@Composable
fun UnifiedSettingsDialog(
    lanServerState: LanWebServer.LanServerState,
    webDavConfig: WebDavBackupManager.WebDavConfig,
    backupFiles: List<BackupManager.BackupFile>,
    memoryCurveConfig: MemoryCurveConfig,
    onToggleTheme: () -> Unit,
    onStartLanServer: () -> Unit,
    onStopLanServer: () -> Unit,
    onRefreshLanPin: () -> Unit,
    onSaveWebDavSettings: (
        serverUrl: String,
        username: String,
        password: String,
        encryptionPassword: String,
        wifiAutoSyncEnabled: Boolean
    ) -> Unit,
    onRefreshBackups: () -> Unit,
    onSelectBackupToRestore: (BackupManager.BackupFile) -> Unit,
    onSaveMemoryCurveConfig: (MemoryCurveConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var activeTab by remember { mutableStateOf(SettingsTab.LAN) }
    val isDarkTheme = LocalIsDarkTheme.current
    val contentColor = MaterialTheme.colorScheme.onSurface
    val mutedText = MaterialTheme.colorScheme.onSurfaceVariant
    val hairlineBorder = MaterialTheme.colorScheme.outlineVariant
    val dialogSurfaceColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val onlineGreen = if (isDarkTheme) DarkOnlineGreen else LightOnlineGreen

    LaunchedEffect(activeTab) {
        if (activeTab == SettingsTab.RESTORE) {
            onRefreshBackups()
        }
    }

    var settingsLookupWord by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        MinimalTextToolbarHost(
            onTranslateRequest = { word -> settingsLookupWord = word },
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.84f)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .border(
                        width = 1.dp,
                        color = hairlineBorder,
                        shape = RoundedCornerShape(18.dp)
                    ),
                shape = RoundedCornerShape(18.dp),
                color = dialogSurfaceColor,
                tonalElevation = 0.dp
            ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                // 顶部标题栏：标题 + 浅色/深色主题即时切换 + 关闭
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "设置",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = contentColor,
                        modifier = Modifier.weight(1f)
                    )

                    // 浅色 / 深色极简切换控件
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, hairlineBorder, RoundedCornerShape(8.dp))
                            .padding(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ThemeSwitchSegment(
                            label = "浅色",
                            selected = !isDarkTheme,
                            onClick = {
                                if (isDarkTheme) onToggleTheme()
                            }
                        )
                        ThemeSwitchSegment(
                            label = "深色",
                            selected = isDarkTheme,
                            onClick = {
                                if (!isDarkTheme) onToggleTheme()
                            }
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

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

                Spacer(modifier = Modifier.height(10.dp))

                // 顶部四段极简标签栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, hairlineBorder, RoundedCornerShape(10.dp))
                        .padding(3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingsTab.entries.forEach { tab ->
                        val selected = activeTab == tab
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.surfaceContainerHighest
                                    else Color.Transparent
                                )
                                .clickable { activeTab = tab },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (tab == SettingsTab.LAN && lanServerState.running) {
                                    Box(
                                        modifier = Modifier
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(onlineGreen)
                                    )
                                }
                                Text(
                                    text = tab.label,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) contentColor else mutedText
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = hairlineBorder)
                Spacer(modifier = Modifier.height(12.dp))

                // 内容区
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    when (activeTab) {
                        SettingsTab.LAN -> LanConnectTabContent(
                            lanServerState = lanServerState,
                            onlineGreen = onlineGreen,
                            onStart = onStartLanServer,
                            onStop = onStopLanServer,
                            onRefreshPin = onRefreshLanPin
                        )

                        SettingsTab.SYNC -> SyncSettingsTabContent(
                            webDavConfig = webDavConfig,
                            onSave = onSaveWebDavSettings
                        )

                        SettingsTab.RESTORE -> BackupRestoreTabContent(
                            backupFiles = backupFiles,
                            onRefresh = onRefreshBackups,
                            onSelectBackup = onSelectBackupToRestore
                        )

                        SettingsTab.CURVE -> MemoryCurveTabContent(
                            initialConfig = memoryCurveConfig,
                            onSave = onSaveMemoryCurveConfig
                        )

                        SettingsTab.DICTIONARY -> DictionarySettingsTabContent()
                    }
                }
            }
        }
        }
    }

    settingsLookupWord?.let { word ->
        DictionaryLookupDialog(
            initialQuery = word,
            onDismiss = { settingsLookupWord = null }
        )
    }
}

@Composable
private fun ThemeSwitchSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val contentColor = MaterialTheme.colorScheme.onSurface
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceContainerHighest
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 11.5.sp,
            maxLines = 1,
            softWrap = false,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) contentColor else mutedColor
        )
    }
}

@Composable
private fun LanConnectTabContent(
    lanServerState: LanWebServer.LanServerState,
    onlineGreen: Color,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRefreshPin: () -> Unit
) {
    val context = LocalContext.current
    val contentColor = MaterialTheme.colorScheme.onSurface
    val mutedText = MaterialTheme.colorScheme.onSurfaceVariant
    val hairlineBorder = MaterialTheme.colorScheme.outlineVariant

    var previewUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(lanServerState.running, lanServerState.port) {
        if (!lanServerState.running) {
            previewUrls = withContext(Dispatchers.IO) {
                LanWebServer.previewLanUrls(lanServerState.port)
            }
        }
    }

    val rawUrls = if (lanServerState.running) lanServerState.urls else previewUrls
    val displayUrls = if (rawUrls.isNotEmpty()) {
        rawUrls
    } else {
        listOf("http://127.0.0.1:${lanServerState.port}")
    }

    val formattedPin = if (lanServerState.running && lanServerState.pin.length == 6) {
        lanServerState.pin.substring(0, 3) + " " + lanServerState.pin.substring(3)
    } else if (lanServerState.running && lanServerState.pin.isNotEmpty()) {
        lanServerState.pin
    } else {
        "--- ---"
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(
                            if (lanServerState.running) onlineGreen
                            else mutedText.copy(alpha = 0.4f)
                        )
                )
                Text(
                    text = when {
                        !lanServerState.running -> "未开启"
                        lanServerState.activeSessions > 0 && !lanServerState.lastClientIp.isNullOrBlank() ->
                            "直连运行中 · 已连接 ${lanServerState.activeSessions} 台设备（${lanServerState.lastClientIp}）"
                        else -> "直连运行中 · 等待浏览器连接"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (lanServerState.running) contentColor else mutedText
                )
            }

            Text(
                text = "浏览器链接",
                style = MaterialTheme.typography.labelMedium,
                color = mutedText
            )

            displayUrls.forEach { url ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, hairlineBorder, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = url,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (lanServerState.running) contentColor else contentColor.copy(alpha = 0.72f),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("FeiNote LAN URL", url))
                            Toast.makeText(context, "已复制访问地址", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.defaultMinSize(minWidth = 1.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("复制", style = MaterialTheme.typography.labelMedium, color = contentColor)
                    }
                }
            }

            if (rawUrls.isEmpty()) {
                Text(
                    text = "未检测到 Wi-Fi/热点局域网 IP",
                    style = MaterialTheme.typography.labelMedium,
                    color = mutedText
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, hairlineBorder, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "配对码",
                        style = MaterialTheme.typography.labelMedium,
                        color = mutedText
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = formattedPin,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (lanServerState.running) contentColor else mutedText.copy(alpha = 0.55f)
                    )
                }
                if (lanServerState.running) {
                    TextButton(
                        onClick = {
                            onRefreshPin()
                            Toast.makeText(context, "配对码已刷新，旧连接已断开", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.defaultMinSize(minWidth = 1.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("换一个", style = MaterialTheme.typography.labelMedium, color = contentColor)
                    }
                }
            }

            lanServerState.errorMessage?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(color = hairlineBorder)
        Spacer(modifier = Modifier.height(10.dp))

        // 单个开关按钮：点击切换开启直连 / 停止直连
        val isRunning = lanServerState.running
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (isRunning) Color.Transparent
                    else MaterialTheme.colorScheme.primary
                )
                .then(
                    if (isRunning) {
                        Modifier.border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(10.dp)
                        )
                    } else {
                        Modifier
                    }
                )
                .clickable {
                    if (isRunning) onStop() else onStart()
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isRunning) "停止直连" else "开启直连",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

@Composable
private fun SyncSettingsTabContent(
    webDavConfig: WebDavBackupManager.WebDavConfig,
    onSave: (
        serverUrl: String,
        username: String,
        password: String,
        encryptionPassword: String,
        wifiAutoSyncEnabled: Boolean
    ) -> Unit
) {
    val context = LocalContext.current
    var serverUrlInput by remember(webDavConfig) { mutableStateOf(webDavConfig.serverUrl) }
    var usernameInput by remember(webDavConfig) { mutableStateOf(webDavConfig.username) }
    var passwordInput by remember(webDavConfig) { mutableStateOf(webDavConfig.password) }
    var passwordVisible by remember { mutableStateOf(false) }
    var encryptionPasswordInput by remember(webDavConfig) {
        mutableStateOf(webDavConfig.encryptionPassword)
    }
    var encryptionPasswordVisible by remember { mutableStateOf(false) }
    var confirmEncryptionPasswordInput by remember(webDavConfig) {
        mutableStateOf(webDavConfig.encryptionPassword)
    }
    var confirmEncryptionPasswordVisible by remember { mutableStateOf(false) }
    var encryptionPasswordMismatchError by remember { mutableStateOf(false) }
    var serverUrlInvalidError by remember { mutableStateOf(false) }
    var wifiAutoSyncInput by remember(webDavConfig) {
        mutableStateOf(webDavConfig.wifiAutoSyncEnabled)
    }

    val isServerUrlUnsafe = serverUrlInput.isNotBlank() &&
        !WebDavBackupManager.isAllowedServerUrl(serverUrlInput)
    val showServerUrlError = serverUrlInvalidError || isServerUrlUnsafe
    val isEncryptionPasswordMismatch = encryptionPasswordInput.isNotEmpty() &&
        encryptionPasswordInput != confirmEncryptionPasswordInput
    val showMismatchError = encryptionPasswordMismatchError ||
        (confirmEncryptionPasswordInput.isNotEmpty() && isEncryptionPasswordMismatch)

    val contentColor = MaterialTheme.colorScheme.onSurface
    val mutedText = MaterialTheme.colorScheme.onSurfaceVariant
    val hairlineBorder = MaterialTheme.colorScheme.outlineVariant
    val fieldTextStyle = MaterialTheme.typography.bodyMedium
    val fieldShape = RoundedCornerShape(8.dp)

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "WebDAV 同步备份",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor
                )
                SmartOutlinedTextField(
                    value = serverUrlInput,
                    onValueChange = {
                        serverUrlInput = it
                        serverUrlInvalidError = false
                    },
                    label = { Text("地址", style = MaterialTheme.typography.labelMedium) },
                    textStyle = fieldTextStyle,
                    shape = fieldShape,
                    singleLine = true,
                    isError = showServerUrlError,
                    modifier = Modifier.fillMaxWidth()
                )
                if (showServerUrlError) {
                    Text(
                        text = "公网 WebDAV 服务器必须使用 https:// 加密地址",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                SmartOutlinedTextField(
                    value = usernameInput,
                    onValueChange = { usernameInput = it },
                    label = { Text("账号", style = MaterialTheme.typography.labelMedium) },
                    textStyle = fieldTextStyle,
                    shape = fieldShape,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                SmartOutlinedTextField(
                    value = passwordInput,
                    onValueChange = { passwordInput = it },
                    label = { Text("应用密码（留空则不上传）", style = MaterialTheme.typography.labelMedium) },
                    textStyle = fieldTextStyle,
                    shape = fieldShape,
                    singleLine = true,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(
                            onClick = { passwordVisible = !passwordVisible },
                            modifier = Modifier.defaultMinSize(minWidth = 1.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text(
                                text = if (passwordVisible) "隐藏" else "显示",
                                style = MaterialTheme.typography.labelMedium,
                                color = contentColor
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            HorizontalDivider(color = hairlineBorder)

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "加密备份",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor
                )
                SmartOutlinedTextField(
                    value = encryptionPasswordInput,
                    onValueChange = {
                        encryptionPasswordInput = it
                        if (it.isEmpty()) {
                            confirmEncryptionPasswordInput = ""
                            wifiAutoSyncInput = false
                        }
                        encryptionPasswordMismatchError = false
                    },
                    label = { Text("加密密码（留空则不加密）", style = MaterialTheme.typography.labelMedium) },
                    textStyle = fieldTextStyle,
                    shape = fieldShape,
                    singleLine = true,
                    visualTransformation = if (encryptionPasswordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(
                            onClick = { encryptionPasswordVisible = !encryptionPasswordVisible },
                            modifier = Modifier.defaultMinSize(minWidth = 1.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text(
                                text = if (encryptionPasswordVisible) "隐藏" else "显示",
                                style = MaterialTheme.typography.labelMedium,
                                color = contentColor
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                SmartOutlinedTextField(
                    value = confirmEncryptionPasswordInput,
                    onValueChange = {
                        confirmEncryptionPasswordInput = it
                        encryptionPasswordMismatchError = false
                    },
                    label = { Text("确认加密密码", style = MaterialTheme.typography.labelMedium) },
                    textStyle = fieldTextStyle,
                    shape = fieldShape,
                    singleLine = true,
                    isError = showMismatchError,
                    visualTransformation = if (confirmEncryptionPasswordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(
                            onClick = { confirmEncryptionPasswordVisible = !confirmEncryptionPasswordVisible },
                            modifier = Modifier.defaultMinSize(minWidth = 1.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text(
                                text = if (confirmEncryptionPasswordVisible) "隐藏" else "显示",
                                style = MaterialTheme.typography.labelMedium,
                                color = contentColor
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                if (showMismatchError) {
                    Text(
                        text = "两次输入的加密密码不一致",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            HorizontalDivider(color = hairlineBorder)

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val canEnableWifiSync = encryptionPasswordInput.isNotEmpty()
                val effectiveWifiSyncChecked = wifiAutoSyncInput && canEnableWifiSync
                Text(
                    text = "局域网自动同步",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 34.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = canEnableWifiSync) {
                            wifiAutoSyncInput = !effectiveWifiSyncChecked
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = effectiveWifiSyncChecked,
                        onCheckedChange = { checked -> wifiAutoSyncInput = checked },
                        enabled = canEnableWifiSync,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "同一 Wi-Fi 下同时打开时同步",
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor.copy(alpha = if (canEnableWifiSync) 1f else 0.45f)
                    )
                }
                Text(
                    text = if (canEnableWifiSync) {
                        "复用上方加密密码，两台设备密码一致时自动加密同步笔记与设置"
                    } else {
                        "需先在上方设置加密密码，两台设备密码一致即可自动同步笔记与设置"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp,
                    color = mutedText
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(color = hairlineBorder)
        Spacer(modifier = Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary)
                .clickable {
                    if (isServerUrlUnsafe) {
                        serverUrlInvalidError = true
                        return@clickable
                    }
                    if (isEncryptionPasswordMismatch) {
                        encryptionPasswordMismatchError = true
                        return@clickable
                    }
                    onSave(
                        serverUrlInput,
                        usernameInput,
                        passwordInput,
                        encryptionPasswordInput,
                        wifiAutoSyncInput && encryptionPasswordInput.isNotEmpty()
                    )
                    Toast.makeText(context, "同步设置已保存", Toast.LENGTH_SHORT).show()
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "保存同步设置",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

@Composable
private fun BackupRestoreTabContent(
    backupFiles: List<BackupManager.BackupFile>,
    onRefresh: () -> Unit,
    onSelectBackup: (BackupManager.BackupFile) -> Unit
) {
    val contentColor = MaterialTheme.colorScheme.onSurface
    val mutedText = MaterialTheme.colorScheme.onSurfaceVariant
    val hairlineBorder = MaterialTheme.colorScheme.outlineVariant

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "点击任意备份文件可覆盖恢复笔记",
                fontSize = 12.sp,
                color = mutedText,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, hairlineBorder, RoundedCornerShape(8.dp))
                    .clickable(onClick = onRefresh)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "刷新",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = contentColor.copy(alpha = 0.78f)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (backupFiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "没有找到飞记备份文件，或正在读取",
                    style = MaterialTheme.typography.bodyMedium,
                    color = mutedText
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 4.dp)
            ) {
                items(backupFiles, key = { it.uri.toString() }) { file ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, hairlineBorder, RoundedCornerShape(10.dp))
                            .clickable { onSelectBackup(file) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = file.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = contentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "恢复",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = mutedText
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryCurveTabContent(
    initialConfig: MemoryCurveConfig,
    onSave: (MemoryCurveConfig) -> Unit
) {
    val context = LocalContext.current
    var unfamiliarText by remember(initialConfig) { mutableStateOf(initialConfig.unfamiliarDays.toString()) }
    var initialSomewhatText by remember(initialConfig) { mutableStateOf(initialConfig.initialSomewhatDays.toString()) }
    var initialVeryText by remember(initialConfig) { mutableStateOf(initialConfig.initialVeryDays.toString()) }
    var easeFactorText by remember(initialConfig) { mutableStateOf(formatCurveDecimal(initialConfig.initialEaseFactor, 2)) }
    var somewhatMultText by remember(initialConfig) { mutableStateOf(formatCurveDecimal(initialConfig.somewhatMultiplier, 2)) }
    var maxIntervalText by remember(initialConfig) { mutableStateOf(initialConfig.maxIntervalDays.toString()) }

    val draftConfig = remember(
        unfamiliarText,
        initialSomewhatText,
        initialVeryText,
        easeFactorText,
        somewhatMultText,
        maxIntervalText
    ) {
        MemoryCurveConfig(
            unfamiliarDays = unfamiliarText.toIntOrNull() ?: initialConfig.unfamiliarDays,
            initialSomewhatDays = initialSomewhatText.toIntOrNull() ?: initialConfig.initialSomewhatDays,
            initialVeryDays = initialVeryText.toIntOrNull() ?: initialConfig.initialVeryDays,
            initialEaseFactor = easeFactorText.toFloatOrNull() ?: initialConfig.initialEaseFactor,
            somewhatMultiplier = somewhatMultText.toFloatOrNull() ?: initialConfig.somewhatMultiplier,
            maxIntervalDays = maxIntervalText.toIntOrNull() ?: initialConfig.maxIntervalDays
        ).normalized()
    }

    val veryPreview = remember(draftConfig) {
        SpacedRepetitionEngine.simulateIntervals(draftConfig, ReviewFamiliarity.VERY_FAMILIAR, 4)
            .joinToString(" → ") { "${it}天" }
    }
    val somewhatPreview = remember(draftConfig) {
        SpacedRepetitionEngine.simulateIntervals(draftConfig, ReviewFamiliarity.SOMEWHAT_FAMILIAR, 4)
            .joinToString(" → ") { "${it}天" }
    }

    val contentColor = MaterialTheme.colorScheme.onSurface
    val mutedText = MaterialTheme.colorScheme.onSurfaceVariant
    val hairlineBorder = MaterialTheme.colorScheme.outlineVariant

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "自定义五角星知识卡片的复习间隔递增规则",
                fontSize = 12.sp,
                color = mutedText
            )

            CurveIntParameterRow(
                label = "陌生重学间隔",
                unit = "天",
                text = unfamiliarText,
                min = 1,
                max = 30,
                step = 1,
                fallback = draftConfig.unfamiliarDays,
                onTextChange = { unfamiliarText = it }
            )
            CurveIntParameterRow(
                label = "首次大概了解",
                unit = "天",
                text = initialSomewhatText,
                min = 1,
                max = 60,
                step = 1,
                fallback = draftConfig.initialSomewhatDays,
                onTextChange = { initialSomewhatText = it }
            )
            CurveIntParameterRow(
                label = "首次很熟悉",
                unit = "天",
                text = initialVeryText,
                min = 1,
                max = 90,
                step = 1,
                fallback = draftConfig.initialVeryDays,
                onTextChange = { initialVeryText = it }
            )

            HorizontalDivider(color = hairlineBorder.copy(alpha = 0.7f))

            CurveFloatParameterRow(
                label = "初始熟练倍率",
                unit = "×",
                text = easeFactorText,
                min = SpacedRepetitionEngine.MIN_EASE_FACTOR,
                max = SpacedRepetitionEngine.MAX_EASE_FACTOR,
                step = 0.1f,
                fallback = draftConfig.initialEaseFactor,
                onTextChange = { easeFactorText = it }
            )
            CurveFloatParameterRow(
                label = "大概了解系数",
                unit = "×",
                text = somewhatMultText,
                min = 0.30f,
                max = 1.00f,
                step = 0.05f,
                fallback = draftConfig.somewhatMultiplier,
                onTextChange = { somewhatMultText = it }
            )
            CurveIntParameterRow(
                label = "最大间隔上限",
                unit = "天",
                text = maxIntervalText,
                min = SpacedRepetitionEngine.MIN_MAX_INTERVAL_DAYS,
                max = SpacedRepetitionEngine.HARD_MAX_INTERVAL_DAYS,
                step = 30,
                fallback = draftConfig.maxIntervalDays,
                onTextChange = { maxIntervalText = it }
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, hairlineBorder, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "新卡节奏预览",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = mutedText
                )
                Text(
                    text = "很熟悉：$veryPreview",
                    fontSize = 12.sp,
                    color = contentColor.copy(alpha = 0.88f)
                )
                Text(
                    text = "大概了解：$somewhatPreview",
                    fontSize = 12.sp,
                    color = mutedText
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(color = hairlineBorder)
        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .height(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, hairlineBorder, RoundedCornerShape(10.dp))
                    .clickable {
                        val defaults = MemoryCurveConfig()
                        unfamiliarText = defaults.unfamiliarDays.toString()
                        initialSomewhatText = defaults.initialSomewhatDays.toString()
                        initialVeryText = defaults.initialVeryDays.toString()
                        easeFactorText = formatCurveDecimal(defaults.initialEaseFactor, 2)
                        somewhatMultText = formatCurveDecimal(defaults.somewhatMultiplier, 2)
                        maxIntervalText = defaults.maxIntervalDays.toString()
                    }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "恢复默认",
                    style = MaterialTheme.typography.bodyMedium,
                    color = mutedText
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable {
                        onSave(draftConfig)
                        Toast.makeText(context, "记忆曲线参数已保存", Toast.LENGTH_SHORT).show()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "保存参数",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

@Composable
private fun CurveIntParameterRow(
    label: String,
    unit: String,
    text: String,
    min: Int,
    max: Int,
    step: Int,
    fallback: Int,
    onTextChange: (String) -> Unit
) {
    CurveParameterStepperRow(
        label = label,
        unit = unit,
        text = text,
        keyboardType = KeyboardType.Number,
        onTextChange = { raw ->
            onTextChange(raw.filter { it.isDigit() }.take(4))
        },
        onDecrement = {
            val current = text.toIntOrNull() ?: fallback
            onTextChange((current - step).coerceIn(min, max).toString())
        },
        onIncrement = {
            val current = text.toIntOrNull() ?: fallback
            onTextChange((current + step).coerceIn(min, max).toString())
        }
    )
}

@Composable
private fun CurveFloatParameterRow(
    label: String,
    unit: String,
    text: String,
    min: Float,
    max: Float,
    step: Float,
    fallback: Float,
    onTextChange: (String) -> Unit
) {
    CurveParameterStepperRow(
        label = label,
        unit = unit,
        text = text,
        keyboardType = KeyboardType.Decimal,
        onTextChange = { raw ->
            var dotSeen = false
            val filtered = buildString {
                for (ch in raw) {
                    if (ch.isDigit()) {
                        append(ch)
                    } else if (ch == '.' && !dotSeen) {
                        dotSeen = true
                        append(ch)
                    }
                }
            }.take(5)
            onTextChange(filtered)
        },
        onDecrement = {
            val current = text.toFloatOrNull() ?: fallback
            val next = (((current - step) * 100f).roundToInt() / 100f).coerceIn(min, max)
            onTextChange(formatCurveDecimal(next, 2))
        },
        onIncrement = {
            val current = text.toFloatOrNull() ?: fallback
            val next = (((current + step) * 100f).roundToInt() / 100f).coerceIn(min, max)
            onTextChange(formatCurveDecimal(next, 2))
        }
    )
}

@Composable
private fun CurveParameterStepperRow(
    label: String,
    unit: String,
    text: String,
    keyboardType: KeyboardType,
    onTextChange: (String) -> Unit,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit
) {
    val contentColor = MaterialTheme.colorScheme.onSurface
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val borderColor = MaterialTheme.colorScheme.outlineVariant

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            modifier = Modifier.weight(1f)
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .border(1.dp, borderColor, RoundedCornerShape(7.dp))
                    .clickable(onClick = onDecrement),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "−", fontSize = 15.sp, color = contentColor)
            }

            Box(
                modifier = Modifier
                    .width(56.dp)
                    .height(28.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, borderColor, RoundedCornerShape(7.dp))
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                var stepperFieldValue by remember {
                    mutableStateOf(TextFieldValue(text, TextRange(text.length)))
                }
                if (stepperFieldValue.text != text) {
                    stepperFieldValue = TextFieldValue(text, TextRange(text.length))
                }
                val stepperSmartSelection = remember { SmartSelectionController() }
                BasicTextField(
                    value = stepperFieldValue,
                    onValueChange = { incoming ->
                        val adjusted = stepperSmartSelection.adjustIncomingValue(
                            previous = stepperFieldValue,
                            incoming = incoming
                        )
                        val textChanged = adjusted.text != stepperFieldValue.text
                        stepperFieldValue = adjusted
                        if (textChanged) {
                            onTextChange(adjusted.text)
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = contentColor,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Medium
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    onTextLayout = { layout ->
                        stepperSmartSelection.layout = layout
                    },
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .smartSelectionObserver(
                                    key = label,
                                    controller = stepperSmartSelection,
                                    currentValue = stepperFieldValue,
                                    onSelectionChange = { newSelection ->
                                        stepperFieldValue = stepperFieldValue.copy(
                                            selection = newSelection
                                        )
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            innerTextField()
                        }
                    }
                )
            }

            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .border(1.dp, borderColor, RoundedCornerShape(7.dp))
                    .clickable(onClick = onIncrement),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "+", fontSize = 15.sp, color = contentColor)
            }

            Text(
                text = unit,
                fontSize = 12.sp,
                color = mutedColor,
                modifier = Modifier.width(16.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun formatCurveDecimal(value: Float, digits: Int): String =
    String.format(Locale.US, "%.${digits}f", value).trimEnd('0').trimEnd('.')

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DictionarySettingsTabContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val contentColor = MaterialTheme.colorScheme.onSurface
    val mutedText = MaterialTheme.colorScheme.onSurfaceVariant
    val hairlineBorder = MaterialTheme.colorScheme.outlineVariant
    val cardBg = MaterialTheme.colorScheme.surface
    val buttonBg = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f)
    val dragHighlightBg = MaterialTheme.colorScheme.surfaceContainerHigh

    LaunchedEffect(Unit) {
        MdxDictionaryManager.ensureLoaded(context)
    }

    val dictionaries by MdxDictionaryManager.dictionaries.collectAsState()
    val preferredDictId by MdxDictionaryManager.preferredDictionaryId.collectAsState()
    val defaultCssName by MdxDictionaryManager.defaultCssName.collectAsState()
    var isImporting by remember { mutableStateOf(false) }
    var pendingCssTargetDictId by remember { mutableStateOf<String?>(null) }

    val orderedDicts = remember { mutableStateListOf<MdxDictionaryManager.DictionaryEntry>() }
    var draggingDictId by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(dictionaries, draggingDictId) {
        if (draggingDictId == null) {
            orderedDicts.clear()
            orderedDicts.addAll(dictionaries)
        }
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
                }.onFailure { err ->
                    Toast.makeText(
                        context,
                        err.message ?: "导入词库失败",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    val cssPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val targetId = pendingCssTargetDictId
        pendingCssTargetDictId = null
        if (uri != null) {
            isImporting = true
            scope.launch {
                val res = MdxDictionaryManager.importCss(
                    context = context,
                    uri = uri,
                    targetDictionaryId = targetId
                )
                isImporting = false
                res.onSuccess { msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
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

    val rowHeightDp = 54.dp
    val rowStepPx = with(density) { (rowHeightDp + 0.5.dp).toPx() }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 顶部栏：左侧「词典」标题 + 右侧「↑ 导入词库」按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "词典",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = contentColor,
                modifier = Modifier.weight(1f)
            )

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(buttonBg)
                    .border(1.dp, hairlineBorder, RoundedCornerShape(10.dp))
                    .clickable(enabled = !isImporting) {
                        libraryPicker.launch(arrayOf("*/*"))
                    }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DictUploadTrayIcon(color = contentColor)
                Text(
                    text = if (isImporting) "导入中…" else "导入词库",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = contentColor,
                    maxLines = 1
                )
            }
        }

        if (orderedDicts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(cardBg)
                    .border(1.dp, hairlineBorder, RoundedCornerShape(14.dp))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "暂无词典",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = contentColor
                    )
                    Text(
                        text = "点击右上角「导入词库」选择 .mdx 词典文件，导入后可为每本词典单独配置 .css 样式。",
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedText,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 统一圆角分组卡片：每本词典单行展示，支持拖拽排序、点选优先词典、单独配/换样式、删除
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(cardBg)
                        .border(1.dp, hairlineBorder, RoundedCornerShape(14.dp))
                ) {
                    orderedDicts.forEachIndexed { index, dict ->
                        key(dict.id) {
                            if (index > 0) {
                                HorizontalDivider(
                                    thickness = 0.5.dp,
                                    color = hairlineBorder.copy(alpha = 0.65f)
                                )
                            }

                            val isDragging = draggingDictId == dict.id
                            val isPreferred = dict.id == preferredDictId
                            val hasCss = dict.cssFileName != null

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(rowHeightDp)
                                    .zIndex(if (isDragging) 10f else 0f)
                                    .graphicsLayer {
                                        translationY = if (isDragging) dragOffsetY else 0f
                                    }
                                    .background(if (isDragging) dragHighlightBg else Color.Transparent)
                                    .padding(start = 6.dp, end = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 左侧 6 点拖拽手柄
                                Box(
                                    modifier = Modifier
                                        .width(26.dp)
                                        .fillMaxHeight()
                                        .pointerInput(dict.id) {
                                            detectDragGestures(
                                                onDragStart = {
                                                    draggingDictId = dict.id
                                                    dragOffsetY = 0f
                                                    haptic.performHapticFeedback(
                                                        HapticFeedbackType.LongPress
                                                    )
                                                },
                                                onDragEnd = {
                                                    val finalIds = orderedDicts.map { it.id }
                                                    draggingDictId = null
                                                    dragOffsetY = 0f
                                                    MdxDictionaryManager.reorderDictionaries(
                                                        context,
                                                        finalIds
                                                    )
                                                },
                                                onDragCancel = {
                                                    val finalIds = orderedDicts.map { it.id }
                                                    draggingDictId = null
                                                    dragOffsetY = 0f
                                                    MdxDictionaryManager.reorderDictionaries(
                                                        context,
                                                        finalIds
                                                    )
                                                },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    dragOffsetY += dragAmount.y
                                                    var curIdx = orderedDicts.indexOfFirst {
                                                        it.id == dict.id
                                                    }
                                                    if (curIdx < 0) return@detectDragGestures

                                                    while (
                                                        dragOffsetY > rowStepPx * 0.52f &&
                                                        curIdx < orderedDicts.lastIndex
                                                    ) {
                                                        val item = orderedDicts.removeAt(curIdx)
                                                        orderedDicts.add(curIdx + 1, item)
                                                        curIdx++
                                                        dragOffsetY -= rowStepPx
                                                        haptic.performHapticFeedback(
                                                            HapticFeedbackType.TextHandleMove
                                                        )
                                                    }
                                                    while (
                                                        dragOffsetY < -rowStepPx * 0.52f &&
                                                        curIdx > 0
                                                    ) {
                                                        val item = orderedDicts.removeAt(curIdx)
                                                        orderedDicts.add(curIdx - 1, item)
                                                        curIdx--
                                                        dragOffsetY += rowStepPx
                                                        haptic.performHapticFeedback(
                                                            HapticFeedbackType.TextHandleMove
                                                        )
                                                    }

                                                    val minY = if (curIdx == 0) 0f else -rowStepPx
                                                    val maxY = if (curIdx == orderedDicts.lastIndex) {
                                                        0f
                                                    } else {
                                                        rowStepPx
                                                    }
                                                    dragOffsetY = dragOffsetY.coerceIn(minY, maxY)
                                                }
                                            )
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    DictDragDotsIcon(color = mutedText.copy(alpha = 0.65f))
                                }

                                // 词典图标（[A] / ✓）+ 词典名称，点击切换优先词典
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            MdxDictionaryManager.togglePreferredDictionary(
                                                context,
                                                dict.id
                                            )
                                        }
                                        .padding(horizontal = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier.size(20.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isPreferred) {
                                            DictCheckmarkIcon(color = contentColor)
                                        } else {
                                            DictBookAIcon(color = mutedText.copy(alpha = 0.85f))
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = dict.title,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Normal,
                                        color = contentColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                // 单本词典样式按钮（</> 样式 / </> 换样式；长按已绑定样式可清除）
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .combinedClickable(
                                            enabled = !isImporting,
                                            onClick = {
                                                pendingCssTargetDictId = dict.id
                                                cssPicker.launch(arrayOf("*/*"))
                                            },
                                            onLongClick = {
                                                if (hasCss) {
                                                    MdxDictionaryManager.clearDictionaryCss(
                                                        context,
                                                        dict.id
                                                    )
                                                    Toast.makeText(
                                                        context,
                                                        "已清除「${dict.title}」的自定义样式",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                        )
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    DictCodeStyleIcon(color = mutedText.copy(alpha = 0.85f))
                                    Text(
                                        text = if (hasCss) "换样式" else "样式",
                                        fontSize = 13.sp,
                                        color = mutedText,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }

                                // 删除按钮
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            MdxDictionaryManager.deleteDictionary(context, dict.id)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    DictTrashIcon(color = mutedText.copy(alpha = 0.85f))
                                }
                            }
                        }
                    }
                }

                if (defaultCssName != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(cardBg)
                            .border(1.dp, hairlineBorder, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "通用样式：$defaultCssName",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "清除",
                            style = MaterialTheme.typography.labelMedium,
                            color = mutedText,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { MdxDictionaryManager.clearDefaultCss(context) }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DictUploadTrayIcon(color: Color) {
    Canvas(modifier = Modifier.size(15.dp)) {
        val sw = 1.4.dp.toPx()
        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val w = size.width
        val h = size.height
        // 向上箭头竖杆
        drawLine(
            color = color,
            start = Offset(w * 0.5f, h * 0.68f),
            end = Offset(w * 0.5f, h * 0.18f),
            strokeWidth = sw,
            cap = StrokeCap.Round
        )
        // 箭头双翼
        val arrowPath = Path().apply {
            moveTo(w * 0.28f, h * 0.38f)
            lineTo(w * 0.5f, h * 0.16f)
            lineTo(w * 0.72f, h * 0.38f)
        }
        drawPath(arrowPath, color = color, style = stroke)
        // 底部托盘
        val trayPath = Path().apply {
            moveTo(w * 0.16f, h * 0.66f)
            lineTo(w * 0.16f, h * 0.84f)
            lineTo(w * 0.84f, h * 0.84f)
            lineTo(w * 0.84f, h * 0.66f)
        }
        drawPath(trayPath, color = color, style = stroke)
    }
}

@Composable
private fun DictDragDotsIcon(color: Color) {
    Canvas(modifier = Modifier.size(width = 10.dp, height = 16.dp)) {
        val r = 1.15.dp.toPx()
        val cols = listOf(size.width * 0.28f, size.width * 0.72f)
        val rows = listOf(size.height * 0.2f, size.height * 0.5f, size.height * 0.8f)
        for (cy in rows) {
            for (cx in cols) {
                drawCircle(color = color, radius = r, center = Offset(cx, cy))
            }
        }
    }
}

@Composable
private fun DictBookAIcon(color: Color) {
    Canvas(modifier = Modifier.size(17.dp)) {
        val sw = 1.3.dp.toPx()
        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val w = size.width
        val h = size.height
        // 外框（竖版词典封面轮廓）
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.16f, h * 0.08f),
            size = Size(w * 0.68f, h * 0.84f),
            cornerRadius = CornerRadius(2.2.dp.toPx(), 2.2.dp.toPx()),
            style = stroke
        )
        // 字母 A
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
        // 底部书脊横线
        drawLine(
            color = color,
            start = Offset(w * 0.28f, h * 0.78f),
            end = Offset(w * 0.72f, h * 0.78f),
            strokeWidth = sw * 0.85f,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun DictCheckmarkIcon(color: Color) {
    Canvas(modifier = Modifier.size(17.dp)) {
        val sw = 1.7.dp.toPx()
        val path = Path().apply {
            moveTo(size.width * 0.18f, size.height * 0.52f)
            lineTo(size.width * 0.42f, size.height * 0.76f)
            lineTo(size.width * 0.84f, size.height * 0.26f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
private fun DictCodeStyleIcon(color: Color) {
    Canvas(modifier = Modifier.size(15.dp)) {
        val sw = 1.25.dp.toPx()
        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val w = size.width
        val h = size.height
        // 折角文档轮廓
        val docPath = Path().apply {
            moveTo(w * 0.18f, h * 0.10f)
            lineTo(w * 0.62f, h * 0.10f)
            lineTo(w * 0.84f, h * 0.32f)
            lineTo(w * 0.84f, h * 0.90f)
            lineTo(w * 0.18f, h * 0.90f)
            close()
        }
        drawPath(docPath, color = color, style = stroke)
        // 右上折角线
        val foldPath = Path().apply {
            moveTo(w * 0.60f, h * 0.10f)
            lineTo(w * 0.60f, h * 0.32f)
            lineTo(w * 0.84f, h * 0.32f)
        }
        drawPath(foldPath, color = color, style = stroke)
        // <> 代码括号
        val leftChevron = Path().apply {
            moveTo(w * 0.44f, h * 0.50f)
            lineTo(w * 0.31f, h * 0.62f)
            lineTo(w * 0.44f, h * 0.74f)
        }
        val rightChevron = Path().apply {
            moveTo(w * 0.58f, h * 0.50f)
            lineTo(w * 0.71f, h * 0.62f)
            lineTo(w * 0.58f, h * 0.74f)
        }
        drawPath(leftChevron, color = color, style = stroke)
        drawPath(rightChevron, color = color, style = stroke)
    }
}

@Composable
private fun DictTrashIcon(color: Color) {
    Canvas(modifier = Modifier.size(16.dp)) {
        val sw = 1.3.dp.toPx()
        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val w = size.width
        val h = size.height
        // 桶盖顶柄
        val handlePath = Path().apply {
            moveTo(w * 0.38f, h * 0.24f)
            lineTo(w * 0.38f, h * 0.14f)
            lineTo(w * 0.62f, h * 0.14f)
            lineTo(w * 0.62f, h * 0.24f)
        }
        drawPath(handlePath, color = color, style = stroke)
        // 桶盖横线
        drawLine(
            color = color,
            start = Offset(w * 0.16f, h * 0.24f),
            end = Offset(w * 0.84f, h * 0.24f),
            strokeWidth = sw,
            cap = StrokeCap.Round
        )
        // 桶身
        val bodyPath = Path().apply {
            moveTo(w * 0.25f, h * 0.26f)
            lineTo(w * 0.29f, h * 0.86f)
            lineTo(w * 0.71f, h * 0.86f)
            lineTo(w * 0.75f, h * 0.26f)
        }
        drawPath(bodyPath, color = color, style = stroke)
        // 桶内两条竖纹
        drawLine(
            color = color,
            start = Offset(w * 0.42f, h * 0.42f),
            end = Offset(w * 0.43f, h * 0.72f),
            strokeWidth = sw * 0.9f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(w * 0.58f, h * 0.42f),
            end = Offset(w * 0.57f, h * 0.72f),
            strokeWidth = sw * 0.9f,
            cap = StrokeCap.Round
        )
    }
}


