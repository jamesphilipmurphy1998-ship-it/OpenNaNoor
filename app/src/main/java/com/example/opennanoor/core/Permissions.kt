package com.example.opennanoor.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.TextUtils

/**
 * Helpers for the special permissions this app leans on. None of these can be
 * granted with a normal runtime dialog - each one sends the user to a system
 * settings page where they flip it manually.
 */
object Permissions {

    /** "Display over other apps" - required for any overlay feature. */
    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun overlaySettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )

    /**
     * Whether our AccessibilityService is enabled. There is no API to ask
     * directly, so we read the secure setting listing enabled services.
     */
    fun isAccessibilityEnabled(context: Context, service: Class<*>): Boolean {
        val expected = ComponentName(context, service).flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    /** Whether OpenNaNoor is the currently selected home app. */
    fun isDefaultHome(context: Context): Boolean {
        val resolved = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            0
        )
        return resolved?.activityInfo?.packageName == context.packageName
    }

    /**
     * Opens the system's "Home app" picker. There is no API to switch the
     * default home app directly - only the user, in that system screen, can
     * do it - so this is as close to a toggle as the platform allows.
     */
    fun homeAppSettingsIntent(): Intent = Intent(Settings.ACTION_HOME_SETTINGS)
}
