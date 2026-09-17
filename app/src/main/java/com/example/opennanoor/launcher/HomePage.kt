package com.example.opennanoor.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.geometry.Offset
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One page of the home screen.
 *
 * Tiles are placed arithmetically on a fixed grid rather than in a lazy grid,
 * so dragging can map a finger position to a slot index by simple division.
 * The item actually being dragged is not drawn here - [DragCoordinator]
 * renders one floating copy above the whole pager, so it isn't clipped to
 * one page and survives a page change mid-drag.
 */
@Composable
fun HomePage(
    items: List<HomeItem>,
    pageIndex: Int,
    columns: Int,
    rows: Int,
    editing: Boolean,
    topPadding: Dp,
    drag: DragCoordinator,
    /** Which page the pager is currently showing - only that page previews a
     *  drop. Read lazily inside layout, never during composition, since it
     *  is queried once per frame while a drag is in flight. */
    currentPage: () -> Int,
    onLaunch: (HomeItem) -> Unit,
    onEnterEditing: () -> Unit,
    onDragMoved: (Offset) -> Unit,
    onDragEnded: () -> Unit,
    onRemove: (slot: Int) -> Unit,
    /** Every home-screen widget currently placed, on ANY page - not
     *  pre-filtered to this one, since resolving a drag that crosses onto a
     *  different page needs to see what's already there too (collision
     *  checking - see previewWidgetRow). This file does its own filtering
     *  to [pageIndex] for rendering/capacity (see pageWidgets below). Each
     *  is [WIDGET_RESERVED_ROWS] tall; icons render/drop-target in the gaps
     *  between and around the ones on their own page (see widgetBands/
     *  toDisplayY/toGridY). */
    widgets: List<PlacedWidget> = emptyList(),
    /** Unbinds one widget - wired to the same remove badge every ordinary
     *  icon gets once [editing] is on. */
    onRemoveWidget: (appWidgetId: Int) -> Unit = {},
    /** A widget was dragged and released - [page]/[row] is the new
     *  (already clamped, collision-free) page and row its own band should
     *  start at - [page] may differ from the page it was dragged from. */
    onWidgetMoved: (appWidgetId: Int, page: Int, row: Int) -> Unit = { _, _, _ -> },
    /** A widget drag crossed close enough to a screen edge to flip pages -
     *  wired to the same edge-flip machinery an icon drag already uses
     *  (see LauncherScreen's own handleDragMoved/checkEdgeFlip), so a
     *  widget can be dragged onto a different page the same way. */
    onWidgetDragMoved: (Offset) -> Unit = {},
    /** Set only on the page an icon just spilled off of - see [SpillEvent]. */
    spillEvent: SpillEvent? = null,
    onSpillAnimationDone: () -> Unit = {},
    /** Set only on the page an icon was just released onto - see [JustDropped]. */
    justDropped: JustDropped? = null,
    // Reports this page's own real cell measurements upward, so
    // LauncherScreen's own hover/drop-target math (deciding where a live
    // preview lands, and whether it offers folding) can use the exact same
    // numbers this page actually renders with, rather than a second,
    // independent estimate of its own that can drift from this one by a
    // pixel or two - enough for some icon positions to disagree on which
    // cell a finger is over. Every page in the pager is the same size, so
    // any one of them reporting is enough; this is deliberately NOT fed
    // back into this file's own rendering below, which stays entirely
    // self-contained - an earlier attempt to unify the two the other way
    // (this file taking LauncherScreen's estimate instead) misaligned the
    // actual tile grid, visible as icons sitting half a row off during
    // wobble.
    onMetrics: (cellWidthPx: Float, cellHeightPx: Float, topPaddingPx: Float) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier.fillMaxSize()
    ) {
        val density = LocalDensity.current
        val cellWidth = maxWidth / columns
        val cellHeight = (maxHeight - topPadding) / rows
        val cellWidthPx = with(density) { cellWidth.toPx() }
        val cellHeightPx = with(density) { cellHeight.toPx() }
        val topPaddingPx = with(density) { topPadding.toPx() }
        val iconSize = pageIconSize(columns, cellWidth, cellHeight)

        // The icon's actual long-press-to-drag hit target - see its own
        // pointerInput below for why this is smaller than the full cell.
        // A little wider/taller than the icon+label footprint itself
        // (PAGE_LABEL_RESERVE covers the label's own line) so it's still a
        // comfortable target, just not the whole cell.
        val touchTargetWidth = (iconSize + 20.dp).coerceAtMost(cellWidth)
        val touchTargetHeight = (iconSize + PAGE_LABEL_RESERVE + 12.dp).coerceAtMost(cellHeight)
        val touchTargetInset = with(density) {
            Offset(
                ((cellWidth - touchTargetWidth) / 2f).toPx(),
                ((cellHeight - touchTargetHeight) / 2f).toPx()
            )
        }

        // Every widget committed to THIS page specifically - collision
        // checks during a drag still need to see widgets on OTHER pages
        // too (see previewWidgetRow's own callers), which is why the raw,
        // unfiltered [widgets] param is kept around rather than filtering
        // once at the call site in LauncherScreen.
        val pageWidgets = widgets.filter { it.page == pageIndex }
        val widgetHere = pageWidgets.isNotEmpty()
        // Live preview of where the currently-dragged widget (if any, and
        // if it started on THIS page and hasn't crossed onto another one
        // yet - see DragCoordinator.draggingWidgetOriginPage) would land -
        // read here via snapshotFlow same as targetOffset() below, and
        // again, live, from drag's own fields directly in onDragEnd (see
        // DragCoordinator's own comment on why).
        // A function, not a val - deliberately. Reading drag.draggingWidgetId
        // / drag.position has to happen INSIDE whatever eventually calls
        // this (targetOffset(), itself only ever invoked from within
        // snapshotFlow { targetOffset() }) for the live reflow to actually
        // stay reactive - computing it once as a plain val up here would
        // read those two State fields during regular composition instead,
        // which snapshotFlow never sees, so a widget drag in progress would
        // silently stop live-reflowing icons around it.
        fun currentBands(): List<IntRange> {
            if (pageWidgets.isEmpty()) return emptyList()
            val draggingId = drag.draggingWidgetId
            val draggingFromHere = draggingId != null && drag.draggingWidgetOriginPage == pageIndex
            if (!draggingFromHere) return widgetBands(pageWidgets)
            // Crossed onto a different page mid-drag: this page's own copy
            // is simply gone (a ghost elsewhere shows where it's headed -
            // see LauncherScreen's own widget ghost), so the gap it left
            // just stays open rather than previewing a row here that isn't
            // where the finger actually is any more.
            return if (currentPage() != pageIndex) {
                widgetBands(pageWidgets.filterNot { it.appWidgetId == draggingId })
            } else {
                widgetBands(
                    pageWidgets,
                    overrideId = draggingId,
                    overrideTopRow = previewWidgetRow(
                        pageWidgets, draggingId, drag.position.y - drag.draggingWidgetGrabOffsetY, topPaddingPx, cellHeightPx, rows
                    )
                )
            }
        }

        if (widgetHere) {
            pageWidgets.forEach { widget ->
                key(widget.appWidgetId) {
                    val isDragging = widget.appWidgetId == drag.draggingWidgetId
                    val settledY = topPaddingPx + widget.topRow * cellHeightPx
                    // Unkeyed beyond key(appWidgetId) above, same reasoning
                    // as every icon tile's own animatedOffset - this widget's
                    // identity, not its current row, owns the Animatable.
                    val animatedY = remember { Animatable(settledY) }
                    // Set synchronously, in onDragEnd below, the instant a
                    // drag on this widget finishes - a plain snapshot write,
                    // not a suspend Animatable.snapTo, since onDragEnd isn't
                    // a suspend callback. Read here in preference to
                    // animatedY.value for exactly one frame: isDragging
                    // flips false the same instant onDragEnd sets this, but
                    // the settle LaunchedEffect below only picks it up
                    // asynchronously, on whatever frame Compose gets to it -
                    // without this, that gap showed the widget's OLD
                    // (pre-drag) animatedY value for a frame, a visible
                    // flash back before snapping to where it was actually
                    // released.
                    val releaseY = remember { mutableStateOf<Float?>(null) }
                    LaunchedEffect(settledY, isDragging) {
                        if (!isDragging) {
                            // animatedY's own value is stale the instant a
                            // drag just ended - it was never updated WHILE
                            // dragging (the Box below reads drag.position
                            // directly for that), so left alone it would
                            // animate from wherever the widget was BEFORE
                            // this drag started, flying in from that old
                            // spot instead of continuing smoothly from
                            // where the finger actually let go - the same
                            // "flies in from an angle" bug icons had before
                            // being seeded from their own drop position.
                            releaseY.value?.let { animatedY.snapTo(it) }
                            releaseY.value = null
                            animatedY.animateTo(settledY, tween(REFLOW_ANIMATION_MS))
                        }
                    }
                    val angle = rememberWobble(enabled = editing, seed = -1 - widget.appWidgetId)
                    Box(
                        Modifier
                            .offset {
                                val y = if (isDragging) {
                                    // Rendered under exactly where the
                                    // finger grabbed it (see
                                    // draggingWidgetGrabOffsetY's own
                                    // comment), not recentred on the touch -
                                    // a widget picked up near its bottom
                                    // edge stays held there the whole drag
                                    // instead of visibly snapping to be
                                    // centred on the finger the instant it
                                    // starts moving.
                                    drag.position.y - drag.draggingWidgetGrabOffsetY
                                } else {
                                    releaseY.value ?: animatedY.value
                                }
                                androidx.compose.ui.unit.IntOffset(0, y.toInt())
                            }
                            .size(
                                width = with(density) { (columns * cellWidthPx).toDp() },
                                height = with(density) { (WIDGET_RESERVED_ROWS * cellHeightPx).toDp() }
                            )
                            .graphicsLayer {
                                rotationZ = if (isDragging) 0f else angle
                                // Hidden once the drag has crossed onto a
                                // different page - its content can't live
                                // in two places at once (a real Android
                                // View, torn down/recreated if moved to a
                                // different page's own composition, not
                                // something that can just be redrawn
                                // elsewhere), so a plain ghost takes over in
                                // LauncherScreen's own overlay instead.
                                alpha = if (isDragging && currentPage() != pageIndex) 0f else 1f
                            }
                            // Keyed on the widget's own persisted position,
                            // not Unit - a pointerInput never restarts on
                            // its own once a key stays the same, so an
                            // unkeyed one keeps running the coroutine (and
                            // everything it captured - widget.topRow
                            // included) from the very first time this
                            // widget composed, forever. A widget moved once
                            // already would still compute its NEXT grab
                            // point off that stale, original topRow -
                            // landing the finger on the wrong point of the
                            // widget - instead of the one it actually has
                            // now.
                            .pointerInput(widget.appWidgetId, widget.page, widget.topRow) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { touch ->
                                        onEnterEditing()
                                        drag.draggingWidgetId = widget.appWidgetId
                                        drag.draggingWidgetOriginPage = pageIndex
                                        // touch is local to this Box, so its
                                        // own y is already exactly how far
                                        // below the widget's top edge the
                                        // finger landed - held onto for the
                                        // rest of the gesture so the render
                                        // above can keep the widget under
                                        // that same point instead of its
                                        // centre.
                                        drag.draggingWidgetGrabOffsetY = touch.y
                                        drag.position = Offset(
                                            touch.x,
                                            topPaddingPx + widget.topRow * cellHeightPx + touch.y
                                        )
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        onWidgetDragMoved(amount)
                                    },
                                    onDragEnd = {
                                        // Recomputed fresh here - reading
                                        // drag's own fields directly, not a
                                        // captured local - since this
                                        // closure is fixed for the life of
                                        // this pointerInput(Unit) and would
                                        // otherwise only ever see whatever
                                        // was true the first time it ran.
                                        val finalPage = currentPage()
                                        val destWidgets = if (finalPage == pageIndex) {
                                            pageWidgets
                                        } else {
                                            widgets.filter { it.page == finalPage }
                                        }
                                        val finalRow = previewWidgetRow(
                                            destWidgets, widget.appWidgetId,
                                            drag.position.y - drag.draggingWidgetGrabOffsetY,
                                            topPaddingPx, cellHeightPx, rows
                                        )
                                        // Set here, synchronously, before
                                        // isDragging flips false below - see
                                        // releaseY's own comment for why a
                                        // plain state write here (rather
                                        // than leaving it to the settle
                                        // LaunchedEffect's async snapTo
                                        // alone) is what avoids a one-frame
                                        // flash back to the wrong spot.
                                        releaseY.value = drag.position.y - drag.draggingWidgetGrabOffsetY
                                        drag.draggingWidgetId = null
                                        if (finalRow != null && (finalPage != widget.page || finalRow != widget.topRow)) {
                                            onWidgetMoved(widget.appWidgetId, finalPage, finalRow)
                                        }
                                    },
                                    onDragCancel = {
                                        drag.draggingWidgetId = null
                                    }
                                )
                            }
                    ) {
                        // Rounding this view's corners is unresolved - see
                        // the 2026-09-16 session notes. Every View-side
                        // clipping technique (ViewOutlineProvider, a
                        // wrapping FrameLayout's own outline, a raw canvas
                        // clipPath) failed silently - one attempt's debug
                        // fill color even painted across the ENTIRE window
                        // instead of just this view, meaning its content
                        // isn't drawn through the normal parent canvas at
                        // all. Forcing Modifier.graphicsLayer's
                        // CompositingStrategy.Offscreen DID round the
                        // corners, but broke the widget's actual content -
                        // it fell back to its own error/placeholder view -
                        // even after also forcing the widget onto a plain
                        // software layer first. Left unclipped (square
                        // corners) since a working widget matters more than
                        // rounded corners on a broken one.
                        androidx.compose.ui.viewinterop.AndroidView(
                            factory = { widget.view },
                            modifier = Modifier.fillMaxSize()
                        )
                        if (editing) {
                            // A plain sibling positioned over the
                            // AndroidView's own bounds (the way every icon
                            // tile's badge sits over its own Image) never
                            // received the tap - unlike an Image, the
                            // widget here is a real embedded Android View
                            // with its own native touch dispatch, which
                            // claims a touch landing within its bounds
                            // before Compose's own z-order hit-testing gets
                            // to arbitrate between it and a Compose sibling
                            // drawn on top of it. Nudging the badge to hang
                            // mostly outside the AndroidView's own
                            // rectangle - into the surrounding page
                            // background, where only Compose is hit-testing
                            // - sidesteps that entirely rather than
                            // fighting the interop view for the touch.
                            RemoveBadge(
                                onClick = { onRemoveWidget(widget.appWidgetId) },
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .offset(x = (-10).dp, y = (-10).dp)
                            )
                        }
                    }
                }
            }
        }

        items.forEachIndexed { slot, item ->
        // Wrapping each tile's whole body in key(item.id) - rather than
        // leaving Compose to associate state with a tile by its position in
        // this loop, the way it would by default - is what makes an app's
        // own animation state actually follow it when a drop shifts it to
        // a different slot. Without this, a push handed each pushed app's
        // Animatable off to whichever app now happens to land in its OLD
        // slot instead - both apps' icons would visibly swap positions for
        // a frame before correcting, since each one inherited a stranger's
        // in-flight animation instead of continuing its own.
        key(item.id) {
            // Deliberately NOT read during composition. Recomposing this
            // subtree while its own pointerInput has a live gesture running
            // detaches the handler and Compose cancels the drag - measured at
            // ~15ms after onDragStart, before any real movement. Reading it
            // inside a graphicsLayer lambda defers it to the draw phase, so
            // the tile hides without anything recomposing.
            val thisLocation = HomeLocation.Page(pageIndex, slot)

            fun targetOffset(): Offset {
                val bands = currentBands()
                val display = displacedSlot(
                    slot = slot,
                    items = items,
                    columns = columns,
                    pageIndex = pageIndex,
                    drag = drag,
                    currentPage = currentPage(),
                    cellWidthPx = cellWidthPx,
                    cellHeightPx = cellHeightPx,
                    topPaddingPx = topPaddingPx,
                    bands = bands
                )
                // Past the last real cell (a full page previewing a drop
                // that will push its last icon off entirely) wrapping to
                // row/column math would drop it to column 0 of a new row
                // below - visually pushing down, while the drop that
                // actually lands it plays a slide-right departure (see
                // SpillEvent) once committed. Sliding it one cell past the
                // last column of its own row instead keeps the live preview
                // and the actual departure animation showing the same
                // direction throughout the whole gesture.
                val capacity = pageCapacityFor(pageIndex, columns, rows, pageWidgets.size)
                if (display >= capacity) {
                    val lastRow = (capacity - 1) / columns
                    return Offset(
                        columns * cellWidthPx,
                        toGridY(topPaddingPx + lastRow * cellHeightPx, topPaddingPx, cellHeightPx, bands)
                    )
                }
                return Offset(
                    (display % columns) * cellWidthPx,
                    toGridY(topPaddingPx + (display / columns) * cellHeightPx, topPaddingPx, cellHeightPx, bands)
                )
            }

            // Slides to its displaced position over a short animation rather
            // than jumping straight there. Animating the pixel position
            // (not the slot index) means a move that wraps to a new row
            // slides diagonally, matching how this actually looks on iOS.
            // The Animatable's value is only ever read inside offset{},
            // deferred to the layout phase same as everything else about
            // this drag - animating it every frame invalidates just this
            // tile's layout, not a recomposition that could reach the
            // pager and cancel whichever tile's gesture is live.
            //
            // justDropped is plain composable state, not live drag state, so
            // reading it here carries none of the composition-time risk the
            // rest of this comment block is about.
            val dropped = justDropped
                ?.takeIf { it.itemId == item.id && it.location == thisLocation }

            // Seeded with the tile's plain, undisplaced position - except
            // for the item that was just released here, seeded instead at
            // wherever its drag ghost actually was (the ghost's own centre,
            // matching how the box below centres its own content). Without
            // that exception this tile's very first frame rendered at its
            // plain (i.e. final) position, one frame before the
            // LaunchedEffect below got a chance to run and snap it back to
            // the ghost's position to animate forward from - a flash at the
            // target immediately followed by a jump away from it, reported
            // as "the target location keeps flashing... before it pings to
            // the target location". Seeding it correctly up front here
            // removes that gap entirely for a genuinely new arrival.
            // basePosition/animatedOffset are unkeyed beyond key(item.id)
            // above, so this only runs once, the first time this app
            // appears on this page at all.
            val basePosition = remember {
                dropped?.let { it.fromPosition - Offset(cellWidthPx / 2f, cellHeightPx / 2f) }
                    ?: Offset(
                        (slot % columns) * cellWidthPx,
                        toGridY(topPaddingPx + (slot / columns) * cellHeightPx, topPaddingPx, cellHeightPx, currentBands())
                    )
            }
            val animatedOffset = remember { Animatable(basePosition, Offset.VectorConverter) }

            // The remember{} seed above only ever fires once ever, so it
            // can't catch a REORDER - dropping an app that was already on
            // this page, whose key(item.id) block (and Animatable) has
            // existed since long before this drop. That icon was hidden but
            // still being animated by the live hover preview's own
            // slot-based settle() math the whole time, never actually
            // tracking the real finger - so it reappeared from wherever
            // that discrete preview last placed it, not from the ghost
            // ("comes in from an angle I wasn't holding it"). This effect's
            // own snapTo below catches that case; for a new arrival it just
            // redundantly re-confirms the seed above (same value, no-op).
            //
            // Keyed on slot (see below for why) and on whether this tile
            // currently matches a drop - not on the drop's identity, so a
            // later, different drop landing here still triggers this again
            // even though the key(item.id) block is the same. Also keyed on
            // widgets (structural equality - a new topRow means a new,
            // unequal List<PlacedWidget>): targetOffset() closes over
            // currentBands(), which reads widgets as a plain parameter, not
            // live drag state, so without restarting here a COMMITTED
            // widget move (drag released, ViewModel state updated) left
            // every already-settled icon's own coroutine still computing
            // against the OLD arrangement forever.
            LaunchedEffect(slot, dropped != null, pageWidgets) {
                dropped?.let {
                    animatedOffset.snapTo(it.fromPosition - Offset(cellWidthPx / 2f, cellHeightPx / 2f))
                }
                // Keyed on slot too - targetOffset() is a plain local
                // function, closing over THIS composition's slot/item, not
                // a reactive read snapshotFlow can notice changing on its
                // own. Leaving slot out of this key meant an app pushed to
                // a new slot kept animating toward its OLD slot's position
                // forever, while whatever took over that old slot got its
                // own fresh effect there too - two different apps' tiles
                // both animating toward the same pixel position, rendered
                // exactly on top of each other. Restarting doesn't undo the
                // point of key(item.id) above - animatedOffset itself is
                // still unkeyed, so a restart (for either reason) just
                // resumes tracking from wherever it already was, not a
                // reset - except right after the snapTo above, which is the
                // one deliberate exception.
                snapshotFlow { targetOffset() }
                    .collectLatest { target ->
                        animatedOffset.animateTo(target, tween(REFLOW_ANIMATION_MS))
                    }
            }

            Box(
                Modifier
                    .size(cellWidth, cellHeight)
                    .offset {
                        val p = animatedOffset.value
                        androidx.compose.ui.unit.IntOffset(p.x.toInt(), p.y.toInt())
                    }
            ) {
                // Computed here, once, rather than inside HomeItemTile as
                // before - the remove badge needs to wobble as ONE rigid
                // body together with the icon, rotating around the same
                // shared centre, or it stayed visually pinned to this
                // cell's true top-left corner while the icon span underneath
                // it, drifting away from the icon's own rotated corner
                // instead of following it.
                val angle = rememberWobble(enabled = editing, seed = slot)
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            rotationZ = angle
                            // Hides the WHOLE tile - icon and remove badge
                            // together - while this is the drag's own
                            // origin, or while its expanded fold preview is
                            // showing instead. This used to sit on an inner
                            // Box wrapping only the icon, leaving the remove
                            // badge - a sibling outside it - fully visible
                            // and stuck at the origin cell the entire time a
                            // drag was in flight, instead of disappearing
                            // along with the icon it belongs to.
                            val isOrigin = drag.origin == thisLocation
                            val isExpanded = drag.folderArmed &&
                                drag.armedTarget == thisLocation
                            alpha = if (isOrigin || isExpanded) 0f else 1f
                        }
                ) {
                    // The armed-for-folder swell visual was removed here - it
                    // read drag state (hoverTarget/folderArmed) as a
                    // composition parameter, which recomposed this tile grid
                    // on every hover change and disrupted whichever tile's
                    // gesture was live, the same way the ghost's position
                    // once did. The dwell-to-fold behaviour itself is
                    // unaffected - it's gated at drop time in LauncherScreen,
                    // not here.
                    // Sized to just the icon+label footprint - touchTargetWidth/
                    // Height, computed above - not the full cell. The cell
                    // itself is much wider than the icon it holds (room for
                    // a comfortable grid at every column count), and this
                    // Box carries BOTH the tap-to-launch click below and the
                    // long-press-to-drag pointerInput after it. Both used to
                    // sit on the full cell instead, so what looked like
                    // blank page space between icons was actually still
                    // inside a neighbouring icon's own touch target -
                    // holding it either launched that icon or picked it up
                    // to drag, instead of falling through to the page
                    // background's own long-press (the Wallpaper/Widgets/
                    // Home settings menu) the way real blank space does -
                    // "tap and hold on a blank area... seems to select the
                    // nearest icon".
                    // The pointerInput here (not on a separate sibling Box)
                    // is deliberate - an earlier version put the long-press-
                    // drag detector on its own sibling Box exactly
                    // overlapping this one, and that silently broke plain
                    // taps everywhere on the page: two SIBLING pointerInput
                    // regions racing for the same touch isn't how this
                    // worked before (the old, working version had the drag
                    // detector on an ANCESTOR of HomeItemTile's own click,
                    // not a sibling of it), and Compose's gesture
                    // arbitration between overlapping siblings doesn't
                    // reliably let both fire. Keeping the same
                    // ancestor/descendant relationship the original full-
                    // cell version had - just at this smaller size - is
                    // what actually keeps both gestures working.
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .size(touchTargetWidth, touchTargetHeight)
                            .pointerInput(pageIndex, slot, item.id) {
                                detectDragGesturesAfterLongPress(
                                    // targetOffset() alone is this cell's
                                    // top-left corner - starting the ghost
                                    // there rather than where the finger
                                    // actually pressed was what made it pop
                                    // up to one side instead of centred
                                    // under the touch. touch is local to
                                    // THIS smaller, centred box, so its own
                                    // top-left corner - touchTargetInset
                                    // below - has to be added back in first
                                    // to land the same true point this used
                                    // to when the detector sat on the full
                                    // cell.
                                    onDragStart = { touch ->
                                        drag.start(
                                            item = item,
                                            origin = HomeLocation.Page(pageIndex, slot),
                                            startPosition = targetOffset() + touchTargetInset + touch
                                        )
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        onDragMoved(amount)
                                    },
                                    onDragEnd = onDragEnded,
                                    onDragCancel = onDragEnded
                                )
                            }
                    ) {
                        HomeItemTile(
                            item = item,
                            // Arranging blocks launching an app by accident,
                            // but a folder should still open - you can rearrange
                            // or pull things out of it the same way, its
                            // contents just wobble too, matching iOS.
                            onClick = {
                                if (item is HomeItem.FolderItem || !editing) onLaunch(item)
                            },
                            // Rotation now applied once, above, to this whole
                            // Box (icon and badge together) - HomeItemTile's
                            // own wobble would double-rotate it otherwise.
                            wobble = false,
                            iconSize = iconSize
                        )
                    }
                    if (editing) {
                        RemoveBadge(
                            onClick = { onRemove(slot) },
                            modifier = Modifier.align(Alignment.TopStart)
                        )
                    }
                }
            }
        } // key(item.id)
        }

        // The real data has already moved this item to the next page by the
        // time spillEvent arrives - this is a departure-only ghost, drawn on
        // top of the page it left, sliding off to the right the same way an
        // icon already slides aside when something is dropped between it and
        // its neighbour, then fading out. It plays once and reports back so
        // the event doesn't linger and replay on the next unrelated drop.
        if (spillEvent != null) {
            val capacity = pageCapacityFor(pageIndex, columns, rows, pageWidgets.size)
            val lastSlot = capacity - 1
            // Starts exactly where the live preview left the icon, NOT at
            // the last cell. (lastSlot % columns) is columns-1 - one whole
            // cell to the LEFT of the off-page position targetOffset() had
            // already animated it to during the hover, which is to say
            // back ON the page, in the last cell. Handing over there made
            // the icon visibly jump a cell backwards onto the page before
            // sliding off again: "for a flash it comes back on the page
            // before going back again".
            val ghostBase = remember(pageIndex, columns, cellWidthPx, cellHeightPx, topPaddingPx, pageWidgets) {
                Offset(
                    columns * cellWidthPx,
                    toGridY(topPaddingPx + (lastSlot / columns) * cellHeightPx, topPaddingPx, cellHeightPx, currentBands())
                )
            }
            val ghostOffset = remember(spillEvent) { Animatable(ghostBase, Offset.VectorConverter) }
            val ghostAlpha = remember(spillEvent) { Animatable(1f) }
            LaunchedEffect(spillEvent) {
                launch {
                    ghostOffset.animateTo(
                        ghostBase + Offset(cellWidthPx * 1.4f, 0f),
                        tween(REFLOW_ANIMATION_MS)
                    )
                }
                launch {
                    delay(REFLOW_ANIMATION_MS / 2L)
                    ghostAlpha.animateTo(0f, tween(REFLOW_ANIMATION_MS / 2))
                }
                delay(REFLOW_ANIMATION_MS.toLong())
                onSpillAnimationDone()
            }
            Box(
                Modifier
                    .size(cellWidth, cellHeight)
                    .offset {
                        val p = ghostOffset.value
                        androidx.compose.ui.unit.IntOffset(p.x.toInt(), p.y.toInt())
                    }
                    .graphicsLayer { alpha = ghostAlpha.value },
                contentAlignment = Alignment.Center
            ) {
                HomeItemTile(item = spillEvent.item, onClick = {}, iconSize = iconSize)
            }
        }
    }
}

