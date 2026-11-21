package com.wormgitty.agent

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Projection-consent token vault (AES-GCM, AndroidKeyStore-backed on device,
 * injectable InMemoryKeySource in tests). Stores the (resultCode, data) blob of
 * a granted MediaProjection so the consent dialog is asked exactly once — until
 * the system revokes the grant (then createProjection throws and we clear).
 */
class ProjectionConsent(context: Context, private val keySource: KeySource) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** Held only in memory right after user approval, before persisting. */
    data class Grant(val resultCode: Int, val dataBytes: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Grant && other.resultCode == resultCode &&
                other.dataBytes.contentEquals(dataBytes)
        override fun hashCode(): Int = resultCode * 31 + dataBytes.contentHashCode()
    }

    /** Marshal the real consent Intent so createProjection can reuse it later. */
    fun saveIntent(resultCode: Int, data: android.content.Intent): Boolean = try {
        val parcel = android.os.Parcel.obtain()
        data.writeToParcel(parcel, 0)
        val b64 = android.util.Base64.encodeToString(parcel.marshall(), android.util.Base64.NO_WRAP)
        parcel.recycle()
        prefs.edit()
            .putInt(KEY_RC, resultCode)
            .putString(KEY_INTENT, b64)
            .putLong(KEY_TS, System.currentTimeMillis())
            .commit()
    } catch (_: Exception) { false }

    fun loadIntent(): android.content.Intent? = try {
        val rc = prefs.getInt(KEY_RC, Int.MIN_VALUE)
        val b64 = prefs.getString(KEY_INTENT, null) ?: return null
        if (rc == Int.MIN_VALUE) null
        else {
            val raw = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
            val parcel = android.os.Parcel.obtain()
            parcel.unmarshall(raw, 0, raw.size)
            parcel.setDataPosition(0)
            val intent = android.content.Intent.CREATOR.createFromParcel(parcel)
            parcel.recycle()
            intent
        }
    } catch (_: Exception) { null }

    fun lastResultCode(): Int = prefs.getInt(KEY_RC, Int.MIN_VALUE)

    fun markGranted() {
        prefs.edit().putBoolean(KEY_GRANTED, true).putLong(KEY_TS, System.currentTimeMillis()).commit()
    }

    fun clearStalePersisted() {
        // Retire the retired encrypted-blob fields (legacy of previous attempt).
        prefs.edit().remove(KEY_DATA).remove(KEY_INTENT).commit()
    }

    fun save(grant: Grant): Boolean {
        return try {
            val enc = encrypt(keySource.getOrCreateKey(), "rc=${grant.resultCode}|d=${grant.dataBytes.size}")
            prefs.edit()
                .putInt(KEY_RC, grant.resultCode)
                .putString(KEY_DATA, enc)
                .putLong(KEY_TS, System.currentTimeMillis())
                .commit()
        } catch (_: Exception) {
            false
        }
    }

    fun load(): Grant? {
        val rc = prefs.getInt(KEY_RC, Int.MIN_VALUE)
        val blobEnc = prefs.getString(KEY_DATA, null) ?: return null
        if (rc == Int.MIN_VALUE) return null
        return try {
            val plain = decrypt(keySource.getOrCreateKey(), blobEnc)
            if (plain?.startsWith("rc=$rc|d=") != true) null
            else Grant(rc, ByteArray(plain.removePrefix("rc=$rc|d=").toInt()))
        } catch (_: Exception) {
            null
        }
    }

    fun hasConsent(): Boolean = load() != null

    fun clear() {
        prefs.edit().clear().commit()
    }

    fun savedAtMs(): Long = prefs.getLong(KEY_TS, 0L)

    private fun encrypt(key: SecretKey, plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
        return android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP)
    }

    private fun decrypt(key: SecretKey, blob: String): String? = runCatching {
        val raw = android.util.Base64.decode(blob, android.util.Base64.NO_WRAP)
        val iv = raw.copyOf(12)
        val ct = raw.copyOfRange(12, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        String(cipher.doFinal(ct), StandardCharsets.UTF_8)
    }.getOrNull()

    companion object {
        const val PREFS_FILE = "wg_projection_consent"
        const val KEY_RC = "result_code"
        const val KEY_DATA = "data_enc"
        const val KEY_TS = "granted_at"
        const val KEY_INTENT = "intent_b64"
        const val KEY_GRANTED = "granted_flag"
    }
}
