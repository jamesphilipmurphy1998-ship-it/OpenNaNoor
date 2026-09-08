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
                            else -> false
                        }
                        FeatureState(feature, enabled, granted)
                    }
                }

                HomeScreen(
                    features = states,
                    foregroundApp = foregroundApp.takeIf { monitorConnected },
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
            FeatureCatalog.ID_TINT ->
                if (on) TintOverlayService.start(this, TINT_COLOR)
                else TintOverlayService.stop(this)

            // The monitor is switched on and off in system settings, not here.
            FeatureCatalog.ID_APP_MONITOR ->
                startActivity(Permissions.accessibilitySettingsIntent())
        }
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
