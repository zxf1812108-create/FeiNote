package com.feiji.bulletnotes

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.feiji.bulletnotes.alarm.NoteAlarmScheduler
import com.feiji.bulletnotes.ui.BulletNotesScreen
import com.feiji.bulletnotes.ui.NoteViewModel
import com.feiji.bulletnotes.ui.theme.FeiJiTheme
import com.feiji.bulletnotes.ui.theme.ThemePreferences
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private val viewModel: NoteViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleAlarmOpenIntent(intent)
        setContent {
            // Compose 专用函数必须在 Composable 上下文中先求值，再用于 rememberSaveable 初始化。
            val systemDarkTheme = isSystemInDarkTheme()
            var darkTheme by rememberSaveable {
                mutableStateOf(ThemePreferences.resolveDarkTheme(this@MainActivity, systemDarkTheme))
            }
            FeiJiTheme(darkTheme = darkTheme) {
                BackupSetupPrompt(
                    viewModel = viewModel,
                    onToggleTheme = {
                        darkTheme = !darkTheme
                        ThemePreferences.saveDarkTheme(this@MainActivity, darkTheme)
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAlarmOpenIntent(intent)
    }

    private fun handleAlarmOpenIntent(intent: Intent?) {
        val dateStr = intent?.getStringExtra(NoteAlarmScheduler.EXTRA_OPEN_DATE)
        if (!dateStr.isNullOrBlank()) {
            runCatching { LocalDate.parse(dateStr) }.getOrNull()?.let { targetDate ->
                viewModel.selectDate(targetDate)
            }
            intent.removeExtra(NoteAlarmScheduler.EXTRA_OPEN_DATE)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.triggerSilentWebDavBackup()
        viewModel.onAppForegroundChanged(true)
    }

    override fun onPause() {
        super.onPause()
        viewModel.onAppForegroundChanged(false)
    }
}

@Composable
private fun BackupSetupPrompt(viewModel: NoteViewModel, onToggleTheme: () -> Unit) {
    val needsSetup by viewModel.needsBackupSetup.collectAsState()
    val todayBackupPrompt: com.feiji.bulletnotes.data.BackupManager.BackupFile? by viewModel.todayBackupPrompt.collectAsState()
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                viewModel.onBackupFolderSelected(uri, result.data?.flags ?: 0)
            }
        }
    }
    BulletNotesScreen(viewModel = viewModel, onToggleTheme = onToggleTheme)
    if (needsSetup) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissBackupSetup() },
            title = { Text("设置自动备份文件夹") },
            text = { Text("请选择一个文件夹并授权飞记读写。笔记变化后自动备份；同一天覆盖同名文件，仅保留今天、昨天和前天共三天的飞记备份，更早的将自动清理。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissBackupSetup()
                    folderPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    })
                }) { Text("选择文件夹") }
            },
            dismissButton = { TextButton(onClick = { viewModel.dismissBackupSetup() }) { Text("稍后设置") } }
        )
    }
    if (todayBackupPrompt != null) {
        AlertDialog(
            onDismissRequest = { viewModel.skipTodayBackupRestore() },
            title = { Text("发现今天的备份") },
            text = { Text("备份文件夹中存在今天的备份文件：${todayBackupPrompt?.name}。是否使用这份备份覆盖恢复飞记中的笔记数据？") },
            confirmButton = {
                TextButton(onClick = { viewModel.restoreTodayBackupFromPrompt() }) { Text("恢复备份") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.skipTodayBackupRestore() }) { Text("暂不恢复") }
            }
        )
    }
}