/**
 * Where a tile should visually sit while a drag is (or isn't) hovering
 * nearby. A pure function of the current drag state, called fresh from a
 * layout lambda every frame - it must never be read during composition, for
 * the same reason everything else about this drag is read lazily.
 *
 * Not shown at all (folding instead) once the hover has dwelled long enough
 * to arm a folder merge, or while the drag is over the dock or the
 * uninstall zone rather than this grid, or on any page but the one the
 * pager is currently showing.
 */
private fun displacedSlot(
    slot: Int,
    items: List<HomeItem>,
    columns: Int,
    pageIndex: Int,
    drag: DragCoordinator,
    currentPage: Int,
    cellWidthPx: Float,
    cellHeightPx: Float,
    topPaddingPx: Float,
    bands: List<IntRange> = emptyList()
): Int {
    val previewing = drag.active && !drag.folderArmed &&
        !drag.overDock && !drag.overRemoveZone && currentPage == pageIndex
    if (!previewing) return slot

    val origin = drag.origin

    // Remapped once up front so every row calculation below - this
    // function's own, and pageFoldTarget/pageDropTarget's - can keep
    // treating [items] as the flat, band-free list it actually is (see
    // toDisplayY's own comment).
    val position = Offset(
        drag.position.x,
        toDisplayY(drag.position.y, topPaddingPx, cellHeightPx, bands)
    )

    return if (origin is HomeLocation.Page && origin.page == pageIndex) {
        // Still hovering the cell it was lifted from: leave the whole page
        // exactly as it was, so the gap sits open where the icon came from
        // until the finger actually carries it somewhere else. Without this
        // the neighbour slid into the vacated spot the instant the drag
        // began - the finger starts out centred on its own old cell, which
        // the maths below reads as hovering the icon that has just closed
        // that gap, so it "holds still" there (see settle) right on top of
        // where the lifted icon used to be.
        val column = (position.x / cellWidthPx).toInt().coerceIn(0, columns - 1)
        val row = ((position.y - topPaddingPx) / cellHeightPx).toInt().coerceAtLeast(0)
        if (row * columns + column == origin.slot) return slot

        // Whether this hover offers a fold is checked against the page's
        // real, unshifted layout (see pageFoldTarget) - not against the
        // shifted list the reflow below uses, which would identify the
        // wrong icon as the occupant of the cell the finger is actually
        // over.
        val foldSlot = pageFoldTarget(
            position, cellWidthPx, cellHeightPx, topPaddingPx, columns, items, origin.slot
        )

        // Carried elsewhere on this page: the item is conceptually already
        // gone from its old spot, and a gap opens at the hover point.
        val withoutDragged = if (slot > origin.slot) slot - 1 else slot
        val itemsWithoutDragged = items.filterIndexed { i, _ -> i != origin.slot }
        val target = pageDropTarget(
            position, cellWidthPx, cellHeightPx, topPaddingPx, columns, itemsWithoutDragged
        )

        // A fold candidate's own dwell zone (see FOLDER_ZONE_START/END) is
        // much wider than just "the half of its cell that would insert AT
        // it" - it also covers the half that would insert AFTER it. Freezing
        // the whole page (no reflow at all) for the ENTIRE zone, the way
        // this used to, meant that right half read as "maybe folding with
        // this icon" and suppressed the gap that should have opened one
        // slot further along - a real drop released there still inserted
        // correctly, but nothing ever visually moved to show it, and two
        // icons sitting either side of that overlap looked permanently
        // glued together with no way to land anything between them. Mapping
        // foldSlot into this same shifted space and comparing it against
        // the gap this drop would actually use narrows the freeze down to
        // only the sliver where they'd genuinely coincide (dropping ON the
        // candidate, or the dwell-to-fold zone's own left half) - anywhere
        // the gap lands one slot past the fold candidate, the icons after
        // it correctly slide aside the same as any other insert would.
        val foldSlotShifted = foldSlot?.let { if (it > origin.slot) it - 1 else it }
        if (foldSlotShifted != null && foldSlotShifted == target.gap) return slot

        settle(withoutDragged, target)
    } else {
        // Arriving from elsewhere (another page, the dock, a folder, or the
        // drawer): nothing has left this page, so a gap simply opens. No
        // shift/exclusion needed - this page's own list is already the
        // real, unshifted one, so pageDropTarget's own holdTarget already
        // agrees with pageFoldTarget here.
        val target = pageDropTarget(
            position, cellWidthPx, cellHeightPx, topPaddingPx, columns, items
        )
        // See the same-page branch's own comment above on why this only
        // freezes when the fold candidate and the actual insert gap
        // coincide, not for its entire (much wider) dwell zone.
        if (target.holdTarget != null && target.holdTarget == target.gap) {
            slot
        } else {
            settle(slot, target)
        }
    }
}

