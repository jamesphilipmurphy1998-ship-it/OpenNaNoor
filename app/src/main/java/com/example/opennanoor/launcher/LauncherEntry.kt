package com.example.opennanoor.launcher

import android.graphics.drawable.Drawable

/** An app plus whatever icon we ended up showing for it. */
data class LauncherEntry(
    val app: LaunchableApp,
    val icon: Drawable
)
