package com.example.opennanoor.launcher

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset

/**
 * Tracks one tile being dragged across the home screen, hoisted above the
 * pager so it survives a page change mid-drag.
 *
 * Position is accumulated finger movement, not a coordinate in any one
 * page's layout - it starts at the origin slot's on-screen position and
 * moves by exactly how far the finger has moved. Since every page and the
 * dock share the same coordinate frame (they are all full-size siblings in
 * one Box), that position means the same thing regardless of which page the
 * pager is currently showing, so no per-page bookkeeping is needed.
 */
@Stable
class DragCoordinator {
    var item by mutableStateOf<HomeItem?>(null)
        private set
    var origin by mutableStateOf<HomeLocation?>(null)
        private set
    var position by mutableStateOf(Offset.Zero)
    var overDock by mutableStateOf(false)
    var overRemoveZone by mutableStateOf(false)

    /**
     * The occupied slot the finger is currently resting over, if any. Set
     * only for a slot that already holds something and isn't where the drag
     * began - the two cases where dwelling could form a folder.
     */
    var hoverTarget by mutableStateOf<HomeLocation?>(null)

    /**
     * True once the finger has rested over [hoverTarget] long enough that
     * releasing there should merge into a folder rather than push the other
     * icons aside. Cleared whenever the finger moves to a genuinely
     * different slot (see [armedTarget]).
     */
    var folderArmed by mutableStateOf(false)

    /**
     * Which slot [folderArmed] was armed for, kept even through a momentary
     * flicker of [hoverTarget] back to null - on-device logging showed a
     * finger that had held rock-steady through the whole dwell would almost
     * always twitch by a frame or two right as the fold preview popped in
     * (a startle at the very feedback that confirmed it had worked), and
     * clearing folderArmed on that alone meant the drop, moments later,
     * read as un-armed even though the finger never actually left the
     * target. Only hovering a different real slot - not a blip back to
     * nothing - should cancel an armed fold.
     */
    var armedTarget by mutableStateOf<HomeLocation?>(null)

    val active: Boolean get() = item != null

    /** True when this drag began in the drawer rather than on a home slot. */
    val fromDrawer: Boolean get() = active && origin == null

    fun start(item: HomeItem, origin: HomeLocation?, startPosition: Offset) {
        this.item = item
        this.origin = origin
        this.position = startPosition
        this.hoverTarget = null
        this.folderArmed = false
        this.armedTarget = null
    }

    fun moveBy(delta: Offset) {
        position += delta
    }

    fun end() {
        item = null
        origin = null
        overDock = false
        overRemoveZone = false
        hoverTarget = null
        folderArmed = false
        armedTarget = null
    }
}
