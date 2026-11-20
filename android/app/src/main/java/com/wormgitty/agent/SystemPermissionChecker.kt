package com.wormgitty.agent

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat

class SystemPermissionChecker : PermissionChecker {
    override fun states(ctx: Context): Map<PermKey, Boolean> {
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val adminComp = ComponentName(ctx, AdminRcv::class.java)
        return mapOf(
            PermKey.OVERLAY to Settings.canDrawOverlays(ctx),
            PermKey.ACCESSIBILITY to am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            ).any { it.resolveInfo.serviceInfo.packageName == ctx.packageName },
            PermKey.ALL_FILES to allFilesGranted(),
            PermKey.BATTERY_OPT to pm.isIgnoringBatteryOptimizations(ctx.packageName),
            PermKey.DEVICE_ADMIN to dpm.isAdminActive(adminComp),
            PermKey.NOTIFICATIONS to NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        )
    }

    private fun allFilesGranted(): Boolean =
        Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()
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
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }
}
