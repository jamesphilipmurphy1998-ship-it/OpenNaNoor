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
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
    onDismiss: () -> Unit
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
            modifier = Modifier.align(Alignment.TopEnd)
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
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SystemPanelButton(
                        icon = Icons.Filled.Wifi,
                        label = "Wi-Fi",
                        modifier = Modifier.weight(1f)
                    ) {
                        // Settings.Panel.ACTION_WIFI (a lighter-weight quick
                        // panel) needs API 26 - this app's minSdk is 24, so
                        // the plain settings screen (available since API 1)
                        // is what actually works on every supported device.
                        context.startActivity(
                            Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                    SystemPanelButton(
                        icon = Icons.Filled.Bluetooth,
                        label = "Bluetooth",
                        modifier = Modifier.weight(1f)
                    ) {
                        context.startActivity(
                            Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                    SystemPanelButton(
                        icon = Icons.Filled.AirplanemodeActive,
                        label = "Airplane",
                        modifier = Modifier.weight(1f)
                    ) {
                        context.startActivity(
                            Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
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
            .background(if (on) Color.Black.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.05f))
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
 */
@Composable
private fun BrightnessSlider() {
    val context = LocalContext.current
    var canWrite by remember { mutableStateOf(Settings.System.canWrite(context)) }
    var value by remember {
        mutableFloatStateOf(
            runCatching {
                Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            }.getOrDefault(128) / 255f
        )
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
                            Settings.System.SCREEN_BRIGHTNESS,
                            (it * 255).toInt().coerceIn(1, 255)
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

@Composable
private fun SystemPanelButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.05f))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, contentDescription = null, tint = controlCenterContentColor)
        Text(label, color = controlCenterContentColor, style = MaterialTheme.typography.labelSmall)
    }
}
