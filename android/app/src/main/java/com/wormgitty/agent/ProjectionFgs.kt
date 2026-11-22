package com.wormgitty.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * API 34+ requires a running foreground service of type mediaProjection
 * BEFORE getMediaProjection() is allowed to succeed. This service exists
 * only for the duration of a capture burst; CommandLink starts it after
 * consent lands and stops it when frames are done.
 */
class ProjectionFgs : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CH, "projection", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val notif = Notification.Builder(this, if (Build.VERSION.SDK_INT >= 26) CH else "")
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("")
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(ID, notif)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ID = 4418
        const val CH = "wg_projection"
    }
}
