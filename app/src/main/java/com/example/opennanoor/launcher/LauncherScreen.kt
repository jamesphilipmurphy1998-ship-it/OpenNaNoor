package com.example.opennanoor.launcher

import android.content.ComponentName
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
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
    onMove: (from: HomeLocation, to: HomeLocation) -> Unit,
    onPlaceFromDrawer: (entry: LauncherEntry, to: HomeLocation) -> Unit,
    onRemove: (page: Int, slot: Int) -> Unit,
    onOpenFolder: (String) -> Unit,
    onCloseFolder: () -> Unit,
    onRemoveFromFolder: (folderId: String, componentId: String) -> Unit,
    onUninstall: (ComponentName) -> Unit,
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
    val drag = remember { DragCoordinator() }
    var lastEdgeAdvance by remember { mutableLongStateOf(0L) }
    var dockBounds by remember { mutableStateOf<Rect?>(null) }
    var removeZoneBounds by remember { mutableStateOf<Rect?>(null) }
    var outerOrigin by remember { mutableStateOf(Offset.Zero) }
    val topPadding = 16.dp + insets.calculateTopPadding()

    var pagerSizePx by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { outerOrigin = it.positionInWindow() }
    ) {
        val outerWidthPx = with(density) { maxWidth.toPx() }
        val topPaddingPx = with(density) { topPadding.toPx() }
        // Cell size is measured from the pager's own rendered area rather
        // than recomputed from the outer box's total height, so the grid
        // drop math lines up exactly with what HomePage actually draws -
        // two independent formulas for the same thing drifted apart badly
        // enough to misplace long drags toward the dock.
        val cellWidthPx = if (pagerSizePx.width > 0) {
            pagerSizePx.width / state.columns.toFloat()
        } else with(density) { (maxWidth / state.columns).toPx() }
        val cellHeightPx = if (pagerSizePx.height > 0) {
            (pagerSizePx.height - topPaddingPx) / HomeLayout.ROWS_PER_PAGE.toFloat()
        } else with(density) {
            ((maxHeight - topPadding - DOCK_AREA_HEIGHT) / HomeLayout.ROWS_PER_PAGE).toPx()
        }

        fun handleDragMoved(delta: Offset) {
            drag.moveBy(delta)
            val center = drag.position + Offset(cellWidthPx / 2f, cellHeightPx / 2f)

            drag.overDock = dockBounds?.contains(center) == true
            drag.overRemoveZone = editing && removeZoneBounds?.contains(center) == true

            // Dragging out of the drawer reveals the home screen underneath it
            // so there is somewhere visible to drop onto.
            if (drag.fromDrawer && drawerOpen) onDrawerOpenChange(false)

            val now = System.currentTimeMillis()
            if (!drag.overDock && !drag.overRemoveZone &&
                now - lastEdgeAdvance > EDGE_ADVANCE_COOLDOWN_MS
            ) {
                when {
                    drag.position.x < EDGE_MARGIN_PX && pagerState.currentPage > 0 -> {
                        lastEdgeAdvance = now
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                    }
                    drag.position.x > outerWidthPx - EDGE_MARGIN_PX &&
                        pagerState.currentPage < pageCount - 1 -> {
                        lastEdgeAdvance = now
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                }
            }
        }

        fun handleDragEnded() {
            val item = drag.item
            if (item != null) {
                val target: HomeLocation = when {
                    drag.overDock -> {
                        val bounds = dockBounds
                        val dockSlot = if (bounds != null && bounds.width > 0f) {
                            val cellW = bounds.width / HomeLayout.DOCK_SIZE
                            ((drag.position.x - bounds.left) / cellW).toInt()
                                .coerceIn(0, HomeLayout.DOCK_SIZE - 1)
                        } else 0
                        HomeLocation.Dock(dockSlot)
                    }
                    drag.overRemoveZone -> {
                        if (item is HomeItem.AppItem) onUninstall(item.entry.app.component)
                        drag.end()
                        return
                    }
                    else -> {
                        val column = (drag.position.x / cellWidthPx).toInt()
                            .coerceIn(0, state.columns - 1)
                        val row = ((drag.position.y - topPaddingPx) / cellHeightPx).toInt()
                            .coerceAtLeast(0)
                        HomeLocation.Page(pagerState.currentPage, row * state.columns + column)
                    }
                }

                val origin = drag.origin
                if (origin != null) onMove(origin, target)
                else if (item is HomeItem.AppItem) onPlaceFromDrawer(item.entry, target)
            }
            drag.end()
        }

        Column(Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !drag.active,
                modifier = Modifier
                    .weight(1f)
                    .onGloballyPositioned { pagerSizePx = it.size }
                    // A swipe up anywhere on a page opens the drawer, as on a
                    // stock home screen - only while nothing is being dragged.
                    .pointerInputIf(!drag.active) {
                        detectVerticalDragGestures { _, dragAmount ->
                            if (dragAmount < -DRAWER_DRAG_THRESHOLD) onDrawerOpenChange(true)
                        }
                    }
            ) { pageIndex ->
                HomePage(
                    items = state.pages.getOrNull(pageIndex).orEmpty(),
                    pageIndex = pageIndex,
                    columns = state.columns,
                    rows = HomeLayout.ROWS_PER_PAGE,
                    editing = editing,
                    topPadding = topPadding,
                    drag = drag,
                    onLaunch = ::handleTap,
                    onEnterEditing = { onEditingChange(true) },
                    onDragMoved = ::handleDragMoved,
                    onDragEnded = ::handleDragEnded,
                    onRemove = { slot -> onRemove(pageIndex, slot) }
                )
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
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
        AnimatedVisibility(
            visible = drawerVisible,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut()
        ) {
            AppDrawer(
                state = state,
                insets = insets,
                dimmed = drag.active && drag.fromDrawer,
                onLaunch = {
                    onDrawerOpenChange(false)
                    onLaunchApp(it)
                },
                onDismiss = { onDrawerOpenChange(false) },
                drag = drag,
                onDragMoved = ::handleDragMoved,
                onDragEnded = ::handleDragEnded
            )
        }

        state.openFolder?.let { folder ->
            FolderOverlay(
                folder = folder,
                insets = insets,
                onLaunch = onLaunchApp,
                onDismiss = onCloseFolder,
                onRemoveItem = { componentId -> onRemoveFromFolder(folder.folderId, componentId) }
            )
        }

        if (drag.active) {
            DragGhost(item = drag.item!!, position = drag.position)
        }
    }
}

/** The floating tile that tracks the finger during a drag. */
@Composable
private fun DragGhost(item: HomeItem, position: Offset) {
    Box(
        Modifier
            .offset { IntOffset(position.x.toInt(), position.y.toInt()) }
            .size(GHOST_SIZE)
            .scale(GHOST_SCALE)
    ) {
        HomeItemTile(item = item, onClick = {})
    }
}

@Composable
private fun RemoveZone(highlighted: Boolean, onPositioned: (Rect) -> Unit) {
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

@Composable
private fun Dock(
    items: List<HomeItem>,
    drag: DragCoordinator,
    editing: Boolean,
    onTap: (HomeItem) -> Unit,
    onEnterEditing: () -> Unit,
    onDragMoved: (Offset) -> Unit,
    onDragEnded: () -> Unit,
    onPositioned: (Rect) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .height(DOCK_AREA_HEIGHT - 8.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .padding(vertical = 10.dp, horizontal = 8.dp)
            .onGloballyPositioned { onPositioned(it.boundsInWindow()) },
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        items.forEachIndexed { index, item ->
            val isDragOrigin = drag.active && drag.origin == HomeLocation.Dock(index)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (!isDragOrigin) {
                    Box(
                        Modifier.pointerInput(items, index) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { offset ->
                                    onEnterEditing()
                                    drag.start(
                                        item = item,
                                        origin = HomeLocation.Dock(index),
                                        startPosition = offset
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
                            onClick = { if (!editing) onTap(item) },
                            showLabel = false,
                            wobble = editing
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
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

/**
 * The full app list. A long-press on any app begins a drag using the same
 * [DragCoordinator] the home pages use - the caller fades this drawer's
 * background while that drag is in flight so the home screen underneath is
 * visible to drop onto, rather than closing the drawer outright, which would
 * cancel the gesture mid-flight.
 */
@Composable
private fun AppDrawer(
    state: LauncherUiState,
    insets: PaddingValues,
    dimmed: Boolean,
    onLaunch: (LaunchableApp) -> Unit,
    onDismiss: () -> Unit,
    drag: DragCoordinator,
    onDragMoved: (Offset) -> Unit,
    onDragEnded: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = if (dimmed) 0.1f else 0.92f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onDismiss() }
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(state.columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = 24.dp + insets.calculateTopPadding(),
                bottom = 24.dp + insets.calculateBottomPadding()
            ),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.allApps, key = { it.id }) { appItem ->
                val isDragOrigin = drag.active && drag.fromDrawer &&
                    drag.item?.id == appItem.id
                Box(
                    Modifier.pointerInput(appItem.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                drag.start(item = appItem, origin = null, startPosition = offset)
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
                        item = appItem,
                        onClick = { if (!drag.active) onLaunch(appItem.entry.app) }
                    )
                    if (isDragOrigin) {
                        // The dragged tile is shown by the floating ghost
                        // instead, so hide the drawer's own copy of it.
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = if (dimmed) 0.1f else 0.92f))
                        )
                    }
                }
            }
        }
    }
}

/** Full-screen view of one folder's contents, with the same jiggle-and-remove. */
@Composable
private fun FolderOverlay(
    folder: HomeItem.FolderItem,
    insets: PaddingValues,
    onLaunch: (LaunchableApp) -> Unit,
    onDismiss: () -> Unit,
    onRemoveItem: (componentId: String) -> Unit
) {
    var editing by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.94f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onDismiss() }
    ) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp + insets.calculateTopPadding())
                .padding(horizontal = 24.dp)
        ) {
            Text(
                text = folder.name,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                modifier = Modifier.padding(bottom = 20.dp)
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(folder.items, key = { it.id }) { appItem ->
                    Box {
                        HomeItemTile(
                            item = appItem,
                            onClick = {
                                if (editing) editing = false
                                else {
                                    onDismiss()
                                    onLaunch(appItem.entry.app)
                                }
                            },
                            onLongClick = { editing = true },
                            wobble = editing
                        )
                        if (editing) {
                            RemoveBadge(
                                onClick = {
                                    onRemoveItem(appItem.entry.app.component.flattenToString())
                                },
                                modifier = Modifier.align(Alignment.TopStart)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Applies pointer input handling only when [condition] is true. */
@Composable
private fun Modifier.pointerInputIf(
    condition: Boolean,
    block: suspend PointerInputScope.() -> Unit
): Modifier = if (condition) this.pointerInput(condition, block) else this

private const val DRAWER_DRAG_THRESHOLD = 18f
private const val EDGE_MARGIN_PX = 60f
private const val EDGE_ADVANCE_COOLDOWN_MS = 450L
private val DOCK_AREA_HEIGHT = 96.dp
private val GHOST_SIZE = 72.dp
private const val GHOST_SCALE = 1.12f
