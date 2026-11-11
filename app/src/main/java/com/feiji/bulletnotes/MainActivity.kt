package com.feiji.bulletnotes

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
        setContent {
            FeiJiTheme {
                BackupSetupPrompt(viewModel)
            }
        }
    }
}

@Composable
private fun BackupSetupPrompt(viewModel: NoteViewModel) {
    val needsSetup by viewModel.needsBackupSetup.collectAsState()
    val openExisting = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                viewModel.onBackupDocumentSelected(uri, result.data?.flags ?: 0)
            }
        }
    }
    val createNew = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                viewModel.onBackupDocumentSelected(uri, result.data?.flags ?: 0)
            }
        }
    }

    BulletNotesScreen(viewModel = viewModel)

    if (needsSetup) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissBackupSetup() },
            title = { Text("设置每日自动备份") },
            text = { Text("为避免 Download 文件夹出现（1）、（2）等重复备份，请先选择已有的‘飞记每日自动备份.json’，授予飞记覆盖权限。若没有该文件，可新建一个。之后每天首次打开时自动覆盖，不再新建文件。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissBackupSetup()
                    openExisting.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    })
                }) { Text("选择已有文件") }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.dismissBackupSetup()
                    createNew.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/json"
                        putExtra(Intent.EXTRA_TITLE, "飞记每日自动备份.json")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    })
                }) { Text("新建文件") }
            }
        )
    }
}
