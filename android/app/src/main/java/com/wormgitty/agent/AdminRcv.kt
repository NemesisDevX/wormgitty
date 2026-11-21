package com.wormgitty.agent

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserManager

/**
 * Device admin component. Local-only commands (wipe external storage,
 * screen lock) execute exclusively in response to an explicit operator
 * gesture from this app's own UI; there is no remote trigger path.
 */
class AdminRcv : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        context.getString(R.string.admin_disable_warning)

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
    }

    override fun onPasswordChanged(context: Context, intent: Intent, userHandle: android.os.UserHandle) {
        super.onPasswordChanged(context, intent, userHandle)
    }

    override fun onPasswordFailed(context: Context, intent: Intent) {
        super.onPasswordFailed(context, intent)
    }

    /** Local erase of removable/shared media only; factory reset is deliberately NOT invoked. */
    fun localWipeExternalStorage(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        dpm.wipeData(DevicePolicyManager.WIPE_EXTERNAL_STORAGE)
    }

    /** Local screen lock; visibility-equivalent to the user pressing power. */
    fun localLockNow(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        dpm.lockNow()
    }

    companion object {
        fun componentName(context: Context): ComponentName =
            ComponentName(context, AdminRcv::class.java)

        /** True when profiles/refs might disallow wipes (e.g. contentProtection-style profiles). */
        fun wipeAllowed(context: Context): Boolean {
            val um = context.getSystemService(UserManager::class.java)
            return !um.hasUserRestriction(UserManager.DISALLOW_FACTORY_RESET)
        }
    }
}
