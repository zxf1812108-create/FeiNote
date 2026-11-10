package com.feiji.bulletnotes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.feiji.bulletnotes.ui.BulletNotesScreen
import com.feiji.bulletnotes.ui.NoteViewModel
import com.feiji.bulletnotes.ui.theme.FeiJiTheme

class MainActivity : ComponentActivity() {

    private val viewModel: NoteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            FeiJiTheme {
                BulletNotesScreen(viewModel = viewModel)
            }
        }
    }

}
