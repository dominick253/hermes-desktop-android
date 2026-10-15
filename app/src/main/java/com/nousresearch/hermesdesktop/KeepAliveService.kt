package com.nousresearch.hermesdesktop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Optional keep-alive. Android freezes cached apps and lets the CPU idle, which
 * drops the dashboard WebSocket mid-turn. A low-importance foreground service plus
 * a partial wake lock keeps the process (and the Chromium renderer bound to it)
 * runnable while the screen is off. Opt-in from the in-app menu.
 */
class KeepAliveService : Service() {

    companion object {
        const val CHANNEL_ID = "hermes_stream"
        const val NOTIF_ID = 4200
        const val WAKE_LOCK_TAG = "hermes:stream"
        const val MAX_HOLD_MS = 4 * 60 * 60 * 1000L

        @Volatile
        var running = false
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Hermes session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the Hermes dashboard stream alive"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(MAX_HOLD_MS)
        }
        running = true
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_hermes)
            .setContentTitle("Hermes connected")
            .setContentText("Streaming continues while the screen is off")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running = false
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
