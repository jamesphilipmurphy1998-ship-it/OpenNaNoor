package com.example.opennanoor.launcher

import android.content.ComponentName
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
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
    onMove: (from: HomeLocation, to: HomeLocation, fold: Boolean) -> Unit,
    onPlaceFromDrawer: (entry: LauncherEntry, to: HomeLocation, fold: Boolean) -> Unit,
    onRemove: (page: Int, slot: Int) -> Unit,
    onOpenFolder: (String) -> Unit,
    onCloseFolder: () -> Unit,
    onRemoveFromFolder: (folderId: String, componentId: String) -> Unit,
    onRenameFolder: (folderId: String, newName: String) -> Unit,
    onUninstall: (ComponentName) -> Unit,
    onSpillAnimationDone: () -> Unit,
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

    // Removing the last icon on a non-final page deletes that page and
    // shifts every later one down an index - nothing told the pager, so it
    // could keep pointing at an index that no longer means the same page,
    // or is now out of range entirely. pageCount changes rarely (a page
    // being added or removed, not per-frame drag movement), so reading it
    // here in composition carries none of the risk that ruled out reading
    // live drag fields this way.
    LaunchedEffect(pageCount) {
        if (pagerState.currentPage >= pageCount) {
            pagerState.scrollToPage((pageCount - 1).coerceAtLeast(0))
        }
    }
    val drag = remember { DragCoordinator() }
    // Which edge the drag is currently resting in (-1 left, 0 neither, 1
    // right) and when it started resting there - a page only flips once the
    // finger has held inside the edge strip for EDGE_HOLD_MS, not the
    // instant it crosses in, so passing through the edge on the way
    // somewhere else doesn't trigger it.
    var edgeHoldSide by remember { mutableIntStateOf(0) }
    var edgeHoldSince by remember { mutableLongStateOf(0L) }
    var dockBounds by remember { mutableStateOf<Rect?>(null) }
    var removeZoneBounds by remember { mutableStateOf<Rect?>(null) }
    var outerOrigin by remember { mutableStateOf(Offset.Zero) }
    // The ghost tracks the finger throughout a drag, but the instant it
    // ends, drag.end() wipes drag.position and the real tile at the target
    // slot just appears there with no transition - the ghost vanishing and
    // a tile popping into place at the same frame read as a jolt. Capturing
    // where the ghost actually was right before that reset lets the arriving
    // tile seed its own reflow animation from that exact point instead of
    // its plain grid position, so release continues the same motion instead
    // of cutting between two different renders of the same icon.
    var justDropped by remember { mutableStateOf<JustDropped?>(null) }
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
            // drag.position is seeded at the actual touch point (see
            // HomePage and Dock) and moved by exactly the finger's own
            // delta since, so it already is the point to hit-test against -
            // no half-cell correction needed to approximate a centre
            // anymore.
            val center = drag.position

            drag.overDock = dockBounds?.contains(center) == true
            drag.overRemoveZone = editing && removeZoneBounds?.contains(center) == true

            // Track the folder, if any, the finger is resting over. Only a
            // hold zone (the centre of a folder's cell - see pageDropTarget)
            // arms the fold timer; hovering near either edge of that same
            // cell previews an insert instead, and neither dwells.
            val hovered = if (drag.overDock || drag.overRemoveZone) null else {
                pageItemsForPreview(state, pagerState.currentPage, drag.origin)?.let { pageItems ->
                    val target = pageDropTarget(
                        drag.position, cellWidthPx, cellHeightPx, topPaddingPx, state.columns, pageItems
                    )
                    target.holdTarget?.let { HomeLocation.Page(pagerState.currentPage, it) }
                }
            }
            if (hovered != drag.hoverTarget) {
                drag.hoverTarget = hovered
                drag.folderArmed = false
            }

            // Dragging out of the drawer reveals the home screen underneath it
            // so there is somewhere visible to drop onto.
            if (drag.fromDrawer && drawerOpen) onDrawerOpenChange(false)

            // The dragged icon's own centre, not its top-left corner - using
            // the corner meant a leftmost-column icon started the drag
            // already sitting at x=0, inside the edge strip before the
            // finger had moved at all, flipping the page the instant it was
            // picked up.
            val side = when {
                drag.overDock || drag.overRemoveZone -> 0
                center.x < EDGE_MARGIN_PX && pagerState.currentPage > 0 -> -1
                center.x > outerWidthPx - EDGE_MARGIN_PX && pagerState.currentPage < pageCount - 1 -> 1
                else -> 0
            }
            val now = System.currentTimeMillis()
            if (side != edgeHoldSide) {
                edgeHoldSide = side
                edgeHoldSince = now
            } else if (side != 0 && now - edgeHoldSince >= EDGE_HOLD_MS) {
                // Reset rather than let the next check fire again next
                // frame - holding through the flip requires dwelling the
                // full second again before it repeats.
                edgeHoldSince = now
                when (side) {
                    -1 -> scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                    1 -> scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                }
            }
        }

        fun handleDragEnded() {
            edgeHoldSide = 0
            val item = drag.item
            if (item != null) {
                val target: HomeLocation = when {
                    drag.overDock -> {
                        val bounds = dockBounds
                        val dockSlot = if (bounds != null && bounds.width > 0f) {
                            // However many icons the dock will actually hold
                            // once this drop's own source slot (if it came
                            // from the dock itself) is removed - the same
                            // count insertItem's own clamp resolves against.
                            val itemCount = state.dock.size -
                                if (drag.origin is HomeLocation.Dock) 1 else 0
                            // Must exactly match Dock's own packing() - icons
                            // are centred with n+1 equal gaps, not spread
                            // evenly across dockIconCount cells. Using the
                            // plain bounds.width/dockIconCount division this
                            // used to use ignored that centring and the
                            // actual icon size entirely, so the index this
                            // resolved to routinely disagreed with whatever
                            // the live preview (and the on-screen icons
                            // themselves) had just been showing - the drop
                            // landing somewhere other than where it visibly
                            // was aimed.
                            // Dock's own live preview packs for one MORE
                            // icon than are here now when this is an arrival
                            // from outside (see previewCount) - reserving
                            // the squeeze the incoming icon will need, right
                            // up until release. Resolving the final index
                            // against the un-squeezed current packing instead
                            // would disagree with the spacing the icons were
                            // actually just shown at.
                            val packingCount = if (drag.origin is HomeLocation.Dock) {
                                itemCount
                            } else {
                                itemCount + 1
                            }
                            val iconPx = with(density) { dockIconSize(state.dockIconCount).toPx() }
                            val n = packingCount.coerceAtLeast(1)
                            val gapPx = ((bounds.width - iconPx * n) / (n + 1)).coerceAtLeast(0f)
                            val pitchPx = iconPx + gapPx
                            dockDropTarget(drag.position.x - bounds.left - gapPx, pitchPx, itemCount)
                        } else 0
                        HomeLocation.Dock(dockSlot)
                    }
                    drag.overRemoveZone -> {
                        if (item is HomeItem.AppItem) onUninstall(item.entry.app.component)
                        drag.end()
                        return
                    }
                    else -> {
                        val pageItems = pageItemsForPreview(state, pagerState.currentPage, drag.origin)
                            .orEmpty()
                        val resolved = pageDropTarget(
                            drag.position, cellWidthPx, cellHeightPx, topPaddingPx, state.columns, pageItems
                        )
                        HomeLocation.Page(pagerState.currentPage, resolved.gap)
                    }
                }

                // Only a drop that dwelled over this exact slot folds into it.
                val fold = drag.folderArmed && drag.hoverTarget == target
                val origin = drag.origin

                // A full dock rejects this drop outright (see insertItem) -
                // nothing moves, so without this the ghost would simply
                // blink out and the icon reappear at home with no motion at
                // all, giving no sign the drop was refused rather than
                // silently lost. Mirrors insertItem's own condition: a drag
                // that STARTED in the dock always has room (its own slot is
                // freed first), and a fold merges into an occupant rather
                // than needing a slot of its own.
                val dockFull = target is HomeLocation.Dock &&
                    origin !is HomeLocation.Dock &&
                    state.dock.size >= state.dockIconCount &&
                    !fold

                if (dockFull) {
                    // Ping back to where it came from, then let go - the
                    // ghost is still what's on screen (drag.item stays set
                    // until end()), and it simply tracks drag.position, so
                    // animating that position home animates the icon home.
                    val home = when (origin) {
                        is HomeLocation.Page -> Offset(
                            (origin.slot % state.columns) * cellWidthPx + cellWidthPx / 2f,
                            topPaddingPx + (origin.slot / state.columns) * cellHeightPx + cellHeightPx / 2f
                        )
                        // A folder's own overlay and the drawer both sit
                        // above the pages rather than at a home slot of
                        // their own - nothing meaningful to fly back to.
                        else -> null
                    }
                    if (home == null) {
                        drag.end()
                    } else {
                        scope.launch {
                            Animatable(drag.position, Offset.VectorConverter)
                                .animateTo(home, tween(REJECT_RETURN_MS)) { drag.position = value }
                            drag.end()
                        }
                    }
                    return
                }

                // A fold merges into an existing tile rather than landing as
                // one of its own - there's no standalone arrival to seed.
                if (!fold) {
                    justDropped = JustDropped(item.id, target, drag.position)
                    scope.launch {
                        delay(REFLOW_ANIMATION_MS.toLong())
                        justDropped = null
                    }
                }

                if (origin != null) onMove(origin, target, fold)
                else if (item is HomeItem.AppItem) onPlaceFromDrawer(item.entry, target, fold)
            }
            drag.end()
        }

        // Entering arranging mode is driven by drag.active rather than
        // called directly from the gesture callback above. Flipping it
        // synchronously inside onDragStart triggered a same-frame
        // recomposition (remove badges appearing on every other tile) that
        // was disrupting Compose's tracking of the still-active gesture -
        // the drag would start, then die with zero move events recorded.
        // Keyed on Unit, not on the drag field itself - using a changing
        // field as a LaunchedEffect key means reading it during composition
        // to evaluate the key, which is the same recomposition-cancels-the-
        // gesture problem the ghost and the armed-tile highlight both hit.
        // snapshotFlow watches it from inside the effect instead.
        LaunchedEffect(Unit) {
            androidx.compose.runtime.snapshotFlow { drag.active }
                .collect { active -> if (active) onEditingChange(true) }
        }

        // Resting over one slot for this long arms the folder merge. The
        // hover value is debounced first - a real finger flickers in and
        // out of a target by a pixel or two even while trying to hold
        // still, and reacting to every one of those as a fresh target reset
        // the dwell countdown before it could ever finish.
        LaunchedEffect(Unit) {
            androidx.compose.runtime.snapshotFlow { drag.hoverTarget }
                .debounce(HOVER_DEBOUNCE_MS)
                .collectLatest { target ->
                    if (target != null) {
                        kotlinx.coroutines.delay(FOLDER_DWELL_MS)
                        drag.folderArmed = true
                    }
                }
        }

        // A real backdrop blur of the actual home screen - wallpaper and
        // icons genuinely behind it, not a fake copy - while a folder is
        // open, the way iOS's newer glass material reads: content seen
        // through it, not hidden behind a flat tint. Opening a folder is a
        // discrete tap, not a continuous drag signal, so reading
        // state.openFolder here in composition carries none of the
        // gesture-cancellation risk that ruled out reading drag fields this
        // way - nothing is being dragged when this changes.
        val folderOpen = state.openFolder != null
        // Ramped rather than switched on outright - the glass frosting over
        // as the folder grows, and clearing again as it shrinks away, is
        // most of what makes the whole thing read as one movement instead
        // of the home screen blinking between two states. Read inside the
        // graphicsLayer lambda below, so each frame of it invalidates only
        // the draw pass, never a recomposition.
        val blurRadius by animateFloatAsState(
            targetValue = if (folderOpen) BLUR_RADIUS_PX else 0f,
            animationSpec = tween(FOLDER_OPEN_MS, easing = FastOutSlowInEasing),
            label = "folderBlur"
        )
        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    renderEffect = if (blurRadius > 0.01f &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ) {
                        RenderEffect
                            .createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP)
                            .asComposeRenderEffect()
                    } else {
                        null
                    }
                }
        ) {
            // userScrollEnabled and the swipe-up gesture below used to read
            // drag.active directly, toggling off the instant a tile drag
            // began. That is a composition-time parameter on the pager
            // itself - the direct parent of every tile - and changing it
            // turned out to be what was cancelling the drag, not anything in
            // HomePage. A long-press-then-drag already consumes its own
            // pointer events, so the pager's plain swipe detectors leave it
            // alone without needing to be switched off by hand.
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .onGloballyPositioned { pagerSizePx = it.size }
                    .pointerInput(Unit) {
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
                    currentPage = { pagerState.currentPage },
                    onLaunch = ::handleTap,
                    onEnterEditing = { onEditingChange(true) },
                    onDragMoved = ::handleDragMoved,
                    onDragEnded = ::handleDragEnded,
                    onRemove = { slot -> onRemove(pageIndex, slot) },
                    spillEvent = state.spillEvent?.takeIf { it.fromPage == pageIndex },
                    onSpillAnimationDone = onSpillAnimationDone,
                    justDropped = justDropped?.takeIf { it.location is HomeLocation.Page && it.location.page == pageIndex }
                )
            }

            PageDots(
                count = pageCount,
                current = pagerState.currentPage,
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
            )

            Dock(
                items = state.dock,
                slotCount = state.dockIconCount,
                drag = drag,
                editing = editing,
                onTap = ::handleTap,
                onEnterEditing = { onEditingChange(true) },
                onDragMoved = ::handleDragMoved,
                onDragEnded = ::handleDragEnded,
                onPositioned = { bounds ->
                    dockBounds = bounds.translate(-outerOrigin)
                },
                outerOrigin = outerOrigin,
                justDropped = justDropped?.takeIf { it.location is HomeLocation.Dock },
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

        // A visible way out of arranging mode, alongside back and home,
        // which already work. Sized generously and pinned above the grid's
        // own top padding - the first attempt sat right on top of the
        // top-right tile's remove badge, and a tap aimed at the button
        // could land on the sliver of badge still exposed beside it,
        // removing that tile instead of exiting.
        AnimatedVisibility(
            visible = editing,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 12.dp)
        ) {
            Text(
                text = "Done",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .padding(top = insets.calculateTopPadding())
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF3A3A3C))
                    .clickable { onEditingChange(false) }
                    .padding(horizontal = 18.dp, vertical = 9.dp)
            )
        }

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
                outerOrigin = outerOrigin,
                onDragMoved = ::handleDragMoved,
                onDragEnded = ::handleDragEnded
            )
        }

        state.openFolder?.let { folder ->
            val draggingOutOfThis = drag.active && drag.origin.let {
                it is HomeLocation.Folder && it.folderId == folder.folderId
            }
            FolderOverlay(
                folder = folder,
                insets = insets,
                dimmed = draggingOutOfThis,
                homeEditing = editing,
                onLaunch = onLaunchApp,
                onDismiss = onCloseFolder,
                onRemoveItem = { componentId -> onRemoveFromFolder(folder.folderId, componentId) },
                onRename = { newName -> onRenameFolder(folder.folderId, newName) },
                drag = drag,
                outerOrigin = outerOrigin,
                onDragMoved = ::handleDragMoved,
                // Left open rather than auto-closed: the folder still shows,
                // now one member lighter, and the user dismisses it deliberately.
                // Auto-closing exactly on drag-end would need this callback to
                // see live drag state from inside an already-running gesture,
                // which Compose does not reliably propagate mid-drag.
                onDragEnded = ::handleDragEnded
            )
        }

        // Always composed rather than conditionally inserted, and hidden via
        // alpha instead. Adding it to the tree only once a drag begins was a
        // structural change to this Box while a tile deep inside the pager
        // had an active raw pointer-input gesture running - and that seems to
        // be enough for Compose to cancel the gesture outright: onDragCancel
        // was firing within ~15ms of onDragStart, before any real movement.
        DragGhost(drag = drag)

        // The folder someone is dwelling on to drop into expands into a
        // real 2x2 preview of what's inside, the way iOS shows it - rather
        // than just a swelling highlight. drag.folderArmed only flips once
        // per dwell lock (not on every pixel of movement, unlike position),
        // so reading it here in composition is the same category of
        // infrequent change RemoveZone's own visibility already relies on
        // safely - not the continuous kind that cancels a live gesture.
        // A plain app being dwelled on (about to form a brand new folder,
        // not join an existing one) gets the same expanded preview now too -
        // built on the fly from the two apps that would end up in it, since
        // there is no real folder yet to read contents from.
        //
        // drag.folderArmed is checked BEFORE drag.hoverTarget is ever read,
        // not after - hoverTarget changes on every cell the finger crosses,
        // not once per dwell, so reading it unconditionally recomposed this
        // on every such crossing (the same class of read this file's other
        // comments describe cancelling a live gesture outright). Gating on
        // folderArmed first means hoverTarget is only read once it has
        // already settled - handleDragMoved un-arms on every hover change,
        // so by construction it can't move again while still armed.
        val armedFolder = if (drag.folderArmed) {
            (drag.hoverTarget as? HomeLocation.Page)
                ?.takeIf { it.page == pagerState.currentPage }
                ?.let { loc ->
                    val occupant = pageItemsForPreview(state, loc.page, null)?.getOrNull(loc.slot)
                    when {
                        occupant is HomeItem.FolderItem -> occupant
                        occupant is HomeItem.AppItem && drag.item is HomeItem.AppItem ->
                            HomeItem.FolderItem(
                                folderId = "preview",
                                name = "New Folder",
                                items = listOf(occupant, drag.item as HomeItem.AppItem)
                            )
                        else -> null
                    }
                }
        } else null

        AnimatedVisibility(
            visible = armedFolder != null,
            enter = scaleIn(initialScale = 0.6f) + fadeIn(),
            exit = scaleOut(targetScale = 0.6f) + fadeOut()
        ) {
            val folder = armedFolder
            val loc = drag.hoverTarget as? HomeLocation.Page
            if (folder != null && loc != null) {
                val centreX = (loc.slot % state.columns) * cellWidthPx + cellWidthPx / 2f
                val centreY = topPaddingPx + (loc.slot / state.columns) * cellHeightPx + cellHeightPx / 2f
                FolderPreview(
                    folder = folder,
                    centreOffsetPx = Offset(centreX, centreY),
                    cellSizePx = cellWidthPx
                )
            }
        }
    }
}

