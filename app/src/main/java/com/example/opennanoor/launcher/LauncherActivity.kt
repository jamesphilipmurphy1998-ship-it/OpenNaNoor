package com.example.opennanoor.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.opennanoor.core.Settings
import com.example.opennanoor.ui.theme.OpenNaNoorTheme

/**
 * The home screen. Declared with CATEGORY_HOME so Android offers it as a
 * launcher choice - it only takes over if the user picks it as default.
 */
class LauncherActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val settings = Settings(this)

        setContent {
            OpenNaNoorTheme {
                val vm: LauncherViewModel = viewModel()
                val state by vm.state.collectAsState()

                LauncherScreen(
                    state = state,
                    columns = settings.columns,
                    onLaunch = { AppRepository.launch(this, it.component) },
                    onSelectPack = vm::selectIconPack,
                    onToggleIosStyle = vm::setIosStyle,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xFF101014), Color(0xFF1C1C24))
                            )
                        )
                )
            }
        }
    }

    /** Home is always "already there" - pressing home should not restart it. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
    }
}
