package com.example.opennanoor.core

import android.content.Context
import androidx.core.content.edit

/** One widget's persisted spot: which page it's on and which row it starts at. */
data class WidgetPlacement(val appWidgetId: Int, val page: Int, val topRow: Int)

/** Small persisted preferences. Deliberately plain - no DataStore ceremony yet. */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("opennanoor", Context.MODE_PRIVATE)

    /** Package name of the chosen icon pack, or null for stock icons. */
    var iconPackPackage: String?
        get() = prefs.getString(KEY_ICON_PACK, null)
        set(value) = prefs.edit { putString(KEY_ICON_PACK, value) }

    /** Reshape every icon into an iOS-style squircle, with or without a pack. */
    var iosIconStyle: Boolean
        get() = prefs.getBoolean(KEY_IOS_STYLE, false)
        set(value) = prefs.edit { putBoolean(KEY_IOS_STYLE, value) }

    /** How many columns a home page's grid is divided into, 1-6. */
    var columns: Int
        get() = prefs.getInt(KEY_COLUMNS, 4)
        set(value) = prefs.edit { putInt(KEY_COLUMNS, value.coerceIn(1, 6)) }

    /** How many rows a home page's grid is divided into, 1-6. */
    var rows: Int
        get() = prefs.getInt(KEY_ROWS, 5)
        set(value) = prefs.edit { putInt(KEY_ROWS, value.coerceIn(1, 6)) }

    /**
     * Flattened component names of the most recently launched apps, newest
     * first - shown under the search panel's own search field. Comma-
     * separated rather than a real list type, matching how this class
     * stores everything else.
     */
    var recentAppComponents: List<String>
        get() = prefs.getString(KEY_RECENT_APPS, null)
            ?.split(",")
            ?.filter { it.isNotBlank() }
            .orEmpty()
        set(value) = prefs.edit { putString(KEY_RECENT_APPS, value.joinToString(",")) }

    /**
     * Every bound home-screen widget's (AppWidgetId, page, topRow) - which
     * page it's on and which row of it (0-indexed from the top) it starts
     * at, each independently. "id:page:row" triples, comma-separated same
     * as everything else here; empty means none placed. See WidgetHost.kt.
     */
    var widgetPlacements: List<WidgetPlacement>
        get() = prefs.getString(KEY_WIDGET_PLACEMENTS, null)
            ?.split(",")
            ?.mapNotNull { entry ->
                val parts = entry.split(":")
                val id = parts.getOrNull(0)?.toIntOrNull()
                val page = parts.getOrNull(1)?.toIntOrNull()
                val row = parts.getOrNull(2)?.toIntOrNull()
                if (id != null && page != null && row != null) WidgetPlacement(id, page, row) else null
            }
            .orEmpty()
        set(value) = prefs.edit {
            putString(KEY_WIDGET_PLACEMENTS, value.joinToString(",") { "${it.appWidgetId}:${it.page}:${it.topRow}" })
        }

    private companion object {
        const val KEY_ICON_PACK = "icon_pack"
        const val KEY_COLUMNS = "columns"
        const val KEY_ROWS = "rows"
        const val KEY_IOS_STYLE = "ios_icon_style"
        const val KEY_RECENT_APPS = "recent_apps"
        const val KEY_WIDGET_PLACEMENTS = "widget_placements"
    }
}
