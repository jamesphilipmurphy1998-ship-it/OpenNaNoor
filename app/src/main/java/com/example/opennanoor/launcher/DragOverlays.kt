/**
 * The two things drawn on top of everything during a drag: the tile that follows the finger, and the uninstall target it can be dropped on.
 * Both read DragCoordinator inside layout/draw lambdas rather than during composition - see DragCoordinator's own notes on why.
 */
package com.example.opennanoor.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp


/**
 * The floating tile that tracks the finger during a drag.
 *
 * Position and visibility are read inside layout and draw lambdas rather
 * than during composition. Reading them in composition made this screen -
 * and with it the tile whose gesture was in flight - recompose on every
 * movement, which Compose answered by cancelling the drag.
 */
@Composable
internal fun DragGhost(drag: DragCoordinator, pageIconSize: Dp, dockIconSize: Dp) {
    // Reading drag.overDock here, in a LaunchedEffect key, is the same
    // pattern the mini fold-preview already uses for drag.armedTarget -
    // safe because DragGhost is its own composable, entirely separate from
    // whichever tile deep in the pager or dock has the actual live gesture
    // running, so recomposing this one doesn't touch that tile's own
    // pointerInput the way reading drag state during a TILE's own
    // composition would.
    val animatedIconSize = remember { Animatable(pageIconSize.value) }
    LaunchedEffect(drag.overDock, pageIconSize, dockIconSize) {
        val target = if (drag.overDock) dockIconSize.value else pageIconSize.value
        animatedIconSize.animateTo(target, tween(GHOST_RESIZE_MS))
    }
    Box(
        Modifier
            // drag.position is now the actual touch point, not a tile's
            // corner - centre the ghost on it rather than anchoring its
            // own top-left there, or it would sit visibly down-and-right
            // of the finger by half its own size.
            .offset {
                val half = GHOST_SIZE.roundToPx() / 2
                IntOffset(drag.position.x.toInt() - half, drag.position.y.toInt() - half)
            }
            .size(GHOST_SIZE)
            .graphicsLayer {
                // Also hidden once a fold has armed - the ghost sits right
                // next to (or on top of) wherever the fold preview is
                // about to grow from, since arming only happens while
                // hovering close to that same spot. Left showing, it read
                // as "the icon itself expanding" - the preview growing in
                // right underneath a same-sized, already-familiar shape
                // rather than something new appearing at the folder.
                val shown = drag.item != null && !drag.folderArmed
                alpha = if (shown) 1f else 0f
                scaleX = GHOST_SCALE
                scaleY = GHOST_SCALE
            }
    ) {
        drag.item?.let {
            HomeItemTile(item = it, onClick = {}, iconSize = animatedIconSize.value.dp)
        }
    }
}

/** How long the ghost takes to resize once it crosses into/out of the dock. */
internal const val GHOST_RESIZE_MS = 150

@Composable
internal fun RemoveZone(highlighted: Boolean, onPositioned: (Rect) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (highlighted) Color(0xFFD32F2F) else Color(0xFFD32F2F).copy(alpha = 0.6f)
            )
            .padding(horizontal = 18.dp, vertical = 10.dp)
            .onGloballyPositioned { onPositioned(it.boundsInWindow()) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Delete, contentDescription = null, tint = Color.White)
        Spacer(Modifier.size(6.dp))
        Text("Uninstall", color = Color.White)
    }
}


