package com.example.opennanoor.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Watches window changes and publishes the package name of whatever is in
 * front. Deliberately does nothing else yet - per-app rules will hang off
 * [foregroundPackage] once we build them.
 */
class AppMonitorService : AccessibilityService() {

    override fun onServiceConnected() {
        connected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Ignore our own windows and the system UI shade so the readout is useful.
        if (pkg == packageName) return
        foregroundPackage.value = pkg
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        connected.value = false
        super.onDestroy()
    }

    companion object {
        private val _connected = MutableStateFlow(false)
        private val _foregroundPackage = MutableStateFlow<String?>(null)

        internal val connected: MutableStateFlow<Boolean> get() = _connected
        internal val foregroundPackage: MutableStateFlow<String?> get() = _foregroundPackage

        val isConnected: StateFlow<Boolean> = _connected
        val currentApp: StateFlow<String?> = _foregroundPackage
    }
}
