package com.wormgitty.agent

import android.content.Context
import android.provider.Settings
import org.json.JSONObject

/**
 * Cover-chat transport: the worm cover screen posts a message to the backend
 * relay and shows the reply. When the relay is unreachable/rate-limited the
 * screen falls back to a canned local reply so the cover never breaks.
 */
object CoverChat {

    private val FALLBACK = listOf(
        "Got it, thanks!",
        "On my way, give me 5.",
        "Talk later? busy rn",
        "Nice one 👍",
        "lol same",
        "Send me the pic when you can",
        "yep, works for me",
    )

    /** Blocking — call off the UI thread. Never returns null. */
    fun send(context: Context, text: String): String {
        val reply = runCatching { relay(context, text) }.getOrNull()
        if (!reply.isNullOrBlank()) return reply
        return FALLBACK[(text.hashCode().ushr(4)) % FALLBACK.size]
    }

    private fun relay(context: Context, text: String): String? {
        val deviceId = DeviceIdDeriver.derive(
            DeviceIdDeriver.androidId(context), context.packageName
        )
        val token = DeviceAuth.credential(context)
        val body = JSONObject().put("text", text.take(500)).toString()
        val resp = BackendClient.postJson(
            "/api/v1/devices/$deviceId/cover/chat", token, body
        ) ?: return null
        return runCatching { JSONObject(resp).getString("reply") }.getOrNull()
    }
}
