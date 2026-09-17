/**
 * The iOS-style Control Center panel - a swipe down starting in the top-
 * right corner (distinct from the plain swipe-down-anywhere that opens
 * SearchPanel - see the pager's own pointerInput in LauncherScreen) reveals
 * a handful of quick toggles. Real WiFi/Bluetooth/airplane-mode toggles are
 * off-limits to a normal app on modern Android (that needs a system-level
 * permission no launcher gets), so those open the matching system panel/
 * settings screen instead of flipping silently - still one tap away, just
 * not instant the way flashlight and brightness (which this app CAN drive
 * directly) are.
 */
package com.example.opennanoor.launcher

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp

/** Fraction of the screen width the panel occupies - narrower than
 *  [SearchPanel]'s full width, and anchored to the corner it was pulled
 *  down from rather than centred, matching iOS's own Control Center.
 *  Not private - the swipe zone that opens this panel (see LauncherScreen's
 *  pager pointerInput) is sized off this same constant, so the trigger area
 *  always matches the panel's own footprint instead of being a second,
 *  independently-tuned number that could quietly drift out of sync with it. */
internal const val CONTROL_CENTER_WIDTH_FRACTION = 0.62f

// Fully solid now, no alpha at all - not a frosted/glass material like the
// folder preview or search panel, just a plain opaque card.
private val controlCenterBackground = Color(0xFFF2F2F4)

// The panel background above is now solidly white, not translucent dark
// glass - white text/icons (fine when this used to blend into whatever was
// behind it) had gone invisible against it. Dark content is what actually
// reads against an opaque light panel, matching how iOS's own Control
// Center flips to dark icons on its light material.
private val controlCenterContentColor = Color(0xFF1C1C1E)

