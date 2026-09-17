package com.example.opennanoor.launcher

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import com.example.opennanoor.widget.ClockWidgetProvider
import com.example.opennanoor.widget.ClockWidgetStore

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

                val widgetHost = rememberWidgetHostController(this)

                // Which widget "Background image" was tapped for - read
                // once the image picker returns, since that activity
                // result callback is fixed at first composition and can't
                // capture a value chosen later the normal way.
                var pendingBackgroundImageTarget by remember { mutableStateOf<Int?>(null) }
                val currentBackgroundImageTarget by rememberUpdatedState(pendingBackgroundImageTarget)
                val pickImageLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.GetContent()
                ) { uri ->
                    val targetId = currentBackgroundImageTarget
                    pendingBackgroundImageTarget = null
                    if (uri != null && targetId != null) {
                        val original = runCatching {
                            contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
                        }.getOrNull()
                        if (original != null) {
                            ClockWidgetStore.setBackgroundImage(this, targetId, downscale(original, maxDimension = 480))
                            ClockWidgetStore.setBackgroundTransparentFor(this, targetId, false)
                            ClockWidgetProvider.refresh(this, targetId)
                        }
                    }
                }

                LauncherScreen(
                    state = state,
                    onLaunchApp = {
                        vm.recordLaunch(it.component)
                        AppRepository.launch(this, it.component)
                    },
                    onOpenSettings = { openSettings() },
                    widgets = widgetHost.placedWidgets,
                    onAddWidget = { page -> widgetHost.startPick(page) },
                    onRemoveWidget = { id -> widgetHost.removeWidget(id); vm.refreshSettingsIfChanged() },
                    onWidgetMoved = { id, page, row ->
                        widgetHost.setWidgetPlacement(id, page, row)
                        // Moving between pages changes how many rows each of
                        // those two pages reserves, so the icon grid has to
                        // reflow for the new capacities - see the ViewModel's
                        // own widgetCountByPage.
                        vm.refreshSettingsIfChanged()
                    },
                    onWidgetsSwapped = { firstId, secondId ->
                        widgetHost.swapWidgets(firstId, secondId)
                        // Same-page swap never changes either page's own
                        // reserved row count, but the icon grid still reads
                        // topRow-derived bands to lay itself out around them.
                        vm.refreshSettingsIfChanged()
                    },
                    onWidgetResized = { id, rowSpan, columnSpan, widthDp, heightDp ->
                        widgetHost.resizeWidget(id, rowSpan, columnSpan, widthDp, heightDp)
                        // Growing/shrinking a widget changes how many rows
                        // its own page reserves, same reflow reasoning as
                        // onWidgetMoved above.
                        vm.refreshSettingsIfChanged()
                    },
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
                    onRenameApp = vm::renameApp,
                    onSpillAnimationDone = vm::clearSpillEvent,
                    onPickWidgetTextColor = { id, color ->
                        ClockWidgetStore.setTextColorFor(this, id, color)
                        ClockWidgetProvider.refresh(this, id)
                    },
                    onPickWidgetBackgroundImage = { id ->
                        pendingBackgroundImageTarget = id
                        pickImageLauncher.launch("image/*")
                    },
                    onClearWidgetBackgroundImage = { id ->
                        // "No background" - genuinely transparent, not a
                        // fallback to the default solid scrim (see
                        // ClockWidgetStore's own backgroundTransparentFor).
                        ClockWidgetStore.clearBackgroundImage(this, id)
                        ClockWidgetStore.setBackgroundTransparentFor(this, id, true)
                        ClockWidgetProvider.refresh(this, id)
                    },
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

/**
 * Scales [bitmap] down so its longer side is at most [maxDimension] -
 * a picked photo can be several thousand pixels wide, and
 * ClockWidgetProvider redecodes whatever's saved on every tick (see its own
 * scheduleTick), so saving it at full resolution would waste real CPU/memory
 * every minute for no visible gain at widget size. Returns [bitmap]
 * unchanged if it's already smaller than that.
 */
private fun downscale(bitmap: Bitmap, maxDimension: Int): Bitmap {
    val longerSide = maxOf(bitmap.width, bitmap.height)
    if (longerSide <= maxDimension) return bitmap
    val scale = maxDimension.toFloat() / longerSide
    return Bitmap.createScaledBitmap(
        bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true
    )
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