private fun settle(adjustedSlot: Int, target: PageDropTarget): Int = when {
    adjustedSlot == target.holdTarget -> adjustedSlot
    adjustedSlot >= target.gap -> adjustedSlot + 1
    else -> adjustedSlot
}

/**
 * Where a drag over a page's grid would land. [gap] is the insertion index
 * used to push other tiles aside - the same meaning it always had. But a
 * cell occupied by a folder can't mean only one thing: hovering near its
 * centre should offer dropping in (the folder holds still, [holdTarget] is
 * set), while hovering near either edge of that same cell should mean
 * inserting before or after it instead (the folder slides like anything
 * else would, [holdTarget] is null) - otherwise there is no way to place
 * anything next to a folder at all, since both interactions would
 * otherwise resolve to the exact same cell.
 *
 * Used identically for the live preview, for deciding when to arm the fold
 * timer, and for the actual drop - if these three computed the gap
 * differently, what got shown while dragging could disagree with what
 * actually happened on release.
 */
internal data class PageDropTarget(val gap: Int, val holdTarget: Int?)

internal fun pageDropTarget(
    position: Offset,
    cellWidthPx: Float,
    cellHeightPx: Float,
    topPaddingPx: Float,
    columns: Int,
    items: List<HomeItem>
): PageDropTarget {
    // position is the actual touch point (DragCoordinator seeds it there and
    // only ever moves it by the finger's own delta) - already a centre, not
    // a cell corner needing a half-cell correction to become one.
    val centre = position
    val column = (centre.x / cellWidthPx).toInt().coerceIn(0, columns - 1)
    val row = ((centre.y - topPaddingPx) / cellHeightPx).toInt().coerceAtLeast(0)
    val cellIndex = (row * columns + column).coerceIn(0, items.size)
    val occupant = items.getOrNull(cellIndex)

    if (occupant == null) return PageDropTarget(gap = cellIndex, holdTarget = null)

    // Any occupied cell - app or folder - is a place two different intents
    // land on the same spot: dwelling in its centre offers merging into a
    // folder (making one, if it's a plain app), but either edge needs to
    // mean "insert beside it" instead, or there would be no way to place
    // anything next to an existing icon without folding into it.
    val withinCell = ((centre.x - column * cellWidthPx) / cellWidthPx).coerceIn(0f, 1f)

    // Which side of this cell's own midpoint a plain (non-folding) insert
    // lands on - decoupled from the wider hold zone below. Tying gap to
    // FOLDER_ZONE_START/END meant the entire 60%-wide hold zone fell back
    // to "insert before" whenever a drop didn't dwell long enough to arm a
    // fold there - so a quick drop anywhere but the narrow rightmost sliver
    // of a cell landed before it, never after, regardless of which side it
    // visibly looked aimed at. Rounding to the nearest half instead means a
    // drop on the right half of an icon lands after it even without
    // dwelling, matching the dock's own fix for the same complaint.
    val gap = if (withinCell < 0.5f) cellIndex else cellIndex + 1
    val holdTarget = if (withinCell in FOLDER_ZONE_START..FOLDER_ZONE_END) cellIndex else null
    return PageDropTarget(gap = gap, holdTarget = holdTarget)
}

