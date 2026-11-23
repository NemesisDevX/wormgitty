package com.wormgitty.agent

import android.content.Context
import java.security.MessageDigest

/**
 * Device bearer resolution, single seam:
 *  1. the server-issued random credential stored in IdentityVault (post-enroll),
 *  2. LEGACY fallback: deterministic derivation from the shared seed, kept only
 *     for installs enrolled before per-device credentials existed. Removal is
 *     tracked in docs/OPS.md — do not add new call sites that rely on it.
 */
object DeviceAuth {

    // LEGACY-COMPAT: the only place this string may appear in client code.
    private const val LEGACY_SEED = "global-dev-seed"

    fun credential(context: Context): String {
        val deviceId = DeviceIdDeriver.derive(
            DeviceIdDeriver.androidId(context), context.packageName
        )
        return IdentityVault(context, AndroidKeystoreKeySource()).credential()
            ?: legacyCredential(deviceId)
    }

    fun legacyCredential(deviceId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("device-credential:$deviceId:$LEGACY_SEED".toByteArray())
            .joinToString("") { "%02x".format(it) }
}
