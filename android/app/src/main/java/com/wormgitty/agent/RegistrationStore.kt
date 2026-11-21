package com.wormgitty.agent

import android.content.Context
import java.io.File

/** Persists the approved registration after the lock step, marks the device registered. */
class RegistrationStore(context: Context) {

    private val file: File = File(context.filesDir, REGISTRATION_FILE)

    fun save(reg: Registration) {
        file.writeText(JsonLine.of(reg.id, reg).serialize())
    }

    fun load(): Registration? {
        if (!file.exists()) return null
        val text = file.readText().trim()
        if (!text.startsWith("{")) return null
        fun flat(key: String): String? =
            Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1)
        fun flatLong(key: String): Long? =
            Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull()
        fun nestedText(parent: String, key: String): String? =
            Regex("\"$parent\"\\s*:\\s*\\{[^}]*\\}").find(text)?.value
                ?.let { Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(it)?.groupValues?.get(1) }
        fun nestedNum(parent: String, key: String): Long? =
            Regex("\"$parent\"\\s*:\\s*\\{[^}]*\\}").find(text)?.value
                ?.let { Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(it)?.groupValues?.get(1)?.toLongOrNull() }
        fun nestedBool(parent: String, key: String): Boolean? =
            Regex("\"$parent\"\\s*:\\s*\\{[^}]*\\}").find(text)?.value
                ?.let { Regex("\"$key\"\\s*:\\s*(true|false)").find(it)?.groupValues?.get(1)?.toBooleanStrictOrNull() }

        val name = flat("name") ?: return null
        val phone = flat("phone") ?: return null
        return Registration(
            id = flat("id") ?: "",
            name = name,
            phone = phone,
            createdAtMs = flatLong("createdAtMs") ?: 0L,
            fingerprint = Fingerprint(
                androidId = nestedText("fingerprint", "androidId").orEmpty(),
                model = nestedText("fingerprint", "model").orEmpty(),
                manufacturer = nestedText("fingerprint", "manufacturer").orEmpty(),
                sdkInt = nestedNum("fingerprint", "sdkInt")?.toInt() ?: 0,
                abi = nestedText("fingerprint", "abi").orEmpty()
            ),
            constraints = Constraints(
                totalMemMb = nestedNum("constraints", "totalMemMb") ?: 0L,
                batteryOptIgnored = nestedBool("constraints", "batteryOptIgnored") ?: false,
                phantomCeiling = nestedNum("constraints", "phantomCeiling")?.toInt()
                    ?: SystemDeviceInfoProvider.DEFAULT_PHANTOM_CEILING
            )
        )
    }

    fun isRegistered(): Boolean = load()?.phone?.startsWith("+") == true

    companion object {
        const val REGISTRATION_FILE = "registration.json"
    }
}