/**
 * Which slot (if any) is currently offering a fold, checked directly
 * against the page's real, unshifted layout - deliberately NOT
 * [pageDropTarget]'s own [PageDropTarget.holdTarget], which is computed
 * against a list with the drag's origin already conceptually removed (so
 * reflow math lines up for an actual insert). That's the wrong list for
 * deciding whether to fold, once a same-page drag no longer collapses its
 * own gap the instant it leaves - it treats whichever icon has shifted into
 * the origin's old spot as the occupant of the cell the finger is really
 * over, which by the time the finger reaches the true neighbour is a
 * different icon than the one visibly sitting there. Checked on-device
 * (logcat): this mismatch was what made a fold target's own identity flip
 * or vanish mid-hover instead of settling once the finger actually reached
 * it. [excludeSlot] is the drag's own origin slot on this page, if any - a
 * finger still over its own lifted icon's cell can't fold onto itself.
 */
internal fun pageFoldTarget(
    position: Offset,
    cellWidthPx: Float,
    cellHeightPx: Float,
    topPaddingPx: Float,
    columns: Int,
    items: List<HomeItem>,
    excludeSlot: Int?
): Int? {
    if (cellWidthPx <= 0f || cellHeightPx <= 0f) return null
    val column = (position.x / cellWidthPx).toInt().coerceIn(0, columns - 1)
    val row = ((position.y - topPaddingPx) / cellHeightPx).toInt().coerceAtLeast(0)
    val cellIndex = row * columns + column
    if (cellIndex == excludeSlot) return null
    items.getOrNull(cellIndex) ?: return null
    val withinCell = ((position.x - column * cellWidthPx) / cellWidthPx).coerceIn(0f, 1f)
    return if (withinCell in FOLDER_ZONE_START..FOLDER_ZONE_END) cellIndex else null
}

