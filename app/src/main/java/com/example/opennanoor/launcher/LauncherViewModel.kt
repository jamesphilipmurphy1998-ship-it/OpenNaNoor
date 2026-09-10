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
    /** One app inside an open folder, addressed by the folder and its component. */
    data class Folder(val folderId: String, val componentId: String) : HomeLocation()
}

/**
 * One icon that just spilled off the end of a full page onto the next one -
 * kept around only long enough for the UI to play the same push-aside
 * motion a live drag already shows, on the page it left, before clearing
 * itself. The data has already moved; this is purely a cue for that one
 * departure animation.
 */
data class SpillEvent(val item: HomeItem, val fromPage: Int)

data class LauncherUiState(
    val pages: List<List<HomeItem>> = emptyList(),
    val dock: List<HomeItem> = emptyList(),
    val allApps: List<HomeItem.AppItem> = emptyList(),
    val availablePacks: List<IconPackInfo> = emptyList(),
    val activePack: String? = null,
    val iosStyle: Boolean = false,
    val columns: Int = 4,
    val dockIconCount: Int = 4,
    val openFolderId: String? = null,
    val spillEvent: SpillEvent? = null,
    val loading: Boolean = true
) {
    /** The folder currently shown full-screen, if any, resolved fresh each state. */
    val openFolder: HomeItem.FolderItem?
        get() = openFolderId?.let { id ->
            (pages.flatten() + dock).filterIsInstance<HomeItem.FolderItem>()
                .firstOrNull { it.folderId == id }
        }

    /** Whether [component] is already placed somewhere on the home screen - a page, or a folder on one, or the dock. */
    fun contains(component: ComponentName): Boolean =
        (pages.flatten() + dock).any { item ->
            when (item) {
                is HomeItem.AppItem -> item.entry.app.component == component
                is HomeItem.FolderItem -> item.items.any { it.entry.app.component == component }
            }
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
            val dockIconCount = settings.dockIconCount

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
                dockIconCount = dockIconCount,
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
     * Settings are changed from MainActivity, a separate screen from the
     * running home screen - this instance's [settings] reads happened once,
     * at [init], and never again on their own. Called when the home screen
     * resumes so a change made while it was in the background (dock icon
     * count, columns, icon pack, iOS style) actually takes effect instead of
     * silently being ignored until the process is killed and restarted.
     * Icon pack and iOS style need every icon regenerated, so those go
     * through the full [refresh]; the rest are plain numbers the UI already
     * reads straight off [LauncherUiState].
     */
    fun refreshSettingsIfChanged() {
        val packChanged = settings.iconPackPackage != _state.value.activePack
        val iosChanged = settings.iosIconStyle != _state.value.iosStyle
        if (packChanged || iosChanged) {
            refresh()
        } else {
            _state.value = _state.value.copy(
                columns = settings.columns,
                dockIconCount = settings.dockIconCount
            )
        }
    }

    /**
     * Moves whatever sits at [from] to [to]. Dropping an app onto another app
     * bundles them into a new folder; dropping one onto a folder joins it;
     * dropping onto empty space inserts there, pushing later items along -
     * one falling off the end of a full page spills onto the front of the
     * next one instead of that page silently holding one over its own row
     * count. A full dock (dockIconCount icons already in it) rejects a
     * non-merging drop instead, since it has nowhere to spill into.
     */
    fun moveItem(from: HomeLocation, to: HomeLocation, fold: Boolean = false) {
        val current = _state.value
        val pagesWorking = current.pages.map { it.toMutableList() }.toMutableList()
        val dockWorking = current.dock.toMutableList()

        val sourceItem: HomeItem = when (from) {
            is HomeLocation.Page -> {
                val list = pagesWorking.getOrNull(from.page) ?: return
                if (from.slot !in list.indices) return
                list.removeAt(from.slot)
            }
            is HomeLocation.Dock -> {
                if (from.slot !in dockWorking.indices) return
                dockWorking.removeAt(from.slot)
            }
            is HomeLocation.Folder ->
                extractFromFolder(from.folderId, from.componentId, pagesWorking, dockWorking)
                    ?: return
        }

        val spillEvent = insertItem(
            sourceItem, to, pagesWorking, dockWorking, current.columns, current.dockIconCount, fold
        )

        val finalPages = pagesWorking.filterIndexed { _, page ->
            page.isNotEmpty() || pagesWorking.size == 1
        }.ifEmpty { listOf(mutableListOf()) }

        _state.value = current.copy(pages = finalPages, dock = dockWorking, spillEvent = spillEvent)
        persist(finalPages, dockWorking)
    }

    /** Clears a [SpillEvent] once the UI has finished playing its departure animation. */
    fun clearSpillEvent() {
        _state.value = _state.value.copy(spillEvent = null)
    }

    /**
     * Pulls one app out of whichever folder holds it (searching pages, then
     * the dock) and returns it, leaving the folder behind with one fewer
     * member - dissolved back to a plain app tile if that leaves just one,
     * removed entirely if that empties it out.
     */
    private fun extractFromFolder(
        folderId: String,
        componentId: String,
        pagesWorking: MutableList<MutableList<HomeItem>>,
        dockWorking: MutableList<HomeItem>
    ): HomeItem.AppItem? {
        fun tryList(list: MutableList<HomeItem>): HomeItem.AppItem? {
            val index = list.indexOfFirst { it is HomeItem.FolderItem && it.folderId == folderId }
            if (index == -1) return null
            val folder = list[index] as HomeItem.FolderItem
            val extracted = folder.items.firstOrNull { it.id == "app:$componentId" } ?: return null
            val remaining = folder.items - extracted
            list[index] = when {
                remaining.isEmpty() -> return extracted.also { list.removeAt(index) }
                remaining.size == 1 -> remaining.first()
                else -> folder.copy(items = remaining)
            }
            return extracted
        }

        pagesWorking.forEach { page -> tryList(page)?.let { return it } }
        return tryList(dockWorking)
    }

    /**
     * Inserts [item] at [to], mutating [pagesWorking]/[dockWorking] in place.
     * Dropping onto another app bundles both into a new folder; dropping onto
     * a folder joins it. Dropping into a full page still lands exactly where
     * aimed, pushing every icon after it along - the one that falls off the
     * end spills onto the front of the next page (see [spillOverflow]) rather
     * than the page holding one more than its own row count. A full dock
     * (dockCapacity icons already in it) rejects a non-merging drop instead,
     * since it has nowhere to spill into - its own outer size never changes.
     */
    private fun insertItem(
        item: HomeItem,
        to: HomeLocation,
        pagesWorking: MutableList<MutableList<HomeItem>>,
        dockWorking: MutableList<HomeItem>,
        columns: Int,
        dockCapacity: Int,
        fold: Boolean
    ): SpillEvent? {
        val capacity = columns * HomeLayout.ROWS_PER_PAGE

        // A folder is never a drop destination in its own right - dropping
        // onto a folder is resolved below by finding it as the occupant of a
        // page or dock slot.
        fun listFor(location: HomeLocation): MutableList<HomeItem>? = when (location) {
            is HomeLocation.Page -> pagesWorking.getOrNull(location.page)
            is HomeLocation.Dock -> dockWorking
            is HomeLocation.Folder -> null
        }

        val destination = to

        // Unlike a full page, a full dock has nowhere to spill over to - its
        // own outer size is fixed by dockCapacity. Without this guard, a
        // non-merging drop onto a full dock still inserted, silently pushing
        // the last icon past dockCapacity where Dock's repeat(slotCount)
        // never draws it again - an icon would vanish and the drop would
        // look like it failed.
        if (destination is HomeLocation.Dock && dockWorking.size >= dockCapacity) {
            val occupant = dockWorking.getOrNull(destination.slot.coerceIn(0, dockWorking.lastIndex))
            val willMerge = fold && (
                occupant is HomeItem.FolderItem && item is HomeItem.AppItem ||
                    occupant is HomeItem.AppItem && item is HomeItem.AppItem && occupant != item
                )
            if (!willMerge) return null
        }

        val targetList = listFor(destination) ?: pagesWorking.lastOrNull() ?: run {
            pagesWorking.add(mutableListOf(item))
            return null
        }

        val insertIndex = when (val target = destination) {
            is HomeLocation.Page -> target.slot.coerceIn(0, targetList.size)
            is HomeLocation.Dock -> target.slot.coerceIn(
                0,
                minOf(targetList.size, dockCapacity - 1)
            )
            is HomeLocation.Folder -> targetList.size
        }

        val occupant = targetList.getOrNull(insertIndex)
        when {
            occupant == null -> targetList.add(insertIndex, item)

            // Merging only happens when the drag dwelled over this slot long
            // enough to arm it. Otherwise the drop inserts here and pushes the
            // other icons along, which is what a quick drag past them means.
            !fold -> targetList.add(insertIndex, item)

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

        // The insert above lands exactly where the drop was aimed, pushing
        // everything after it along the way it always does - even into a
        // full page, which now grows one past capacity rather than being
        // redirected to a fresh page regardless of where within it was
        // dropped. That one extra icon spills off the end onto the front of
        // the next page instead, cascading again if that page was also
        // full, rather than sitting in the current one past its own row
        // count.
        return if (destination is HomeLocation.Page) {
            spillOverflow(destination.page, pagesWorking, capacity)
        } else {
            null
        }
    }

    /**
     * Bumps the last icon off [pageIndex] onto the front of the next page
     * if it's grown past [capacity], creating that next page if there isn't
     * one yet - and keeps cascading, since bumping one into an already-full
     * next page just moves the problem one page further along.
     */
    private fun spillOverflow(
        pageIndex: Int,
        pagesWorking: MutableList<MutableList<HomeItem>>,
        capacity: Int
    ): SpillEvent? {
        // Only the first page's departure gets a SpillEvent - a cascade
        // reaching a second or third full page in a row is rare enough,
        // and animating each of those in turn would need its own queue
        // rather than one event, not worth it for how seldom it happens.
        var firstSpill: SpillEvent? = null
        var index = pageIndex
        while (true) {
            val page = pagesWorking.getOrNull(index) ?: return firstSpill
            if (page.size <= capacity) return firstSpill
            val overflow = page.removeAt(page.lastIndex)
            if (firstSpill == null) firstSpill = SpillEvent(overflow, index)
            val nextIndex = index + 1
            if (nextIndex >= pagesWorking.size) pagesWorking.add(mutableListOf())
            pagesWorking[nextIndex].add(0, overflow)
            index = nextIndex
        }
    }

    /**
     * Places an app dragged in from the drawer at an exact spot, using the
     * same occupant/folder-create rules as [moveItem] - there is just no
     * source location to remove it from first.
     */
    fun placeFromDrawer(entry: LauncherEntry, to: HomeLocation, fold: Boolean = false) {
        val current = _state.value
        if (current.contains(entry.app.component)) return

        val pagesWorking = current.pages.map { it.toMutableList() }.toMutableList()
        val dockWorking = current.dock.toMutableList()
        val spillEvent = insertItem(
            HomeItem.AppItem(entry), to, pagesWorking, dockWorking, current.columns, current.dockIconCount, fold
        )

        val finalPages = pagesWorking.ifEmpty { listOf(mutableListOf()) }
        _state.value = current.copy(pages = finalPages, dock = dockWorking, spillEvent = spillEvent)
        persist(finalPages, dockWorking)
    }

    /** Puts an app on the home screen if it isn't already there. */
    fun addToHome(entry: LauncherEntry) {
        val current = _state.value
        if (current.contains(entry.app.component)) return

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
    /** Renames a folder in place, wherever it sits - a page or the dock. */
    fun renameFolder(folderId: String, newName: String) {
        val trimmed = newName.trim().ifEmpty { "Folder" }
        val current = _state.value
        val pages = current.pages.map { page ->
            page.map { item ->
                if (item is HomeItem.FolderItem && item.folderId == folderId) {
                    item.copy(name = trimmed)
                } else item
            }
        }
        val dock = current.dock.map { item ->
            if (item is HomeItem.FolderItem && item.folderId == folderId) {
                item.copy(name = trimmed)
            } else item
        }
        _state.value = current.copy(pages = pages, dock = dock)
        persist(pages, dock)
    }

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

        _state.value = current.copy(pages = pages, dock = dock)
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
