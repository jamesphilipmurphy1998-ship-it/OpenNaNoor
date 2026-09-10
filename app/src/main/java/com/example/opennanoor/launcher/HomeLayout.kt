package com.example.opennanoor.launcher

import android.content.ComponentName
import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A slot's saved contents: one app, or a folder of apps. */
sealed class HomeSlot {
    data class AppSlot(val component: ComponentName) : HomeSlot()
    data class FolderSlot(
        val id: String,
        val name: String,
        val members: List<ComponentName>
    ) : HomeSlot()
}

/**
 * Which apps and folders sit on which home page, and which sit in the dock.
 *
 * Stored as flattened component names inside a small JSON document. Anything
 * uninstalled since the layout was written is dropped on read - a missing
 * app leaves a gap rather than a crash, and a folder that loses every member
 * this way is dropped along with it.
 */
data class HomeLayout(
    val pages: List<List<HomeSlot>>,
    val dock: List<HomeSlot>
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
                pages = rest.chunked(perPage)
                    .map { page -> page.map { HomeSlot.AppSlot(it.component) } },
                dock = dock.map { HomeSlot.AppSlot(it.component) }
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
                        page.mapSlots(available)
                    }.filter { it.isNotEmpty() },
                    dock = json.getJSONArray("dock").mapSlots(available)
                )
            }.getOrNull()
        }

        fun save(context: Context, layout: HomeLayout) {
            val json = JSONObject().apply {
                put("pages", JSONArray().apply {
                    layout.pages.forEach { page ->
                        put(JSONArray().apply { page.forEach { put(it.toJson()) } })
                    }
                })
                put("dock", JSONArray().apply {
                    layout.dock.forEach { put(it.toJson()) }
                })
            }
            prefs(context).edit { putString(KEY, json.toString()) }
        }

        fun newFolderId(): String = UUID.randomUUID().toString()

        private fun prefs(context: Context) =
            context.getSharedPreferences("opennanoor", Context.MODE_PRIVATE)

        private const val KEY = "home_layout"

        private fun HomeSlot.toJson(): JSONObject = when (this) {
            is HomeSlot.AppSlot -> JSONObject().apply {
                put("t", "app")
                put("c", component.flattenToString())
            }
            is HomeSlot.FolderSlot -> JSONObject().apply {
                put("t", "folder")
                put("id", id)
                put("name", name)
                put("members", JSONArray().apply {
                    members.forEach { put(it.flattenToString()) }
                })
            }
        }

        private fun JSONArray.mapSlots(available: Set<ComponentName>): List<HomeSlot> =
            (0 until length()).mapNotNull { index ->
                // Slots used to be bare component strings, before folders
                // existed. Read that shape too, as a plain app slot, so an
                // older saved layout doesn't silently lose everything.
                val legacy = optString(index, "")
                if (legacy.isNotEmpty() && optJSONObject(index) == null) {
                    return@mapNotNull ComponentName.unflattenFromString(legacy)
                        ?.takeIf { it in available }
                        ?.let { HomeSlot.AppSlot(it) }
                }

                val obj = optJSONObject(index) ?: return@mapNotNull null
                when (obj.optString("t")) {
                    "app" -> ComponentName.unflattenFromString(obj.optString("c"))
                        ?.takeIf { it in available }
                        ?.let { HomeSlot.AppSlot(it) }

                    "folder" -> {
                        // distinct(): a component that somehow got saved into
                        // a folder's members twice - possible before app list
                        // deduplication was added - crashed every grid keyed
                        // on it the moment that folder rendered. This heals
                        // an already-corrupted saved layout on next load,
                        // rather than only preventing new duplicates.
                        val members = obj.optJSONArray("members")?.let { arr ->
                            (0 until arr.length()).mapNotNull {
                                ComponentName.unflattenFromString(arr.optString(it))
                                    ?.takeIf { c -> c in available }
                            }
                        }.orEmpty().distinct()
                        if (members.isEmpty()) null
                        else HomeSlot.FolderSlot(
                            id = obj.optString("id").ifEmpty { newFolderId() },
                            name = obj.optString("name").ifEmpty { "Folder" },
                            members = members
                        )
                    }

                    else -> null
                }
            }

        private fun <T> JSONArray.mapArrays(block: (JSONArray) -> T): List<T> =
            (0 until length()).mapNotNull(::optJSONArray).map(block)
    }
}
