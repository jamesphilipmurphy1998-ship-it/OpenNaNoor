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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.opennanoor.MainActivity
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
                var editing by remember { mutableStateOf(false) }

                // Settings live on a different screen (MainActivity), so a
                // change made there - dock icon count, columns, icon pack,
                // iOS style - only reaches this already-running home screen
                // by re-checking on resume, the same way MainActivity
                // re-reads its own permission state on resume.
                OnResume { vm.refreshSettingsIfChanged() }

                // Pressing home while the drawer is open closes it, as it would
                // on any stock launcher.
                // Home closes the drawer and ends arranging, as it would on a
                // stock launcher.
                remember(homePressed) {
                    drawerOpen = false
                    editing = false
                    homePressed
                }

                BackHandler(enabled = drawerOpen || editing || state.openFolderId != null) {
                    when {
                        state.openFolderId != null -> vm.closeFolder()
                        drawerOpen -> drawerOpen = false
                        else -> editing = false
                    }
                }

                LauncherScreen(
                    state = state,
                    // A settings change (dock icon count) made on the other
                    // screen only reaches this ViewModel's own `state` on
                    // the next resume (see refreshSettingsIfChanged above) -
                    // fine for rendering, which just waits for that next
                    // recomposition, but a capacity decision made right at
                    // drop time needs the true current value THIS SAME
                    // instant. Reading straight from SharedPreferences here
                    // is a cheap, synchronous, side-effect-free way to get
                    // that - calling vm.refreshSettingsIfChanged() instead
                    // (an earlier version of this) ran a full state refresh
                    // on every drag end, not just ones headed for the dock,
                    // and raced with the move a plain page-to-page or
                    // dock-to-page drag was already making: the refresh's
                    // reload of the last SAVED layout could land after
                    // onMove and silently undo it, which looked like the
                    // dragged icon snapping back to where it started.
                    currentDockIconCount = { com.example.opennanoor.core.Settings(this).dockIconCount },
                    onLaunchApp = { AppRepository.launch(this, it.component) },
                    onOpenSettings = { openSettings() },
                    drawerOpen = drawerOpen,
                    onDrawerOpenChange = { drawerOpen = it },
                    editing = editing,
                    onEditingChange = { editing = it },
                    onMove = vm::moveItem,
                    onPlaceFromDrawer = vm::placeFromDrawer,
                    onRemove = vm::removeFromHome,
                    onOpenFolder = vm::openFolder,
                    onCloseFolder = vm::closeFolder,
                    onRemoveFromFolder = vm::removeFromFolder,
                    onRenameFolder = vm::renameFolder,
                    onUninstall = vm::uninstallApp,
                    onSpillAnimationDone = vm::clearSpillEvent,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    /** Settings must start in its own task, since ours is the home task. */
    private fun openSettings() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        homePressed++
    }
}

/** Runs [block] each time the host lifecycle reaches RESUMED. */
@Composable
private fun OnResume(block: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) block()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
