package com.example.opennanoor.widget

import android.content.Context
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

    fun remove(context: Context, appWidgetId: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(key(appWidgetId))
            .apply()
    }

    private fun key(appWidgetId: Int) = "cities_$appWidgetId"
}
