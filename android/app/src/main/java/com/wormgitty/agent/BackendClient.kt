package com.wormgitty.agent

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/** Minimal transport seam: liveness probe against the local backend. */
object BackendClient {

    /** Emulator's alias for the host loopback; real devices use the LAN address. */
    var baseUrl: String = "http://10.0.2.2:3000"

    /** GET {baseUrl}/api/v1/ping — returns the HTTP status code, null when unreachable. */
    fun ping(): Int? = try {
        val conn = URL("$baseUrl/api/v1/ping").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "GET"
            conn.responseCode.also { Log.i("BackendClient", "ping $baseUrl -> $it") }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        Log.e("BackendClient", "ping failed", e)
        null
    }
}
