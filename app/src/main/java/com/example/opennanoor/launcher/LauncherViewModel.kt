package com.example.opennanoor.launcher

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.opennanoor.core.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where an item sits: a slot on a page, or a slot in the dock. */
sealed class HomeLocation {
    data class Page(val page: Int, val slot: Int) : HomeLocation()
    data class Dock(val slot: Int) : HomeLocation()
}

data class LauncherUiState(
    val pages: List<List<HomeItem>> = emptyList(),
    val dock: List<HomeItem> = emptyList(),
    val allApps: List<HomeItem.AppItem> = emptyList(),
    val availablePacks: List<IconPackInfo> = emptyList(),
    val activePack: String? = null,
    val iosStyle: Boolean = false,
    val columns: Int = 4,
    val openFolderId: String? = null,
    val loading: Boolean = true
) {
    /** The folder currently shown full-screen, if any, resolved fresh each state. */
    val openFolder: HomeItem.FolderItem?
        get() = openFolderId?.let { id ->
            (pages.flatten() + dock).filterIsInstance<HomeItem.FolderItem>()
                .firstOrNull { it.folderId == id }
        }
}

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
                val icons: Map<ComponentName, HomeItem.AppItem> = apps.associate { app ->
                    val fromPack = pack?.iconFor(app.component, app.rawIcon, ICON_PX)
                    val themed = fromPack ?: app.rawIcon
                    val finished = if (ios) {
                        SquircleIcons.apply(themed, ICON_PX, alreadyTiled = fromPack != null)
                    } else {
                        themed
                    }
                    app.component to HomeItem.AppItem(LauncherEntry(app, finished))
                }

                fun resolve(slot: HomeSlot): HomeItem? = when (slot) {
                    is HomeSlot.AppSlot -> icons[slot.component]
                    is HomeSlot.FolderSlot -> {
                        val members = slot.members.mapNotNull { icons[it] }
                        when {
                            members.isEmpty() -> null
                            members.size == 1 -> members.first()
                            else -> HomeItem.FolderItem(slot.id, slot.name, members)
                        }
                    }
                }

                val layout = HomeLayout.load(context, byComponent.keys)
                    ?: HomeLayout.default(apps, columns).also { HomeLayout.save(context, it) }

                val resolvedPages = layout.pages.map { page -> page.mapNotNull(::resolve) }
                val resolvedDock = layout.dock.mapNotNull(::resolve)

                // Apps installed since the layout was written land on a new page
                // rather than silently disappearing.
                val placed = (resolvedPages.flatten() + resolvedDock)
                    .flatMap { item ->
                        when (item) {
                            is HomeItem.AppItem -> listOf(item.entry.app.component)
                            is HomeItem.FolderItem -> item.items.map { it.entry.app.component }
                        }
                    }.toSet()
                val unplaced = apps.map { it.component }.filterNot { it in placed }
                val pages = resolvedPages + unplaced
                    .mapNotNull { icons[it] }
                    .chunked(columns * HomeLayout.ROWS_PER_PAGE)
                    .filter { it.isNotEmpty() }

                Loaded(
                    pages = pages.ifEmpty { listOf(emptyList()) },
                    dock = resolvedDock,
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
                openFolderId = _state.value.openFolderId,
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
     * Moves whatever sits at [from] to [to]. Dropping an app onto another app
     * bundles them into a new folder; dropping one onto a folder joins it;
     * dropping onto empty space inserts there, pushing later items along. A
     * page that fills past its row count spills the drop onto a fresh page
     * rather than silently overflowing; the dock, fixed at four slots, clamps
     * to its last slot instead.
     */
    fun moveItem(from: HomeLocation, to: HomeLocation) {
        val current = _state.value
        val pagesWorking = current.pages.map { it.toMutableList() }.toMutableList()
        val dockWorking = current.dock.toMutableList()

        fun listFor(location: HomeLocation): MutableList<HomeItem>? = when (location) {
            is HomeLocation.Page -> pagesWorking.getOrNull(location.page)
            is HomeLocation.Dock -> dockWorking
        }

        val sourceList = listFor(from) ?: return
        val sourceIndex = when (from) {
            is HomeLocation.Page -> from.slot
            is HomeLocation.Dock -> from.slot
        }
        if (sourceIndex !in sourceList.indices) return
        val sourceItem = sourceList.removeAt(sourceIndex)

        insertItem(sourceItem, to, pagesWorking, dockWorking, current.columns)

        val finalPages = pagesWorking.filterIndexed { _, page ->
            page.isNotEmpty() || pagesWorking.size == 1
        }.ifEmpty { listOf(mutableListOf()) }

        _state.value = current.copy(pages = finalPages, dock = dockWorking)
        persist(finalPages, dockWorking)
    }

    /**
     * Inserts [item] at [to], mutating [pagesWorking]/[dockWorking] in place.
     * Dropping onto another app bundles both into a new folder; dropping onto
     * a folder joins it; dropping past a full page's row count spills onto a
     * fresh page rather than overflowing it, and the dock, fixed at four
     * slots, clamps to its last one.
     */
    private fun insertItem(
        item: HomeItem,
        to: HomeLocation,
        pagesWorking: MutableList<MutableList<HomeItem>>,
        dockWorking: MutableList<HomeItem>,
        columns: Int
    ) {
        val capacity = columns * HomeLayout.ROWS_PER_PAGE

        fun listFor(location: HomeLocation): MutableList<HomeItem>? = when (location) {
            is HomeLocation.Page -> pagesWorking.getOrNull(location.page)
            is HomeLocation.Dock -> dockWorking
        }

        var destination = to
        if (destination is HomeLocation.Page) {
            val destPage = pagesWorking.getOrNull(destination.page)
            if (destPage != null && destPage.size >= capacity && destination.slot >= destPage.size) {
                pagesWorking.add(destination.page + 1, mutableListOf())
                destination = HomeLocation.Page(destination.page + 1, 0)
            }
        }

        val targetList = listFor(destination) ?: pagesWorking.lastOrNull() ?: run {
            pagesWorking.add(mutableListOf(item))
            return
        }

        val insertIndex = when (destination) {
            is HomeLocation.Page -> destination.slot.coerceIn(0, targetList.size)
            is HomeLocation.Dock -> destination.slot.coerceIn(
                0,
                minOf(targetList.size, HomeLayout.DOCK_SIZE - 1)
            )
        }

        val occupant = targetList.getOrNull(insertIndex)
        when {
            occupant == null -> targetList.add(insertIndex, item)

            occupant is HomeItem.FolderItem && item is HomeItem.AppItem ->
                targetList[insertIndex] = occupant.copy(items = occupant.items + item)

            occupant is HomeItem.AppItem && item is HomeItem.AppItem && occupant != item ->
                targetList[insertIndex] = HomeItem.FolderItem(
                    folderId = HomeLayout.newFolderId(),
                    name = "Folder",
                    items = listOf(occupant, item)
                )

            else -> targetList.add(insertIndex, item)
        }
    }

    /**
     * Places an app dragged in from the drawer at an exact spot, using the
     * same occupant/folder-create rules as [moveItem] - there is just no
     * source location to remove it from first.
     */
    fun placeFromDrawer(entry: LauncherEntry, to: HomeLocation) {
        val current = _state.value
        val already = (current.pages.flatten() + current.dock).any { item ->
            when (item) {
                is HomeItem.AppItem -> item.entry.app.component == entry.app.component
                is HomeItem.FolderItem -> item.items.any {
                    it.entry.app.component == entry.app.component
                }
            }
        }
        if (already) return

        val pagesWorking = current.pages.map { it.toMutableList() }.toMutableList()
        val dockWorking = current.dock.toMutableList()
        insertItem(HomeItem.AppItem(entry), to, pagesWorking, dockWorking, current.columns)

        val finalPages = pagesWorking.ifEmpty { listOf(mutableListOf()) }
        _state.value = current.copy(pages = finalPages, dock = dockWorking)
        persist(finalPages, dockWorking)
    }

    /** Puts an app on the home screen if it isn't already there. */
    fun addToHome(entry: LauncherEntry) {
        val current = _state.value
        val already = (current.pages.flatten() + current.dock).any { item ->
            when (item) {
                is HomeItem.AppItem -> item.entry.app.component == entry.app.component
                is HomeItem.FolderItem -> item.items.any {
                    it.entry.app.component == entry.app.component
                }
            }
        }
        if (already) return

        val capacity = current.columns * HomeLayout.ROWS_PER_PAGE
        val target = current.pages.indexOfFirst { it.size < capacity }
        val destination = if (target >= 0) {
            HomeLocation.Page(target, current.pages[target].size)
        } else {
            HomeLocation.Page(current.pages.size, 0)
        }

        // Land it directly rather than routing through moveItem, which expects
        // to remove something from a real source location first.
        val pages = current.pages.toMutableList()
        if (destination.page == pages.size) pages.add(mutableListOf())
        val page = pages[destination.page].toMutableList()
        page.add(HomeItem.AppItem(entry))
        pages[destination.page] = page

        _state.value = current.copy(pages = pages)
        persist(pages, current.dock)
    }

    /** Takes an item off the home screen entirely. Apps stay in the drawer. */
    fun removeFromHome(pageIndex: Int, slot: Int) {
        val current = _state.value
        val page = current.pages.getOrNull(pageIndex) ?: return
        if (slot !in page.indices) return

        val pages = current.pages.toMutableList()
        pages[pageIndex] = page.toMutableList().apply { removeAt(slot) }
        if (pages[pageIndex].isEmpty() && pages.size > 1) pages.removeAt(pageIndex)

        _state.value = current.copy(pages = pages)
        persist(pages, current.dock)
    }

    fun openFolder(folderId: String) {
        _state.value = _state.value.copy(openFolderId = folderId)
    }

    fun closeFolder() {
        _state.value = _state.value.copy(openFolderId = null)
    }

    /**
     * Pulls one app out of an open folder and back onto the page or dock the
     * folder itself sits on. A folder left with one member dissolves back
     * into a plain app tile - the same rule iOS uses.
     */
    fun removeFromFolder(folderId: String, componentId: String) {
        val current = _state.value
        val pages = current.pages.map { it.toMutableList() }.toMutableList()
        val dock = current.dock.toMutableList()

        fun replace(list: MutableList<HomeItem>): Boolean {
            val index = list.indexOfFirst { it is HomeItem.FolderItem && it.folderId == folderId }
            if (index == -1) return false
            val folder = list[index] as HomeItem.FolderItem
            val remaining = folder.items.filterNot { it.id == "app:$componentId" }
            list[index] = when {
                remaining.isEmpty() -> return true.also { list.removeAt(index) }
                remaining.size == 1 -> remaining.first()
                else -> folder.copy(items = remaining)
            }
            return true
        }

        val foundInPage = pages.any(::replace)
        if (!foundInPage) replace(dock)

        _state.value = current.copy(pages = pages, dock = dock)
        persist(pages, dock)
    }

    /** Sends the app to the system uninstall dialog and clears it from home. */
    fun uninstallApp(component: ComponentName) {
        val context = getApplication<Application>()
        val intent = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", component.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)

        val current = _state.value
        val pages = current.pages.map { page ->
            page.mapNotNull { item -> dropComponent(item, component) }
        }
        val dock = current.dock.mapNotNull { item -> dropComponent(item, component) }

        _state.value = current.copy(pages = pages)
        persist(pages, dock)
    }

    /** Removes [component] from an item, dropping the item if that empties it. */
    private fun dropComponent(item: HomeItem, component: ComponentName): HomeItem? = when (item) {
        is HomeItem.AppItem ->
            if (item.entry.app.component == component) null else item

        is HomeItem.FolderItem -> {
            val remaining = item.items.filterNot { it.entry.app.component == component }
            when {
                remaining.isEmpty() -> null
                remaining.size == 1 -> remaining.first()
                else -> item.copy(items = remaining)
            }
        }
    }

    private fun persist(pages: List<List<HomeItem>>, dock: List<HomeItem>) {
        fun HomeItem.toSlot(): HomeSlot = when (this) {
            is HomeItem.AppItem -> HomeSlot.AppSlot(entry.app.component)
            is HomeItem.FolderItem -> HomeSlot.FolderSlot(
                id = folderId,
                name = name,
                members = items.map { it.entry.app.component }
            )
        }
        HomeLayout.save(
            getApplication(),
            HomeLayout(
                pages = pages.map { page -> page.map { it.toSlot() } },
                dock = dock.map { it.toSlot() }
            )
        )
    }

    private data class Loaded(
        val pages: List<List<HomeItem>>,
        val dock: List<HomeItem>,
        val allApps: List<HomeItem.AppItem>,
        val packs: List<IconPackInfo>,
        val packActive: Boolean
    )

    private companion object {
        /** Render size for icons. Generous so they stay sharp when scaled. */
        const val ICON_PX = 192
    }
}
