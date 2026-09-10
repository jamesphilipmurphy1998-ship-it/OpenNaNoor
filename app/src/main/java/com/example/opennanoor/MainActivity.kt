package com.example.opennanoor

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.opennanoor.core.Feature
import com.example.opennanoor.core.FeatureCatalog
import com.example.opennanoor.core.Permissions
import com.example.opennanoor.core.Settings
import com.example.opennanoor.launcher.IconPack
import com.example.opennanoor.core.Requirement
import com.example.opennanoor.service.AppMonitorService
import com.example.opennanoor.service.TintOverlayService
import com.example.opennanoor.ui.FeatureState
import com.example.opennanoor.ui.HomeScreen
import com.example.opennanoor.ui.theme.OpenNaNoorTheme

class MainActivity : ComponentActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            OpenNaNoorTheme {
                val context = LocalContext.current

                // The special permissions are granted in system settings, so we
                // re-read them every time this screen comes back to the front.
                var epoch by remember { mutableIntStateOf(0) }
                OnResume { epoch++ }

                val monitorConnected by AppMonitorService.isConnected.collectAsState()
                val foregroundApp by AppMonitorService.currentApp.collectAsState()

                val settings = remember { Settings(context) }
                val iconPacks = remember(epoch) { IconPack.installedPacks(context) }

                val states = remember(epoch, monitorConnected) {
                    FeatureCatalog.all.map { feature ->
                        val granted = when (feature.requirement) {
                            Requirement.NONE -> true
                            Requirement.OVERLAY -> Permissions.canDrawOverlays(context)
                            Requirement.ACCESSIBILITY -> Permissions.isAccessibilityEnabled(
                                context, AppMonitorService::class.java
                            )
                            // Shizuku and notification access aren't wired up yet.
                            else -> false
                        }
                        val enabled = when (feature.id) {
                            FeatureCatalog.ID_TINT -> TintOverlayService.running
                            FeatureCatalog.ID_APP_MONITOR -> monitorConnected
                            FeatureCatalog.ID_IOS_ICONS -> settings.iosIconStyle
                            else -> false
                        }
                        FeatureState(feature, enabled, granted)
                    }
                }

                HomeScreen(
                    features = states,
                    foregroundApp = foregroundApp.takeIf { monitorConnected },
                    iconPacks = iconPacks,
                    activePack = remember(epoch) { settings.iconPackPackage },
                    isDefaultHome = remember(epoch) { Permissions.isDefaultHome(context) },
                    dockIconCount = remember(epoch) { settings.dockIconCount },
                    onSelectPack = { pack ->
                        settings.iconPackPackage = pack
                        epoch++
                    },
                    onOpenHomeSettings = { startActivity(Permissions.homeAppSettingsIntent()) },
                    onSetDockIconCount = { count ->
                        settings.dockIconCount = count
                        epoch++
                    },
                    onToggle = { feature, on ->
                        toggle(feature, on)
                        epoch++
                    },
                    onGrant = { requirement -> openSettingsFor(requirement) }
                )
            }
        }
    }

    private fun toggle(feature: Feature, on: Boolean) {
        when (feature.id) {
            FeatureCatalog.ID_IOS_ICONS -> setIosLook(on)

            FeatureCatalog.ID_TINT ->
                if (on) TintOverlayService.start(this, TINT_COLOR)
                else TintOverlayService.stop(this)

            // The monitor is switched on and off in system settings, not here.
            FeatureCatalog.ID_APP_MONITOR ->
                startActivity(Permissions.accessibilitySettingsIntent())
        }
    }

    /**
     * The iOS look is one switch to the user, so it drives both halves: the
     * squircle shape, and an icon pack to supply Apple-style artwork. An
     * explicit pack choice made in the launcher's own menu is left alone.
     */
    private fun setIosLook(on: Boolean) {
        val settings = Settings(this)
        settings.iosIconStyle = on

        if (on) {
            if (settings.iconPackPackage == null) {
                settings.iconPackPackage = preferredIosPack()
            }
        } else {
            settings.iconPackPackage = null
        }
    }

    /** iPear if it's installed, otherwise whatever pack is, otherwise none. */
    private fun preferredIosPack(): String? {
        val packs = IconPack.installedPacks(this)
        return packs.firstOrNull { it.packageName == IPEAR }?.packageName
            ?: packs.firstOrNull { it.label.contains("ios", ignoreCase = true) }?.packageName
            ?: packs.firstOrNull()?.packageName
    }

    private fun openSettingsFor(requirement: Requirement) {
        when (requirement) {
            Requirement.OVERLAY -> startActivity(Permissions.overlaySettingsIntent(this))
            Requirement.ACCESSIBILITY -> startActivity(Permissions.accessibilitySettingsIntent())
            else -> Unit
        }
    }

    companion object {
        /** Warm amber at low alpha - visible but not obstructive. */
        private const val TINT_COLOR = 0x33FF9500

        /** The iOS-style pack this app suggests first when one is installed. */
        private const val IPEAR = "com.eatos.ipux"
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
