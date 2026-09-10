package com.example.opennanoor.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/** One launchable app on the device. */
data class LaunchableApp(
    val component: ComponentName,
    val label: String,
    val rawIcon: Drawable
)

object AppRepository {

    /** Every app with a launcher entry, alphabetically, minus ourselves. */
    fun installedApps(context: Context): List<LaunchableApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        // Our own settings activity is listed like any other app - the home
        // screen is otherwise the only way to reach it, via a corner tap no
        // one would guess at.
        return pm.queryIntentActivities(intent, 0)
            .asSequence()
            .map { info ->
                LaunchableApp(
                    component = ComponentName(
                        info.activityInfo.packageName,
                        info.activityInfo.name
                    ),
                    label = info.loadLabel(pm).toString(),
                    rawIcon = info.loadIcon(pm)
                )
            }
            // Some apps' manifests match the launcher query more than once
            // (seen in practice with Hostelworld) - the package manager then
            // hands back two ResolveInfo entries for the exact same
            // activity. Every HomeItem's id is derived from the component
            // alone, so two entries with an identical component crashed
            // every LazyVerticalGrid keyed on that id - the drawer, a
            // folder, the dock preview - with "Key ... was already used".
            .distinctBy { it.component }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    fun launch(context: Context, component: ComponentName) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { context.startActivity(intent) }
    }
}
