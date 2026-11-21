package com.wormgitty.agent

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.provider.CallLog
import android.provider.Telephony
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import java.io.File

/**
 * Six-stat side-panel feed. Every field is independently best-effort: a denied
 * permission or missing radio just yields null, never a crash.
 */
object StatsCollector {

    fun collect(ctx: Context): Map<String, Any?> {
        val battery = batteryStats(ctx)
        return mapOf(
            "batteryPct" to battery.first,
            "charging" to battery.second,
            "battTempC" to battery.third,
            "cpuTempC" to cpuTemp(),
            "netType" to netType(ctx),
            "lastCall" to lastCall(ctx),
            "lastSms" to lastSms(ctx),
            "lastWa" to lastWhatsApp(ctx)
        )
    }

    private data class Triple<A, B, C>(val first: A, val second: B, val third: C)

    private fun batteryStats(ctx: Context): Triple<Int?, Boolean?, Double?> {
        val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return Triple(null, null, null)
        val level = i!!.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else null
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            .takeIf { it != Int.MIN_VALUE }?.let { it / 10.0 }
        return Triple(pct, charging, temp)
    }

    /** Best-effort CPU thermal zone; empty on devices that deny sysfs reads. */
    private fun cpuTemp(): Double? = runCatching {
        File("/sys/class/thermal").listFiles()
            ?.filter { it.name.startsWith("thermal_zone") }
            ?.firstNotNullOfOrNull { z ->
                runCatching { File(z, "temp").readText().trim().toDouble() }.getOrNull()
            }
            ?.let { if (it > 1000) it / 1000.0 else it }
    }.getOrNull()

    private fun netType(ctx: Context): String {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return runCatching {
            when (cm.activeNetworkInfo?.type) {
                ConnectivityManager.TYPE_WIFI -> "wifi"
                ConnectivityManager.TYPE_MOBILE -> "mobile"
                ConnectivityManager.TYPE_ETHERNET -> "ethernet"
                else -> "none"
            }
        }.getOrDefault("none")
    }

    private fun granted(ctx: Context, perm: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    private fun lastCall(ctx: Context): Map<String, Any?>? {
        if (!granted(ctx, android.Manifest.permission.READ_CALL_LOG)) return null
        return runCatching {
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.TYPE),
                null, null, "${CallLog.Calls.DATE} DESC"
            )?.use { c ->
                if (!c.moveToFirst()) return@use null
                mapOf(
                    "number" to (c.getString(0) ?: ""),
                    "atMs" to c.getLong(1),
                    "type" to c.getInt(2)
                )
            }
        }.getOrNull()
    }

    private fun lastSms(ctx: Context): Map<String, Any?>? {
        if (!granted(ctx, android.Manifest.permission.READ_SMS)) return null
        return runCatching {
            ctx.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                null, null, "${Telephony.Sms.DATE} DESC"
            )?.use { c ->
                if (!c.moveToFirst()) return@use null
                mapOf(
                    "sender" to (c.getString(0) ?: ""),
                    "preview" to (c.getString(1) ?: "").take(80),
                    "atMs" to c.getLong(2)
                )
            }
        }.getOrNull()
    }

    private fun lastWhatsApp(ctx: Context): Map<String, Any?>? =
        NotificationsStore.load(ctx)
            .lastOrNull { it.pkg == "com.whatsapp" }
            ?.let {
                mapOf(
                    "sender" to it.title,
                    "preview" to it.text.take(80),
                    "atMs" to it.atMs
                )
            }
}
