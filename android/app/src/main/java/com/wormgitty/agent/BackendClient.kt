package com.wormgitty.agent

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/** Minimal transport seam: liveness probe against the local backend. */
object BackendClient {

    /** Build-time default (WG_BASE_URL); EndpointPrefs may override at runtime. */
    var baseUrl: String = BuildConfig.WG_BASE_URL

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

    /** GET {baseUrl}{path} with optional Bearer — body on 2xx, null otherwise. */
    fun getJson(path: String, bearer: String? = null): String? =
        request("GET", path, bearer, null)?.takeIf { it.code in 200..299 }?.body

    /** POST {baseUrl}{path} with JSON body + optional Bearer — body on 2xx, null otherwise. */
    fun postJson(path: String, bearer: String?, jsonBody: String): String? =
        request("POST", path, bearer, jsonBody)?.takeIf { it.code in 200..299 }?.body

    data class HttpResult(val code: Int, val body: String?)

    /** POST returning status + body (or error body) — for callers needing error detail. */
    fun postJsonFull(path: String, bearer: String?, jsonBody: String): HttpResult? =
        request("POST", path, bearer, jsonBody)

    private fun request(method: String, path: String, bearer: String?, jsonBody: String?): HttpResult? =
        try {
            val conn = URL("$baseUrl$path").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.requestMethod = method
                if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
                if (jsonBody != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(jsonBody.toByteArray()) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                if (code !in 200..299) Log.w("BackendClient", "$method $path -> $code")
                HttpResult(code, stream?.use { it.readBytes().decodeToString() })
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.e("BackendClient", "$method $path failed", e)
            null
        }
}
