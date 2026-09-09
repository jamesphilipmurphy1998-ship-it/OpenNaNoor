package com.example.opennanoor.launcher

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap

/** Pixel size icons are rasterised at - generous so they stay sharp. */
private const val ICON_PX = 192

@Composable
fun LauncherScreen(
    state: LauncherUiState,
    onLaunch: (LaunchableApp) -> Unit,
    onSelectPack: (String?) -> Unit,
    onToggleIosStyle: (Boolean) -> Unit,
    drawerOpen: Boolean,
    onDrawerOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val insets = WindowInsets.systemBars.asPaddingValues()

    Box(modifier.fillMaxSize()) {
        if (state.loading) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
            return@Box
        }

        HomePages(
            state = state,
            insets = insets,
            onLaunch = onLaunch,
            onOpenDrawer = { onDrawerOpenChange(true) },
            onSelectPack = onSelectPack,
            onToggleIosStyle = onToggleIosStyle
        )

        AnimatedVisibility(
            visible = drawerOpen,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut()
        ) {
            AppDrawer(
                state = state,
                insets = insets,
                onLaunch = {
                    onDrawerOpenChange(false)
                    onLaunch(it)
                }
            )
        }
    }
}

@Composable
private fun HomePages(
    state: LauncherUiState,
    insets: PaddingValues,
    onLaunch: (LaunchableApp) -> Unit,
    onOpenDrawer: () -> Unit,
    onSelectPack: (String?) -> Unit,
    onToggleIosStyle: (Boolean) -> Unit
) {
    val pageCount = state.pages.size.coerceAtLeast(1)
    val pagerState = rememberPagerState(pageCount = { pageCount })
    val layoutDirection = LocalLayoutDirection.current

    Column(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                // A swipe up anywhere on a page opens the drawer, the way a
                // stock home screen behaves.
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount ->
                        if (dragAmount < -DRAWER_DRAG_THRESHOLD) onOpenDrawer()
                    }
                }
        ) { pageIndex ->
            val page = state.pages.getOrNull(pageIndex).orEmpty()
            LazyVerticalGrid(
                columns = GridCells.Fixed(state.columns),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = 16.dp + insets.calculateTopPadding(),
                    bottom = 8.dp
                ),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                userScrollEnabled = false
            ) {
                items(page, key = { it.app.component.flattenToString() }) { entry ->
                    AppTile(entry = entry, onClick = { onLaunch(entry.app) })
                }
            }
        }

        PageDots(
            count = pageCount,
            current = pagerState.currentPage,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp)
        )

        Dock(
            entries = state.dock,
            onLaunch = onLaunch,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 8.dp + insets.calculateBottomPadding())
        )
    }

    // Settings affordance, tucked into the corner clear of the status bar.
    IconPackMenu(
        state = state,
        onSelectPack = onSelectPack,
        onToggleIosStyle = onToggleIosStyle,
        modifier = Modifier.padding(top = insets.calculateTopPadding())
    )
}

@Composable
private fun Dock(
    entries: List<LauncherEntry>,
    onLaunch: (LaunchableApp) -> Unit,
    modifier: Modifier = Modifier
) {
    if (entries.isEmpty()) return

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        entries.forEach { entry ->
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                AppTile(
                    entry = entry,
                    onClick = { onLaunch(entry.app) },
                    showLabel = false
                )
            }
        }
    }
}

@Composable
private fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    if (count <= 1) return

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center
    ) {
        repeat(count) { index ->
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(
                        Color.White.copy(alpha = if (index == current) 0.95f else 0.35f)
                    )
            )
        }
    }
}

@Composable
private fun AppDrawer(
    state: LauncherUiState,
    insets: PaddingValues,
    onLaunch: (LaunchableApp) -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
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
            items(state.allApps, key = { it.app.component.flattenToString() }) { entry ->
                AppTile(entry = entry, onClick = { onLaunch(entry.app) })
            }
        }
    }
}

@Composable
private fun AppTile(
    entry: LauncherEntry,
    onClick: () -> Unit,
    showLabel: Boolean = true
) {
    val bitmap = remember(entry.icon) {
        entry.icon.toBitmap(ICON_PX, ICON_PX).asImageBitmap()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = entry.app.label,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(56.dp)
        )
        if (showLabel) {
            Text(
                text = entry.app.label,
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
private fun IconPackMenu(
    state: LauncherUiState,
    onSelectPack: (String?) -> Unit,
    onToggleIosStyle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }

    Box(modifier) {
        // A small invisible touch target rather than a visible gear sitting on
        // top of the first row of icons.
        Spacer(
            Modifier
                .size(44.dp)
                .clickable { open = true }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(if (state.iosStyle) "iOS shape: on" else "iOS shape: off") },
                onClick = { onToggleIosStyle(!state.iosStyle); open = false }
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("System icons") },
                onClick = { onSelectPack(null); open = false }
            )
            state.availablePacks.forEach { pack ->
                DropdownMenuItem(
                    text = { Text(pack.label) },
                    onClick = { onSelectPack(pack.packageName); open = false }
                )
            }
        }
    }
}

private const val DRAWER_DRAG_THRESHOLD = 18f
