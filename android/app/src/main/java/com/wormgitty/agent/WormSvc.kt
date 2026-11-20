package com.wormgitty.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.File

/**
 * Special-use foreground service: heartbeats a row to a local file every 60s
 * with IMPORTANCE_MIN so it stays invisible to the user.
 */
class WormSvc : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var beatThread: Thread

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val mgr = getSystemService(NotificationManager::class.java)
        val chan = NotificationChannel(
            CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_MIN
        )
        mgr.createNotificationChannel(chan)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceAlive.started = true
        val notif: Notification =
            Notification.Builder(this, CHANNEL_ID)
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
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        startBeating()
        return START_STICKY
    }

    private fun startBeating() {
        if (::beatThread.isInitialized && beatThread.isAlive) return
        beatThread = Thread {
            while (ServiceAlive.started) {
                try {
                    ServiceAlive.lastBeatMs = System.currentTimeMillis()
                    heartbeatFile(this).appendText("${ServiceAlive.lastBeatMs},alive\n")
                } catch (_: Exception) {
                }
                try {
                    Thread.sleep(INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }.also { it.name = "worm-heartbeat" }
        beatThread.start()
    }

    override fun onDestroy() {
        ServiceAlive.started = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "worm_svc"
        const val NOTIF_ID = 1
        const val INTERVAL_MS = 60_000L

        @JvmStatic
        fun start(context: Context) {
            val i = Intent(context, WormSvc::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }

        @JvmStatic
        fun heartbeatFile(context: Context): File =
            File(context.filesDir, "heartbeat.log")
    }
}

object ServiceAlive {
    @Volatile
    var started: Boolean = false

    @Volatile
    var lastBeatMs: Long = 0L
}
