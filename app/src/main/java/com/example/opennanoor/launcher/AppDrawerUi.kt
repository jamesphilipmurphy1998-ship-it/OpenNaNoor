/**
 * The two ways to reach an app that isn't on a page: the full app drawer (swipe up) and the quick search panel (swipe down).
 */
package com.example.opennanoor.launcher

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp


/**
 * The full app list. A long-press on any app begins a drag using the same
 * [DragCoordinator] the home pages use - the caller fades this drawer's
 * background while that drag is in flight so the home screen underneath is
 * visible to drop onto, rather than closing the drawer outright, which would
 * cancel the gesture mid-flight.
 */
@Composable
internal fun AppDrawer(
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
    val gridState = rememberLazyGridState()
    // Pulling down anywhere on the grid closes it - but only once the grid
    // is already scrolled to its very top, or this would fight the grid's
    // own downward scroll through its content. NestedScrollConnection
    // rather than a plain pointerInput drag detector because the grid
    // itself already consumes vertical drag for scrolling - a second,
    // independent drag detector on the same Box would compete with it for
    // the gesture instead of only stepping in once the grid has nothing
    // left to scroll. onPreScroll sees a downward drag (available.y > 0)
    // before the grid gets to consume it; only once accumulated past a
    // real swipe's worth (not just the small settle of a fling arriving at
    // the top) does it actually dismiss, and only once per gesture.
    var pullDistance by remember { mutableStateOf(0f) }
    var dismissed by remember { mutableStateOf(false) }
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // canScrollBackward, not a hand-rolled index/offset==0 check
                // - after scrolling down and flinging back, the grid can
                // visually settle back at the top with a few stray pixels
                // still in firstVisibleItemScrollOffset (overscroll/spring
                // settling), which an exact ==0 check treats as "not at the
                // top" and refuses to arm at all. canScrollBackward is the
                // same signal the grid's own scrolling already trusts to
                // decide whether it has anywhere left to scroll to.
                val atTop = !gridState.canScrollBackward
                if (!atTop) {
                    // Confirmed on-device (logged identityHashCode across
                    // several close/reopen cycles): this composable's
                    // `remember`ed state is NOT recreated fresh each time
                    // the drawer reopens - it's the same instance the whole
                    // session, so `dismissed` staying latched true from the
                    // first successful dismiss silently blocked every
                    // later one forever. Resetting it here, the moment the
                    // grid leaves the top - which happens naturally on
                    // every reopen as soon as there's any interaction -
                    // re-arms it instead of relying on a fresh composition
                    // that was never actually going to happen.
                    dismissed = false
                    pullDistance = 0f
                    return Offset.Zero
                }
                if (dismissed) {
                    return Offset.Zero
                }
                // Confirmed on-device (logged available.y while dragging
                // down at the top): it's positive, not negative - two
                // earlier guesses at this sign both went the wrong way.
                if (available.y > 0f) {
                    pullDistance += available.y
                    if (pullDistance > PULL_TO_DISMISS_THRESHOLD_PX) {
                        dismissed = true
                        onDismiss()
                    }
                } else if (available.y < 0f) {
                    // Only a genuine reversal (scrolling forward into
                    // content) resets the count - a still-continuous
                    // downward drag can report an exact 0.0 delta on some
                    // individual frames (seen on-device), and resetting on
                    // THOSE too meant the accumulator kept getting zeroed
                    // mid-gesture, so only some swipes built up enough
                    // distance to cross the threshold before the next zero
                    // frame wiped it - intermittent, not a real toggle.
                    pullDistance = 0f
                }
                return Offset.Zero
            }
        }
    }

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
            state = gridState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(nestedScrollConnection)
                // Only the background used to dim while dragging one of
                // these out - every OTHER app tile in the grid stayed at
                // full opacity the whole time, so the home page underneath
                // (the actual drop target) was fighting for attention
                // against a still-fully-visible wall of app icons on top of
                // it, not a faded-away backdrop. Fading the whole grid,
                // icons included, leaves only the floating drag ghost
                // visible as "an app" while a drawer drag is in flight.
                .graphicsLayer { alpha = if (dimmed) 0f else 1f },
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
 * A quick way to find one app without opening the full drawer - dragging
 * down from anywhere on a page (see SEARCH_DRAG_THRESHOLD at the call site)
 * reveals this panel, a third of the screen tall, rather than a full-screen
 * takeover the way the drawer is. Tapping the scrim below it, same as the
 * drawer's own background tap, dismisses it.
 *
 * The dim behind everything and the glass card are two separately animated
 * pieces sharing one Box, not one sliding-in block - the backdrop blur
 * itself (see glassOpen/blurRadius at the call site) is already uniform and
 * immediate the moment [visible] flips, covering the whole screen at once.
 * An earlier version slid the dim down together with the card, so for as
 * long as that slide took, the blur was already showing everywhere but the
 * dim/card hadn't reached most of the screen yet - the dim's own leading
 * edge read as a solid line sweeping down over an already-blurred page. The
 * dim now just fades in place across the full screen in step with the
 * blur, and only the card itself slides.
 */
