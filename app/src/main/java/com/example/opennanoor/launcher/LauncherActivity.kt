package com.example.opennanoor.launcher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.opennanoor.ui.theme.OpenNaNoorTheme

/**
 * The home screen. Declared with CATEGORY_HOME so Android offers it as a
 * launcher choice - it only takes over once the user picks it as default.
 */
class LauncherActivity : ComponentActivity() {

    /** Set when the system delivers a HOME press while we are already showing. */
    private var homePressed by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            OpenNaNoorTheme {
                val vm: LauncherViewModel = viewModel()
                val state by vm.state.collectAsState()
                var drawerOpen by remember { mutableStateOf(false) }

                // Pressing home while the drawer is open closes it, as it would
                // on any stock launcher.
                remember(homePressed) {
                    drawerOpen = false
                    homePressed
                }

                BackHandler(enabled = drawerOpen) { drawerOpen = false }

                LauncherScreen(
                    state = state,
                    onLaunch = { AppRepository.launch(this, it.component) },
                    onSelectPack = vm::selectIconPack,
                    onToggleIosStyle = vm::setIosStyle,
                    drawerOpen = drawerOpen,
                    onDrawerOpenChange = { drawerOpen = it },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        homePressed++
    }
}
