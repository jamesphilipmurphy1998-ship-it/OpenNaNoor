package com.example.opennanoor.widget

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import org.json.JSONArray

/**
 * Which cities each individual clock widget instance shows - keyed by
 * appWidgetId, since a user can drop more than one of these widgets and
 * each picks its own cities independently. Plain JSON in its own prefs
 * file, same lightweight approach Settings uses elsewhere in this app for
 * small structured data.
 */
object ClockWidgetStore {
    private const val PREFS_NAME = "clock_widget_store"

    fun citiesFor(context: Context, appWidgetId: Int): List<ClockCity> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(key(appWidgetId), null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                ClockCity(obj.getString("label"), obj.getString("zoneId"))
            }
        }.getOrDefault(emptyList())
    }

    fun setCitiesFor(context: Context, appWidgetId: Int, cities: List<ClockCity>) {
        val array = JSONArray()
        cities.forEach { city ->
            array.put(
                org.json.JSONObject().apply {
                    put("label", city.label)
                    put("zoneId", city.zoneId)
                }
            )
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(key(appWidgetId), array.toString())
            .apply()
    }

    /** White, matching clock_widget.xml's own default - only ever
     *  overridden once a user actually picks a color via the widget's own
     *  options menu (the spanner). */
    const val DEFAULT_TEXT_COLOR = 0xFFFFFFFF.toInt()

    fun textColorFor(context: Context, appWidgetId: Int): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(colorKey(appWidgetId), DEFAULT_TEXT_COLOR)

    fun setTextColorFor(context: Context, appWidgetId: Int, color: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(colorKey(appWidgetId), color)
            .apply()
    }

    /**
     * A user-picked background image, kept as our own PNG copy under
     * filesDir rather than the picked content:// Uri - we already own
     * both ends (ClockWidgetProvider inflates its RemoteViews in this same
     * app's process, since this app IS the widget host), so there's no
     * real cross-app permission boundary to route around, and a private
     * copy survives the source Uri's own permission grant ever being
     * revoked. Callers should downscale before calling [setBackgroundImage]
     * - it's redecoded from disk on every tick (see ClockWidgetProvider's
     * own scheduleTick), so a full-resolution photo would be wasted work
     * every minute for no visible benefit at widget size.
     */
    fun backgroundImageFile(context: Context, appWidgetId: Int): File =
        File(context.filesDir, "widget_backgrounds").apply { mkdirs() }.resolve("$appWidgetId.png")

    fun setBackgroundImage(context: Context, appWidgetId: Int, bitmap: Bitmap) {
        backgroundImageFile(context, appWidgetId).outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    fun clearBackgroundImage(context: Context, appWidgetId: Int) {
        backgroundImageFile(context, appWidgetId).delete()
    }

    /**
     * "No background" isn't just "no photo" - it's genuinely transparent,
     * not a fallback to the widget's own default dark scrim. Tracked
     * separately from [backgroundImageFile]'s own presence/absence: without
     * this flag, clearing a photo has nowhere to land except back on the
     * default solid color, which is exactly what a user picking "No
     * background" is trying to get AWAY from. ClockWidgetProvider checks
     * this before falling back to the default.
     */
    fun backgroundTransparentFor(context: Context, appWidgetId: Int): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(transparentKey(appWidgetId), false)

    fun setBackgroundTransparentFor(context: Context, appWidgetId: Int, transparent: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(transparentKey(appWidgetId), transparent)
            .apply()
    }

    fun remove(context: Context, appWidgetId: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(key(appWidgetId))
            .remove(colorKey(appWidgetId))
            .remove(transparentKey(appWidgetId))
            .apply()
        clearBackgroundImage(context, appWidgetId)
    }

    private fun key(appWidgetId: Int) = "cities_$appWidgetId"
    private fun colorKey(appWidgetId: Int) = "text_color_$appWidgetId"
    private fun transparentKey(appWidgetId: Int) = "bg_transparent_$appWidgetId"
}
