/**
 * The dock along the bottom, its drop-target maths, and the page dots above it.
 */
package com.example.opennanoor.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp


@Composable
internal fun Dock(
    items: List<HomeItem>,
    drag: DragCoordinator,
    editing: Boolean,
    onTap: (HomeItem) -> Unit,
    onEnterEditing: () -> Unit,
    onDragMoved: (Offset) -> Unit,
    onDragEnded: () -> Unit,
    onPositioned: (Rect) -> Unit,
    // To seed a drag at the right spot in the shared page/dock coordinate
    // frame (see DragCoordinator), a dock icon needs its own absolute
    // position translated into that frame the same way dockBounds already
    // is - this is that same translation, handed down so each icon can do
    // it for itself at the moment its drag starts.
    outerOrigin: Offset,
    justDropped: JustDropped? = null,
    modifier: Modifier = Modifier
) {
    // Tracks however many apps are actually in the dock right now - no
    // separate capacity setting any more, so removing an icon shrinks
    // straight to that count's own size and adding one back grows it again.
    val iconSize = dockIconSize(items.size)
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier
            .height(DOCK_AREA_HEIGHT - 8.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Color.White.copy(alpha = 0.12f))
            // Vertical padding was exactly 10dp+10dp, leaving this Box's own
            // inner maxHeight at precisely 68dp - equal to iconPx at its
            // largest (PAGE_4_ICON_SIZE), with zero slack. HomeItemTile's
            // own Column then adds its own 2dp+2dp vertical padding on top
            // of THAT, which had nowhere to go - the icon Image itself,
            // sized to exactly 68dp, got coerced down to fit the 64dp
            // actually left over, silently rendering smaller than its own
            // requested size. Freeing a few dp here (not the dock block's
            // own outer height, DOCK_AREA_HEIGHT, which stays untouched)
            // gives that padding room to exist without shrinking the icon.
            .padding(vertical = 6.dp, horizontal = 8.dp)
    ) {
        // This Box's own on-screen position, translated into the same frame
        // dockBounds uses - needed locally too so the live push-preview
        // below can turn the shared-frame drag.position into an x offset
        // from the dock's own left edge, the same conversion dockBounds
        // itself goes through one layer up.
        var localOrigin by remember { mutableStateOf(Offset.Zero) }

        // Icons are a fixed size now (see dockIconSize), and spaced by how
        // many are actually in the dock right now - not the capacity the
        // count setting allows - so 2 sit centred and evenly spaced same as
        // 3, 4 or 5 do, each narrower gap only as more icons are actually
        // added. Splitting the available width evenly into slotCount cells
        // instead, regardless of fill, could make a cell narrower than the
        // icon inside it - a centred icon wider than its own cell spills
        // out of it, and the outermost icons had nowhere to spill into but
        // past the dock's own edge.
        val iconPx = with(density) { iconSize.toPx() }
        val availablePx = with(density) { maxWidth.toPx() }
        // The dock's own inner horizontal padding (see this Box's modifier
        // below) PLUS the outer margin the whole dock block sits at from
        // the actual screen edge (see the Dock() call site's own Modifier)
        // - both eat into the gap between the true screen edge and where
        // this Box's own maxWidth starts, so both have to be added back to
        // reconstruct the page's true, unpadded width below. Missing the
        // outer one was why the dock's 4 icons still sat visibly inset
        // from the page's own 4 columns even after this same fix's first
        // pass.
        val dockPaddingPx = with(density) { (8.dp + 8.dp).toPx() }

        // gapPx for spacing count icons evenly, including the margin
        // before the first and after the last - count+1 equal gaps around
        // count icons is what centres them.
        //
        // 4 is a special case: rather than those same n+1 equal gaps (which
        // for 4 icons leaves noticeably more empty margin at each edge than
        // the page grid's own 4-column layout has, since a page column's
        // width comes from splitting the available width into 4 EQUAL
        // cells, not gaps sized around a fixed icon width), 4 icons are
        // pitched at availablePx/4 - one per column-width slot, each icon
        // centred within its own slot the same way a page's 4-column grid
        // centres its own tiles. Icon size itself (dockIconSize) is
        // unaffected - only how close to the edge the outer two icons sit.
        fun packing(count: Int): Pair<Float, Float> {
            if (count == 4) {
                // The page grid has no side margin of its own at all - a
                // column's width is the full screen width split 4 ways.
                // Splitting only availablePx (already inside the dock's
                // own 8dp side padding) 4 ways was still leaving noticeably
                // more edge margin than the page grid has, since it never
                // accounted for that padding the dock itself already ate.
                // Reconstructing the true, un-padded width first and
                // dividing THAT by 4 lines the dock's 4 icons up with
                // where a 4-column page's own tiles actually sit, then
                // dockPaddingPx is subtracted back off since gap is
                // measured from the dock's own (padded) left edge, not the
                // true screen edge.
                val trueWidth = availablePx + 2 * dockPaddingPx
                val cellWidth = trueWidth / 4f
                val gap = ((cellWidth - iconPx) / 2f - dockPaddingPx).coerceAtLeast(0f)
                return gap to cellWidth
            }
            if (count == 5) {
                // Not aiming for column alignment here (5 doesn't map to
                // any page layout column count the way 4 does) - just the
                // same "split into count equal cells, centre each icon in
                // its own cell" idea as 4 above, applied to the dock's own
                // available width rather than a reconstructed page width.
                // The old n+1-equal-gaps formula below gives 5 icons the
                // same margin at the edges as between each other; splitting
                // into cells instead gives a smaller edge margin (half a
                // cell's leftover space) than the gap between icons,
                // pushing the two outer icons closer to the dock's own
                // edges.
                val cellWidth = availablePx / 5f
                val gap = ((cellWidth - iconPx) / 2f).coerceAtLeast(0f)
                return gap to cellWidth
            }
            val n = count.coerceAtLeast(1)
            val gap = ((availablePx - iconPx * n) / (n + 1)).coerceAtLeast(0f)
            return gap to (iconPx + gap)
        }

        val (restGapPx, restPitchPx) = packing(items.size)

        // While a drag is hovering, the spacing itself previews squeezing
        // to make room: one more icon than are here now if this is an
        // arrival from outside (the drawer, a page) - reordering within the
        // dock doesn't change how many are here, just which order. An icon
        // that started in the dock and has been carried elsewhere (not
        // hovering the dock any more) is conceptually already gone the
        // instant it lifts off, not only once the drop actually lands -
        // the remaining icons close its gap and recentre right away, the
        // same way lifting an icon off a real dock behaves, rather than
        // leaving a hole there until the drag finishes somewhere else
        // entirely.
        // A full dock is going to refuse this drop outright (see insertItem,
        // and the ping-back in handleDragEnded) - so it shouldn't spend the
        // hover pretending otherwise, squeezing its icons aside to open a
        // gap that nothing can ever land in. Mirrors the same condition the
        // rejection itself uses: an icon dragged FROM the dock always has
        // room, since its own slot frees up first.
        fun dockWouldRefuse(): Boolean =
            drag.active &&
                drag.origin !is HomeLocation.Dock &&
                items.size >= DOCK_MAX_SIZE

        fun previewCount(): Int {
            if (!drag.active) return items.size
            val origin = drag.origin
            return when {
                origin is HomeLocation.Dock && !drag.overDock -> items.size - 1
                origin is HomeLocation.Dock -> items.size
                drag.overDock && !dockWouldRefuse() -> items.size + 1
                else -> items.size
            }
        }

        fun displacedSlot(slot: Int): Int {
            if (!drag.active) return slot
            if (dockWouldRefuse()) return slot
            val origin = drag.origin
            if (origin is HomeLocation.Dock) {
                // Still hovering the icon it was lifted from: hold the whole
                // dock still, leaving the gap open where it came from, until
                // the finger actually carries it somewhere else. Same reason
                // as HomePage's own version of this - the finger starts out
                // over its own old slot, which otherwise reads as hovering
                // whichever icon has just slid in to close that gap.
                if (drag.overDock) {
                    val (restGap, restPitch) = packing(items.size)
                    val hovered = dockDropTarget(
                        drag.position.x - localOrigin.x - restGap, restPitch, items.size - 1
                    )
                    if (hovered == origin.slot) return slot
                }
                val withoutDragged = if (slot > origin.slot) slot - 1 else slot
                if (!drag.overDock) return withoutDragged
                val (gapPx, pitchPx) = packing(previewCount())
                val localX = drag.position.x - localOrigin.x - gapPx
                val gap = dockDropTarget(localX, pitchPx, items.size - 1)
                return if (withoutDragged >= gap) withoutDragged + 1 else withoutDragged
            }
            if (!drag.overDock) return slot
            val (gapPx, pitchPx) = packing(previewCount())
            val localX = drag.position.x - localOrigin.x - gapPx
            val gap = dockDropTarget(localX, pitchPx, items.size)
            return if (slot >= gap) slot + 1 else slot
        }

        fun targetX(slot: Int): Float {
            val display = displacedSlot(slot)
            val (gapPx, pitchPx) = if (drag.active) packing(previewCount()) else restGapPx to restPitchPx
            return gapPx + display * pitchPx
        }

        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned {
                    localOrigin = it.positionInWindow() - outerOrigin
                    onPositioned(it.boundsInWindow())
                }
        )

        items.forEachIndexed { slot, item ->
        // Wrapping each icon's whole body in key(item.id), same as
        // HomePage's tiles - see there for why. Without it, a drop
        // shifting where an icon sits handed its Animatable off to
        // whichever icon now happens to land at its OLD index instead of
        // following the icon it actually belonged to, visibly swapping the
        // two icons' positions for a frame.
        key(item.id) {
            val thisLocation = HomeLocation.Dock(slot)
            val isDragOrigin = drag.active && drag.origin == thisLocation

            // justDropped is plain composable state, safe to read at
            // composition time.
            val dropped = justDropped
                ?.takeIf { it.itemId == item.id && it.location == thisLocation }

            // Seeded with the plain resting position - except for the icon
            // that was just released here, seeded instead at wherever its
            // drag ghost actually was. fromPosition is in the shared outer
            // frame the ghost is drawn in, but this icon's own x is local
            // to the dock's left edge (localOrigin is that same translation
            // dockBounds itself uses), and the ghost is centred on
            // fromPosition while this icon is a fixed iconPx-wide box
            // positioned by its left edge - both corrections are what
            // HomePage's version does in one step for a page tile, which
            // already lives in that outer frame the way a dock icon
            // doesn't. Without this seed, a genuinely new arrival's very
            // first frame rendered at its plain (final) position, one frame
            // before the LaunchedEffect below got a chance to snap it back
            // to the ghost's position and animate forward - a flash at the
            // target immediately followed by a jump away from it. unkeyed
            // beyond key(item.id) above, so this only ever runs once, the
            // first time this app appears in the dock at all.
            val basePosition = remember {
                dropped?.let { Offset(it.fromPosition.x - localOrigin.x - iconPx / 2f, 0f) }
                    ?: Offset(restGapPx + slot * restPitchPx, 0f)
            }
            val animatedOffset = remember { Animatable(basePosition, Offset.VectorConverter) }

            // The remember{} seed above only ever fires once ever, so it
            // can't catch a REORDER: dropping an icon that was already in
            // the dock, whose key(item.id) block has existed since before
            // this drop. That icon was hidden but still being animated by
            // the live preview's own slot-based math the whole time, never
            // actually tracking the real finger - so it reappeared from
            // wherever that left it, not from the ghost. This effect's own
            // snapTo below catches that case; for a new arrival it just
            // redundantly re-confirms the seed above (same value, no-op).
            // Keyed on slot, items.size, and now whether this tile currently
            // matches a drop (not the drop's identity, so a later different
            // drop landing here still retriggers this even though the
            // key(item.id) block is the same). slot and items.size stay for
            // the reasons explained the first time this was fixed: targetX
            // (slot) is a plain local function whose closure snapshotFlow
            // alone won't refresh, and restGapPx/restPitchPx are plain
            // captured vals, not Compose state, so a collector already
            // running doesn't notice either changing on its own. Restarting
            // doesn't reset animatedOffset itself (unkeyed, kept alive by
            // key(item.id) above), so this only resumes tracking from
            // wherever it already was, not a fresh jump - except right
            // after the snapTo below, the one deliberate exception.
            LaunchedEffect(slot, items.size, dropped != null) {
                dropped?.let {
                    animatedOffset.snapTo(Offset(it.fromPosition.x - localOrigin.x - iconPx / 2f, 0f))
                }
                androidx.compose.runtime.snapshotFlow { targetX(slot) }
                    .collectLatest { x ->
                        animatedOffset.animateTo(Offset(x, 0f), tween(REFLOW_ANIMATION_MS))
                    }
            }

            // This icon's own on-screen position, captured on every layout
            // pass and translated into the same frame dockBounds uses, so a
            // drag starting here lands the ghost at the actual touch point
            // instead of way up at the top of the screen - the local offset
            // onDragStart receives is only a few dp within this one icon,
            // not a position in the shared frame the ghost is drawn in.
            var iconOrigin by remember { mutableStateOf(Offset.Zero) }

            // Kept composed while dragging - see HomePage - and merely
            // made invisible, so the gesture handler survives.
            Box(
                Modifier
                    .size(with(density) { iconPx.toDp() }, maxHeight)
                    .offset {
                        val p = animatedOffset.value
                        IntOffset(p.x.toInt(), p.y.toInt())
                    }
                    .onGloballyPositioned {
                        iconOrigin = it.positionInWindow() - outerOrigin
                    }
                    // Same fix as HomePage's tiles - item.id keeps this
                    // bound to what's actually in the slot, not just its
                    // position, without restarting mid-gesture.
                    .pointerInput(slot, item.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { touch ->
                                drag.start(
                                    item = item,
                                    origin = thisLocation,
                                    startPosition = iconOrigin + touch
                                )
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                onDragMoved(amount)
                            },
                            onDragEnd = onDragEnded,
                            onDragCancel = onDragEnded
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                HomeItemTile(
                    item = item,
                    onClick = {
                        if (item is HomeItem.FolderItem || !editing) onTap(item)
                    },
                    showLabel = false,
                    wobble = editing,
                    iconSize = iconSize,
                    modifier = Modifier.alpha(if (isDragOrigin) 0f else 1f)
                )
            }
        } // key(item.id)
        }
    }
}

/**
 * Where a drag over the dock would land - a plain insertion index among
 * [itemCount] existing icons. Unlike a page's grid the dock never merges
 * into a folder, so there is no hold-to-fold zone to carve out of this the
 * way [pageDropTarget] has to.
 */
// Rounds to the nearest slot boundary rather than flooring to the one an
// icon's own left edge sits on - flooring meant hovering ANYWHERE within an
// icon's cell, including its whole right half, still resolved to "insert
// before this icon": there was no way to land a drop after an icon at all
// without dragging past it into the following icon's own cell. Adding half
// a pitch before dividing is the standard nearest-boundary rounding this
// needs - past an icon's midpoint counts as "after it" the way it visibly
// looks like it should.
internal fun dockDropTarget(localX: Float, cellWidthPx: Float, itemCount: Int): Int =
    if (cellWidthPx <= 0f) 0 else ((localX + cellWidthPx / 2f) / cellWidthPx).toInt().coerceIn(0, itemCount)

@Composable
internal fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    if (count <= 1) return
    Row(modifier = modifier, horizontalArrangement = Arrangement.Center) {
        repeat(count) { index ->
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (index == current) 0.95f else 0.35f))
            )
        }
    }
}


