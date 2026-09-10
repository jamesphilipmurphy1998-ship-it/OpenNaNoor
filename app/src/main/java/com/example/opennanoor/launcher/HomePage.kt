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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
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
    /** Set only on the page an icon just spilled off of - see [SpillEvent]. */
    spillEvent: SpillEvent? = null,
    onSpillAnimationDone: () -> Unit = {},
    /** Set only on the page an icon was just released onto - see [JustDropped]. */
    justDropped: JustDropped? = null,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val cellWidth = maxWidth / columns
        val cellHeight = (maxHeight - topPadding) / rows
        val cellWidthPx = with(density) { cellWidth.toPx() }
        val cellHeightPx = with(density) { cellHeight.toPx() }
        val topPaddingPx = with(density) { topPadding.toPx() }

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
                val display = displacedSlot(
                    slot = slot,
                    items = items,
                    columns = columns,
                    pageIndex = pageIndex,
                    drag = drag,
                    currentPage = currentPage(),
                    cellWidthPx = cellWidthPx,
                    cellHeightPx = cellHeightPx,
                    topPaddingPx = topPaddingPx
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
                val capacity = columns * rows
                if (display >= capacity) {
                    val lastRow = (capacity - 1) / columns
                    return Offset(
                        columns * cellWidthPx,
                        topPaddingPx + lastRow * cellHeightPx
                    )
                }
                return Offset(
                    (display % columns) * cellWidthPx,
                    topPaddingPx + (display / columns) * cellHeightPx
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
            // Seeded with the tile's plain, undisplaced position - never
            // reads drag state inside this remember{} initializer, which
            // runs during composition. basePosition/animatedOffset are
            // unkeyed beyond key(item.id) above, so this only runs once,
            // the first time this app appears on this page at all.
            val basePosition = remember {
                Offset((slot % columns) * cellWidthPx, topPaddingPx + (slot / columns) * cellHeightPx)
            }
            val animatedOffset = remember { Animatable(basePosition, Offset.VectorConverter) }

            // The item that was just released here snaps to wherever its
            // drag ghost actually was (the ghost's own centre, matching how
            // the box below centres its own content) before resuming normal
            // tracking - justDropped is plain composable state, not live
            // drag state, so reading it here carries none of the
            // composition-time risk the seeding above is about.
            //
            // This can't be handled by seeding basePosition once at first
            // composition the way SpillEvent's ghost is: a REORDER drops an
            // app that was already on this page, whose key(item.id) block
            // (and Animatable) has existed since long before this drop - it
            // was never a fresh composition remember{} could catch. Without
            // this, only a genuinely new arrival (from the drawer, another
            // page, the dock) got seeded correctly; a same-page reorder's
            // origin tile - hidden but still being animated by the live
            // hover preview's own slot-based settle() math the whole time,
            // never actually tracking the real finger position - just
            // reappeared from wherever that discrete preview last placed
            // it, not from the ghost, which is what "comes in from an angle
            // I wasn't holding it" turned out to mean.
            //
            // Keyed on slot (see below for why) and on whether this tile
            // currently matches a drop - not on the drop's identity, so a
            // later, different drop landing here still triggers this again
            // even though the key(item.id) block is the same.
            val dropped = justDropped
                ?.takeIf { it.itemId == item.id && it.location == thisLocation }
            LaunchedEffect(slot, dropped != null) {
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
                    // Keyed on this cell's identity plus what's actually in
                    // it. Position alone was wrong: once a fold or a drop
                    // elsewhere reshuffled which item sits at this slot, the
                    // still-running coroutine from before that reshuffle kept
                    // using its original, now-stale item - so picking up the
                    // new occupant showed the ghost of whatever used to be
                    // there and never resolved as a real drag of the new one.
                    // Keying on `editing` (or the whole item list) was ALSO
                    // wrong the other way - tore the detector down and
                    // restarted it the instant a drag began, since editing
                    // flips true inside onDragStart, killing the drag before
                    // a single move event landed. item.id changes only
                    // between gestures, never mid-one, so it avoids both.
                    .pointerInput(pageIndex, slot, item.id) {
                        detectDragGesturesAfterLongPress(
                            // targetOffset() alone is this cell's top-left
                            // corner - starting the ghost there rather than
                            // where the finger actually pressed within the
                            // cell was what made it pop up to one side
                            // instead of centred under the touch. Adding the
                            // local touch point (onDragStart's own offset)
                            // gives the drag its true starting position in
                            // the shared frame every other calculation here
                            // already assumes it's in.
                            onDragStart = { touch ->
                                drag.start(
                                    item = item,
                                    origin = HomeLocation.Page(pageIndex, slot),
                                    startPosition = targetOffset() + touch
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
                // The armed-for-folder swell visual was removed here - it
                // read drag state (hoverTarget/folderArmed) as a composition
                // parameter, which recomposed this tile grid on every hover
                // change and disrupted whichever tile's gesture was live, the
                // same way the ghost's position once did. The dwell-to-fold
                // behaviour itself is unaffected - it's gated at drop time in
                // LauncherScreen, not here.
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val isOrigin = drag.origin == thisLocation
                            // Hidden while its own expanded preview shows -
                            // whether it's an existing folder, or a plain
                            // app about to become one (the preview now
                            // covers both).
                            val isExpanded = drag.folderArmed &&
                                drag.hoverTarget == thisLocation
                            alpha = if (isOrigin || isExpanded) 0f else 1f
                        },
                    contentAlignment = Alignment.Center
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
                        wobble = editing,
                        wobbleSeed = slot
                    )
                }
                if (editing) {
                    RemoveBadge(
                        onClick = { onRemove(slot) },
                        modifier = Modifier.align(Alignment.TopStart)
                    )
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
            val capacity = columns * rows
            val lastSlot = capacity - 1
            // Starts exactly where the live preview left the icon, NOT at
            // the last cell. (lastSlot % columns) is columns-1 - one whole
            // cell to the LEFT of the off-page position targetOffset() had
            // already animated it to during the hover, which is to say
            // back ON the page, in the last cell. Handing over there made
            // the icon visibly jump a cell backwards onto the page before
            // sliding off again: "for a flash it comes back on the page
            // before going back again".
            val ghostBase = remember(pageIndex, columns, cellWidthPx, cellHeightPx, topPaddingPx) {
                Offset(
                    columns * cellWidthPx,
                    topPaddingPx + (lastSlot / columns) * cellHeightPx
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
                HomeItemTile(item = spillEvent.item, onClick = {})
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
    topPaddingPx: Float
): Int {
    val previewing = drag.active && !drag.folderArmed &&
        !drag.overDock && !drag.overRemoveZone && currentPage == pageIndex
    if (!previewing) return slot

    val origin = drag.origin

    return if (origin is HomeLocation.Page && origin.page == pageIndex) {
        // Still hovering the cell it was lifted from: leave the whole page
        // exactly as it was, so the gap sits open where the icon came from
        // until the finger actually carries it somewhere else. Without this
        // the neighbour slid into the vacated spot the instant the drag
        // began - the finger starts out centred on its own old cell, which
        // the maths below reads as hovering the icon that has just closed
        // that gap, so it "holds still" there (see settle) right on top of
        // where the lifted icon used to be.
        val column = (drag.position.x / cellWidthPx).toInt().coerceIn(0, columns - 1)
        val row = ((drag.position.y - topPaddingPx) / cellHeightPx).toInt().coerceAtLeast(0)
        if (row * columns + column == origin.slot) return slot

        // Carried elsewhere on this page: the item is conceptually already
        // gone from its old spot, and a gap opens at the hover point.
        val withoutDragged = if (slot > origin.slot) slot - 1 else slot
        val itemsWithoutDragged = items.filterIndexed { i, _ -> i != origin.slot }
        val target = pageDropTarget(
            drag.position, cellWidthPx, cellHeightPx, topPaddingPx, columns, itemsWithoutDragged
        )
        settle(withoutDragged, target)
    } else {
        // Arriving from elsewhere (another page, the dock, a folder, or the
        // drawer): nothing has left this page, so a gap simply opens.
        val target = pageDropTarget(
            drag.position, cellWidthPx, cellHeightPx, topPaddingPx, columns, items
        )
        settle(slot, target)
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

// Widened from an earlier 0.3/0.7. A real finger can't hold still to the
// pixel - natural tremor crossed that narrow a boundary often enough that
// dwelling to fold felt inconsistent, each crossing resetting the timer.
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
