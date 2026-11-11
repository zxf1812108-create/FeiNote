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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.feiji.bulletnotes.ui.BulletNotesScreen
import com.feiji.bulletnotes.ui.NoteViewModel
import com.feiji.bulletnotes.ui.theme.FeiJiTheme

class MainActivity : ComponentActivity() {
    private val viewModel: NoteViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FeiJiTheme { BackupSetupPrompt(viewModel) } }
    }
}

@Composable
private fun BackupSetupPrompt(viewModel: NoteViewModel) {
    val needsSetup by viewModel.needsBackupSetup.collectAsState()
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                viewModel.onBackupFolderSelected(uri, result.data?.flags ?: 0)
            }
        }
    }
    BulletNotesScreen(viewModel = viewModel)
    if (needsSetup) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissBackupSetup() },
            title = { Text("设置自动备份文件夹") },
            text = { Text("请选择一个文件夹并授权飞记读写。笔记变化后每分钟检查并备份；同一天覆盖同名文件，每天首次打开时清理三天以前的飞记备份。") },
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
}