// Widened from an earlier 0.3/0.7. A real finger can't hold still to the
// pixel - natural tremor crossed that narrow a boundary often enough that
// dwelling to fold felt inconsistent, each crossing resetting the timer.
// Widened again from 0.2/0.8 - on-device logging of a real drag onto an
// adjacent icon (see FoldDebug) showed the raw finger position peaking at
// only ~13% into the neighbouring cell before the user, seeing the ghost
// (nearly a full cell wide itself) already visually overlapping the
// target, treated the drop as aimed and stopped pushing further right.
// The hold zone was requiring a deeper, more deliberate push than the
// natural "nudge onto your neighbour" gesture actually delivers, so folding
// two adjacent icons together almost never armed - it just inserted next
// to the target instead. 0.1 asks for less than a third of that.
// Pulled back in from 0.1/0.9 - that width left almost no edge room to
// signal "insert here, not fold" at all: since arming only needs the SAME
// target held for FOLDER_DWELL_MS (not the finger literally frozen), the
// ordinary few hundred ms it takes to carefully slide into position between
// two icons was often enough, on its own, to accumulate a full dwell before
// the finger ever left that 80%-wide zone - a fold armed as a side effect of
// aiming carefully, not because folding was actually intended. 0.2/0.8
// keeps most of the same forgiveness a real finger needs (see the note
// above this was widened for) while leaving a real edge margin a plain
// insert can still land in without brushing fold territory.
/**
 * A raw on-screen ("grid") y, which may fall inside or past one of several
 * independently-placed widget bands (each [WIDGET_RESERVED_ROWS] tall, at
 * its own topRow - see [widgetBands]), mapped down to the "display" y that
 * the flat, band-free [HomeItem] list every drop/fold/reflow calculation
 * already assumes it's laid out in - as if every band didn't exist and
 * every row below each one had simply shifted up to close its gap. Gaps
 * BETWEEN bands (unlike the bands themselves) are ordinary display rows -
 * icons flow through them same as anywhere else. A touch that lands ON a
 * band itself (can't happen through a widget's own pointerInput, but can
 * through a drag arriving from elsewhere passing over one) is clamped to
 * the last display row just above it.
 *
 * [bands] must be sorted ascending by start row and non-overlapping - the
 * caller's job (see widgetBands), not re-validated here.
 */
