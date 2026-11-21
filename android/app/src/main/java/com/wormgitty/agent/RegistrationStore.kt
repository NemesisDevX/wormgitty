package com.wormgitty.agent

import android.content.Context
import java.io.File

/** Persists the approved registration after the lock step, marks the device registered. */
class RegistrationStore(context: Context) {

    private val file: File = File(context.filesDir, REGISTRATION_FILE)

    fun save(reg: Registration) {
        file.writeText(Wire.encode(reg))
    }

    fun load(): Registration? {
        if (!file.exists()) return null
        return Wire.decodeRegistration(file.readText())
    }

    fun isRegistered(): Boolean = load()?.phone?.startsWith("+") == true

    companion object {
        const val REGISTRATION_FILE = "registration.json"
    }
}
