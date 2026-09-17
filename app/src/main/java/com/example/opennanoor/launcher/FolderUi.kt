/**
 * Folders, on screen.
 * The full-screen folder view, the 2x2 preview shown while dwelling over a folder mid-drag, and the rename dialog.
 * Folder *behaviour* (what merging/removing does to the model) lives in LauncherViewModel; this file is only how it looks.
 */
package com.example.opennanoor.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch


/**
 * A real preview of a folder's contents - up to four of its icons in a 2x2
 * grid - grown to roughly twice a normal tile's size and centred on the
 * folder being dwelled over. Dropping while this is showing merges into the
 * same folder it shows; it isn't just decoration standing in for that.
 *
 * [appear] is read lazily inside the graphicsLayer draw lambda, the same
 * reason drag position/ghost placement elsewhere in this file are - so this
 * animating every frame invalidates only this box's own layer, not a
 * recomposition of the screen around it.
 */
@Composable
internal fun FolderPreview(
    folder: HomeItem.FolderItem,
    centreOffsetPx: Offset,
    cellSizePx: Float,
    appear: () -> Float
) {
    val density = LocalDensity.current
    val sizeDp = with(density) { (cellSizePx * MINI_PREVIEW_SIZE_MULTIPLIER).toDp() }

    Box(
        Modifier
            .offset {
                IntOffset(
                    (centreOffsetPx.x - cellSizePx * 1.05f).toInt(),
                    (centreOffsetPx.y - cellSizePx * 1.05f).toInt()
                )
            }
            .size(sizeDp)
            .graphicsLayer {
                val scale = appear()
                scaleX = scale
                scaleY = scale
                // Anchored at the target cell's own top edge, not the
                // box's centre (graphicsLayer's own default). This box is
                // drawn far bigger than one cell (see
                // MINI_PREVIEW_SIZE_MULTIPLIER), centred on the folder
                // being hovered - scaling that from its own centre means
                // the top edge balloons upward into the ROW ABOVE the
                // folder as it grows, even though the box's centre never
                // moves. Anchoring at the target cell's own top edge
                // (MINI_PREVIEW_ORIGIN_Y, a fixed fraction of the box's
                // own height) means growth only ever extends downward and
                // sideways from the folder's actual position - never
                // upward past it.
                transformOrigin = TransformOrigin(0.5f, MINI_PREVIEW_ORIGIN_Y)
            }
            .clip(RoundedCornerShape(24.dp))
            .background(folderGlassBrush)
            .border(1.dp, folderGlassBorderBrush, RoundedCornerShape(24.dp))
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
 * Full-screen view of one folder's contents. Tap to launch; long-press to
 * jiggle, with a badge to pull an app out without going anywhere; or hold
 * and drag an icon past the folder to place it back on the home screen -
 * the same [DragCoordinator] the home pages use, so dropping it resolves
 * exactly the same way a page-to-page drag does. The folder fades rather
 * than closes while that drag is in flight, so the home screen underneath
 * is visible to drop onto without cancelling the gesture.
 */
@Composable
internal fun FolderOverlay(
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
                // A visible panel edge to grow, rather than just the title
                // and icon grid scaling in place with nothing marking their
                // own boundary - without this the scrim behind (already at
                // full size and darkness from the first frame) gave the eye
                // nothing to anchor the growth to, so the icons read as
                // zooming in isolation rather than one panel expanding.
                .clip(RoundedCornerShape(28.dp))
                .background(Color.White.copy(alpha = if (dimmed) 0f else 0.08f))
                .padding(20.dp)
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
                            // Computed once and applied to the tile and its
                            // badge together (same fix as HomePage's own
                            // tiles) - rotating only the icon, via
                            // HomeItemTile's own internal wobble, left the
                            // badge sitting at this cell's true corner while
                            // the icon spun underneath it, drifting away
                            // from the icon's own rotated corner instead of
                            // following it.
                            val angle = rememberWobble(enabled = editing, seed = componentId.hashCode())
                            Box(Modifier.graphicsLayer { rotationZ = angle }) {
                                HomeItemTile(
                                    item = appItem,
                                    onClick = {
                                        if (editing) editing = false
                                        else {
                                            onDismiss()
                                            onLaunch(appItem.entry.app)
                                        }
                                    },
                                    wobble = false
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
}


@Composable
internal fun RenameFolderDialog(
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


