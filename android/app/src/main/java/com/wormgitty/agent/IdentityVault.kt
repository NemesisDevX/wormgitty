package com.wormgitty.agent

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.provider.Settings
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Local identity seam. Storage is AES-GCM ciphertext in a plain prefs file;
 * the wrapping key lives in AndroidKeyStore on hardware. The KeySource is
 * injectable so unit tests exercise persistence semantics deterministically
 * (hardware keystore is exercised only on-device).
 */
interface KeySource {
    fun getOrCreateKey(): SecretKey
}

/** Production key: hardware-backed AES-256/GCM under a fixed alias. */
class AndroidKeystoreKeySource(private val alias: String = "wg_identity_key") : KeySource {

    override fun getOrCreateKey(): SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}

/** Test/probe key: process-stable random; never leaves the JVM. */
class InMemoryKeySource : KeySource {
    private var key: SecretKey? = null
    override fun getOrCreateKey(): SecretKey =
        key ?: javax.crypto.KeyGenerator.getInstance("AES")
            .apply { init(256) }.generateKey().also { key = it }
}

/** Derived identity + enrollment secret for this installation. */
data class DeviceIdentity(
    val deviceId: String,
    val enrollToken: String
)

object DeviceIdDeriver {

    /**
     * Stable per installation: hash(ANDROID_ID + ':' + packageName), lower-case
     * hex, first 32 chars. Pure so tests pin exact values.
     */
    fun derive(androidId: String, packageName: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest("$androidId:$packageName".toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    fun androidId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
}

class IdentityVault(context: Context, private val keySource: KeySource) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /**
     * Loads existing identity, or creates it once and PERSISTS both halves.
     * Subsequent calls (and future app runs, same keystore key) return values
     * byte-identical to the first — never regenerated wholesale.
     */
    fun getOrCreate(deviceIdInput: String): DeviceIdentity {
        val savedId = prefs.getString(KEY_DEVICE_ID, null)
        val savedToken = decrypt(prefs.getString(KEY_TOKEN_ENC, null))
        if (!savedId.isNullOrBlank() && !savedToken.isNullOrBlank()) {
            return DeviceIdentity(savedId, savedToken)
        }
        val fresh = DeviceIdentity(
            deviceId = DeviceIdDeriver.derive(deviceIdInput, PREFS_FILE_PKG_SENTINEL),
            enrollToken = UUID.randomUUID().toString()
        )
        prefs.edit()
            .putString(KEY_DEVICE_ID, fresh.deviceId)
            .putString(KEY_TOKEN_ENC, encrypt(fresh.enrollToken))
            .commit()
        return fresh
    }

    /** True when nothing has been persisted yet (used by UI copy/tests). */
    fun isEmpty(): Boolean = prefs.getString(KEY_DEVICE_ID, null).isNullOrEmpty()

    /** Server-issued bearer credential (random per device) — stored encrypted. */
    fun saveCredential(credential: String) {
        prefs.edit().putString(KEY_CRED_ENC, encrypt(credential)).commit()
    }

    fun credential(): String? =
        decrypt(prefs.getString(KEY_CRED_ENC, null))?.takeIf { it.isNotBlank() }

    /** Enrollment completed: a server credential exists. */
    fun isEnrolled(): Boolean = credential() != null

    private fun encrypt(plain: String?): String? {
        if (plain == null) return null
        val key = keySource.getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
        val out = ByteArray(iv.size + ct.size)
        iv.copyInto(out)
        ct.copyInto(out, iv.size)
        return android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP)
    }

    private fun decrypt(blob: String?): String? {
        if (blob == null) return null
        val key = keySource.getOrCreateKey()
        val raw = android.util.Base64.decode(blob, android.util.Base64.NO_WRAP)
        val iv = raw.copyOf(IV_LEN)
        val ct = raw.copyOfRange(IV_LEN, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ct), StandardCharsets.UTF_8)
    }

    companion object {
        const val PREFS_FILE = "wg_identity_vault"
        const val PREFS_FILE_PKG_SENTINEL = "com.wormgitty.agent"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_TOKEN_ENC = "enroll_token_enc"
        const val KEY_CRED_ENC = "credential_enc"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val GCM_TAG_BITS = 128

        /** Random nonce helper for tests needing unreadable randoms. */
        fun randomHex(bytes: Int = 16): String =
            ByteArray(bytes).also { SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it) }
    }
}
