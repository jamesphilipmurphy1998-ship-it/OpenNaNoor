package com.example.opennanoor.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.widget.RemoteViews
import com.example.opennanoor.R
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A small multi-city clock, each instance showing whichever cities its own
 * ClockWidgetConfigActivity run picked (see ClockWidgetStore). Android's
 * own updatePeriodMillis has a 30-minute floor - far too coarse for a
 * clock - so live refresh is instead driven by a self-rescheduling
 * AlarmManager tick (see scheduleTick/onReceive's ACTION_TICK branch),
 * running only while at least one instance of this widget actually exists
 * (started in onEnabled, stopped in onDisabled - not tied to this
 * receiver's own lifetime, which is far shorter than that).
 */
class ClockWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateWidget(context, appWidgetManager, it) }
    }

    override fun onEnabled(context: Context) {
        scheduleTick(context)
    }

    override fun onDisabled(context: Context) {
        cancelTick(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { ClockWidgetStore.remove(context, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TICK) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(android.content.ComponentName(context, ClockWidgetProvider::class.java))
            ids.forEach { updateWidget(context, manager, it) }
            // AlarmManager alarms are one-shot even when originally scheduled
            // as "repeating" once Doze/battery optimisation gets involved on
            // most OEMs in practice - rescheduling explicitly from here every
            // tick is what actually keeps this reliable long-term, the same
            // pattern AOSP's own analog/digital clock widgets use.
            if (ids.isNotEmpty()) scheduleTick(context)
        }
    }

    private fun scheduleTick(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = tickPendingIntent(context)
        val triggerAt = SystemClock.elapsedRealtime() + 60_000L
        // Inexact (setAndAllowWhileIdle, not setExactAndAllowWhileIdle) on
        // purpose - exact alarms need SCHEDULE_EXACT_ALARM, a permission
        // with its own user-facing grant step on Android 12+. A clock
        // widget doesn't need to the second anyway; the OS batching this
        // against other apps' own alarms is a fine tradeoff for not
        // needing another special permission.
        runCatching {
            alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
        }
    }

    private fun cancelTick(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(tickPendingIntent(context))
    }

    private fun tickPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ClockWidgetProvider::class.java).apply { action = ACTION_TICK }
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val ACTION_TICK = "com.example.opennanoor.widget.CLOCK_TICK"

        /** Called by the config activity right after saving, so the widget
         *  reflects a freshly-picked city list immediately rather than
         *  waiting for the next tick. */
        fun refresh(context: Context, appWidgetId: Int) {
            val manager = AppWidgetManager.getInstance(context)
            updateWidget(context, manager, appWidgetId)
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
            val cities = ClockWidgetStore.citiesFor(context, appWidgetId)
            val views = RemoteViews(context.packageName, R.layout.clock_widget)
            val rowIds = listOf(
                Triple(R.id.clock_row_0, R.id.clock_label_0, R.id.clock_time_0),
                Triple(R.id.clock_row_1, R.id.clock_label_1, R.id.clock_time_1),
                Triple(R.id.clock_row_2, R.id.clock_label_2, R.id.clock_time_2),
                Triple(R.id.clock_row_3, R.id.clock_label_3, R.id.clock_time_3)
            )
            val formatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
            val textColor = ClockWidgetStore.textColorFor(context, appWidgetId)
            rowIds.forEachIndexed { index, (rowId, labelId, timeId) ->
                val city = cities.getOrNull(index)
                if (city == null) {
                    views.setViewVisibility(rowId, android.view.View.GONE)
                } else {
                    views.setViewVisibility(rowId, android.view.View.VISIBLE)
                    views.setTextViewText(labelId, city.label)
                    views.setTextColor(labelId, textColor)
                    views.setTextColor(timeId, textColor)
                    val now = runCatching {
                        java.time.ZonedDateTime.now(ZoneId.of(city.zoneId)).format(formatter)
                    }.getOrDefault("--:--")
                    views.setTextViewText(timeId, now)
                }
            }
            views.setViewVisibility(
                R.id.clock_empty_hint,
                if (cities.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            )
            views.setTextColor(R.id.clock_empty_hint, textColor)

            // Three states, not two: a user-picked background photo (see
            // ClockWidgetStore's own backgroundImageFile) sits behind
            // clock_scrim, whose color switches to a much lighter overlay
            // so the photo stays visible; "No background"
            // (backgroundTransparentFor) makes clock_scrim genuinely
            // transparent rather than falling back to the solid default -
            // that fallback was the actual bug report ("no background
            // makes it black"), since the default IS a near-black solid.
            // Only with neither set does the solid #CC1C1C1E default apply.
            val transparent = ClockWidgetStore.backgroundTransparentFor(context, appWidgetId)
            val bgFile = ClockWidgetStore.backgroundImageFile(context, appWidgetId)
            val backgroundBitmap = if (transparent) null else bgFile.takeIf { it.exists() }
                ?.let { BitmapFactory.decodeFile(it.path) }
            if (backgroundBitmap != null) {
                views.setImageViewBitmap(R.id.clock_bg_image, backgroundBitmap)
                views.setViewVisibility(R.id.clock_bg_image, android.view.View.VISIBLE)
                views.setInt(R.id.clock_scrim, "setBackgroundColor", 0x40000000)
            } else if (transparent) {
                views.setViewVisibility(R.id.clock_bg_image, android.view.View.GONE)
                views.setInt(R.id.clock_scrim, "setBackgroundColor", 0x00000000)
            } else {
                views.setViewVisibility(R.id.clock_bg_image, android.view.View.GONE)
                views.setInt(R.id.clock_scrim, "setBackgroundColor", 0xCC1C1C1E.toInt())
            }

            // Deliberately NOT wired to a click-to-reconfigure PendingIntent
            // across the widget's own background any more - this launcher
            // already learned the hard way (see HomePage's own RemoveBadge
            // comment) that a click target spanning an AppWidgetHostView's
            // full bounds is a real native View with its own touch
            // dispatch, and it claims touches landing anywhere in that
            // rectangle before Compose's overlay handles (like this
            // widget's own resize grip, positioned right at its bottom
            // edge) ever get a chance to - "can't resize it" was this
            // widget's version of the exact bug that comment describes.
            // Re-picking cities for now means removing and re-adding the
            // widget; a dedicated way to reopen the config screen (e.g.
            // from the widget's own options menu) is better future work
            // than reintroducing this.

            manager.updateAppWidget(appWidgetId, views)
        }
    }
}
