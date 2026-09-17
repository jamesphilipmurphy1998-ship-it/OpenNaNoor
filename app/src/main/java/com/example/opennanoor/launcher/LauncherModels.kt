/**
 * Small shared types and helpers the drag/drop code across this package agrees on.
 * Anything here is depended on by more than one file - keep it that way, and keep single-file helpers in their own file.
 */
package com.example.opennanoor.launcher

import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput


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


/** Applies pointer input handling only when [condition] is true. */
@Composable
internal fun Modifier.pointerInputIf(
    condition: Boolean,
    block: suspend PointerInputScope.() -> Unit
): Modifier = if (condition) this.pointerInput(condition, block) else this


