/**
 * The menu long-pressing empty space on a page opens.
 */
package com.example.opennanoor.launcher

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp


/**
 * The menu long-pressing empty space on a page opens - Wallpaper & style,
 * Widgets, Home settings, the same three Pixel Launcher's own long-press
 * offers.
 */
@Composable
internal fun HomeLongPressMenu(
    onOpenSettings: () -> Unit,
    onAddWidget: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    PopupMenuScrim(onDismiss = onDismiss) {
        HomeLongPressMenuItem("Wallpaper & style") {
            onDismiss()
            val intent = Intent(Intent.ACTION_SET_WALLPAPER)
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            }
        }
        HomeLongPressMenuItem("Widgets") {
            onDismiss()
            onAddWidget()
        }
        HomeLongPressMenuItem("Home settings", onClick = onOpenSettings)
    }
}

/**
 * The menu an app icon's own options badge (the spanner) opens - Rename,
 * Remove from screen, Uninstall, Change image. Same look/size/shape as
 * [HomeLongPressMenu] - both are [PopupMenuScrim]'s own content, just
 * different items.
 *
 * Uninstall doesn't show its own confirmation here - [onUninstall] hands
 * straight to the system's ACTION_DELETE, which brings up Android's own
 * "Do you want to uninstall this app?" dialog, the same one a real Pixel
 * uninstall shows, rather than this app asking twice.
 *
 * [onChangeImage] isn't wired to anything yet - a future feature, kept as
 * a visible but inert item for now rather than leaving it off the menu
 * only to add it back later.
 */
@Composable
internal fun TileOptionsMenu(
    onRename: () -> Unit,
    onRemoveFromScreen: () -> Unit,
    onUninstall: () -> Unit,
    onChangeImage: () -> Unit,
    onDismiss: () -> Unit
) {
    // Only the action itself is called here, no separate onDismiss() on top
    // of it - each action (in LauncherScreen) already closes the popup
    // itself as part of its own state change (e.g. Rename flips
    // showAppOptionsMenu off but deliberately leaves the target it still
    // needs alone). Calling this menu's onDismiss - which unconditionally
    // clears that target too - right after would undo exactly the state
    // Rename still needs, and reading the same target right after it was
    // cleared is what silently broke "Remove from screen" (and, on the
    // widget version of this menu, "Remove widget") before this. onDismiss
    // is still wired to PopupMenuScrim's own tap-outside-to-cancel below.
    PopupMenuScrim(onDismiss = onDismiss) {
        HomeLongPressMenuItem("Rename", onClick = onRename)
        HomeLongPressMenuItem("Remove from screen", onClick = onRemoveFromScreen)
        HomeLongPressMenuItem("Uninstall", onClick = onUninstall)
        HomeLongPressMenuItem("Change image", onClick = onChangeImage)
    }
}

/**
 * The menu a widget's own options badge (the spanner) opens - "Remove
 * widget" always, plus "Text color" and "Background image" when
 * [showAppearanceOptions] is set (only meaningful for a widget this app
 * itself renders the RemoteViews for, like the clock widget - a
 * third-party widget's own layout isn't ours to recolor or re-background).
 * Same [PopupMenuScrim] as every other popup here. Remove still acts
 * instantly on tap, no confirmation - one accidental brush of the badge
 * lost the widget outright.
 */
@Composable
internal fun WidgetOptionsMenu(
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
    showAppearanceOptions: Boolean = false,
    onPickTextColor: (() -> Unit)? = null,
    onPickBackgroundImage: (() -> Unit)? = null
) {
    // Only the action, same reasoning as TileOptionsMenu above - onRemove
    // itself needs to read widgetOptionsTarget in LauncherScreen before
    // anything clears it, and onDismiss does exactly that.
    PopupMenuScrim(onDismiss = onDismiss) {
        if (showAppearanceOptions) {
            onPickTextColor?.let { HomeLongPressMenuItem("Text color", onClick = it) }
            onPickBackgroundImage?.let { HomeLongPressMenuItem("Background image", onClick = it) }
        }
        HomeLongPressMenuItem("Remove widget", onClick = onRemove)
    }
}

/**
 * A small fixed palette rather than a full HSV picker - covers the common
 * cases (white/black plus a handful of saturated colors) without needing
 * any color-picker dependency or a bunch of slider UI just for a widget's
 * text. Opens over [WidgetOptionsMenu] the same way [RenameFolderDialog]
 * sits over its own long-press menu.
 */
/**
 * "No background" first - clears back to the widget's own default solid
 * color - then "Choose photo" opens the system picker. Same
 * [PopupMenuScrim] shape as every other popup here.
 */
@Composable
internal fun WidgetBackgroundImageDialog(
    onClear: () -> Unit,
    onChoosePhoto: () -> Unit,
    onDismiss: () -> Unit
) {
    PopupMenuScrim(onDismiss = onDismiss) {
        HomeLongPressMenuItem("No background", onClick = { onClear(); onDismiss() })
        HomeLongPressMenuItem("Choose photo", onClick = { onChoosePhoto(); onDismiss() })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WidgetTextColorDialog(
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val swatches = listOf(
        0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFF3B30.toInt(), 0xFFFF9500.toInt(),
        0xFFFFCC00.toInt(), 0xFF34C759.toInt(), 0xFF007AFF.toInt(), 0xFFAF52DE.toInt()
    )
    PopupMenuScrim(onDismiss = onDismiss) {
        Text(
            text = "Text color",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
        )
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            swatches.forEach { swatch ->
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(swatch))
                        .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                        .clickable {
                            onPick(swatch)
                            onDismiss()
                        }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * The shared scrim + solid rounded bubble every popup menu on the home
 * screen (long-press-empty-space, a tile's own options) uses - tapping the
 * dimmed background outside it dismisses, same as [HomeLongPressMenu]
 * always did before this was pulled out into its own composable.
 */
@Composable
private fun PopupMenuScrim(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .padding(horizontal = 48.dp)
                .clip(shape)
                // Solid, not the same translucent glass the folder preview
                // uses - this menu sits over whatever busy wallpaper/icons
                // are behind it, and needed to read clearly on any of them
                // rather than blend into the page.
                .background(Color(0xFF232326))
                .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
                .padding(vertical = 8.dp),
            content = content
        )
    }
}

@Composable
internal fun HomeLongPressMenuItem(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = Color.White,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp)
    )
}


