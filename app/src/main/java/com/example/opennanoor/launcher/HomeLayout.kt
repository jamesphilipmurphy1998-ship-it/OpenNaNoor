package com.example.opennanoor.launcher

import android.content.ComponentName
import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Which apps sit on which home page, and which sit in the dock.
 *
 * Stored as flattened component names. Anything uninstalled since the layout
 * was written is dropped on read, so a missing app leaves a gap rather than a
 * crash.
 */
data class HomeLayout(
    val pages: List<List<ComponentName>>,
    val dock: List<ComponentName>
) {
    companion object {
        const val ROWS_PER_PAGE = 5
        const val DOCK_SIZE = 4

        /**
         * A first-run layout: common apps in the dock, everything else paged in
         * alphabetical order. Roughly what a phone looks like out of the box.
         */
        fun default(apps: List<LaunchableApp>, columns: Int): HomeLayout {
            val perPage = columns * ROWS_PER_PAGE

            val dock = DOCK_PREFERENCES
                .mapNotNull { hint ->
                    apps.firstOrNull { it.component.packageName.contains(hint, true) }
                }
                .distinctBy { it.component }
                .take(DOCK_SIZE)

            val dockComponents = dock.map { it.component }.toSet()
            val rest = apps.filterNot { it.component in dockComponents }

            return HomeLayout(
                pages = rest.chunked(perPage).map { page -> page.map { it.component } },
                dock = dock.map { it.component }
            )
        }

        /** Packages we try to seed the dock with, in priority order. */
        private val DOCK_PREFERENCES = listOf(
            "dialer", "messaging", "chrome", "camera"
        )

        fun load(context: Context, available: Set<ComponentName>): HomeLayout? {
            val raw = prefs(context).getString(KEY, null) ?: return null
            return runCatching {
                val json = JSONObject(raw)
                HomeLayout(
                    pages = json.getJSONArray("pages").mapArrays { page ->
                        page.mapComponents().filter { it in available }
                    }.filter { it.isNotEmpty() },
                    dock = json.getJSONArray("dock").mapComponents()
                        .filter { it in available }
                )
            }.getOrNull()
        }

        fun save(context: Context, layout: HomeLayout) {
            val json = JSONObject().apply {
                put("pages", JSONArray().apply {
                    layout.pages.forEach { page ->
                        put(JSONArray().apply {
                            page.forEach { put(it.flattenToString()) }
                        })
                    }
                })
                put("dock", JSONArray().apply {
                    layout.dock.forEach { put(it.flattenToString()) }
                })
            }
            prefs(context).edit { putString(KEY, json.toString()) }
        }

        private fun prefs(context: Context) =
            context.getSharedPreferences("opennanoor", Context.MODE_PRIVATE)

        private const val KEY = "home_layout"

        private fun JSONArray.mapComponents(): List<ComponentName> =
            (0 until length()).mapNotNull {
                ComponentName.unflattenFromString(optString(it))
            }

        private fun <T> JSONArray.mapArrays(block: (JSONArray) -> T): List<T> =
            (0 until length()).mapNotNull { optJSONArray(it) }.map(block)
    }
}