@Composable
internal fun SearchPanel(
    visible: Boolean,
    apps: List<HomeItem.AppItem>,
    recentApps: List<HomeItem.AppItem>,
    query: String,
    onQueryChange: (String) -> Unit,
    insets: PaddingValues,
    onLaunch: (LaunchableApp) -> Unit,
    onDismiss: () -> Unit
) {
    // Dismissing (tapping the scrim, launching an app, or however else
    // `visible` goes false) used to leave the keyboard up on its own timer,
    // noticeably slower than the panel's own fade/slide-out - closing felt
    // like two separate things happening rather than one. Hiding it the
    // instant `visible` flips means it comes down together with the panel
    // instead of lagging behind it.
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(visible) {
        if (!visible) {
            keyboardController?.hide()
            focusManager.clearFocus()
        } else {
            // Requesting focus the instant the panel appears, rather than
            // waiting for the user to tap the field themselves - typing is
            // the whole point of dragging this down, so the keyboard
            // should already be there for it.
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    // Empty until something's actually typed - showing every app by
    // default meant whichever one sorted first was sitting there looking
    // like a suggestion before the panel had done anything.
    // A label starting with what's typed is a closer fit than one that
    // merely contains it somewhere in the middle - "Maps" typing "ma"
    // should beat "Claude" - so those sort first rather than staying in
    // whatever order allApps happened to already be in.
    val results = remember(apps, query) {
        if (query.isBlank()) {
            emptyList()
        } else {
            apps.filter { it.entry.app.label.contains(query, ignoreCase = true) }
                .sortedBy { if (it.entry.app.label.startsWith(query, ignoreCase = true)) 0 else 1 }
        }
    }

    val shape = RoundedCornerShape(28.dp)
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.15f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onDismiss() }
            )
        }

        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(SEARCH_PANEL_HEIGHT_FRACTION)
                    .padding(
                        start = 8.dp,
                        end = 8.dp,
                        top = insets.calculateTopPadding() + 12.dp,
                        bottom = 12.dp
                    )
                    .clip(shape)
                    .background(searchGlassBrush)
                    .border(1.dp, folderGlassBorderBrush, shape)
                    .padding(16.dp)
            ) {
                val fieldShape = RoundedCornerShape(20.dp)
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(fieldShape)
                        .background(searchGlassBrush)
                        .focusRequester(searchFocusRequester),
                    placeholder = { Text("Search apps") },
                    singleLine = true,
                    shape = fieldShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color.White,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.4f),
                        cursorColor = Color.White,
                        focusedPlaceholderColor = Color.White,
                        unfocusedPlaceholderColor = Color.White
                    )
                )
                Spacer(Modifier.height(24.dp))
                // Nothing typed: the spare space below the search field
                // holds a quick way back into whatever was opened most
                // recently. The instant a letter lands, that same row swaps
                // to the 4 apps that best fit it instead - not a separate
                // list appearing underneath, the row itself just changes
                // what it's showing, the same as the dock resizing in place
                // rather than a second dock appearing. Sized to match the
                // 4-icon dock (see dockIconSize) specifically, not the page
                // grid's own 4-column size - the two just happen to be
                // equal today, but this row means the dock's size,
                // wherever that ends up.
                val displayApps = if (query.isBlank()) recentApps else results
                // Spread edge-to-edge - the leftmost and rightmost SLOTS
                // (not just whatever icons happen to be filled) line up
                // with the search field's own left and right edges above
                // them, same as this Column's own horizontal padding both
                // already share. Always laying out 4 slots via SpaceBetween
                // - real icon, or an invisible same-size placeholder if
                // there's no match for that slot - rather than only the
                // apps actually present is what keeps every filled
                // position pinned exactly where it was as the result count
                // changes. Without the placeholders, SpaceBetween would
                // recompute fresh positions for however many icons are
                // actually there each time, so typing another letter and
                // losing a match would shift every REMAINING icon rather
                // than just the lost one disappearing off the right.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    for (slot in 0 until 4) {
                        val appItem = displayApps.getOrNull(slot)
                        Box(Modifier.width(dockIconSize(DOCK_BASELINE_FOR_RECENTS))) {
                            if (appItem != null) {
                                // HomeItemTile's own Column always
                                // fillMaxWidth()s itself - fine inside the
                                // fixed-size cell every other caller places
                                // it in (a page, the dock, the drawer's
                                // grid), but as a bare child of this plain
                                // Row that meant each tile claimed the
                                // ENTIRE row's width for itself, leaving
                                // only the first one actually visible.
                                // This fixed-width Box gives it something
                                // narrower to fillMaxWidth() within instead.
                                //
                                // key(item.id) - see HomePage's own comment
                                // on this pattern - matters here
                                // specifically because typing swaps which
                                // app this same slot index shows; without
                                // it Compose would otherwise reuse
                                // whichever tile's state (its wobble/press
                                // animation) already lived at this position
                                // for the new, unrelated app that just
                                // landed there.
                                key(appItem.id) {
                                    HomeItemTile(
                                        item = appItem,
                                        onClick = { onLaunch(appItem.entry.app) },
                                        showLabel = false,
                                        iconSize = dockIconSize(DOCK_BASELINE_FOR_RECENTS)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** dockIconSize's own count for a 4-icon dock - see the recents row above. */
internal const val DOCK_BASELINE_FOR_RECENTS = 4

// The panel is top-anchored, so shrinking this moves only its bottom edge
// up, leaving the search field and the recents row exactly where they were.
// Was 1/3 - measured on-device that left about 139px of empty space below
// the recents row before the card's own bottom border; this trims roughly
// half of that back off, rather than the icons' own position moving.
internal const val SEARCH_PANEL_HEIGHT_FRACTION = 0.305f

// More opaque than folderGlassBrush - the search panel (its card and the
// search field inside it, both using this same brush so they read as one
// material) is meant to look more solidly frosted than the dim scrim
// around it, not just a slightly brighter version of the same thin glass a
// folder preview uses.
internal val searchGlassBrush = Brush.linearGradient(
    colors = listOf(
        Color.White.copy(alpha = 0.55f),
        Color.White.copy(alpha = 0.30f)
    )
)


