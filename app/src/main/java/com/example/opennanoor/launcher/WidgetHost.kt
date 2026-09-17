package com.example.opennanoor.launcher

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.opennanoor.core.Settings
import com.example.opennanoor.core.WidgetPlacement

/**
 * One placed widget: its id, its hosted view, which page it's on, which row
 * (0-indexed from the top) it starts at, how many rows tall it currently is,
 * and the shortest it can ever be shrunk to - the widget's own declared
 * [AppWidgetProviderInfo.minResizeHeight] (falling back to minHeight, for a
 * provider that doesn't set a smaller one specifically for live resizing),
 * in dp. Kept as dp, not rows, since a row's own height in px depends on
 * this device's row-count setting - HomePage converts it to a row count
 * against whatever cellHeightPx it's actually rendering with right now.
 */
data class PlacedWidget(
    val appWidgetId: Int,
    val view: AppWidgetHostView,
    val page: Int,
    val topRow: Int,
    val rowSpan: Int,
    val minHeightDp: Int
)

/**
 * Picking, binding, and hosting any number of home-screen widgets.
 * AppWidgetManager.ACTION_APPWIDGET_PICK is the real, OS-provided widget
 * picker every launcher (Pixel's included) is built on - no custom UI of
 * our own needed, and it handles the user's bind-permission approval as
 * part of picking.
 *
 * Every widget is independently positioned by its own page and topRow
 * (icons flow around each one, and through any gap between them on its own
 * page - see HomePage's own multi-band toDisplayY/toGridY) - free to drag
 * anywhere, including to a different page entirely.
 */
