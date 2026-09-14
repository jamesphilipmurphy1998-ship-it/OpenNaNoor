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
 * Icon size for a dock holding [count] icons. 1-3 match the page grid's own
 * 4-column size (PAGE_4_ICON_SIZE) - sparse enough in the dock that they
 * read the same as a sparse page. 4 is its own distinct, larger size
 * (DOCK_4_ICON_SIZE) rather than sharing DEFAULT_ICON_SIZE with 5 - 5 is the
 * baseline every other count is judged against, 4 stands out as bigger
 * still since it's the sparsest count the dock scales on its own terms for.
 * 6 is the other way, scaled down from the 5-icon baseline the same
 * proportional way the original design scaled every count.
 */
internal fun dockIconSize(count: Int): Dp = when {
    count in 1..3 -> PAGE_4_ICON_SIZE
    count == DOCK_BASELINE_COUNT -> DOCK_4_ICON_SIZE
    count == DOCK_BASELINE_COUNT + 1 -> DEFAULT_ICON_SIZE
    else -> DEFAULT_ICON_SIZE * (DOCK_BASELINE_COUNT + 1) / count
}

private val DOCK_4_ICON_SIZE = 66.dp

private const val DOCK_BASELINE_COUNT = 4

/**
 * Icon size for a home page tile whose cell measures [cellWidth] x
 * [cellHeight], on a grid of [columns] x [rows].
 *
 * Driven by [columns] alone, not by [rows] too - a phone has far more
 * vertical room to spare than horizontal, so the same "+2" on rows as on
 * columns is nowhere near as tight a squeeze. Sizing off the smaller of a
 * columns-scale and a rows-scale (as this used to) let a tall-but-narrow
 * grid like 3x6 shrink icons that had plenty of horizontal room to stay
 * their full size, just because rows had grown past its own baseline.
 * Rows still gets a say, but only as a fit check afterwards, not as a
 * second driver of how small the icon should read as.
 *
 * Two constraints:
 *
 * - A density target, scaled from DEFAULT_ICON_SIZE by how far [columns]
 *   sits past the 4-column baseline - the "more icons in a row means a
 *   smaller icon" behaviour itself.
 * - A measured fit budget, straight from the cell's own real on-screen
 *   size rather than the counts that produced it, covering both axes so
 *   the icon can never overflow into a neighbour on either one - this is
 *   what still catches an extreme row count even though rows no longer
 *   drives the target directly.
 *
 * Whichever constraint is smaller wins. Never grows past DEFAULT_ICON_SIZE
 * - only the dock has its own distinct larger size for being sparse; a
 * page just gets more breathing room.
 */
internal fun pageIconSize(columns: Int, cellWidth: Dp, cellHeight: Dp): Dp {
    val widthBudget = (cellWidth * 0.82f).coerceAtLeast(0.dp)
    val heightBudget = ((cellHeight - PAGE_LABEL_RESERVE) * 0.9f).coerceAtLeast(0.dp)
    // PAGE_ICON_MIN_SIZE lifts only the density target, a floor on how far
    // "more icons means smaller icons" scaling shrinks it - it must never
    // lift the final result past what widthBudget/heightBudget actually
    // measured, or an extreme enough combination on a small enough screen
    // could make this function hand back an icon bigger than its own cell.
    val densityTarget = maxOf(pageDensityTarget(columns), PAGE_ICON_MIN_SIZE)
    return minOf(widthBudget, heightBudget, densityTarget)
}

/**
 * The "more icons in a row means a smaller icon" target size for [columns]
 * icons per row, tuned by hand per count (the same shape as [dockIconSize])
 * rather than derived from one continuous formula - so each count's size can
 * be adjusted on its own without the others moving too.
 */
private fun pageDensityTarget(columns: Int): Dp = when (columns) {
    1, 2, 3, PAGE_BASELINE_COLUMNS -> PAGE_4_ICON_SIZE
    5 -> PAGE_5_ICON_SIZE
    else -> DEFAULT_ICON_SIZE * (PAGE_BASELINE_COLUMNS.toFloat() / columns).coerceAtMost(1f)
}

private val PAGE_4_ICON_SIZE = 68.dp
private val PAGE_5_ICON_SIZE = 68.dp

/** Roughly the label's own text line plus the tile Column's vertical padding. */
private val PAGE_LABEL_RESERVE = 26.dp
private val PAGE_ICON_MIN_SIZE = 24.dp
private const val PAGE_BASELINE_COLUMNS = 4

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
