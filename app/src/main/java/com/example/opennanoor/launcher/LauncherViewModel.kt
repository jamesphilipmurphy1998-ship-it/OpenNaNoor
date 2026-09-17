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

/**
 * How many icons one page holds for a [columns] x [rows] grid - the single
 * formula every page-capacity decision (chunking icons into pages, rejecting
 * an insert that would overflow one) is computed from, so they can't drift
 * out of sync with each other the way two separately-written copies of
 * "columns * rows" could. Floored at 1 so a page can never be asked to hold
 * zero icons even if columns or rows somehow came out as 0.
 */
internal fun pageCapacity(columns: Int, rows: Int): Int = (columns * rows).coerceAtLeast(1)

/** How many rows tall each individual home-screen widget's own band is. */
internal const val WIDGET_RESERVED_ROWS = 2

/**
 * Same as [pageCapacity], but reduced by however many widgets are on THIS
 * specific page ([widgetCount] - the caller's job to look up per page, see
 * widgetCountByPage below). The single formula every widget-aware capacity
 * decision (chunking icons into pages, rejecting an insert that would
 * overflow one, and the UI-layer hit-testing in HomePage/LauncherScreen) is
 * computed from, so they can't drift out of sync with each other the way
 * separately-written copies of this same adjustment could.
 */
internal fun pageCapacityFor(pageIndex: Int, columns: Int, rows: Int, reservedRows: Int): Int {
    return (pageCapacity(columns, rows) - columns * reservedRows).coerceAtLeast(1)
}

/**
 * Chunks a flat list of icons into pages, respecting each page's own
 * reduced capacity per [widgetCountByPage] (a page absent from the map has
 * no widgets, full capacity) - fills each page up to its own capacity
 * before spilling into the next, rather than chunking every page at one
 * flat size the way a plain List.chunked() would.
 */
internal fun chunkIntoPages(
    items: List<HomeItem>, columns: Int, rows: Int, widgetCountByPage: Map<Int, Int>
): List<List<HomeItem>> {
    if (items.isEmpty()) return emptyList()
    val pages = mutableListOf<List<HomeItem>>()
    var remaining = items
    var pageIndex = 0
    while (remaining.isNotEmpty()) {
        val capacity = pageCapacityFor(pageIndex, columns, rows, widgetCountByPage[pageIndex] ?: 0)
        pages += remaining.take(capacity)
        remaining = remaining.drop(capacity)
        pageIndex++
    }
    return pages
}

