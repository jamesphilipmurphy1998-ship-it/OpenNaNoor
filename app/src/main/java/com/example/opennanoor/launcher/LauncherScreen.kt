package com.example.opennanoor.launcher

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap

/** Pixel size icons are rasterised at - generous so they stay sharp when scaled. */
private const val ICON_PX = 192

@Composable
fun LauncherScreen(
    state: LauncherUiState,
    columns: Int,
    onLaunch: (LaunchableApp) -> Unit,
    onSelectPack: (String?) -> Unit,
    onToggleIosStyle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier.fillMaxSize()) {
        if (state.loading) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
            return@Box
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 72.dp, bottom = 32.dp
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(state.entries, key = { it.app.component.flattenToString() }) { entry ->
                AppTile(entry = entry, onClick = { onLaunch(entry.app) })
            }
        }

        IconPackMenu(
            state = state,
            onSelectPack = onSelectPack,
            onToggleIosStyle = onToggleIosStyle,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
        )
    }
}

@Composable
private fun AppTile(entry: LauncherEntry, onClick: () -> Unit) {
    val bitmap = remember(entry.icon) {
        entry.icon.toBitmap(ICON_PX, ICON_PX).asImageBitmap()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = entry.app.label,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(56.dp)
        )
        Text(
            text = entry.app.label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
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
        IconButton(onClick = { open = true }) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Choose icon pack",
                tint = Color.White
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = {
                    Text(
                        if (state.iosStyle) "iOS shape: on" else "iOS shape: off"
                    )
                },
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
            if (state.availablePacks.isEmpty()) {
                DropdownMenuItem(
                    enabled = false,
                    text = { Text("No icon packs installed") },
                    onClick = {}
                )
            }
        }
    }
}
