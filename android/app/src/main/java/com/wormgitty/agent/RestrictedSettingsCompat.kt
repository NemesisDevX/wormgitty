package com.wormgitty.agent

import android.content.Context
import android.content.Intent

/**
 * Detection helper for Android 13+ "Restricted settings". There is no public
 * "is my component restricted" API, so the practical signal is: a launcher
 * intent carrying the strict-flags extra combined with a not-yet-enabled
 * accessibility service. Best-effort only; a false negative just means the
 * wizard proceeds and the user grants from system settings directly.
 */
object RestrictedSettingsCompat {

    const val EXTRA_SUSPENDED = "android.intent.extra.RESTRICTED_COMPONENT_SUSPENDED"
    const val ACTION_APP_INFO = "com.android.settings.action.APP_INFO_RESTRICTED_SETTING"

    fun maybeBlocked(context: Context, intent: Intent?): Boolean = runCatching {
        if (android.os.Build.VERSION.SDK_INT < 33) return@runCatching false
        // Sideloading flows commonly set restricted-package flags on the launch intent.
        val flagged = intent != null && intent.hasCategory("android.intent.category.INFO") ||
            intent?.getBooleanExtra(EXTRA_SUSPENDED, false) == true
        if (!flagged) return@runCatching false
        val enabled = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        !enabled.split(":").any {
            it.equals(
                "${context.packageName}/${WormA11yService::class.java.name}",
                ignoreCase = false
            )
        }
    }.getOrDefault(false)
}
