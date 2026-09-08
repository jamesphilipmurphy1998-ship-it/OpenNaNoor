package com.example.opennanoor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.opennanoor.R

/**
 * Foreground service holding a single full-screen overlay view. The view is
 * not touchable, so it tints everything without swallowing input.
 */
class TintOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlay: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val color = intent?.getIntExtra(EXTRA_COLOR, DEFAULT_COLOR) ?: DEFAULT_COLOR
        startForeground(NOTIFICATION_ID, buildNotification())
        showOverlay(color)
        return START_STICKY
    }

    private fun showOverlay(color: Int) {
        val wm = windowManager ?: getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val existing = overlay
        if (existing != null) {
            existing.setBackgroundColor(color)
            return
        }

        val view = View(this).apply { setBackgroundColor(color) }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        wm.addView(view, params)
        overlay = view
        running = true
    }

    override fun onDestroy() {
        overlay?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        overlay = null
        running = false
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen tint",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shown while the screen tint overlay is active." }
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen tint active")
            .setContentText("Tap the toggle in OpenNaNoor to turn it off.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val EXTRA_COLOR = "color"
        const val ACTION_STOP = "com.example.opennanoor.STOP_TINT"

        private const val CHANNEL_ID = "screen_tint"
        private const val NOTIFICATION_ID = 1
        private const val DEFAULT_COLOR = 0x33FF9500

        /** Simple flag so the UI can reflect state without binding. */
        var running: Boolean = false
            private set

        fun start(context: Context, color: Int) {
            val intent = Intent(context, TintOverlayService::class.java)
                .putExtra(EXTRA_COLOR, color)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TintOverlayService::class.java)
                .setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
