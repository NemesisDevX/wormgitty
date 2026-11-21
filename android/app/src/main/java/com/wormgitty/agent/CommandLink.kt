package com.wormgitty.agent

import android.content.Context
import android.provider.Settings
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Device-side command channel. Every INTERVAL_MS it asks the backend for this
 * device's queued commands, executes what it knows, and reports the result
 * back. Silent no-op when the device was never enrolled server-side (404/401).
 */
class CommandLink(private val context: Context) {

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread {
            while (running.get()) {
                try {
                    pollOnce()
                } catch (t: Throwable) {
                    Log.e("CommandLink", "poll failed", t)
                }
                try {
                    Thread.sleep(INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }.also { it.name = "worm-cmdlink" }
        thread?.start()
    }

    fun stop() {
        running.set(false)
    }

    private fun deviceId(): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown"

    /** Same derivation the backend applies: sha256("device-credential:{id}:{seed}"). */
    private fun deviceCredential(deviceId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("device-credential:$deviceId:$ENROLL_SEED".toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun pollOnce() {
        val deviceId = deviceId()
        val token = deviceCredential(deviceId)
        // Keep the device "online" + push the six-stat side-panel feed.
        val gender = RegistrationStore(context).load()?.gender ?: ""
        val statsJson = StatsCollector.collect(context).entries.joinToString(",") { (k, v) ->
            "\"$k\":" + when (v) {
                null -> "null"
                is Number, is Boolean -> v.toString()
                is Map<*, *> -> "{" + v.entries.joinToString(",") { (mk, mv) ->
                    "\"$mk\":" + (when (mv) {
                        null -> "null"
                        is Number, is Boolean -> mv.toString()
                        else -> "\"${mv.toString().replace("\"", "'")}\""
                    })
                } + "}"
                else -> "\"${v.toString().replace("\"", "'")}\""
            }
        }
        BackendClient.postJson(
            "/api/v1/devices/$deviceId/heartbeat", token,
            """{"gender":"$gender","stats":{$statsJson}}"""
        )
        val body = BackendClient.getJson(
            "/api/v1/devices/$deviceId/commands", token
        ) ?: return
        val queued = Json.parseToJsonElement(body).jsonArray
            .map { it.jsonObject }
            .filter { it["state"]?.jsonPrimitive?.content == "queued" }
        for (cmd in queued) {
            val id = cmd["id"]?.jsonPrimitive?.content ?: continue
            val type = cmd["type"]?.jsonPrimitive?.content ?: ""
            val (state, result) = when (type) {
                "ping" -> "executed" to "pong"
                else -> "failed" to "unsupported:$type"
            }
            Log.i("CommandLink", "cmd $id type=$type -> $state")
            BackendClient.postJson(
                "/api/v1/devices/$deviceId/commands/$id/result",
                token,
                """{"state":"$state","result":"$result"}"""
            )
        }
    }

    companion object {
        const val INTERVAL_MS = 15_000L

        /** Dev bootstrap seed shared with the backend enroll (see backend tests). */
        const val ENROLL_SEED = "global-dev-seed"
    }
}