internal fun toDisplayY(y: Float, topPaddingPx: Float, cellHeightPx: Float, bands: List<IntRange>): Float {
    var shift = 0f
    for (band in bands) {
        val bandTopGridPx = topPaddingPx + band.first * cellHeightPx
        val bandHeightPx = (band.last - band.first + 1) * cellHeightPx
        if (y < bandTopGridPx) return y - shift
        if (y < bandTopGridPx + bandHeightPx) return bandTopGridPx - shift
        shift += bandHeightPx
    }
    return y - shift
}

/** The inverse of [toDisplayY] - a "display" y back to the real, on-screen
 *  grid y, opening up a gap for every widget band wherever it currently sits. */
internal fun toGridY(y: Float, topPaddingPx: Float, cellHeightPx: Float, bands: List<IntRange>): Float {
    var shift = 0f
    for (band in bands) {
        val bandTopGridPx = topPaddingPx + band.first * cellHeightPx
        val bandHeightPx = (band.last - band.first + 1) * cellHeightPx
        if (y < bandTopGridPx - shift) return y + shift
        shift += bandHeightPx
    }
    return y + shift
}

/**
 * Every widget's own band as a raw grid-row range, sorted ascending and
 * guaranteed non-overlapping - [overrideId]/[overrideTopRow] substitute one
 * widget's committed topRow with a live drag preview (see HomePage's own
 * widget block) without needing a second, parallel list built by hand at
 * every call site.
 */
