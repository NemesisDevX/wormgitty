package com.wormgitty.agent

import android.content.Context
import org.json.JSONObject

/**
 * Real enrollment against POST /api/v1/devices/enroll.
 * The enroll token is a short-lived single-use JWT issued by the panel; the
 * server returns a random per-device credential which the caller persists in
 * IdentityVault. Blocking — call off the main thread.
 */
object EnrollClient {

    data class Outcome(
        val deviceId: String?,
        val credential: String?,
        val error: String?,
    ) {
        val ok: Boolean get() = credential != null
    }

    fun enroll(
        context: Context,
        enrollToken: String,
        displayName: String,
        phoneE164: String,
    ): Outcome {
        if (enrollToken.isBlank()) return Outcome(null, null, "missing_enroll_token")
        val deviceId = DeviceIdDeriver.derive(
            DeviceIdDeriver.androidId(context), context.packageName
        )
        val fp = SystemDeviceInfoProvider(context).fingerprint()
        val body = JSONObject()
            .put("enrollToken", enrollToken)
            .put("deviceId", deviceId)
            .put("platform", "ANDROID")
            .put("appType", BuildConfig.FLAVOR)
            .put("variant", "standard")
            .put(
                "fingerprint",
                JSONObject()
                    .put("androidId", fp.androidId)
                    .put("model", fp.model)
                    .put("manufacturer", fp.manufacturer)
                    .put("sdkInt", fp.sdkInt)
                    .put("abi", fp.abi)
            )
            .put(
                "identity",
                JSONObject()
                    .put("displayName", displayName)
                    .put("phone", phoneE164)
            )
            .toString()

        val res = BackendClient.postJsonFull("/api/v1/devices/enroll", null, body)
            ?: return Outcome(null, null, "unreachable")
        if (res.code !in 200..299) {
            val serverError = runCatching {
                JSONObject(res.body ?: "").optString("error")
            }.getOrNull().orEmpty()
            return Outcome(null, null, serverError.ifBlank { "http_${res.code}" })
        }
        val json = runCatching { JSONObject(res.body ?: "") }.getOrNull()
            ?: return Outcome(null, null, "bad_response")
        val credential = json.optString("credential").takeIf { it.isNotBlank() }
            ?: return Outcome(null, null, "no_credential")
        return Outcome(json.optString("deviceId", deviceId), credential, null)
    }
}
