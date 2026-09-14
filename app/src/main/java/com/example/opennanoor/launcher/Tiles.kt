package com.example.opennanoor.launcher

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap

/** Pixel size icons are rasterised at - generous so they stay sharp. */
internal const val ICON_PX = 192

/** The one and only icon size, everywhere - pages, the dock, the drawer. */
internal val DEFAULT_ICON_SIZE = 56.dp

/**
 * Icon size for a dock holding [count] icons. 4 is its own distinct, larger
 * size (DOCK_4_ICON_SIZE) rather than sharing DEFAULT_ICON_SIZE with 5 - 5
 * is the baseline every other count is judged against, 4 stands out as
 * bigger still since it's the sparsest the dock ever gets. 6 is the other
 * way, scaled down from the 5-icon baseline the same proportional way the
 * original design scaled every count.
 */
internal fun dockIconSize(count: Int): Dp = when {
    count <= DOCK_BASELINE_COUNT -> DOCK_4_ICON_SIZE
    count == DOCK_BASELINE_COUNT + 1 -> DEFAULT_ICON_SIZE
    else -> DEFAULT_ICON_SIZE * (DOCK_BASELINE_COUNT + 1) / count
}

private val DOCK_4_ICON_SIZE = 66.dp

private const val DOCK_BASELINE_COUNT = 4

/**
 * One tile: an app's icon, or a folder's small 2x2 preview of its contents.
 * Used on home pages, in the dock, and in the drawer, so drag and jiggle
 * behaviour stays visually identical everywhere a tile can appear.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeItemTile(
    item: HomeItem,
    onClick: () -> Unit,
    showLabel: Boolean = true,
    wobble: Boolean = false,
    wobbleSeed: Int = 0,
    onLongClick: (() -> Unit)? = null,
    // Only the dock ever passes anything but the default - pages, the
    // drawer, a folder's own overlay, and the drag ghost all stay at the
    // one fixed size regardless of what the dock's own count is set to.
    iconSize: Dp = DEFAULT_ICON_SIZE,
    modifier: Modifier = Modifier
) {
    val angle = rememberWobble(enabled = wobble, seed = wobbleSeed)
    val label = when (item) {
        is HomeItem.AppItem -> item.entry.app.label
        is HomeItem.FolderItem -> item.name
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { rotationZ = angle }
            .clip(RoundedCornerShape(12.dp))
            // Explicit null indication - the default ripple otherwise
            // covers this whole Column's rectangular bounds (the full
            // tile cell, much wider than the icon itself) with a
            // translucent highlight the instant you press down, before
            // it's even decided whether this is a tap or a long-press
            // drag. The icon graphic already gives its own visual
            // feedback (the wobble/lift once a drag actually starts);
            // this box doesn't need its own on top of that.
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when (item) {
            is HomeItem.AppItem -> AppIcon(item, iconSize)
            is HomeItem.FolderItem -> FolderIcon(item, iconSize)
        }
        if (showLabel) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 11.sp,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 5.dp)
            )
        }
    }
}

@Composable
private fun AppIcon(item: HomeItem.AppItem, size: Dp) {
    val bitmap = remember(item.entry.icon) {
        item.entry.icon.toBitmap(ICON_PX, ICON_PX).asImageBitmap()
    }
    Image(
        bitmap = bitmap,
        contentDescription = item.entry.app.label,
        contentScale = ContentScale.Fit,
        modifier = Modifier.size(size)
    )
}

// iOS's "Liquid Glass" look, faked with a gradient + edge highlight rather
// than a real blur-behind - a genuine backdrop blur would mean re-rendering
// whatever sits under every folder tile in a whole page grid, which is a lot
// of pixel shader work for something this small. A diagonal light-to-dark
// gradient plus a brighter top-left border edge reads as glass at this size
// without any of that cost.
internal val folderGlassBrush = Brush.linearGradient(
    colors = listOf(
        Color.White.copy(alpha = 0.32f),
        Color.White.copy(alpha = 0.10f)
    )
)
internal val folderGlassBorderBrush = Brush.linearGradient(
    colors = listOf(
        Color.White.copy(alpha = 0.55f),
        Color.White.copy(alpha = 0.05f)
    )
)

/** A rounded tile holding up to four of the folder's icons in a 2x2 grid. */
@Composable
private fun FolderIcon(item: HomeItem.FolderItem, size: Dp) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(folderGlassBrush)
            .border(1.dp, folderGlassBorderBrush, shape)
            .padding(6.dp)
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = false
        ) {
            items(item.items.take(4)) { child ->
                val bitmap = remember(child.entry.icon) {
                    child.entry.icon.toBitmap(ICON_PX, ICON_PX).asImageBitmap()
                }
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .aspectRatio(1f)
                        .padding(1.dp)
                )
            }
        }
    }
}
