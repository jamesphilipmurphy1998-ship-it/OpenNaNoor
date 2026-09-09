package com.example.opennanoor.launcher

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * One page of the home screen.
 *
 * Tiles are placed arithmetically on a fixed grid rather than in a lazy grid,
 * because dragging needs to map a finger position to a slot index. With a
 * uniform cell size that is just division.
 */
@Composable
fun HomePage(
    entries: List<LauncherEntry>,
    columns: Int,
    rows: Int,
    editing: Boolean,
    topPadding: Dp,
    onLaunch: (LaunchableApp) -> Unit,
    onEnterEditing: () -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val cellWidth = maxWidth / columns
        val cellHeight = (maxHeight - topPadding) / rows

        val cellWidthPx = with(density) { cellWidth.toPx() }
        val cellHeightPx = with(density) { cellHeight.toPx() }
        val topPaddingPx = with(density) { topPadding.toPx() }

        var draggingSlot by remember { mutableIntStateOf(-1) }
        var dragOffset by remember { mutableStateOf(Offset.Zero) }

        entries.forEachIndexed { slot, entry ->
            val row = slot / columns
            val column = slot % columns
            val isDragging = slot == draggingSlot

            val baseX = column * cellWidthPx
            val baseY = topPaddingPx + row * cellHeightPx

            Box(
                Modifier
                    .size(cellWidth, cellHeight)
                    .offset {
                        val extra = if (isDragging) dragOffset else Offset.Zero
                        IntOffset(
                            (baseX + extra.x).roundToInt(),
                            (baseY + extra.y).roundToInt()
                        )
                    }
                    // The dragged tile floats above its neighbours.
                    .graphicsLayer { if (isDragging) { scaleX = 1.12f; scaleY = 1.12f } }
                    .pointerInput(entries, editing, slot) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                onEnterEditing()
                                draggingSlot = slot
                                dragOffset = Offset.Zero
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount
                            },
                            onDragEnd = {
                                val centreX = baseX + dragOffset.x + cellWidthPx / 2f
                                val centreY = baseY + dragOffset.y + cellHeightPx / 2f
                                val targetColumn = (centreX / cellWidthPx).toInt()
                                    .coerceIn(0, columns - 1)
                                val targetRow = ((centreY - topPaddingPx) / cellHeightPx)
                                    .toInt().coerceAtLeast(0)

                                onMove(slot, targetRow * columns + targetColumn)
                                draggingSlot = -1
                                dragOffset = Offset.Zero
                            },
                            onDragCancel = {
                                draggingSlot = -1
                                dragOffset = Offset.Zero
                            }
                        )
                    }
            ) {
                AppTile(
                    entry = entry,
                    onClick = { if (!editing) onLaunch(entry.app) },
                    wobble = editing && !isDragging,
                    wobbleSeed = slot
                )
            }
        }
    }
}

/**
 * The iOS jiggle: a small rotation oscillation, with each tile started at its
 * own point in the cycle so the grid doesn't move in lockstep.
 *
 * The transition is created unconditionally - remember calls have to happen on
 * every composition, so gating happens on the returned value, not the call.
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