class WidgetHostController(
    private val activity: ComponentActivity,
    private val appWidgetHost: AppWidgetHost,
    private val settings: Settings
) {
    private val appWidgetManager = AppWidgetManager.getInstance(activity)

    /** Every bound widget, in no particular order - HomePage positions each by its own topRow. */
    var placedWidgets by mutableStateOf<List<PlacedWidget>>(emptyList())
        private set

    private var pickLauncher: ((Intent) -> Unit)? = null
    private var configureLauncher: ((Intent) -> Unit)? = null

    fun attachLaunchers(
        pick: (Intent) -> Unit,
        configure: (Intent) -> Unit
    ) {
        pickLauncher = pick
        configureLauncher = configure
    }

    /** Unbinds one placed widget entirely - the same "gone for good" a real uninstall is, not a hide. */
    fun removeWidget(appWidgetId: Int) {
        appWidgetHost.deleteAppWidgetId(appWidgetId)
        settings.widgetPlacements = settings.widgetPlacements.filterNot { it.appWidgetId == appWidgetId }
        placedWidgets = placedWidgets.filterNot { it.appWidgetId == appWidgetId }
    }

    /** Moves one widget to [page]/[row] - dragging it in HomePage's own widget block resolves to this. */
    fun setWidgetPlacement(appWidgetId: Int, page: Int, row: Int) {
        settings.widgetPlacements = settings.widgetPlacements.map {
            if (it.appWidgetId == appWidgetId) it.copy(page = page, topRow = row) else it
        }
        placedWidgets = placedWidgets.map {
            if (it.appWidgetId == appWidgetId) it.copy(page = page, topRow = row) else it
        }
    }

    /**
     * Resizes one widget to [rowSpan] rows tall - dragging its resize
     * handle in HomePage's own widget block resolves to this. [widthDp] is
     * the widget's own current on-screen width (full page width, in dp) and
     * [heightDp] its new height - both handed straight to the widget's own
     * [AppWidgetHostView.updateAppWidgetSize] so its provider gets a proper
     * onAppWidgetOptionsChanged and can redraw for the new size (a compact
     * RemoteViews layout instead of the full one, say) rather than just
     * being stretched or clipped with no say in it.
     */
    fun resizeWidget(appWidgetId: Int, rowSpan: Int, widthDp: Int, heightDp: Int) {
        settings.widgetPlacements = settings.widgetPlacements.map {
            if (it.appWidgetId == appWidgetId) it.copy(rowSpan = rowSpan) else it
        }
        placedWidgets = placedWidgets.map {
            if (it.appWidgetId == appWidgetId) {
                it.view.updateAppWidgetSize(null, widthDp, heightDp, widthDp, heightDp)
                it.copy(rowSpan = rowSpan)
            } else it
        }
    }

    /** Restores every previously bound widget into [placedWidgets] - called once on start. */
    fun restore() {
        val placements = settings.widgetPlacements
        val stillValid = mutableListOf<WidgetPlacement>()
        val restored = mutableListOf<PlacedWidget>()
        for (placement in placements) {
            val info = appWidgetManager.getAppWidgetInfo(placement.appWidgetId)
            if (info == null) {
                // The provider (or its whole app) is gone since this was
                // bound - dropped from the persisted list rather than
                // holding onto a dead id forever, which would just mean
                // every future launch repeats this same no-op lookup.
                continue
            }
            stillValid += placement
            restored += PlacedWidget(
                placement.appWidgetId, createHostView(placement.appWidgetId, info),
                placement.page, placement.topRow, placement.rowSpan, minHeightDpFor(info)
            )
        }
        if (stillValid != placements) settings.widgetPlacements = stillValid
        placedWidgets = restored
    }

    /** Starts the pick flow - a system chooser over every installed widget. */
    fun startPick() {
        val appWidgetId = appWidgetHost.allocateAppWidgetId()
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            // Both required by the system picker even when empty - it
            // reads them to merge in "custom" (non-provider) extra
            // entries, which this app doesn't offer any of.
            putParcelableArrayListExtra(
                AppWidgetManager.EXTRA_CUSTOM_INFO, ArrayList<Bundle>()
            )
            putParcelableArrayListExtra(
                AppWidgetManager.EXTRA_CUSTOM_EXTRAS, ArrayList<Bundle>()
            )
        }
        pickLauncher?.invoke(intent)
    }

    fun onPickResult(resultCode: Int, data: Intent?) {
        val appWidgetId = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
        if (resultCode != Activity.RESULT_OK || appWidgetId == -1) {
            if (appWidgetId != -1) appWidgetHost.deleteAppWidgetId(appWidgetId)
            return
        }
        val info = appWidgetManager.getAppWidgetInfo(appWidgetId)
        if (info == null) {
            appWidgetHost.deleteAppWidgetId(appWidgetId)
            return
        }
        // Some widgets need their own one-time setup screen (a weather
        // widget picking a city, say) before they're usable - the pick
        // alone isn't enough yet for those.
        val configure = info.configure
        if (configure != null) {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                component = configure
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            configureLauncher?.invoke(intent)
        } else {
            finishBinding(appWidgetId, info)
        }
    }

    fun onConfigureResult(resultCode: Int, data: Intent?) {
        val appWidgetId = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
        if (appWidgetId == -1) return
        if (resultCode != Activity.RESULT_OK) {
            appWidgetHost.deleteAppWidgetId(appWidgetId)
            return
        }
        val info = appWidgetManager.getAppWidgetInfo(appWidgetId) ?: run {
            appWidgetHost.deleteAppWidgetId(appWidgetId)
            return
        }
        finishBinding(appWidgetId, info)
    }

    private fun finishBinding(appWidgetId: Int, info: AppWidgetProviderInfo) {
        // Always lands on page 0 first, stacking right under whichever
        // widget already there currently reaches lowest - a sensible
        // starting spot for a newly added one, free to drag anywhere
        // (including to a different page) afterwards same as any other.
        val nextRow = settings.widgetPlacements
            .filter { it.page == 0 }
            .maxOfOrNull { it.topRow + it.rowSpan } ?: 0
        val placement = WidgetPlacement(appWidgetId, page = 0, topRow = nextRow, rowSpan = WIDGET_RESERVED_ROWS)
        settings.widgetPlacements = settings.widgetPlacements + placement
        placedWidgets = placedWidgets + PlacedWidget(
            appWidgetId, createHostView(appWidgetId, info), 0, nextRow, WIDGET_RESERVED_ROWS, minHeightDpFor(info)
        )
    }

    private fun createHostView(appWidgetId: Int, info: AppWidgetProviderInfo): AppWidgetHostView =
        appWidgetHost.createView(activity, appWidgetId, info).apply {
            setAppWidget(appWidgetId, info)
        }

    /** minResizeHeight is the smallest a provider says it can look GOOD at
     *  while being live-resized - falls back to minHeight (its floor for
     *  ever being placed at all) for a provider that leaves it unset (0). */
    private fun minHeightDpFor(info: AppWidgetProviderInfo): Int =
        info.minResizeHeight.takeIf { it > 0 } ?: info.minHeight
}

private const val WIDGET_HOST_ID = 1

/**
 * Sets up the [AppWidgetHost] (tied to the activity's own start/stop, per
 * AppWidgetHost's own contract - listening only while actually visible) and
 * the two activity-result launchers picking/configuring a widget need. Must
 * be called unconditionally near the top of the composable tree, same as
 * any other rememberLauncherForActivityResult use.
 */
@Composable
fun rememberWidgetHostController(activity: ComponentActivity): WidgetHostController {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val appWidgetHost = remember { AppWidgetHost(context, WIDGET_HOST_ID) }
    val controller = remember { WidgetHostController(activity, appWidgetHost, settings) }

    DisposableEffect(appWidgetHost) {
        appWidgetHost.startListening()
        controller.restore()
        onDispose { appWidgetHost.stopListening() }
    }

    val currentController by rememberUpdatedState(controller)
    val configureResultLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        currentController.onConfigureResult(result.resultCode, result.data)
    }
    val pickResultLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        currentController.onPickResult(result.resultCode, result.data)
    }
    controller.attachLaunchers(
        pick = { intent -> pickResultLauncher.launch(intent) },
        configure = { intent -> configureResultLauncher.launch(intent) }
    )

    return controller
}
