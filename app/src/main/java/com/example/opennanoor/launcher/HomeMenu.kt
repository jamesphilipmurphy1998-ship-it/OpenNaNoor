/**
 * The menu long-pressing empty space on a page opens.
 */
package com.example.opennanoor.launcher

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
    PopupMenuScrim(onDismiss = onDismiss) {
        HomeLongPressMenuItem("Rename") {
            onDismiss()
            onRename()
        }
        HomeLongPressMenuItem("Remove from screen") {
            onDismiss()
            onRemoveFromScreen()
        }
        HomeLongPressMenuItem("Uninstall") {
            onDismiss()
            onUninstall()
        }
        HomeLongPressMenuItem("Change image") {
            onDismiss()
            onChangeImage()
        }
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