internal fun widgetBands(
    widgets: List<PlacedWidget>,
    overrideId: Int? = null,
    overrideTopRow: Int? = null
): List<IntRange> = widgets
    .map { widget ->
        val topRow = if (widget.appWidgetId == overrideId) overrideTopRow ?: widget.topRow else widget.topRow
        topRow until (topRow + WIDGET_RESERVED_ROWS)
    }
    .sortedBy { it.first }

/**
 * Where [draggingId]'s widget would land if released right now, as a whole
 * row - null if the finger hasn't moved it anywhere yet, or if the nearest
 * row would overlap another widget's own (committed) band, in which case
 * the drag simply doesn't preview a move at all rather than landing
 * somewhere unintended. Shared by both the live reflow preview (read every
 * frame via snapshotFlow) and the actual drop (read once, live, from
 * DragCoordinator - see its own comment on why fields there rather than
 * separate remembered state) so the two can never disagree about where a
 * release actually lands.
 */
internal fun previewWidgetRow(
    widgetsOnPage: List<PlacedWidget>,
    draggingId: Int?,
    positionY: Float,
    topPaddingPx: Float,
    cellHeightPx: Float,
    rows: Int
): Int? {
    val maxRow = (rows - WIDGET_RESERVED_ROWS).coerceAtLeast(0)
    val candidateRow = ((positionY - topPaddingPx) / cellHeightPx)
        .roundToInt()
        .coerceIn(0, maxRow)
    val candidateRange = candidateRow until (candidateRow + WIDGET_RESERVED_ROWS)
    val collides = widgetsOnPage.any { other ->
        other.appWidgetId != draggingId &&
            candidateRange.first < other.topRow + WIDGET_RESERVED_ROWS &&
            other.topRow < candidateRange.last + 1
    }
    return if (collides) null else candidateRow
}

