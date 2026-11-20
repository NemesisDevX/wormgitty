package com.wormgitty.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

/** Dial *#*#2468#*#* -> read-only status screen. */
class SecretRcv : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TelephonyManager.ACTION_SECRET_CODE ||
            intent.action == "android.provider.Telephony.SECRET_CODE"
        ) {
            context.startActivity(
                Intent(context, StatusActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