data class LauncherUiState(
    val pages: List<List<HomeItem>> = emptyList(),
    val dock: List<HomeItem> = emptyList(),
    val allApps: List<HomeItem.AppItem> = emptyList(),
    val recentApps: List<HomeItem.AppItem> = emptyList(),
    val availablePacks: List<IconPackInfo> = emptyList(),
    val activePack: String? = null,
    val iosStyle: Boolean = false,
    val columns: Int = 4,
    val rows: Int = HomeLayout.ROWS_PER_PAGE,
    // How many home-screen widgets (see WidgetHost.kt) are on each page -
    // pages absent from this map have none. Each reserves WIDGET_RESERVED_
    // ROWS on its own page; the icon grid there needs this to leave that
    // much space alone rather than covering it - see pageCapacityFor,
    // WIDGET_RESERVED_ROWS, and HomePage's own toDisplayY/toGridY.
    val widgetCountByPage: Map<Int, Int> = emptyMap(),
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
            val rows = settings.rows

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
                    ?: HomeLayout.default(apps, columns, rows).also { HomeLayout.save(context, it) }

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
                // Every icon (the saved pages' own, plus anything unplaced)
                // is rechunked to the CURRENT columns/rows here, not just
                // appended in new-sized chunks after old, differently-sized
                // saved pages - a saved layout from before the page layout
                // setting last changed would otherwise keep showing its old
                // per-page count until a resume that also happens to reflow
                // it (see refreshSettingsIfChanged), which doesn't always
                // fire on the very next resume (e.g. one where packChanged
                // is also true takes the full-refresh path instead).
                val widgetCountByPage = settings.widgetPlacements.groupingBy { it.page }
                    .fold(0) { acc, p -> acc + p.rowSpan }
                val pages = chunkIntoPages(
                    resolvedPages.flatten() + unplaced.mapNotNull { icons[it] }, columns, rows, widgetCountByPage
                )

                Loaded(
                    pages = pages.ifEmpty { listOf(emptyList()) },
                    dock = resolvedDock,
                    allApps = apps.mapNotNull { icons[it.component] },
                    packs = packs,
                    packActive = pack != null,
                    widgetCountByPage = widgetCountByPage
                )
            }
            _state.value = LauncherUiState(
                pages = result.pages,
                dock = result.dock,
                allApps = result.allApps,
                recentApps = resolveRecentApps(result.allApps),
                availablePacks = result.packs,
                activePack = packChoice.takeIf { result.packActive },
                iosStyle = ios,
                columns = columns,
                rows = rows,
                widgetCountByPage = result.widgetCountByPage,
                openFolderId = _state.value.openFolderId,
                loading = false
            )
        }
    }

    private fun resolveRecentApps(allApps: List<HomeItem.AppItem>): List<HomeItem.AppItem> {
        val byComponent = allApps.associateBy { it.entry.app.component }
        return settings.recentAppComponents
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .mapNotNull { byComponent[it] }
    }

    /**
     * Called every time an app is launched, from wherever it was tapped -
     * a page, the dock, a folder, the drawer, or the search panel - so the
     * search panel's own "recently opened" row (see SearchPanel) stays
     * current no matter which of those launched it. Persisted immediately
     * so the order survives the process being killed, not just updated in
     * memory.
     */
    fun recordLaunch(component: ComponentName) {
        val flat = component.flattenToString()
        val updated = (listOf(flat) + settings.recentAppComponents.filterNot { it == flat })
            .take(RECENT_APPS_LIMIT)
        settings.recentAppComponents = updated
        _state.value = _state.value.copy(recentApps = resolveRecentApps(_state.value.allApps))
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
     * resumes so a change made while it was in the background (columns/rows,
     * icon pack, iOS style) actually takes effect instead of silently being
     * ignored until the process is killed and restarted. The dock has no
     * settings of its own any more - see [dockIconSize] - so there's nothing
     * for it to catch up on here. Icon pack and iOS style need every icon
     * regenerated, so those go through the full [refresh]; columns/rows are
     * handled here directly.
     */
    fun refreshSettingsIfChanged() {
        val packChanged = settings.iconPackPackage != _state.value.activePack
        val iosChanged = settings.iosIconStyle != _state.value.iosStyle
        val current = _state.value
        val newColumns = settings.columns
        val newRows = settings.rows
        val newWidgetCountByPage = settings.widgetPlacements.groupingBy { it.page }
            .fold(0) { acc, p -> acc + p.rowSpan }
        val layoutChanged = newColumns != current.columns || newRows != current.rows
        val widgetChanged = newWidgetCountByPage != current.widgetCountByPage
        when {
            packChanged || iosChanged -> refresh()
            layoutChanged || widgetChanged -> {
                // A smaller grid (or a widget newly claiming a page's top
                // rows) can't hold as many icons per page as before - reflow
                // every icon (folders kept whole) into pages sized for the
                // new capacity, in the same order they were already in,
                // rather than letting a shrunk page silently overflow or a
                // grown one leave gaps that used to be filled by icons pushed
                // onto the next page.
                val reflowed = chunkIntoPages(current.pages.flatten(), newColumns, newRows, newWidgetCountByPage)
                    .ifEmpty { listOf(emptyList()) }
                _state.value = current.copy(
                    pages = reflowed,
                    columns = newColumns,
                    rows = newRows,
                    widgetCountByPage = newWidgetCountByPage
                )
                persist(reflowed, current.dock)
            }
        }
    }

    /**
     * Moves whatever sits at [from] to [to]. Dropping an app onto another app
     * bundles them into a new folder; dropping one onto a folder joins it;
     * dropping onto empty space inserts there, pushing later items along -
     * one falling off the end of a full page spills onto the front of the
     * next one instead of that page silently holding one over its own row
     * count. A full dock (DOCK_MAX_SIZE icons already in it) rejects a
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

        // A fold target's slot (see armedTarget/pageFoldTarget in
        // LauncherScreen) is deliberately computed against the page's REAL,
        // unshifted layout, so it identifies the right occupant while the
        // finger is still hovering. But `list.removeAt(from.slot)` just
        // above already shifted every slot after it on the SAME page down
        // by one, in pagesWorking specifically - the list insertItem is
        // about to read `to`'s occupant from. Left uncorrected, folding
        // onto a target to the RIGHT of where the drag started reads
        // targetList[to.slot] one slot too far along - whichever icon
        // happened to be sitting one place past the intended target is
        // what actually got folded into, not the one the drop visibly
        // landed on. Dragging to the LEFT never hit this (nothing before
        // the removed slot shifts), which is why only one direction ever
        // looked wrong.
        val adjustedTo = if (
            fold && from is HomeLocation.Page && to is HomeLocation.Page &&
            from.page == to.page && from.slot < to.slot
        ) {
            to.copy(slot = to.slot - 1)
        } else {
            to
        }

        val spillEvent = insertItem(
            sourceItem, adjustedTo, pagesWorking, dockWorking, current.columns, current.rows, fold, current.widgetCountByPage
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
     * (DOCK_MAX_SIZE icons already in it) rejects a non-merging drop
     * instead, since it has nowhere to spill into - its own outer size
     * never changes.
     */
    private fun insertItem(
        item: HomeItem,
        to: HomeLocation,
        pagesWorking: MutableList<MutableList<HomeItem>>,
        dockWorking: MutableList<HomeItem>,
        columns: Int,
        rows: Int,
        fold: Boolean,
        widgetCountByPage: Map<Int, Int>
    ): SpillEvent? {
        // A folder is never a drop destination in its own right - dropping
        // onto a folder is resolved below by finding it as the occupant of a
        // page or dock slot.
        fun listFor(location: HomeLocation): MutableList<HomeItem>? = when (location) {
            is HomeLocation.Page -> pagesWorking.getOrNull(location.page)
            is HomeLocation.Dock -> dockWorking
            is HomeLocation.Folder -> null
        }

        val destination = to

        // The dock never merges into a folder - unlike a page, where
        // dwelling over an occupied cell offers to fold into it, the dock
        // only ever reorders (see dockDropTarget's own comment on this).
        // fold is ignored outright for a Dock destination rather than only
        // at the call site, so this function can't be made to create a
        // dock folder no matter what a future caller passes it.
        val fold = fold && destination !is HomeLocation.Dock

        // Unlike a full page, a full dock has nowhere to spill over to - its
        // own outer size is fixed regardless of how many icons are in it,
        // and since it never merges either, a full dock always rejects a
        // drop outright. Without this guard, a drop onto a full dock still
        // inserted, silently pushing the last icon past DOCK_MAX_SIZE where
        // Dock never draws it again - an icon would vanish and the drop
        // would look like it failed.
        if (destination is HomeLocation.Dock && dockWorking.size >= DOCK_MAX_SIZE) {
            return null
        }

        val targetList = listFor(destination) ?: pagesWorking.lastOrNull() ?: run {
            pagesWorking.add(mutableListOf(item))
            return null
        }

        val insertIndex = when (val target = destination) {
            is HomeLocation.Page -> target.slot.coerceIn(0, targetList.size)
            is HomeLocation.Dock -> target.slot.coerceIn(
                0,
                minOf(targetList.size, DOCK_MAX_SIZE - 1)
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
            spillOverflow(destination.page, pagesWorking, columns, rows, widgetCountByPage)
        } else {
            null
        }
    }

    /**
     * Bumps the last icon off [pageIndex] onto the front of the next page
     * if it's grown past its own (possibly widget-reduced, see
     * [pageCapacityFor]) capacity, creating that next page if there isn't
     * one yet - and keeps cascading, since bumping one into an already-full
     * next page just moves the problem one page further along.
     */
    private fun spillOverflow(
        pageIndex: Int,
        pagesWorking: MutableList<MutableList<HomeItem>>,
        columns: Int,
        rows: Int,
        widgetCountByPage: Map<Int, Int>
    ): SpillEvent? {
        // Only the first page's departure gets a SpillEvent - a cascade
        // reaching a second or third full page in a row is rare enough,
        // and animating each of those in turn would need its own queue
        // rather than one event, not worth it for how seldom it happens.
        var firstSpill: SpillEvent? = null
        var index = pageIndex
        while (true) {
            val page = pagesWorking.getOrNull(index) ?: return firstSpill
            val capacity = pageCapacityFor(index, columns, rows, widgetCountByPage[index] ?: 0)
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
     * same occupant/folder-create rules as [moveItem]. The drawer lists
     * every installed app regardless of whether it's already placed
     * somewhere on the home screen or in a folder, so dragging one that
     * is relocates that existing placement here rather than silently
     * refusing - the old behaviour (a no-op guard) looked, from the drop
     * side, exactly like a drop that simply failed to persist: no error,
     * no ping-back, nothing visibly happened at all.
     */
    fun placeFromDrawer(entry: LauncherEntry, to: HomeLocation, fold: Boolean = false) {
        val current = _state.value
        val pagesWorking = current.pages.map { it.toMutableList() }.toMutableList()
        val dockWorking = current.dock.toMutableList()

        // If this app is already placed on the SAME page it's being
        // dropped onto, removing it first shifts every slot after it on
        // that page down by one - the exact same class of bug fixed for
        // moveItem's own fold path (see its own comment on this). `to`
        // has to be adjusted the same way, or dropping this app back onto
        // (or just past) where it already sat reads the wrong occupant -
        // dropping "onto itself" in particular would otherwise duplicate
        // it right next to itself instead of just leaving it where it was.
        val removedFrom = if (current.contains(entry.app.component)) {
            removeComponent(entry.app.component, pagesWorking, dockWorking)
        } else {
            null
        }
        val adjustedTo = if (
            removedFrom is HomeLocation.Page && to is HomeLocation.Page &&
            removedFrom.page == to.page && removedFrom.slot < to.slot
        ) {
            to.copy(slot = to.slot - 1)
        } else {
            to
        }

        val spillEvent = insertItem(
            HomeItem.AppItem(entry), adjustedTo, pagesWorking, dockWorking, current.columns, current.rows, fold, current.widgetCountByPage
        )

        val finalPages = pagesWorking.filterIndexed { _, page ->
            page.isNotEmpty() || pagesWorking.size == 1
        }.ifEmpty { listOf(mutableListOf()) }
        _state.value = current.copy(pages = finalPages, dock = dockWorking, spillEvent = spillEvent)
        persist(finalPages, dockWorking)
    }

    /**
     * Finds [component] wherever it currently sits - a plain slot on a
     * page or the dock, or a member of a folder on either - and removes
     * it there, dissolving/shrinking that folder the same way
     * [extractFromFolder] already does. Used by [placeFromDrawer] to
     * relocate an already-placed app rather than leaving a duplicate
     * behind. Returns the plain page/dock slot it was removed from (not a
     * folder membership, which doesn't shift any page's own indices the
     * way removing a plain slot does), so the caller can correct for that
     * shift the same way [moveItem] already does for its own fold path.
     */
    private fun removeComponent(
        component: ComponentName,
        pagesWorking: MutableList<MutableList<HomeItem>>,
        dockWorking: MutableList<HomeItem>
    ): HomeLocation? {
        pagesWorking.forEachIndexed { pageIndex, page ->
            val slot = page.indexOfFirst {
                it is HomeItem.AppItem && it.entry.app.component == component
            }
            if (slot != -1) {
                page.removeAt(slot)
                return HomeLocation.Page(pageIndex, slot)
            }
        }
        val dockSlot = dockWorking.indexOfFirst {
            it is HomeItem.AppItem && it.entry.app.component == component
        }
        if (dockSlot != -1) {
            dockWorking.removeAt(dockSlot)
            return HomeLocation.Dock(dockSlot)
        }

        val folderId = (pagesWorking.flatten() + dockWorking)
            .filterIsInstance<HomeItem.FolderItem>()
            .firstOrNull { folder -> folder.items.any { it.entry.app.component == component } }
            ?.folderId ?: return null
        extractFromFolder(folderId, component.flattenToString(), pagesWorking, dockWorking)
        return null
    }

    /** Puts an app on the home screen if it isn't already there. */
    fun addToHome(entry: LauncherEntry) {
        val current = _state.value
        if (current.contains(entry.app.component)) return

        val capacity = pageCapacity(current.columns, current.rows)
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
        val packActive: Boolean,
        val widgetCountByPage: Map<Int, Int>
    )

    private companion object {
        /** Render size for icons. Generous so they stay sharp when scaled. */
        const val ICON_PX = 192

        /** Kept a little past the 4 the search panel actually shows, so an
         *  app uninstalled since its last launch doesn't shrink the row. */
        const val RECENT_APPS_LIMIT = 8
    }
}