/**
 * A real preview of a folder's contents - up to four of its icons in a 2x2
 * grid - grown to roughly twice a normal tile's size and centred on the
 * folder being dwelled over. Dropping while this is showing merges into the
 * same folder it shows; it isn't just decoration standing in for that.
 */
@Composable
private fun FolderPreview(folder: HomeItem.FolderItem, centreOffsetPx: Offset, cellSizePx: Float) {
    val density = LocalDensity.current
    val sizeDp = with(density) { (cellSizePx * 2.1f).toDp() }

    Box(
        Modifier
            .offset {
                IntOffset(
                    (centreOffsetPx.x - cellSizePx * 1.05f).toInt(),
                    (centreOffsetPx.y - cellSizePx * 1.05f).toInt()
                )
            }
            .size(sizeDp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF2C2C2E))
            .padding(12.dp)
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = false,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(folder.items.take(4), key = { it.id }) { appItem ->
                HomeItemTile(item = appItem, onClick = {}, showLabel = false)
            }
        }
    }
}

/**
 * The floating tile that tracks the finger during a drag.
 *
 * Position and visibility are read inside layout and draw lambdas rather
 * than during composition. Reading them in composition made this screen -
 * and with it the tile whose gesture was in flight - recompose on every
 * movement, which Compose answered by cancelling the drag.
 */
