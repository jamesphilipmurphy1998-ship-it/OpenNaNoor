package com.example.opennanoor.core

import android.content.Context
import androidx.core.content.edit

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

    var columns: Int
        get() = prefs.getInt(KEY_COLUMNS, 4)
        set(value) = prefs.edit { putInt(KEY_COLUMNS, value.coerceIn(3, 6)) }

    private companion object {
        const val KEY_ICON_PACK = "icon_pack"
        const val KEY_COLUMNS = "columns"
        const val KEY_IOS_STYLE = "ios_icon_style"
    }
}
