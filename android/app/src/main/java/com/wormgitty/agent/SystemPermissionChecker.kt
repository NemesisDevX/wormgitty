package com.wormgitty.agent

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

class SystemPermissionChecker : PermissionChecker {
    override fun states(ctx: Context): Map<PermKey, Boolean> {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val adminComp = ComponentName(ctx, AdminRcv::class.java)
        return mapOf(
            PermKey.OVERLAY to Settings.canDrawOverlays(ctx),
            PermKey.ACCESSIBILITY to isA11yEnabled(ctx),
            PermKey.ALL_FILES to allFilesGranted(),
            PermKey.BATTERY_OPT to pm.isIgnoringBatteryOptimizations(ctx.packageName),
            PermKey.DEVICE_ADMIN to dpm.isAdminActive(adminComp),
            PermKey.NOTIFICATIONS to NotificationManagerCompat.from(ctx).areNotificationsEnabled(),
            PermKey.NOTIF_LISTENER to isNotifListenerEnabled(ctx),
            PermKey.CAMERA to granted(ctx, android.Manifest.permission.CAMERA),
            PermKey.STORAGE_READ to storageGranted(ctx),
            PermKey.CALL_LOG to granted(ctx, android.Manifest.permission.READ_CALL_LOG)
        )
    }

    private fun granted(ctx: Context, perm: String): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(ctx, perm) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun storageGranted(ctx: Context): Boolean =
        granted(
            ctx,
            if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_IMAGES
            else android.Manifest.permission.READ_EXTERNAL_STORAGE
        )

    private fun isNotifListenerEnabled(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(
            ctx.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        return flat.split(':').any { it.equals(ctx.packageName, ignoreCase = true) ||
            it.startsWith("${ctx.packageName}/") }
    }

    /**
     * Real read of Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES: the value is
     * a colon-separated list of ComponentName flattens, e.g.
     * "com.wormgitty.agent/com.wormgitty.agent.WormA11yService".
     */
    private fun isA11yEnabled(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val wanted = "${ctx.packageName}/${WormA11yService::class.java.name}"
        return enabled.split(':')
            .map { it.trim() }
            .any { it.equals(wanted, ignoreCase = false) }
    }

    private fun allFilesGranted(): Boolean =
        if (Build.VERSION.SDK_INT < 30) true else try {
            Environment.isExternalStorageManager()
        } catch (_: RuntimeException) {
            // Some Robolectric environments lack volume state for this call;
            // defaulting to not-granted is the safe reading of an unavailable probe.
            false
        }
}

class SystemPermissionOpener : PermissionOpener {
    override fun open(ctx: Context, key: PermKey) {
        val intent: Intent = when (key) {
            PermKey.OVERLAY -> Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${ctx.packageName}")
            )

            PermKey.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

            PermKey.ALL_FILES ->
                if (Build.VERSION.SDK_INT >= 30) Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${ctx.packageName}")
                )
                else Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${ctx.packageName}")
                )

            PermKey.BATTERY_OPT -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${ctx.packageName}"))

            PermKey.DEVICE_ADMIN -> Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(
                    DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    ComponentName(ctx, AdminRcv::class.java)
                )
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    ctx.getString(R.string.perm_device_admin)
                )
            }

            PermKey.NOTIFICATIONS ->
                if (Build.VERSION.SDK_INT >= 26) Intent(
                    Settings.ACTION_APP_NOTIFICATION_SETTINGS
                ).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${ctx.packageName}"))

            PermKey.NOTIF_LISTENER -> Intent(
                "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"
            )

            // Runtime keys land on App Details as a fallback; the wizard itself
            // fires the runtime request before ever calling this.
            PermKey.CAMERA, PermKey.STORAGE_READ, PermKey.CALL_LOG ->
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${ctx.packageName}")
                )
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }
}
