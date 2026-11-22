package com.wormgitty.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Dedicated foreground service for the miner — separate from WormSvc because
 * `dataSync` FGS is capped at 6h on Android 15 and would kill long sessions.
 * Declared `specialUse` with the `cryptomining` subtype (see manifest) and
 * holds a time-boxed partial WakeLock while the engine runs.
 */
class MinerFgs : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "miner", NotificationManager.IMPORTANCE_MIN)
        )
        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_channel_name))
            .setContentText(getString(R.string.notif_service_text))
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_MIN)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, StatusActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wormgitty:miner")
            .apply { acquire(12 * 60 * 60 * 1000L) } // 12h ceiling, released on stop
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "wormgitty_miner"
        const val NOTIF_ID = 73

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, MinerFgs::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, MinerFgs::class.java)) }
        }
    }
}
