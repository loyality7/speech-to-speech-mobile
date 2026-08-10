package com.s2s.demo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.s2s.demo.ui.S2SScreen
import com.s2s.demo.ui.theme.S2SMobileTheme
import com.s2s.demo.viewmodel.S2SViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: S2SViewModel by viewModels()

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.toggleSession()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge immersive UI
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            S2SMobileTheme {
                val uiState by viewModel.uiState.collectAsState()

                S2SScreen(
                    uiState = uiState,
                    onToggleSession = {
                        if (hasMicPermission()) {
                            viewModel.toggleSession()
                        } else {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onBargeIn = { viewModel.onBargeIn() },
                    onShowModels = { viewModel.showModelSheet() },
                    onHideModels = { viewModel.hideModelSheet() },
                    onDownloadModel = { viewModel.downloadModel(it) },
                    onSendText = { viewModel.sendTextMessage(it) }
                )
            }
        }
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
}
