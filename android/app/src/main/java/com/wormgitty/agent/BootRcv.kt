package com.wormgitty.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.ForegroundServiceStartNotAllowedException

/** Restarts the agent service after boot, unlock or update; survives missing FGS allowance. */
class BootRcv : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> tryStart(context)
        }
    }

    fun tryStart(context: Context) {
        try {
            WormSvc.start(context)
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException: retried at next boot/update.
            if (android.os.Build.VERSION.SDK_INT >= 31 &&
                e !is ForegroundServiceStartNotAllowedException
            ) {
                android.util.Log.w(TAG, "fgs start deferred", e)
            }
        } catch (e: SecurityException) {
            android.util.Log.w(TAG, "fgs start denied", e)
        }
    }

    companion object {
        const val TAG = "BootRcv"
    }
}
