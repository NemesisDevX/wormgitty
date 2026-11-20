package com.wormgitty.agent

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/** Device admin component exposing wipe-data policy via device_admin.xml. */
class AdminRcv : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
    }
}