@Composable
private fun DragGhost(drag: DragCoordinator) {
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
                val shown = drag.item != null
                alpha = if (shown) 1f else 0f
                scaleX = GHOST_SCALE
                scaleY = GHOST_SCALE
            }
    ) {
        drag.item?.let { HomeItemTile(item = it, onClick = {}) }
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
    // Always renders exactly this many slots, whether or not the dock is
    // actually full - the icon size (dockIconSize) is derived from this
    // count, not from how many apps happen to be sitting there, so a
    // half-empty 5-icon dock still shows 5-icon-sized icons, not 4-sized
    // ones with gaps. The dock's own outer bounds (height, padding, corner
    // radius) never change with this - only what's drawn inside does.
    slotCount: Int,
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
    val iconSize = dockIconSize(slotCount)
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier
            .height(DOCK_AREA_HEIGHT - 8.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .padding(vertical = 10.dp, horizontal = 8.dp)
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

        // gapPx for spacing count icons evenly, including the margin
        // before the first and after the last - count+1 equal gaps around
        // count icons is what centres them.
        fun packing(count: Int): Pair<Float, Float> {
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
                items.size >= slotCount

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

            // Seeded with the plain resting position - unkeyed beyond
            // key(item.id) above, so this only ever runs once, when this
            // app first appears in the dock at all.
            val basePosition = remember {
                Offset(restGapPx + slot * restPitchPx, 0f)
            }
            val animatedOffset = remember { Animatable(basePosition, Offset.VectorConverter) }

            // The icon that was just released here snaps to wherever its
            // drag ghost actually was, before resuming normal tracking -
            // justDropped is plain composable state, safe to read here.
            // fromPosition is in the shared outer frame the ghost is drawn
            // in, but this icon's own x is local to the dock's left edge
            // (localOrigin is that same translation dockBounds itself
            // uses), and the ghost is centred on fromPosition while this
            // icon is a fixed iconPx-wide box positioned by its left edge -
            // both corrections are what HomePage's version does in one step
            // for a page tile, which already lives in that outer frame the
            // way a dock icon doesn't.
            //
            // Can't be handled by seeding basePosition once at first
            // composition: a REORDER drops an icon that was already in the
            // dock, whose key(item.id) block has existed since before this
            // drop - never a fresh composition remember{} could catch. Only
            // a genuinely new arrival got seeded correctly that way; a
            // same-dock reorder's icon - hidden but still being animated by
            // the live preview's own slot-based math the whole time, never
            // actually tracking the real finger - just reappeared from
            // wherever that left it, not from the ghost.
            val dropped = justDropped
                ?.takeIf { it.itemId == item.id && it.location == thisLocation }
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
    // Same translation dockBounds and each dock icon use - a drawer tile
    // needs its own absolute position in that shared frame too, or a drag
    // starting here seeds drag.position with a tiny local touch offset
    // (a few dp inside this one grid cell) instead of a real position, and
    // every hit-test downstream - which dock slot, which page cell - ends
    // up computed from a wildly wrong point.
    outerOrigin: Offset,
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
                var tileOrigin by remember { mutableStateOf(Offset.Zero) }
                Box(
                    Modifier
                        .onGloballyPositioned {
                            tileOrigin = it.positionInWindow() - outerOrigin
                        }
                        .pointerInput(appItem.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { touch ->
                                drag.start(item = appItem, origin = null, startPosition = tileOrigin + touch)
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

/**
 * Full-screen view of one folder's contents. Tap to launch; long-press to
 * jiggle, with a badge to pull an app out without going anywhere; or hold
 * and drag an icon past the folder to place it back on the home screen -
 * the same [DragCoordinator] the home pages use, so dropping it resolves
 * exactly the same way a page-to-page drag does. The folder fades rather
 * than closes while that drag is in flight, so the home screen underneath
 * is visible to drop onto without cancelling the gesture.
 */
@Composable
private fun FolderOverlay(
    folder: HomeItem.FolderItem,
    insets: PaddingValues,
    dimmed: Boolean,
    // Opening a folder while the home screen is already in arranging mode
    // should show its contents already wobbling, not reset to still - this
    // seeds that, but arranging inside the folder can still be entered or
    // left independently of it afterwards.
    homeEditing: Boolean,
    onLaunch: (LaunchableApp) -> Unit,
    onDismiss: () -> Unit,
    onRemoveItem: (componentId: String) -> Unit,
    onRename: (newName: String) -> Unit,
    drag: DragCoordinator,
    outerOrigin: Offset,
    onDragMoved: (Offset) -> Unit,
    onDragEnded: () -> Unit
) {
    var editing by remember { mutableStateOf(homeEditing) }
    var renaming by remember { mutableStateOf(false) }
    // Light enough that the blurred home screen behind genuinely reads
    // through it - a frosted pane, not a solid one - while still giving
    // the folder's own icons somewhere legible to sit.
    val scrimAlpha = if (dimmed) 0.1f else 0.38f

    // 0 closed, 1 fully open. The folder grows into place from a little
    // under its own size rather than being there the moment it's tapped,
    // which is what made opening one feel abrupt - the panel and the scrim
    // both ride this, and the blurred home screen behind ramps on the same
    // timing (see blurRadius in LauncherScreen), so the frosting, the fade
    // and the growth all land together as one motion.
    val appear = remember { Animatable(0f) }
    LaunchedEffect(folder.folderId) {
        appear.animateTo(1f, tween(FOLDER_OPEN_MS, easing = FastOutSlowInEasing))
    }
    // Dismiss runs the same motion backwards before actually closing -
    // the overlay is still mounted until onDismiss lands, so there's
    // something left on screen to animate away.
    val scope = rememberCoroutineScope()
    fun dismissAnimated() {
        scope.launch {
            appear.animateTo(0f, tween(FOLDER_CLOSE_MS, easing = FastOutSlowInEasing))
            onDismiss()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = appear.value }
            .background(Color.Black.copy(alpha = scrimAlpha))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { dismissAnimated() }
    ) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .graphicsLayer {
                    // Grows from FOLDER_OPEN_FROM_SCALE up to its real size,
                    // anchored at the top where the panel actually sits, so
                    // it expands downward from under the title rather than
                    // ballooning out of the screen's middle.
                    val scale = FOLDER_OPEN_FROM_SCALE +
                        (1f - FOLDER_OPEN_FROM_SCALE) * appear.value
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 0f)
                }
                .padding(top = 48.dp + insets.calculateTopPadding())
                .padding(horizontal = 24.dp)
        ) {
            Text(
                text = folder.name,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White.copy(alpha = if (dimmed) 0f else 1f),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !dimmed) { renaming = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .padding(bottom = 16.dp)
            )

            if (renaming) {
                RenameFolderDialog(
                    currentName = folder.name,
                    onSave = { newName -> onRename(newName); renaming = false },
                    onDismiss = { renaming = false }
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(folder.items, key = { it.id }) { appItem ->
                    val componentId = appItem.entry.app.component.flattenToString()
                    val isDragOrigin = drag.active && drag.origin.let {
                        it is HomeLocation.Folder && it.componentId == componentId &&
                            it.folderId == folder.folderId
                    }
                    var tileWindowPos by remember(appItem.id) { mutableStateOf(Offset.Zero) }

                    Box(
                        Modifier
                            .onGloballyPositioned { tileWindowPos = it.positionInWindow() }
                            .pointerInput(appItem.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { localOffset ->
                                        editing = true
                                        drag.start(
                                            item = appItem,
                                            origin = HomeLocation.Folder(folder.folderId, componentId),
                                            startPosition = tileWindowPos - outerOrigin + localOffset
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
                        if (!isDragOrigin) {
                            HomeItemTile(
                                item = appItem,
                                onClick = {
                                    if (editing) editing = false
                                    else {
                                        onDismiss()
                                        onLaunch(appItem.entry.app)
                                    }
                                },
                                wobble = editing
                            )
                            if (editing) {
                                RemoveBadge(
                                    onClick = { onRemoveItem(componentId) },
                                    modifier = Modifier.align(Alignment.TopStart)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The item just released at [location] and where its drag ghost actually
 * was the instant it let go - a one-shot cue so that tile's own arrival can
 * seed its reflow animation from there instead of popping into its plain
 * grid position with no transition. Cleared a beat later by whoever set it.
 */
data class JustDropped(val itemId: String, val location: HomeLocation, val fromPosition: Offset)

/**
 * The items on [pageIndex], with the dragged item excluded if it originated
 * on that same page - the "as if already removed" list every consistent
 * drop-target computation (preview, hover, and the final drop) needs to
 * agree on. Null if that page doesn't exist.
 */
internal fun pageItemsForPreview(
    state: LauncherUiState,
    pageIndex: Int,
    origin: HomeLocation?
): List<HomeItem>? {
    val page = state.pages.getOrNull(pageIndex) ?: return null
    return if (origin is HomeLocation.Page && origin.page == pageIndex) {
        page.filterIndexed { i, _ -> i != origin.slot }
    } else page
}

@Composable
private fun RenameFolderDialog(
    currentName: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(TextFieldValue(currentName, TextRange(0, currentName.length))) }
    val focusRequester = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename folder") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
            )
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.text) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** Applies pointer input handling only when [condition] is true. */
@Composable
private fun Modifier.pointerInputIf(
    condition: Boolean,
    block: suspend PointerInputScope.() -> Unit
): Modifier = if (condition) this.pointerInput(condition, block) else this

private const val DRAWER_DRAG_THRESHOLD = 18f
private const val EDGE_MARGIN_PX = 28f
private const val EDGE_HOLD_MS = 1000L
// A full page has an occupant in every single cell, unlike a normal page's
// mix of icons and empty space - so on a full page, any reasonably-aimed
// drop (people naturally aim for a cell's centre, which is exactly the
// fold-zone) risks arming a fold, and the travel time alone from the dock
// to a spot on a busy page can already approach the old 1000ms threshold
// without the user feeling like they paused at all. Doubled so only a
// genuinely deliberate hold - not just "however long it took to get here" -
// arms a fold.
private const val FOLDER_DWELL_MS = 2000L

/**
 * How long a drop the dock refused takes to fly back where it came from -
 * a touch quicker than a settling reflow, so it reads as a rebound rather
 * than another considered move.
 */
private const val REJECT_RETURN_MS = 200

/** How long a folder takes to grow open, and to shrink back away. */
private const val FOLDER_OPEN_MS = 220
private const val FOLDER_CLOSE_MS = 160

/**
 * How large a folder starts before it expands - close enough to full size
 * that it reads as the same panel growing, not a separate thing zooming in
 * from nowhere.
 */
private const val FOLDER_OPEN_FROM_SCALE = 0.86f

private const val BLUR_RADIUS_PX = 45f
private const val HOVER_DEBOUNCE_MS = 80L
private val DOCK_AREA_HEIGHT = 96.dp
private val GHOST_SIZE = 72.dp
private const val GHOST_SCALE = 1.12f
