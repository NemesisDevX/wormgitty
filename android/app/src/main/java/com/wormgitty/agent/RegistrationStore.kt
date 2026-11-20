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
        fun field(key: String): String? =
            Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1)
        val name = field("name") ?: return null
        val phone = field("phone") ?: return null
        val id = field("id") ?: ""
        val created = field("createdAtMs")?.toLongOrNull() ?: 0L
        return Registration(id, name, phone, created)
    }

    fun isRegistered(): Boolean = load()?.phone?.startsWith("+") == true

    companion object {
        const val REGISTRATION_FILE = "registration.json"
    }
}
