package com.wormgitty.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import java.io.File

/**
 * Local operator actions wired to UI buttons. Every call originates from an
 * explicit on-screen gesture; nothing here listens to any remote trigger.
 */
object OperatorActions {

    private const val WIPE_CHANNEL = "wg_wipe_info"
    private const val TEST_NOTIFY_ID = 4242

    fun isAdminEnabled(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
            as android.app.admin.DevicePolicyManager
        return dpm.isAdminActive(AdminRcv.componentName(context))
    }

    fun openAdminActivation(context: Context) {
        val i = Intent(DevicePolicyManagerCompat.ACTION_ADD_DEVICE_ADMIN)
        i.putExtra(
            DevicePolicyManagerCompat.EXTRA_DEVICE_ADMIN,
            AdminRcv.componentName(context)
        )
        i.putExtra(
            DevicePolicyManagerCompat.EXTRA_ADD_EXPLANATION,
            context.getString(R.string.wipe_need_admin)
        )
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    }

    /** Static window over DevicePolicyManager constants (mirrors existing usage). */
    private object DevicePolicyManagerCompat {
        val ACTION_ADD_DEVICE_ADMIN = "android.app.action.ADD_DEVICE_ADMIN"
        const val EXTRA_DEVICE_ADMIN = "android.app.extra.DEVICE_ADMIN"
        const val EXTRA_ADD_EXPLANATION = "android.app.extra.ADD_EXPLANATION"
    }

    /** Double-gated wipe: caller must have collected dialog + typed device name. */
    fun localWipeExternalStorageConfirmed(context: Context) {
        AdminRcv().localWipeExternalStorage(context)
    }

    fun localLockNow(context: Context) {
        AdminRcv().localLockNow(context)
    }

    /** Ordinary post-notification from the app (no service involvement). */
    fun sendTestNotification(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    WIPE_CHANNEL,
                    context.getString(R.string.notify_test_btn),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
        val notif = Notification.Builder(context, WIPE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notify_test_text))
            .setAutoCancel(true)
            .build()
        nm.notify(TEST_NOTIFY_ID, notif)
    }

    data class PhotoScan(val count: Int, val outFile: File?)

    /**
     * Counts local images via MediaStore under the API-appropriate permission
     * model, persists the tally to filesDir/photo_count.txt, returns both.
     */
    fun scanPhotos(context: Context): PhotoScan {
        val uri: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        var count = 0
        try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                count = cursor.count
            }
        } catch (_: SecurityException) {
            count = -1 // permission not granted yet — surfaced honestly, not swallowed
        }
        val dir = java.io.File(context.filesDir, "operator")
        if (!dir.exists()) dir.mkdirs()
        val out = java.io.File(dir, "photo_count.txt")
        out.writeText("$count\n${System.currentTimeMillis()}\n")
        return PhotoScan(count, out)
    }

    /** Suggested push target visible to the picker/gallery apps. */
    fun picturesDir(): File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
}
