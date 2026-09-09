package com.example.opennanoor.launcher

/**
 * One tile on the home screen or in the dock: either a single app, or a
 * folder holding several. [id] is a stable key for Compose list diffing and
 * for locating an item during a drag - "app:<component>" for an app,
 * "folder:<uuid>" for a folder, so the two spaces never collide.
 */
sealed class HomeItem {
    abstract val id: String

    data class AppItem(val entry: LauncherEntry) : HomeItem() {
        override val id: String get() = "app:${entry.app.component.flattenToString()}"
    }

    data class FolderItem(
        val folderId: String,
        val name: String,
        val items: List<AppItem>
    ) : HomeItem() {
        override val id: String get() = "folder:$folderId"
    }
}
