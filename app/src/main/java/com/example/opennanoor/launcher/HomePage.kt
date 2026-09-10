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

            val row = slot / columns
            val column = slot % columns
            val baseX = column * cellWidthPx
            val baseY = topPaddingPx + row * cellHeightPx

            Box(
                Modifier
                    .size(cellWidth, cellHeight)
                    .offset { androidx.compose.ui.unit.IntOffset(baseX.toInt(), baseY.toInt()) }
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
