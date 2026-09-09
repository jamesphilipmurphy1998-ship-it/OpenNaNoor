package com.example.opennanoor.launcher

import android.app.Application
import android.content.ComponentName
import android.graphics.drawable.Drawable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.opennanoor.core.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** An app plus whatever icon we ended up showing for it. */
data class LauncherEntry(
    val app: LaunchableApp,
    val icon: Drawable
)

data class LauncherUiState(
    val pages: List<List<LauncherEntry>> = emptyList(),
    val dock: List<LauncherEntry> = emptyList(),
    val allApps: List<LauncherEntry> = emptyList(),
    val availablePacks: List<IconPackInfo> = emptyList(),
    val activePack: String? = null,
    val iosStyle: Boolean = false,
    val columns: Int = 4,
    val loading: Boolean = true
)

class LauncherViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = Settings(app)
    private val _state = MutableStateFlow(LauncherUiState())
    val state: StateFlow<LauncherUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val context = getApplication<Application>()
            val packChoice = settings.iconPackPackage
            val ios = settings.iosIconStyle
            val columns = settings.columns

            val result = withContext(Dispatchers.IO) {
                val apps = AppRepository.installedApps(context)
                val packs = IconPack.installedPacks(context)
                val pack = packChoice?.let { IconPack.load(context, it) }

                val byComponent = apps.associateBy { it.component }
                val icons = apps.associate { app ->
                    val fromPack = pack?.iconFor(app.component, app.rawIcon, ICON_PX)
                    val themed = fromPack ?: app.rawIcon
                    // The squircle runs last, so it shapes pack artwork too -
                    // but pack output is already a finished tile, so it only
                    // gets clipped rather than re-inset onto a second tile.
                    val finished = if (ios) {
                        SquircleIcons.apply(themed, ICON_PX, alreadyTiled = fromPack != null)
                    } else {
                        themed
                    }
                    app.component to LauncherEntry(app, finished)
                }

                val layout = HomeLayout.load(context, byComponent.keys)
                    ?: HomeLayout.default(apps, columns).also {
                        HomeLayout.save(context, it)
                    }

                // Apps installed since the layout was written land on a new page
                // rather than silently disappearing.
                val placed = (layout.pages.flatten() + layout.dock).toSet()
                val unplaced = apps.map { it.component }.filterNot { it in placed }
                val pages = layout.pages + unplaced
                    .chunked(columns * HomeLayout.ROWS_PER_PAGE)
                    .filter { it.isNotEmpty() }

                Loaded(
                    pages = pages.map { page -> page.mapNotNull(icons::get) },
                    dock = layout.dock.mapNotNull(icons::get),
                    allApps = apps.mapNotNull { icons[it.component] },
                    packs = packs,
                    packActive = pack != null
                )
            }

            _state.value = LauncherUiState(
                pages = result.pages,
                dock = result.dock,
                allApps = result.allApps,
                availablePacks = result.packs,
                activePack = packChoice.takeIf { result.packActive },
                iosStyle = ios,
                columns = columns,
                loading = false
            )
        }
    }

    fun selectIconPack(packageName: String?) {
        settings.iconPackPackage = packageName
        refresh()
    }

    fun setIosStyle(enabled: Boolean) {
        settings.iosIconStyle = enabled
        refresh()
    }

    /**
     * Moves an app to a new slot on the same page and persists the result.
     * Updates state directly rather than reloading, so the grid doesn't flash
     * while the user is still arranging things.
     */
    fun moveApp(pageIndex: Int, fromSlot: Int, toSlot: Int) {
        val current = _state.value
        val page = current.pages.getOrNull(pageIndex) ?: return
        if (fromSlot !in page.indices) return

        val target = toSlot.coerceIn(0, page.lastIndex)
        if (target == fromSlot) return

        val reordered = page.toMutableList().apply {
            add(target, removeAt(fromSlot))
        }
        val pages = current.pages.toMutableList().apply { set(pageIndex, reordered) }

        _state.value = current.copy(pages = pages)
        persist(pages, current.dock)
    }

    /**
     * Puts an app on the home screen if it isn't already there, filling the
     * first page with a free slot rather than always appending to the end.
     */
    fun addToHome(entry: LauncherEntry) {
        val current = _state.value
        val alreadyPlaced = current.pages.any { page ->
            page.any { it.app.component == entry.app.component }
        } || current.dock.any { it.app.component == entry.app.component }
        if (alreadyPlaced) return

        val capacity = current.columns * HomeLayout.ROWS_PER_PAGE
        val pages = current.pages.toMutableList()
        val target = pages.indexOfFirst { it.size < capacity }

        if (target >= 0) {
            pages[target] = pages[target] + entry
        } else {
            pages.add(listOf(entry))
        }

        _state.value = current.copy(pages = pages)
        persist(pages, current.dock)
    }

    /** Takes an app off the home screen. It stays installed and in the drawer. */
    fun removeFromHome(pageIndex: Int, slot: Int) {
        val current = _state.value
        val page = current.pages.getOrNull(pageIndex) ?: return
        if (slot !in page.indices) return

        val pages = current.pages.toMutableList()
        pages[pageIndex] = page.toMutableList().apply { removeAt(slot) }
        // Drop a page that just emptied, unless it is the only one left.
        if (pages[pageIndex].isEmpty() && pages.size > 1) pages.removeAt(pageIndex)

        _state.value = current.copy(pages = pages)
        persist(pages, current.dock)
    }

    private fun persist(
        pages: List<List<LauncherEntry>>,
        dock: List<LauncherEntry>
    ) {
        HomeLayout.save(
            getApplication(),
            HomeLayout(
                pages = pages.map { page -> page.map { it.app.component } },
                dock = dock.map { it.app.component }
            )
        )
    }

    private data class Loaded(
        val pages: List<List<LauncherEntry>>,
        val dock: List<LauncherEntry>,
        val allApps: List<LauncherEntry>,
        val packs: List<IconPackInfo>,
        val packActive: Boolean
    )

    private companion object {
        /** Render size for icons. Generous so they stay sharp when scaled. */
        const val ICON_PX = 192
    }
}
