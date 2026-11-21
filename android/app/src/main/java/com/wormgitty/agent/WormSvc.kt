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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Special-use foreground service: heartbeats a health snapshot to
 * filesDir/agent/health.json on start and every 60s afterwards, so health is
 * readable by StatusActivity even after this process is killed.
 */
class WormSvc : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val beating = AtomicBoolean(false)
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
        val now = System.currentTimeMillis()
        HealthStore.write(this, HealthState(HealthStore.STATE_STARTED, now, now))
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
        startBeating(now)
        return START_STICKY
    }

    private fun startBeating(startedAt: Long) {
        if (beating.get()) return
        beating.set(true)
        beatThread = Thread {
            while (beating.get()) {
                HealthStore.write(
                    this,
                    HealthState(HealthStore.STATE_RUNNING, startedAt, System.currentTimeMillis())
                )
                try {
                    Thread.sleep(INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }.also { it.name = "worm-healthbeat" }
        beatThread.start()
    }

    override fun onDestroy() {
        beating.set(false)
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
    }
}