@Composable
internal fun ControlCenterPanel(
    visible: Boolean,
    insets: PaddingValues,
    onDismiss: () -> Unit,
    // Reports the panel's own real on-screen footprint (top padding
    // included, since that's part of "the area of the setting when it's
    // visible" too) back up to LauncherScreen, so the swipe zone that
    // opens this panel can be sized off its ACTUAL rendered bounds instead
    // of a second, hand-tuned guess at them.
    onBoundsMeasured: (widthPx: Float, heightPx: Float) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.15f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onDismiss() }
            )
        }

        val layoutDirection = LocalLayoutDirection.current
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                // Attached to the AnimatedVisibility wrapper, not the Column
                // inside it - this is the box that already includes the
                // Column's own end/top padding in its measured size, so the
                // bounds reported here are its true full on-screen footprint,
                // not just the space inside that padding.
                .onGloballyPositioned { onBoundsMeasured(it.size.width.toFloat(), it.size.height.toFloat()) }
        ) {
            val shape = RoundedCornerShape(28.dp)
            Column(
                Modifier
                    .fillMaxWidth(CONTROL_CENTER_WIDTH_FRACTION)
                    .padding(
                        // End, not start - this panel hangs off the right
                        // edge it was pulled down from, same corner in
                        // both LTR and RTL, so it's the layout-relative
                        // "end" inset (calculateEndPadding) that has to
                        // stay clear of the system cutout/gesture area,
                        // not "start".
                        end = insets.calculateEndPadding(layoutDirection) + 8.dp,
                        top = insets.calculateTopPadding() + 12.dp
                    )
                    .clip(shape)
                    .background(controlCenterBackground)
                    .border(1.dp, folderGlassBorderBrush, shape)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                FlashlightToggle()
                BrightnessSlider()
                NowPlayingRow()
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WifiStatusButton(modifier = Modifier.weight(1f))
                    BluetoothStatusButton(modifier = Modifier.weight(1f))
                    AirplaneModeButton(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** Torch control needs a CameraManager reference for the lifetime of the
 *  toggle, and the callback the manager fires if the torch turns off from
 *  outside this app (e.g. the camera app taking over the flash unit) - both
 *  scoped to this composable rather than shared, since only one flashlight
 *  toggle is ever on screen at a time. */
@Composable
private fun FlashlightToggle() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(false) }
    val cameraManager = remember { context.getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    val cameraId = remember {
        cameraManager.cameraIdList.firstOrNull {
            cameraManager.getCameraCharacteristics(it)
                .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }
    DisposableEffect(cameraManager, cameraId) {
        val callback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(camId: String, enabled: Boolean) {
                if (camId == cameraId) on = enabled
            }
        }
        cameraManager.registerTorchCallback(callback, null)
        onDispose { cameraManager.unregisterTorchCallback(callback) }
    }

    ToggleTile(
        icon = Icons.Filled.FlashOn,
        label = "Flashlight",
        on = on,
        enabled = cameraId != null
    ) {
        val id = cameraId ?: return@ToggleTile
        runCatching { cameraManager.setTorchMode(id, !on) }
    }
}

@Composable
private fun ToggleTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    on: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (on) Color.Black.copy(alpha = 0.24f) else Color.Black.copy(alpha = 0.05f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = controlCenterContentColor)
        Text(label, color = controlCenterContentColor, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Screen brightness is one of the few system settings a launcher can
 * actually write directly, rather than only opening a system panel for -
 * but only once WRITE_SETTINGS has been granted, which (being a "modify
 * system settings" special permission) needs a dedicated system screen to
 * grant, not a plain runtime dialog. Tapping the slider before that's
 * granted sends the user there instead of silently failing to move.
 *
 * Two things can otherwise make this slider look like it's lying:
 *
 * 1. Adaptive (auto) brightness. SCREEN_BRIGHTNESS is only the MANUAL
 *    value - while adaptive brightness is on, the system's own algorithm
 *    drives the real screen level continuously and can override a write to
 *    that key within moments, so dragging the slider looked like it had no
 *    real effect. Actually moving it now also force-switches
 *    SCREEN_BRIGHTNESS_MODE to manual, the same implicit switch touching
 *    the real hardware brightness buttons/slider makes elsewhere on stock
 *    Android.
 * 2. Only ever reading the current value once, when the panel first
 *    composes - a change made anywhere else (the real quick settings,
 *    adaptive brightness, another app) while this panel was already open
 *    never reached the slider. A ContentObserver keeps it live for as long
 *    as the panel is on screen.
 */
@Composable
private fun BrightnessSlider() {
    val context = LocalContext.current
    var canWrite by remember { mutableStateOf(Settings.System.canWrite(context)) }
    // The slider's own position is a PERCEPTUAL value, not the raw 0-255
    // SCREEN_BRIGHTNESS index directly - brightness perception (and
    // Android's own brightness curve) isn't linear against that raw index,
    // so a plain linear mapping left the low end of the slider still
    // visibly bright - "lowest" wasn't actually low. Squaring on the way
    // out (and square-rooting on the way back in) approximates that curve:
    // raw = perceptual^2 * 255, so the bottom of the slider's travel maps
    // to a much smaller raw value than the middle does, instead of both
    // being equally far apart in raw terms.
    fun readBrightness(): Float {
        val raw = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(128)
        return kotlin.math.sqrt(raw / 255f)
    }
    var value by remember { mutableFloatStateOf(readBrightness()) }
    DisposableEffect(context) {
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                value = readBrightness()
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
            false,
            observer
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    Column(Modifier.fillMaxWidth()) {
        Text("Brightness", color = controlCenterContentColor, style = MaterialTheme.typography.bodySmall)
        if (canWrite) {
            Slider(
                value = value,
                onValueChange = {
                    value = it
                    runCatching {
                        Settings.System.putInt(
                            context.contentResolver,
                            Settings.System.SCREEN_BRIGHTNESS_MODE,
                            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                        )
                        Settings.System.putInt(
                            context.contentResolver,
                            Settings.System.SCREEN_BRIGHTNESS,
                            (it * it * 255).toInt().coerceIn(1, 255)
                        )
                    }
                },
                colors = SliderDefaults.colors(
                    thumbColor = controlCenterContentColor,
                    activeTrackColor = controlCenterContentColor.copy(alpha = 0.8f),
                    inactiveTrackColor = controlCenterContentColor.copy(alpha = 0.25f)
                )
            )
        } else {
            Text(
                "Tap to allow OpenNaNoor to adjust brightness",
                color = controlCenterContentColor.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .clickable {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                Uri.parse("package:${context.packageName}")
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        canWrite = Settings.System.canWrite(context)
                    }
            )
        }
    }
}

/**
 * Airplane mode is the one radio Android will let a non-system app flip
 * directly at all - but only with WRITE_SECURE_SETTINGS, a signature/system
 * permission no runtime dialog can grant. It can still be granted by hand,
 * once, via `adb shell pm grant <package> android.permission.WRITE_SECURE_SETTINGS`
 * (only possible at all because the permission is declared in the
 * manifest). Until granted, this falls back to the same "open Settings"
 * behaviour Wi-Fi/Bluetooth use.
 */
@Composable
private fun AirplaneModeButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val hasPermission = remember {
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    var on by remember {
        mutableStateOf(
            runCatching {
                Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON) != 0
            }.getOrDefault(false)
        )
    }

    if (!hasPermission) {
        SystemPanelButton(icon = Icons.Filled.AirplanemodeActive, label = "Airplane", modifier = modifier) {
            context.startActivity(
                Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        return
    }

    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (on) Color.Black.copy(alpha = 0.24f) else Color.Black.copy(alpha = 0.05f))
            .clickable {
                val next = !on
                // The setting write is what actually flips the radios (the
                // system observes AIRPLANE_MODE_ON itself) - the broadcast
                // is only a best-effort nudge for anything else listening
                // for the change. A non-system sender can have that
                // broadcast silently rejected, and it used to be in the
                // SAME runCatching as `on = next` below it - when it threw,
                // the whole block bailed before that line ever ran, so the
                // toggle's own local state stayed stuck at whatever it was
                // BEFORE the very first press forever, and every press after
                // that recomputed the same "turn it on" write again instead
                // of alternating - "pressing again doesn't turn it back
                // off". Splitting them means the broadcast can fail on its
                // own without blocking the state flip that actually matters.
                runCatching {
                    Settings.Global.putInt(
                        context.contentResolver,
                        Settings.Global.AIRPLANE_MODE_ON,
                        if (next) 1 else 0
                    )
                }
                runCatching {
                    context.sendBroadcast(
                        Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", next)
                    )
                }
                on = next
            }
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(Icons.Filled.AirplanemodeActive, contentDescription = null, tint = controlCenterContentColor)
        Text("Airplane", color = controlCenterContentColor, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Whatever's currently playing, if anything - MediaSessionManager only
 * hands back active sessions to an app with notification listener access
 * (see MediaListenerService, the component this checks against), a
 * separate opt-in with its own Settings screen. Without it granted, this
 * shows a one-line prompt instead of controls; with nothing actually
 * playing, it shows nothing at all rather than an empty placeholder.
 */
@Composable
private fun NowPlayingRow() {
    val context = LocalContext.current
    val componentName = remember {
        android.content.ComponentName(context, com.example.opennanoor.service.MediaListenerService::class.java)
    }
    fun hasAccess() = runCatching {
        Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?.contains(componentName.flattenToString()) == true
    }.getOrDefault(false)

    var hasAccessState by remember { mutableStateOf(hasAccess()) }
    if (!hasAccessState) {
        Text(
            "Tap to allow Now Playing access",
            color = controlCenterContentColor.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    context.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    hasAccessState = hasAccess()
                }
        )
        return
    }

    val sessionManager = remember {
        context.getSystemService(android.media.session.MediaSessionManager::class.java)
    }
    var controller by remember { mutableStateOf<android.media.session.MediaController?>(null) }
    var title by remember { mutableStateOf<String?>(null) }
    var artist by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }

    fun pickController(): android.media.session.MediaController? {
        val sessions = runCatching { sessionManager?.getActiveSessions(componentName) }.getOrNull().orEmpty()
        // Prefer whichever session is actually mid-playback over one just
        // sitting paused (a music app left open from earlier, say) - the
        // first active session isn't necessarily the one the user means by
        // "what's playing right now".
        return sessions.firstOrNull {
            it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
        } ?: sessions.firstOrNull()
    }

    fun refreshFrom(c: android.media.session.MediaController?) {
        title = c?.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
        artist = c?.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
        playing = c?.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
    }

    DisposableEffect(sessionManager, hasAccessState) {
        if (sessionManager == null) return@DisposableEffect onDispose {}
        val callback = object : android.media.session.MediaController.Callback() {
            override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
                refreshFrom(controller)
            }
            override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) {
                refreshFrom(controller)
            }
        }
        fun attach(c: android.media.session.MediaController?) {
            controller?.unregisterCallback(callback)
            controller = c
            c?.registerCallback(callback)
            refreshFrom(c)
        }
        attach(pickController())
        val sessionsListener =
            android.media.session.MediaSessionManager.OnActiveSessionsChangedListener { attach(pickController()) }
        runCatching { sessionManager.addOnActiveSessionsChangedListener(sessionsListener, componentName) }
        onDispose {
            controller?.unregisterCallback(callback)
            runCatching { sessionManager.removeOnActiveSessionsChangedListener(sessionsListener) }
        }
    }

    val trackTitle = title
    if (trackTitle.isNullOrBlank()) return

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.05f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                trackTitle,
                color = controlCenterContentColor,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1
            )
            if (!artist.isNullOrBlank()) {
                Text(
                    artist.orEmpty(),
                    color = controlCenterContentColor.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
        }
        Icon(
            imageVector = Icons.Filled.SkipPrevious,
            contentDescription = "Previous",
            tint = controlCenterContentColor,
            modifier = Modifier.clickable {
                controller?.transportControls?.skipToPrevious()
            }
        )
        Icon(
            imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (playing) "Pause" else "Play",
            tint = controlCenterContentColor,
            modifier = Modifier
                .clickable {
                    val transport = controller?.transportControls ?: return@clickable
                    if (playing) transport.pause() else transport.play()
                }
        )
        Icon(
            imageVector = Icons.Filled.SkipNext,
            contentDescription = "Next",
            tint = controlCenterContentColor,
            modifier = Modifier.clickable {
                controller?.transportControls?.skipToNext()
            }
        )
    }
}

@Composable
private fun SystemPanelButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    // Read-only status, not a toggle - Wi-Fi/Bluetooth are still one tap to
    // Settings, same as before, this only darkens the tile to reflect
    // whatever state it's ALREADY in (see WifiStatusButton/
    // BluetoothStatusButton), matching the Airplane tile's own on/off look
    // without actually being able to flip either from here.
    on: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (on) Color.Black.copy(alpha = 0.24f) else Color.Black.copy(alpha = 0.05f))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, contentDescription = null, tint = controlCenterContentColor)
        Text(label, color = controlCenterContentColor, style = MaterialTheme.typography.labelSmall)
    }
}

