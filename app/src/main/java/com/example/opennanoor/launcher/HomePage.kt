package com.example.opennanoor.launcher

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
            // Deliberately NOT read during composition. Recomposing this
            // subtree while its own pointerInput has a live gesture running
            // detaches the handler and Compose cancels the drag - measured at
            // ~15ms after onDragStart, before any real movement. Reading it
            // inside a graphicsLayer lambda defers it to the draw phase, so
            // the tile hides without anything recomposing.
            val thisLocation = HomeLocation.Page(pageIndex, slot)

            val baseX = (slot % columns) * cellWidthPx
            val baseY = topPaddingPx + (slot / columns) * cellHeightPx

            Box(
                Modifier
                    .size(cellWidth, cellHeight)
                    // Live displacement: while a drag hovers over this page
                    // without dwelling long enough to fold, every tile from
                    // the hover point onward previews where it would land by
                    // sliding to that slot - the same thing iOS does. Purely
                    // a read inside the layout lambda, computed fresh each
                    // frame from the drag's current position; nothing here
                    // is a composition-time value, so nothing recomposes.
                    .offset {
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
                        val x = (display % columns) * cellWidthPx
                        val y = topPaddingPx + (display / columns) * cellHeightPx
                        androidx.compose.ui.unit.IntOffset(x.toInt(), y.toInt())
                    }
                    // Keyed only on this cell's identity. Keying on `editing`
                    // or on the page's item list tore the gesture detector down
                    // and restarted it the instant a drag began - editing flips
                    // true inside onDragStart - which killed the drag before a
                    // single move event landed.
                    .pointerInput(pageIndex, slot) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                drag.start(
                                    item = item,
                                    origin = HomeLocation.Page(pageIndex, slot),
                                    startPosition = Offset(baseX, baseY)
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
                            alpha = if (drag.origin == thisLocation) 0f else 1f
                        },
                    contentAlignment = Alignment.Center
                ) {
                    HomeItemTile(
                        item = item,
                        onClick = { if (!editing) onLaunch(item) },
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

    val hovered = slotAt(drag.position, cellWidthPx, cellHeightPx, topPaddingPx, columns)
    val origin = drag.origin

    return if (origin is HomeLocation.Page && origin.page == pageIndex) {
        // Dragging within this page: the item is conceptually already gone
        // from its old spot, and a gap opens at the hover point.
        val withoutDragged = if (slot > origin.slot) slot - 1 else slot
        val capacity = (items.size - 1).coerceAtLeast(0)
        val gap = hovered.coerceIn(0, capacity)
        val occupantAtGap = items.withIndex()
            .filter { it.index != origin.slot }
            .getOrNull(gap)?.value
        settle(withoutDragged, gap, occupantAtGap)
    } else {
        // Arriving from elsewhere (another page, the dock, a folder, or the
        // drawer): nothing has left this page, so a gap simply opens.
        val gap = hovered.coerceIn(0, items.size)
        settle(slot, gap, items.getOrNull(gap))
    }
}

/**
 * A folder sitting exactly at the gap a drag is hovering over holds its
 * position rather than sliding aside - hovering directly on it means
 * "drop in", not "insert before or after", so it should look like a
 * landing target, not something in the way. Anything else at or past the
 * gap slides over as usual.
 */
private fun settle(adjustedSlot: Int, gap: Int, occupant: HomeItem?): Int = when {
    adjustedSlot == gap && occupant is HomeItem.FolderItem -> adjustedSlot
    adjustedSlot >= gap -> adjustedSlot + 1
    else -> adjustedSlot
}

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
