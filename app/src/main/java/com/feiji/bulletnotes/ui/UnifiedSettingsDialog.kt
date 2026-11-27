package com.feiji.bulletnotes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
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

@Composable
private fun DictionarySettingsTabContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val contentColor = MaterialTheme.colorScheme.onSurface
    val mutedText = MaterialTheme.colorScheme.onSurfaceVariant
    val hairlineBorder = MaterialTheme.colorScheme.outlineVariant

    LaunchedEffect(Unit) {
        MdxDictionaryManager.ensureLoaded(context)
    }

    val dictionaries by MdxDictionaryManager.dictionaries.collectAsState()
    val defaultCssName by MdxDictionaryManager.defaultCssName.collectAsState()
    var isImporting by remember { mutableStateOf(false) }
    var pendingCssTargetDictId by remember { mutableStateOf<String?>(null) }

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

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 顶部导入操作行：导入 MDX 词典 + 导入 CSS 样式
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .border(1.dp, hairlineBorder, RoundedCornerShape(9.dp))
                    .clickable(enabled = !isImporting) {
                        mdxPicker.launch(arrayOf("*/*"))
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isImporting) "导入中…" else "导入 MDX 词典",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = contentColor
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, hairlineBorder, RoundedCornerShape(9.dp))
                    .clickable(enabled = !isImporting) {
                        pendingCssTargetDictId = null
                        cssPicker.launch(arrayOf("*/*"))
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "导入 CSS 样式",
                    fontSize = 13.sp,
                    color = contentColor
                )
            }
        }

        if (defaultCssName != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, hairlineBorder, RoundedCornerShape(9.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "默认样式：$defaultCssName",
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

        if (dictionaries.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, hairlineBorder, RoundedCornerShape(12.dp))
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "暂无本地词典",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = contentColor
                    )
                    Text(
                        text = "导入 .mdx 字典文件（可配同名 .css 样式）后，在编辑时选中文字点击「翻译」即可离线查词。",
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedText,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(dictionaries, key = { it.id }) { dict ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(11.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, hairlineBorder, RoundedCornerShape(11.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = dict.enabled,
                                onCheckedChange = { checked ->
                                    MdxDictionaryManager.setDictionaryEnabled(context, dict.id, checked)
                                },
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = dict.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (dict.enabled) contentColor else mutedText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${dict.entryCount} 词条 · ${formatFileSize(dict.fileSizeBytes)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = mutedText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = dict.cssFileName?.let { "样式：$it" }
                                    ?: defaultCssName?.let { "样式：默认（$it）" }
                                    ?: "样式：内置默认",
                                style = MaterialTheme.typography.labelSmall,
                                color = mutedText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (dict.cssFileName != null) "换CSS" else "配CSS",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = contentColor,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable(enabled = !isImporting) {
                                            pendingCssTargetDictId = dict.id
                                            cssPicker.launch(arrayOf("*/*"))
                                        }
                                        .padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                                if (dict.cssFileName != null) {
                                    Text(
                                        text = "清CSS",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = mutedText,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable {
                                                MdxDictionaryManager.clearDictionaryCss(context, dict.id)
                                            }
                                            .padding(horizontal = 6.dp, vertical = 3.dp)
                                    )
                                }
                                Text(
                                    text = "删除",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable {
                                            MdxDictionaryManager.deleteDictionary(context, dict.id)
                                        }
                                        .padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