/** Wi-Fi's on/off state is readable with only ACCESS_WIFI_STATE, a normal
 *  (install-time, no dialog) permission - no runtime request needed, unlike
 *  Bluetooth below. Kept current via a broadcast receiver for as long as
 *  this tile is on screen, so toggling Wi-Fi from anywhere else (the real
 *  quick settings, another app) is reflected here too, not just at the
 *  moment this panel happened to open. */
@Composable
private fun WifiStatusButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val wifiManager = remember {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
    }
    var on by remember { mutableStateOf(runCatching { wifiManager.isWifiEnabled }.getOrDefault(false)) }
    DisposableEffect(wifiManager) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                on = runCatching { wifiManager.isWifiEnabled }.getOrDefault(on)
            }
        }
        context.registerReceiver(
            receiver,
            android.content.IntentFilter(android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION)
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    SystemPanelButton(icon = Icons.Filled.Wifi, label = "Wi-Fi", modifier = modifier, on = on) {
        // Settings.Panel.ACTION_WIFI (a lighter-weight quick panel) needs
        // API 26 - this app's minSdk is 24, so the plain settings screen
        // (available since API 1) is what actually works on every
        // supported device.
        context.startActivity(
            Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** Bluetooth's adapter state needs the BLUETOOTH_CONNECT runtime permission
 *  on API 31+ (a plain manifest declaration isn't enough there, unlike
 *  Wi-Fi's ACCESS_WIFI_STATE) - requested once, the first time this tile is
 *  composed, rather than up front at app launch, since it's only needed for
 *  this one optional bit of status. If it's denied, the tile just never
 *  darkens - it still opens Settings fine either way. */
@Composable
private fun BluetoothStatusButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val adapter = remember {
        (context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)
            ?.adapter
    }
    fun hasConnectPermission() = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
        context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

    var hasPermission by remember { mutableStateOf(hasConnectPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }
    LaunchedEffect(Unit) {
        if (!hasPermission && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            permissionLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    fun readEnabled() = if (adapter != null && hasPermission) {
        runCatching { adapter.isEnabled }.getOrDefault(false)
    } else {
        false
    }
    var on by remember(hasPermission) { mutableStateOf(readEnabled()) }
    DisposableEffect(adapter, hasPermission) {
        if (adapter == null || !hasPermission) return@DisposableEffect onDispose {}
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                on = readEnabled()
            }
        }
        context.registerReceiver(
            receiver,
            android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    SystemPanelButton(icon = Icons.Filled.Bluetooth, label = "Bluetooth", modifier = modifier, on = on) {
        context.startActivity(
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
