/**
 * Every tunable number the launcher's gestures and animations share, in one place.
 * Durations are ms, distances are px unless the name says dp.
 * Add new tuning here rather than inlining a magic number at a call site - this file is the one place to look when a gesture feels wrong.
 */
package com.example.opennanoor.launcher

import androidx.compose.ui.unit.dp


internal const val DRAWER_DRAG_THRESHOLD = 18f
internal const val SEARCH_DRAG_THRESHOLD = 18f
/** How far a pull-down at the very top of the drawer's grid has to travel before it dismisses. */
internal const val PULL_TO_DISMISS_THRESHOLD_PX = 120f
internal const val EDGE_HOLD_MS = 1000L
/** How close the finger itself (not the ghost's edge) has to get to the true screen edge to arm a page flip. */
internal val EDGE_TRIGGER_DP = 16.dp
// How long a drag has to rest over an icon before its folder preview opens
// and a drop there would merge rather than insert.
//
// This was briefly doubled to 2000ms on a theory that accidental folds were
// behind drops merging instead of inserting. That theory was wrong - the
// real cause was the drop-index math disagreeing with the rendering math
// (fixed separately) - and the longer dwell only made the folder preview
// feel sluggish to open, so it's back to 1000ms.
internal const val FOLDER_DWELL_MS = 1000L

/**
 * How long a drop the dock refused takes to fly back where it came from -
 * a touch quicker than a settling reflow, so it reads as a rebound rather
 * than another considered move.
 */
internal const val REJECT_RETURN_MS = 200

/** How long a folder takes to grow open, and to shrink back away. */
internal const val FOLDER_OPEN_MS = 220
internal const val FOLDER_CLOSE_MS = 220

/**
 * How large a folder starts before it expands. 0.86 (only a 14% size
 * change) combined with a 110ms duration read as barely-there - closer to
 * the panel just appearing at full size than visibly growing into place.
 * Starting noticeably smaller gives the eye something to actually track.
 */
internal const val FOLDER_OPEN_FROM_SCALE = 0.5f

/**
 * Timing for the small drag-hover fold preview specifically - not tied to
 * FOLDER_OPEN_MS/CLOSE_MS (the full folder view's own timing). A drag is
 * already doing a lot of per-frame work competing for frames, so this one
 * needs to be slower to have any real chance of showing visible in-between
 * frames rather than reading as a cut.
 */
internal const val MINI_PREVIEW_OPEN_MS = 380
internal const val MINI_PREVIEW_CLOSE_MS = 260

/**
 * How big the preview is relative to a plain cell - see FolderPreview's own
 * sizeDp. The closed folder tile underneath is the same size as any other
 * plain cell, so starting the preview's scaleIn at less than this fraction
 * makes it launch from SMALLER than the icon it's replacing - an unrelated
 * tiny thing appearing and growing, not that folder visibly expanding.
 * Starting exactly here means the preview's first frame is the same size,
 * in the same place, as the closed tile it replaces.
 */
internal const val MINI_PREVIEW_SIZE_MULTIPLIER = 2.1f
internal const val MINI_PREVIEW_FROM_SCALE = 1f / MINI_PREVIEW_SIZE_MULTIPLIER

/**
 * Where the target cell's own top edge falls within the preview box's total
 * height, as a fraction from the top - the box is centred on the target
 * cell (see FolderPreview's own offset math: half its own height is
 * MINI_PREVIEW_SIZE_MULTIPLIER/2 cells, the cell itself is 1 cell, so the
 * cell's top sits (MINI_PREVIEW_SIZE_MULTIPLIER/2 - 0.5) cells down from the
 * box's own top edge). Used as the scale animation's transformOrigin so
 * growth is anchored there instead of the box's centre - see the comment on
 * the AnimatedVisibility using it for why.
 */
internal const val MINI_PREVIEW_ORIGIN_Y =
    (MINI_PREVIEW_SIZE_MULTIPLIER / 2f - 0.5f) / MINI_PREVIEW_SIZE_MULTIPLIER

internal const val BLUR_RADIUS_PX = 45f
internal const val HOVER_DEBOUNCE_MS = 80L
/** How often the fold-dwell accumulator (LauncherScreen's own arm
 *  LaunchedEffect) samples drag.hoverTarget. */
internal const val DWELL_TICK_MS = 50L
/** How long a departure from the currently-accumulating fold target is
 *  tolerated - pausing, not resetting, the dwell clock - before it's
 *  treated as a real abandonment and the accumulator resets to zero.
 *  Wide on purpose: on-device logging of a real drag caught a genuine
 *  reach-and-correct overshoot keeping the finger outside the target's
 *  fold zone for the better part of a second before settling back onto
 *  it - normal human movement, not something to punish by losing all
 *  progress toward the dwell. */
internal const val DWELL_ABANDON_MS = 900L
internal val DOCK_AREA_HEIGHT = 96.dp
internal val GHOST_SIZE = 72.dp
internal const val GHOST_SCALE = 1.12f

