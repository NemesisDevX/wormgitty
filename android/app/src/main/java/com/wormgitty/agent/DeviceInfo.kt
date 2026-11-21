package com.wormgitty.agent

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

interface DeviceInfoProvider {
    fun fingerprint(): Fingerprint
    fun constraints(): Constraints
}

/** Reads the real device state; the fake transport serializes exactly what this reports. */
class SystemDeviceInfoProvider(private val context: Context) : DeviceInfoProvider {

    override fun fingerprint(): Fingerprint = Fingerprint(
        androidId = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ANDROID_ID
        ).orEmpty(),
        model = Build.MODEL.orEmpty(),
        manufacturer = Build.MANUFACTURER.orEmpty(),
        sdkInt = Build.VERSION.SDK_INT,
        abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
    )

    override fun constraints(): Constraints {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return Constraints(
            totalMemMb = mem.totalMem / (1024L * 1024L),
            batteryOptIgnored = pm.isIgnoringBatteryOptimizations(context.packageName),
            phantomCeiling = phantomCeiling()
        )
    }

    /**
     * Cached-apps ceiling the OS enforces on background processes. DeviceConfig
     * carries this key but sits outside the public SDK, so the read is reflective
     * and falls back to the documented default of 32 on any refusal.
     */
    private fun phantomCeiling(): Int =
        try {
            val cls = Class.forName("android.provider.DeviceConfig")
            val get = cls.getMethod(
                "getInt", String::class.java, String::class.java, Int::class.javaPrimitiveType
            )
            get.invoke(null, "activity_manager", "max_phantom_processes", DEFAULT_PHANTOM_CEILING)
                as Int
        } catch (_: ReflectiveOperationException) {
            DEFAULT_PHANTOM_CEILING
        } catch (_: RuntimeException) {
            DEFAULT_PHANTOM_CEILING
        }

    companion object {
        const val DEFAULT_PHANTOM_CEILING = 32
    }
}