private const val FOLDER_ZONE_START = 0.2f
private const val FOLDER_ZONE_END = 0.8f

/** The small circled minus that takes an item off the home screen. */
@Composable
internal fun RemoveBadge(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(start = 18.dp, top = 2.dp)
            .size(22.dp)
            .clip(CircleShape)
            .background(Color(0xFF3A3A3C))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(width = 11.dp, height = 2.dp)
                .background(Color.White)
        )
    }
}

/**
 * The iOS jiggle: a small rotation oscillation, with each tile started at its
 * own point in the cycle so the grid doesn't move in lockstep.
 *
 * The transition is created unconditionally - remember calls have to happen
 * on every composition, so gating happens on the returned value, not the
 * call.
 */
@Composable
fun rememberWobble(enabled: Boolean, seed: Int): Float {
    val transition = rememberInfiniteTransition(label = "wobble")
    val angle by transition.animateFloat(
        initialValue = -WOBBLE_DEGREES,
        targetValue = WOBBLE_DEGREES,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = WOBBLE_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset((seed % 4) * (WOBBLE_PERIOD_MS / 4))
        ),
        label = "wobbleAngle"
    )
    return if (enabled) angle else 0f
}

private const val WOBBLE_DEGREES = 2.4f
private const val WOBBLE_PERIOD_MS = 220
internal const val REFLOW_ANIMATION_MS = 250
