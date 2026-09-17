package com.example.opennanoor.launcher

import android.content.ComponentName
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LauncherScreen(
    state: LauncherUiState,
    onLaunchApp: (LaunchableApp) -> Unit,
    onOpenSettings: () -> Unit,
    drawerOpen: Boolean,
    onDrawerOpenChange: (Boolean) -> Unit,
    editing: Boolean,
    onEditingChange: (Boolean) -> Unit,
    onMove: (from: HomeLocation, to: HomeLocation, fold: Boolean) -> Unit,
    onPlaceFromDrawer: (entry: LauncherEntry, to: HomeLocation, fold: Boolean) -> Unit,
    onRemove: (page: Int, slot: Int) -> Unit,
    onOpenFolder: (String) -> Unit,
    onCloseFolder: () -> Unit,
    onRemoveFromFolder: (folderId: String, componentId: String) -> Unit,
    onRenameFolder: (folderId: String, newName: String) -> Unit,
    onUninstall: (ComponentName) -> Unit,
    /** The tile options menu's own "Rename" - a new display-name override for [component]. */
    onRenameApp: (component: ComponentName, label: String) -> Unit = { _, _ -> },
    onSpillAnimationDone: () -> Unit,
    // Every bound home-screen widget (see WidgetHost.kt), each with its own
    // hosted view (a plain Android View, not Compose content, since a
    // widget's actual UI is a RemoteViews the OS renders for us) and topRow
    // - shown via AndroidView, positioned per-widget (see HomePage's own
    // widget block).
    widgets: List<PlacedWidget> = emptyList(),
    onAddWidget: () -> Unit = {},
    onRemoveWidget: (appWidgetId: Int) -> Unit = {},
    onWidgetMoved: (appWidgetId: Int, page: Int, row: Int) -> Unit = { _, _, _ -> },
    onWidgetResized: (appWidgetId: Int, rowSpan: Int, widthDp: Int, heightDp: Int) -> Unit =
        { _, _, _, _ -> },
    modifier: Modifier = Modifier
) {
    val insets = WindowInsets.systemBars.asPaddingValues()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    fun handleTap(item: HomeItem) {
        when (item) {
            is HomeItem.AppItem -> onLaunchApp(item.entry.app)
            is HomeItem.FolderItem -> onOpenFolder(item.folderId)
        }
    }

    if (state.loading) {
        Box(modifier.fillMaxSize()) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        return
    }

    val pageCount = state.pages.size.coerceAtLeast(1)
    val pagerState = rememberPagerState(pageCount = { pageCount })

    // Removing the last icon on a non-final page deletes that page and
    // shifts every later one down an index - nothing told the pager, so it
    // could keep pointing at an index that no longer means the same page,
    // or is now out of range entirely. pageCount changes rarely (a page
    // being added or removed, not per-frame drag movement), so reading it
    // here in composition carries none of the risk that ruled out reading
    // live drag fields this way.
    LaunchedEffect(pageCount) {
        if (pagerState.currentPage >= pageCount) {
            pagerState.scrollToPage((pageCount - 1).coerceAtLeast(0))
        }
    }
    val drag = remember { DragCoordinator() }
    // Which edge the drag is currently resting in (-1 left, 0 neither, 1
    // right) and when it started resting there - a page only flips once the
    // finger has held inside the edge strip for EDGE_HOLD_MS, not the
    // instant it crosses in, so passing through the edge on the way
    // somewhere else doesn't trigger it.
    var edgeHoldSide by remember { mutableIntStateOf(0) }
    var edgeHoldSince by remember { mutableLongStateOf(0L) }
    // The in-flight page-flip animation, if any - held onto so a second
    // flip (finger still resting in the edge strip a second later) cancels
    // the first rather than running alongside it. Two overlapping
    // animateScrollToPage calls were what made the page "jolt": each one
    // restarts the scroll from wherever the other had gotten to.
    var edgeFlipJob by remember { mutableStateOf<Job?>(null) }
    var dockBounds by remember { mutableStateOf<Rect?>(null) }
    var removeZoneBounds by remember { mutableStateOf<Rect?>(null) }
    var outerOrigin by remember { mutableStateOf(Offset.Zero) }
    // The ghost tracks the finger throughout a drag, but the instant it
    // ends, drag.end() wipes drag.position and the real tile at the target
    // slot just appears there with no transition - the ghost vanishing and
    // a tile popping into place at the same frame read as a jolt. Capturing
    // where the ghost actually was right before that reset lets the arriving
    // tile seed its own reflow animation from that exact point instead of
    // its plain grid position, so release continues the same motion instead
    // of cutting between two different renders of the same icon.
    var justDropped by remember { mutableStateOf<JustDropped?>(null) }
    // Guards handleDragEnded's own action (onMove/onPlaceFromDrawer/etc.)
    // against running twice for one release. drag.end() is deferred by a
    // frame (see its own call site) so other tiles' preview has a moment
    // to see the real reorder before the drag preview itself turns off -
    // but that means drag.item stays non-null, and handleDragEnded's own
    // "if (item != null)" guard stays open, for that same extra frame. A
    // second end-of-gesture callback landing inside that window (however
    // it happens) would re-run the whole insert against the same item,
    // silently duplicating it - this makes sure only the first one acts.
    var dragEndHandled by remember { mutableStateOf(false) }
    val topPadding = 20.dp + insets.calculateTopPadding()

    // Dragging down anywhere on a page (not just from the very top, which
    // stays the system's own notification-shade gesture) reveals this - a
    // quick way to find an app without opening the full drawer. Purely
    // local UI state, same as editing/drawerOpen conceptually but with
    // nothing else needing to know about it or survive it across a
    // recomposition from elsewhere.
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // Long-pressing empty space on a page (see HomePage's own
    // onLongPressEmptySpace) opens this - Wallpaper/Widgets/Home settings,
    // the same menu Pixel Launcher's own long-press offers.
    var showHomeMenu by remember { mutableStateOf(false) }
    // The app whose own options badge (the spanner) was just tapped, and
    // whether its Rename dialog is currently up - null/false the rest of
    // the time. Held here, not per-tile in HomePage, same reasoning as
    // showHomeMenu above: the menu itself is a full-screen overlay one
    // level up, not owned by whichever page happened to render the tile.
    var appOptionsTarget by remember { mutableStateOf<HomeLocation.Page?>(null) }
    var renamingApp by remember { mutableStateOf(false) }

    var pagerSizePx by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    // The authoritative cell measurements, reported up by whichever
    // HomePage last measured itself (see its onMetrics) - every page in the
    // pager is the same size, so any one of them is representative. Used
    // for this file's own hover/drop-target math so it can't drift from
    // what HomePage actually renders; zero until the first page reports,
    // same as pagerSizePx below.
    var pageCellWidthPx by remember { mutableStateOf(0f) }
    var pageCellHeightPx by remember { mutableStateOf(0f) }
    var pageTopPaddingPx by remember { mutableStateOf(0f) }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { outerOrigin = it.positionInWindow() }
            // The bottom-most layer everything else sits on top of - an
            // icon's own clickable/pointerInput consumes a tap that lands
            // on it before this ever sees it, the same way the settings
            // corner and the Done button already do, so this only ever
            // fires for a tap that hit neither: empty page space, dock
            // background, between the page dots. One tap there is enough
            // to leave arranging mode, same as Done. Long-pressing the same
            // empty space opens the Wallpaper/Widgets/Settings menu -
            // combinedClickable handles both without either one stealing
            // the other's touch, unlike a separate pointerInput elsewhere
            // for the long-press half: HomePage's own onLongPressEmptySpace
            // detector (a plain detectTapGestures(onLongPress=...)) turned
            // out to unconditionally consume every touch it saw, short taps
            // included, which silently broke tapping empty space to leave
            // arranging mode entirely - "tapping the screen does not
            // un-wobble icons anymore". Handling both gestures on this one
            // node instead removes the competing detector altogether.
            .combinedClickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = { if (editing) onEditingChange(false) },
                onLongClick = { showHomeMenu = true }
            )
    ) {
        val outerWidthPx = with(density) { maxWidth.toPx() }
        // Cell size and top padding used for this file's own hover/drop
        // math come straight from whichever HomePage last reported itself
        // (see HomePage's onMetrics) - the exact numbers actually used to
        // render the grid, not a second estimate of them. Falls back to a
        // rough guess only until the first page reports, which happens
        // before any drag is possible.
        val topPaddingPx = if (pageTopPaddingPx > 0f) pageTopPaddingPx
            else with(density) { topPadding.toPx() }
        val cellWidthPx = if (pageCellWidthPx > 0f) pageCellWidthPx
            else if (pagerSizePx.width > 0) pagerSizePx.width / state.columns.toFloat()
            else with(density) { (maxWidth / state.columns).toPx() }
        val cellHeightPx = if (pageCellHeightPx > 0f) pageCellHeightPx
            else if (pagerSizePx.height > 0) {
                (pagerSizePx.height - topPaddingPx) / state.rows.toFloat()
            } else with(density) {
                ((maxHeight - topPadding - DOCK_AREA_HEIGHT) / state.rows).toPx()
            }

        // Every page reserves rows for whatever widgets are on it, each
        // independently positioned by its own topRow (see HomePage's own
        // identical toDisplayY/toGridY/widgetBands/currentBands - this
        // mirrors that function exactly, since this file's own hover/
        // drop-target math has to agree with it or it hit-tests against
        // the wrong cell), including any widget currently being dragged's
        // own live preview row, so long as it hasn't crossed onto a
        // different page than [pageIndex] mid-drag.
        fun bandsFor(pageIndex: Int): List<IntRange> {
            val pageWidgets = widgets.filter { it.page == pageIndex }
            if (pageWidgets.isEmpty()) return emptyList()
            val draggingId = drag.draggingWidgetId
            val draggingFromHere = draggingId != null && drag.draggingWidgetOriginPage == pageIndex
            if (!draggingFromHere) return widgetBands(pageWidgets)
            return if (pagerState.currentPage != pageIndex) {
                widgetBands(pageWidgets.filterNot { it.appWidgetId == draggingId })
            } else {
                widgetBands(
                    pageWidgets,
                    overrideId = draggingId,
                    overrideTopRow = previewWidgetRow(
                        pageWidgets, draggingId, drag.position.y - drag.draggingWidgetGrabOffsetY,
                        topPaddingPx, cellHeightPx, state.rows,
                        draggingRowSpan = pageWidgets.first { it.appWidgetId == draggingId }.rowSpan
                    )
                )
            }
        }
        fun displayYFor(y: Float, pageIndex: Int) =
            toDisplayY(y, topPaddingPx, cellHeightPx, bandsFor(pageIndex))
        fun gridYFor(y: Float, pageIndex: Int) =
            toGridY(y, topPaddingPx, cellHeightPx, bandsFor(pageIndex))

        // Flips to the next/previous page once the finger has dwelled
        // inside a narrow strip at either screen edge for EDGE_HOLD_MS.
        // Shared by icon drags (handleDragMoved) and widget drags
        // (handleWidgetDragMoved) so both cross pages the same way.
        //
        // [center] is the dragged thing's own centre, not its top-left
        // corner - using the corner meant a leftmost-column icon started
        // the drag already sitting at x=0, inside the edge strip before the
        // finger had moved at all, flipping the page the instant it was
        // picked up. "Over the edge" also used to mean any part of the
        // ghost (half its own width, ~36dp) past the screen edge -
        // reliable, but wide enough that just reaching the LAST column of a
        // page (its own icons sitting close to the true edge by definition)
        // could cross it on its own, with no real intent to change pages at
        // all - a drag carefully aiming for the last column's own slots
        // kept getting hijacked into flipping to the next page instead of
        // landing where it was aimed. EDGE_TRIGGER_DP is a real,
        // deliberately narrow strip instead - big enough to still reliably
        // catch a finger actually pushed to the edge (unlike the flat 28
        // raw px this replaced once already, which was too thin to ever
        // fire), but nowhere near as wide as half a ghost.
        fun checkEdgeFlip(center: Offset) {
            val edgeTriggerPx = with(density) { EDGE_TRIGGER_DP.toPx() }
            val side = when {
                drag.overDock || drag.overRemoveZone -> 0
                center.x < edgeTriggerPx && pagerState.currentPage > 0 -> -1
                center.x > outerWidthPx - edgeTriggerPx && pagerState.currentPage < pageCount - 1 -> 1
                else -> 0
            }
            val now = System.currentTimeMillis()
            if (side != edgeHoldSide) {
                edgeHoldSide = side
                edgeHoldSince = now
            } else if (side != 0 && now - edgeHoldSince >= EDGE_HOLD_MS) {
                // Reset rather than let the next check fire again next
                // frame - holding through the flip requires dwelling the
                // full second again before it repeats.
                edgeHoldSince = now
                edgeFlipJob?.cancel()
                edgeFlipJob = when (side) {
                    -1 -> scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                    1 -> scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    else -> null
                }
            }
        }

        fun handleDragMoved(delta: Offset) {
            drag.moveBy(delta)
            // drag.position is seeded at the actual touch point (see
            // HomePage and Dock) and moved by exactly the finger's own
            // delta since, so it already is the point to hit-test against -
            // no half-cell correction needed to approximate a centre
            // anymore.
            val center = drag.position

            drag.overDock = dockBounds?.contains(center) == true
            drag.overRemoveZone = editing && removeZoneBounds?.contains(center) == true

            // Track the folder, if any, the finger is resting over. Only a
            // hold zone (the centre of a folder's cell - see pageFoldTarget)
            // arms the fold timer; hovering near either edge of that same
            // cell previews an insert instead, and neither dwells. Checked
            // against the page's real, unshifted layout via pageFoldTarget,
            // not pageDropTarget's own holdTarget - see pageFoldTarget's
            // own comment for why the shifted list it computes against
            // (right for reflow, used a few lines down in handleDragEnded's
            // insert path) identifies the wrong occupant once a same-page
            // drag has left its own cell.
            val hovered = if (drag.overDock || drag.overRemoveZone) null else {
                state.pages.getOrNull(pagerState.currentPage)?.let { pageItems ->
                    val originSlot = (drag.origin as? HomeLocation.Page)
                        ?.takeIf { it.page == pagerState.currentPage }?.slot
                    pageFoldTarget(
                        Offset(drag.position.x, displayYFor(drag.position.y, pagerState.currentPage)),
                        cellWidthPx, cellHeightPx, topPaddingPx, state.columns, pageItems, originSlot
                    )?.let { HomeLocation.Page(pagerState.currentPage, it) }
                }
            }
            if (hovered != drag.hoverTarget) {
                drag.hoverTarget = hovered
                // A momentary flicker back to null - a real finger held
                // rock-steady through a whole dwell would still twitch a
                // frame right as the fold preview popped in, seeing that as
                // confirmation and easing off before actually releasing -
                // doesn't cancel an already-armed fold. Only hovering a
                // genuinely different real slot does.
                if (hovered != null && hovered != drag.armedTarget) {
                    drag.folderArmed = false
                }
            }

            // Dragging out of the drawer reveals the home screen underneath it
            // so there is somewhere visible to drop onto.
            if (drag.fromDrawer && drawerOpen) onDrawerOpenChange(false)

            checkEdgeFlip(center)
        }

        // A widget drag reuses this same edge-triggered page flip (see
        // checkEdgeFlip below) instead of duplicating it, so dragging one
        // to the screen edge behaves exactly like dragging an icon there
        // already does.
        fun handleWidgetDragMoved(delta: Offset) {
            drag.moveBy(delta)
            checkEdgeFlip(drag.position)
        }

        // Dropped with no real movement at all - still resting on the exact
        // cell/slot it was lifted from. The normal resolution below computes
        // an index against a same-page/same-dock list that has the origin
        // conceptually removed already, so a same-page or same-dock drop
        // that lands anywhere past that cell's own midpoint resolves to
        // "insert after" whichever neighbour shifted into the gap - a swap
        // with that neighbour, rather than simply placing this one straight
        // back where it was. Bypassing the maths entirely for this one case
        // and resolving straight to the origin is the only way "no movement"
        // reliably means "no change", regardless of which half of the
        // original cell the finger happened to be resting on.
        fun stillOnOriginPageCell(origin: HomeLocation.Page): Boolean {
            if (origin.page != pagerState.currentPage) return false
            val column = (drag.position.x / cellWidthPx).toInt().coerceIn(0, state.columns - 1)
            val row = ((displayYFor(drag.position.y, origin.page) - topPaddingPx) / cellHeightPx).toInt().coerceAtLeast(0)
            return row * state.columns + column == origin.slot
        }

        fun stillOnOriginDockSlot(origin: HomeLocation.Dock): Boolean {
            val bounds = dockBounds
            if (bounds == null || bounds.width <= 0f) return false
            // dockDropTarget below only resolves an X coordinate against the
            // dock's own horizontal pitch - it has no idea whether the
            // finger is anywhere near the dock vertically. Without this,
            // lifting the finger well above the dock (clearly dragging the
            // icon out onto a page) could still land on the origin slot's X
            // range and read as "never moved", snapping the icon straight
            // back to the dock even though it visibly left it. overDock
            // already tracks the true "is the finger over the dock right
            // now" check (see its own assignment), so trust that first.
            if (!drag.overDock) return false
            val iconPx = with(density) { dockIconSize(state.dock.size).toPx() }
            val n = (state.dock.size - 1).coerceAtLeast(1)
            val gapPx = ((bounds.width - iconPx * n) / (n + 1)).coerceAtLeast(0f)
            val pitchPx = iconPx + gapPx
            val hovered = dockDropTarget(
                drag.position.x - bounds.left - gapPx, pitchPx, state.dock.size - 1
            )
            return hovered == origin.slot
        }

        fun handleDragEnded() {
            edgeHoldSide = 0
            // Not cancelling edgeFlipJob here on purpose - it may already be
            // mid-animation when the finger lifts, and cancelling would
            // strand the pager half-scrolled between two pages instead of
            // letting the flip it already committed to finish landing.
            val item = drag.item
            if (item != null && !dragEndHandled) {
                dragEndHandled = true
                val dragOrigin = drag.origin
                val target: HomeLocation = when {
                    dragOrigin is HomeLocation.Page && stillOnOriginPageCell(dragOrigin) -> dragOrigin
                    dragOrigin is HomeLocation.Dock && stillOnOriginDockSlot(dragOrigin) -> dragOrigin
                    drag.overDock -> {
                        val bounds = dockBounds
                        val dockSlot = if (bounds != null && bounds.width > 0f) {
                            // However many icons the dock will actually hold
                            // once this drop's own source slot (if it came
                            // from the dock itself) is removed - the same
                            // count insertItem's own clamp resolves against.
                            val itemCount = state.dock.size -
                                if (drag.origin is HomeLocation.Dock) 1 else 0
                            // Must exactly match Dock's own packing() - icons
                            // are centred with n+1 equal gaps, not spread
                            // evenly across dockIconCount cells. Using the
                            // plain bounds.width/dockIconCount division this
                            // used to use ignored that centring and the
                            // actual icon size entirely, so the index this
                            // resolved to routinely disagreed with whatever
                            // the live preview (and the on-screen icons
                            // themselves) had just been showing - the drop
                            // landing somewhere other than where it visibly
                            // was aimed.
                            // Dock's own live preview packs for one MORE
                            // icon than are here now when this is an arrival
                            // from outside (see previewCount) - reserving
                            // the squeeze the incoming icon will need, right
                            // up until release. Resolving the final index
                            // against the un-squeezed current packing instead
                            // would disagree with the spacing the icons were
                            // actually just shown at.
                            //
                            // A same-dock reorder is different again -
                            // previewCount() deliberately does NOT reduce the
                            // count while hovering the dock (reordering
                            // doesn't change how many icons are here, just
                            // their order), so the live push-preview spaces
                            // icons using the full, un-reduced state.dock.size.
                            // Resolving the drop against the reduced itemCount
                            // (size - 1) would pack tighter than what was just
                            // shown, disagreeing near a slot boundary - the
                            // same "lands where it wasn't aimed" bug this
                            // whole block exists to avoid.
                            val packingCount = if (drag.origin is HomeLocation.Dock) {
                                state.dock.size
                            } else {
                                itemCount + 1
                            }
                            val iconPx = with(density) { dockIconSize(state.dock.size).toPx() }
                            val n = packingCount.coerceAtLeast(1)
                            val gapPx = ((bounds.width - iconPx * n) / (n + 1)).coerceAtLeast(0f)
                            val pitchPx = iconPx + gapPx
                            dockDropTarget(drag.position.x - bounds.left - gapPx, pitchPx, itemCount)
                        } else 0
                        HomeLocation.Dock(dockSlot)
                    }
                    drag.overRemoveZone -> {
                        if (item is HomeItem.AppItem) onUninstall(item.entry.app.component)
                        drag.end()
                        dragEndHandled = false
                        return
                    }
                    // An already-armed fold lands on the slot it armed for,
                    // full stop - not a target re-derived from wherever the
                    // finger happens to be at the exact instant it lifts.
                    // Re-checking the fold zone fresh at release (the
                    // previous version of this) meant a sub-pixel drift in
                    // the moment between arming and actually letting go -
                    // completely normal, a finger is never perfectly still
                    // - could miss the zone by then and silently fall back
                    // to a plain insert, even though drag.folderArmed was
                    // still true: on-device logging caught this exact case,
                    // armed for one slot, the drop resolving to its
                    // neighbour instead. armedTarget already recorded
                    // which slot the dwell actually committed to; trust it.
                    drag.folderArmed && drag.armedTarget is HomeLocation.Page -> drag.armedTarget!!
                    else -> {
                        val pageItems = pageItemsForPreview(state, pagerState.currentPage, drag.origin)
                            .orEmpty()
                        val resolved = pageDropTarget(
                            Offset(drag.position.x, displayYFor(drag.position.y, pagerState.currentPage)),
                            cellWidthPx, cellHeightPx, topPaddingPx, state.columns, pageItems
                        )
                        HomeLocation.Page(pagerState.currentPage, resolved.gap)
                    }
                }

                // Only a drop that dwelled over this exact slot folds into
                // it - checked against armedTarget, not the live
                // hoverTarget, since a last-instant flicker back to null
                // (see DragCoordinator.armedTarget) shouldn't read as
                // un-armed here just because the finger let go a frame
                // after twitching off it. The dock never merges into a
                // folder (see dockDropTarget's own comment on this) -
                // armedTarget is only ever set from a page's own
                // hold-zone, so it can never legitimately equal a Dock
                // target already, but excluding Dock here explicitly means
                // that isn't something a future change to the hover logic
                // could accidentally reintroduce.
                val fold = drag.folderArmed && drag.armedTarget == target && target !is HomeLocation.Dock
                val origin = drag.origin

                // A full dock rejects this drop outright (see insertItem) -
                // nothing moves, so without this the ghost would simply
                // blink out and the icon reappear at home with no motion at
                // all, giving no sign the drop was refused rather than
                // silently lost. Mirrors insertItem's own condition: a drag
                // that STARTED in the dock always has room (its own slot is
                // freed first), and a fold merges into an occupant rather
                // than needing a slot of its own.
                val dockFull = target is HomeLocation.Dock &&
                    origin !is HomeLocation.Dock &&
                    state.dock.size >= DOCK_MAX_SIZE &&
                    !fold

                if (dockFull) {
                    // Ping back to where it came from, then let go - the
                    // ghost is still what's on screen (drag.item stays set
                    // until end()), and it simply tracks drag.position, so
                    // animating that position home animates the icon home.
                    val home = when (origin) {
                        is HomeLocation.Page -> Offset(
                            (origin.slot % state.columns) * cellWidthPx + cellWidthPx / 2f,
                            gridYFor(topPaddingPx + (origin.slot / state.columns) * cellHeightPx, origin.page) + cellHeightPx / 2f
                        )
                        // A folder's own overlay and the drawer both sit
                        // above the pages rather than at a home slot of
                        // their own - nothing meaningful to fly back to.
                        else -> null
                    }
                    if (home == null) {
                        drag.end()
                        dragEndHandled = false
                    } else {
                        scope.launch {
                            Animatable(drag.position, Offset.VectorConverter)
                                .animateTo(home, tween(REJECT_RETURN_MS)) { drag.position = value }
                            drag.end()
                            dragEndHandled = false
                        }
                    }
                    return
                }

                // A fold merges into an existing tile rather than landing as
                // one of its own - there's no standalone arrival to seed.
                if (!fold) {
                    justDropped = JustDropped(item.id, target, drag.position)
                    scope.launch {
                        delay(REFLOW_ANIMATION_MS.toLong())
                        justDropped = null
                    }
                }

                if (origin != null) onMove(origin, target, fold)
                else if (item is HomeItem.AppItem) onPlaceFromDrawer(item.entry, target, fold)
            }
            // Deferred one frame rather than called inline. drag.active
            // (item != null) flips synchronously, but onMove's own state
            // update only reaches this composition a frame later via
            // StateFlow + collectAsState (the launcher's usual one-frame
            // lag between DragCoordinator's plain State and LauncherUiState -
            // see the drag/animation traps memory). Every OTHER tile this
            // drop displaced reads drag.active to decide whether to keep
            // showing itself in the shifted, previewed slot
            // (displacedSlot's own `previewing` check) - ending the drag
            // immediately made that flip false one frame before the real
            // reorder arrived, so a displaced tile briefly reverted to its
            // OLD, pre-preview position (still the stale items list) before
            // snapping forward again once the real state caught up - a
            // visible flicker landing right on top of the tile that was
            // just dropped. Waiting one frame keeps the preview alive until
            // the real state has had a chance to replace it outright, so
            // there's nothing to revert to in between.
            scope.launch {
                withFrameNanos { }
                drag.end()
                dragEndHandled = false
            }
        }

        // Entering arranging mode is driven by drag.active rather than
        // called directly from the gesture callback above. Flipping it
        // synchronously inside onDragStart triggered a same-frame
        // recomposition (remove badges appearing on every other tile) that
        // was disrupting Compose's tracking of the still-active gesture -
        // the drag would start, then die with zero move events recorded.
        // Keyed on Unit, not on the drag field itself - using a changing
        // field as a LaunchedEffect key means reading it during composition
        // to evaluate the key, which is the same recomposition-cancels-the-
        // gesture problem the ghost and the armed-tile highlight both hit.
        // snapshotFlow watches it from inside the effect instead.
        LaunchedEffect(Unit) {
            androidx.compose.runtime.snapshotFlow { drag.active }
                .collect { active -> if (active) onEditingChange(true) }
        }

        // Resting over one slot for this long arms the folder merge. The
        // hover value is debounced first - a real finger flickers in and
        // out of a target by a pixel or two even while trying to hold
        // still, and reacting to every one of those as a fresh target reset
        // the dwell countdown before it could ever finish.
        LaunchedEffect(Unit) {
            androidx.compose.runtime.snapshotFlow { drag.hoverTarget }
                .debounce(HOVER_DEBOUNCE_MS)
                .collectLatest { target ->
                    if (target != null) {
                        kotlinx.coroutines.delay(FOLDER_DWELL_MS)
                        drag.armedTarget = target
                        drag.folderArmed = true
                    }
                }
        }

        // A real backdrop blur of the actual home screen - wallpaper and
        // icons genuinely behind it, not a fake copy - while a folder or the
        // search panel is open, the way iOS's newer glass material reads:
        // content seen through it, not hidden behind a flat tint. Both are
        // discrete state flips, not a continuous drag signal, so reading
        // them here in composition carries none of the gesture-cancellation
        // risk that ruled out reading drag fields this way - nothing is
        // being dragged when either changes.
        val folderOpen = state.openFolder != null
        val glassOpen = folderOpen || searchOpen
        // Ramped rather than switched on outright - the glass frosting over
        // as the folder grows, and clearing again as it shrinks away, is
        // most of what makes the whole thing read as one movement instead
        // of the home screen blinking between two states. Read inside the
        // graphicsLayer lambda below, so each frame of it invalidates only
        // the draw pass, never a recomposition.
        val blurRadius by animateFloatAsState(
            targetValue = if (glassOpen) BLUR_RADIUS_PX else 0f,
            animationSpec = tween(FOLDER_OPEN_MS, easing = FastOutSlowInEasing),
            label = "folderBlur"
        )
        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    renderEffect = if (blurRadius > 0.01f &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ) {
                        RenderEffect
                            .createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP)
                            .asComposeRenderEffect()
                    } else {
                        null
                    }
                }
        ) {
            // userScrollEnabled and the swipe-up gesture below used to read
            // drag.active directly, toggling off the instant a tile drag
            // began. That is a composition-time parameter on the pager
            // itself - the direct parent of every tile - and changing it
            // turned out to be what was cancelling the drag, not anything in
            // HomePage. A long-press-then-drag already consumes its own
            // pointer events, so the pager's plain swipe detectors leave it
            // alone without needing to be switched off by hand.
            HorizontalPager(
                state = pagerState,
                // Default (0) only composes the current page plus whatever
                // the scroll is actively animating through - a tile the
                // finger picked up lives inside a pointerInput keyed to its
                // own page, and once that page scrolls far enough to be
                // dropped from composition, its coroutine is torn down mid-
                // gesture (onDragCancel fires) and the icon drops wherever
                // it happened to be hovering. Keeping every page composed
                // is the fix - a handful of icon grids is cheap to hold in
                // memory, and it's a plain constant (not reactive to
                // drag.active) since toggling a Pager param like this
                // mid-gesture is itself what used to cancel drags (see the
                // userScrollEnabled note below).
                beyondViewportPageCount = (pageCount - 1).coerceAtLeast(0),
                modifier = Modifier
                    .weight(1f)
                    .onGloballyPositioned { pagerSizePx = it.size }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { _, dragAmount ->
                            when {
                                dragAmount < -DRAWER_DRAG_THRESHOLD -> onDrawerOpenChange(true)
                                dragAmount > SEARCH_DRAG_THRESHOLD -> searchOpen = true
                            }
                        }
                    }
            ) { pageIndex ->
                // Keyed on columns/rows so a live page-layout change fully
                // discards and rebuilds every page's composition, including
                // ones currently off-screen (kept composed only so a live
                // drag survives a page scroll - see beyondViewportPageCount
                // above). Without this, an off-screen page's own
                // BoxWithConstraints had already run once under the OLD
                // columns/rows and didn't reliably pick up the new values
                // through plain recomposition alone - it kept rendering its
                // old grid, with the tail of it sitting off the right edge
                // of the screen, until the whole process was killed and
                // relaunched.
                key(state.columns, state.rows) {
                    HomePage(
                        items = state.pages.getOrNull(pageIndex).orEmpty(),
                        pageIndex = pageIndex,
                        columns = state.columns,
                        rows = state.rows,
                        editing = editing,
                        topPadding = topPadding,
                        drag = drag,
                        currentPage = { pagerState.currentPage },
                        onLaunch = ::handleTap,
                        onEnterEditing = { onEditingChange(true) },
                        onDragMoved = ::handleDragMoved,
                        onDragEnded = ::handleDragEnded,
                        onRemove = { slot -> onRemove(pageIndex, slot) },
                        onOpenAppOptions = { slot -> appOptionsTarget = HomeLocation.Page(pageIndex, slot) },
                        widgets = widgets,
                        onRemoveWidget = onRemoveWidget,
                        onWidgetMoved = onWidgetMoved,
                        onWidgetResized = onWidgetResized,
                        onWidgetDragMoved = ::handleWidgetDragMoved,
                        spillEvent = state.spillEvent?.takeIf { it.fromPage == pageIndex },
                        onSpillAnimationDone = onSpillAnimationDone,
                        justDropped = justDropped?.takeIf { it.location is HomeLocation.Page && it.location.page == pageIndex },
                        onMetrics = { w, h, t ->
                            pageCellWidthPx = w
                            pageCellHeightPx = h
                            pageTopPaddingPx = t
                        }
                    )
                }
            }

            PageDots(
                count = pageCount,
                current = pagerState.currentPage,
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
            )

            Dock(
                items = state.dock,
                drag = drag,
                editing = editing,
                onTap = ::handleTap,
                onEnterEditing = { onEditingChange(true) },
                onDragMoved = ::handleDragMoved,
                onDragEnded = ::handleDragEnded,
                onPositioned = { bounds ->
                    dockBounds = bounds.translate(-outerOrigin)
                },
                outerOrigin = outerOrigin,
                justDropped = justDropped?.takeIf { it.location is HomeLocation.Dock },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .padding(bottom = 8.dp + insets.calculateBottomPadding())
            )
        }

        // Settings affordance: an invisible target in the corner, rather than
        // a gear sitting on top of the first row of icons.
        Spacer(
            Modifier
                .padding(top = insets.calculateTopPadding())
                .size(48.dp)
                .clickable(onClick = onOpenSettings)
        )

        // A visible way out of arranging mode, alongside back and home,
        // which already work. Sized generously and pinned above the grid's
        // own top padding - the first attempt sat right on top of the
        // top-right tile's remove badge, and a tap aimed at the button
        // could land on the sliver of badge still exposed beside it,
        // removing that tile instead of exiting.
        AnimatedVisibility(
            visible = editing,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 12.dp)
        ) {
            Text(
                text = "Done",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .padding(top = insets.calculateTopPadding())
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF3A3A3C))
                    .clickable { onEditingChange(false) }
                    .padding(horizontal = 18.dp, vertical = 9.dp)
            )
        }

        AnimatedVisibility(
            visible = editing && drag.active,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = insets.calculateTopPadding() + 8.dp)
        ) {
            RemoveZone(
                highlighted = drag.overRemoveZone,
                onPositioned = { bounds -> removeZoneBounds = bounds.translate(-outerOrigin) }
            )
        }

        // The drawer sits above the pages; while a drag begun inside it is in
        // progress its background fades out so the home screen underneath -
        // the actual drop target - becomes visible without unmounting the
        // drawer itself, which would cancel the gesture mid-drag.
        val drawerVisible = drawerOpen || (drag.active && drag.fromDrawer)
        // Latched rather than read straight off drag.active/fromDrawer - both
        // flip false the instant the drag ends, the same frame drawerVisible
        // above also flips false and its own exit transition (slideOut +
        // fadeOut) starts. Snapping this back to false right then un-dimmed
        // the grid back to full opacity for that one frame, before the exit
        // fade had actually painted anything - the whole library flashed
        // back into view for an instant right as it should have been
        // disappearing. Staying dimmed for a moment past the drag ending
        // lets the exit transition's own fade do the work instead.
        val activeDrawerDrag = drag.active && drag.fromDrawer
        var dimmed by remember { mutableStateOf(false) }
        LaunchedEffect(activeDrawerDrag) {
            if (activeDrawerDrag) {
                dimmed = true
            } else {
                delay(300)
                dimmed = false
            }
        }
        AnimatedVisibility(
            visible = drawerVisible,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut()
        ) {
            AppDrawer(
                state = state,
                insets = insets,
                dimmed = dimmed,
                onLaunch = {
                    onDrawerOpenChange(false)
                    onLaunchApp(it)
                },
                onDismiss = { onDrawerOpenChange(false) },
                drag = drag,
                outerOrigin = outerOrigin,
                onDragMoved = ::handleDragMoved,
                onDragEnded = ::handleDragEnded
            )
        }

        SearchPanel(
            visible = searchOpen,
            apps = state.allApps,
            recentApps = state.recentApps,
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            insets = insets,
            onLaunch = { app ->
                searchOpen = false
                searchQuery = ""
                onLaunchApp(app)
            },
            onDismiss = {
                searchOpen = false
                searchQuery = ""
            }
        )

        if (showHomeMenu) {
            HomeLongPressMenu(
                onOpenSettings = {
                    showHomeMenu = false
                    onOpenSettings()
                },
                onAddWidget = {
                    showHomeMenu = false
                    onAddWidget()
                },
                onDismiss = { showHomeMenu = false }
            )
        }

        // The app icon at appOptionsTarget, resolved fresh each state - the
        // slot's own contents, not a copy captured when the menu opened,
        // so a concurrent change elsewhere (unlikely, but cheap to get
        // right) can't leave this pointing at something stale.
        val appOptionsItem = appOptionsTarget?.let { loc ->
            state.pages.getOrNull(loc.page)?.getOrNull(loc.slot) as? HomeItem.AppItem
        }
        if (appOptionsItem != null) {
            TileOptionsMenu(
                onRename = { renamingApp = true },
                onRemoveFromScreen = {
                    appOptionsTarget?.let { onRemove(it.page, it.slot) }
                    appOptionsTarget = null
                },
                onUninstall = {
                    onUninstall(appOptionsItem.entry.app.component)
                    appOptionsTarget = null
                },
                onChangeImage = { /* not built yet */ },
                onDismiss = { appOptionsTarget = null }
            )
            if (renamingApp) {
                RenameFolderDialog(
                    currentName = appOptionsItem.entry.app.label,
                    onSave = { newLabel ->
                        onRenameApp(appOptionsItem.entry.app.component, newLabel)
                        renamingApp = false
                        appOptionsTarget = null
                    },
                    onDismiss = { renamingApp = false },
                    title = "Rename app"
                )
            }
        }

        state.openFolder?.let { folder ->
            val draggingOutOfThis = drag.active && drag.origin.let {
                it is HomeLocation.Folder && it.folderId == folder.folderId
            }
            FolderOverlay(
                folder = folder,
                insets = insets,
                dimmed = draggingOutOfThis,
                homeEditing = editing,
                onLaunch = onLaunchApp,
                onDismiss = onCloseFolder,
                onRemoveItem = { componentId -> onRemoveFromFolder(folder.folderId, componentId) },
                onRename = { newName -> onRenameFolder(folder.folderId, newName) },
                drag = drag,
                outerOrigin = outerOrigin,
                onDragMoved = ::handleDragMoved,
                // Left open rather than auto-closed: the folder still shows,
                // now one member lighter, and the user dismisses it deliberately.
                // Auto-closing exactly on drag-end would need this callback to
                // see live drag state from inside an already-running gesture,
                // which Compose does not reliably propagate mid-drag.
                onDragEnded = ::handleDragEnded
            )
        }

        // Always composed rather than conditionally inserted, and hidden via
        // alpha instead. Adding it to the tree only once a drag begins was a
        // structural change to this Box while a tile deep inside the pager
        // had an active raw pointer-input gesture running - and that seems to
        // be enough for Compose to cancel the gesture outright: onDragCancel
        // was firing within ~15ms of onDragStart, before any real movement.
        DragGhost(
            drag = drag,
            pageIconSize = pageIconSize(
                state.columns,
                with(density) { cellWidthPx.toDp() },
                with(density) { cellHeightPx.toDp() }
            ),
            dockIconSize = dockIconSize(state.dock.size)
        )

        // A widget being dragged onto a DIFFERENT page than it started on
        // can't keep showing its own live content while it travels - it's a
        // real Android View, and one can't be drawn in two places (nor
        // moved to another page's own composition without being torn down
        // and rebuilt). Its own inline copy hides (see HomePage's widget
        // block) and this plain outline takes over instead, floating above
        // the pager the same way an icon's own ghost does, so there's still
        // something following the finger across the page change.
        val crossingWidget = drag.draggingWidgetId != null &&
            drag.draggingWidgetOriginPage != pagerState.currentPage
        if (crossingWidget) {
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            0,
                            (drag.position.y - drag.draggingWidgetGrabOffsetY).toInt()
                        )
                    }
                    .size(
                        width = with(density) { (state.columns * cellWidthPx).toDp() },
                        height = with(density) {
                            val span = widgets.firstOrNull { it.appWidgetId == drag.draggingWidgetId }?.rowSpan
                                ?: WIDGET_RESERVED_ROWS
                            (span * cellHeightPx).toDp()
                        }
                    )
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.22f))
                    .border(1.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            )
        }

        // The folder someone is dwelling on to drop into expands into a
        // real 2x2 preview of what's inside, the way iOS shows it - rather
        // than just a swelling highlight. drag.folderArmed only flips once
        // per dwell lock (not on every pixel of movement, unlike position),
        // so reading it here in composition is the same category of
        // infrequent change RemoveZone's own visibility already relies on
        // safely - not the continuous kind that cancels a live gesture.
        // A plain app being dwelled on (about to form a brand new folder,
        // not join an existing one) gets the same expanded preview now too -
        // built on the fly from the two apps that would end up in it, since
        // there is no real folder yet to read contents from.
        //
        // drag.folderArmed is checked BEFORE drag.hoverTarget is ever read,
        // not after - hoverTarget changes on every cell the finger crosses,
        // not once per dwell, so reading it unconditionally recomposed this
        // on every such crossing (the same class of read this file's other
        // comments describe cancelling a live gesture outright). Gating on
        // folderArmed first means hoverTarget is only read once it has
        // already settled - handleDragMoved un-arms on every hover change,
        // so by construction it can't move again while still armed.
        val armedFolder = if (drag.folderArmed) {
            (drag.armedTarget as? HomeLocation.Page)
                ?.takeIf { it.page == pagerState.currentPage }
                ?.let { loc ->
                    val occupant = pageItemsForPreview(state, loc.page, null)?.getOrNull(loc.slot)
                    when {
                        occupant is HomeItem.FolderItem -> occupant
                        occupant is HomeItem.AppItem && drag.item is HomeItem.AppItem ->
                            HomeItem.FolderItem(
                                folderId = "preview",
                                name = "New Folder",
                                items = listOf(occupant, drag.item as HomeItem.AppItem)
                            )
                        else -> null
                    }
                }
        } else null

        // Rebuilt from scratch on a plain, self-driven Animatable - the
        // same proven mechanism FolderOverlay's own "appear" already uses
        // to grow the full folder view open - rather than
        // AnimatedVisibility, whose enter/exit transitions went through
        // several rounds of tuning (timing, start scale, transformOrigin)
        // without ever visibly changing what actually showed on the
        // device. Full manual control here means every part of this -
        // when it starts, what scale it starts at, where it's anchored -
        // is a plain value read directly off this Animatable, nothing
        // hidden inside a transition API.
        //
        // The folder+location is retained across the moment armedFolder
        // goes back to null (finger moved off, or the drop just
        // happened) so there's still something on screen to animate
        // closed, the same way FolderOverlay retains its own folder
        // through dismissAnimated.
        var retainedPreview by remember {
            mutableStateOf<Pair<HomeItem.FolderItem, HomeLocation.Page>?>(null)
        }
        val previewAppear = remember { Animatable(0f) }
        LaunchedEffect(armedFolder, drag.armedTarget) {
            val folder = armedFolder
            val loc = drag.armedTarget as? HomeLocation.Page
            if (folder != null && loc != null) {
                retainedPreview = folder to loc
                previewAppear.snapTo(MINI_PREVIEW_FROM_SCALE)
                previewAppear.animateTo(1f, tween(MINI_PREVIEW_OPEN_MS, easing = FastOutSlowInEasing))
            } else if (retainedPreview != null) {
                previewAppear.animateTo(
                    MINI_PREVIEW_FROM_SCALE,
                    tween(MINI_PREVIEW_CLOSE_MS, easing = FastOutSlowInEasing)
                )
                retainedPreview = null
            }
        }

        retainedPreview?.let { (folder, loc) ->
            val centreX = (loc.slot % state.columns) * cellWidthPx + cellWidthPx / 2f
            val centreY = gridYFor(topPaddingPx + (loc.slot / state.columns) * cellHeightPx, loc.page) + cellHeightPx / 2f
            FolderPreview(
                folder = folder,
                centreOffsetPx = Offset(centreX, centreY),
                cellSizePx = cellWidthPx,
                appear = { previewAppear.value }
            )
        }
    }
}

