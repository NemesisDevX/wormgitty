package com.wormgitty.agent

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Every network touchpoint lives behind this boundary; the app itself never
 * talks to a real server, it queues onto the fake transport.
 */
interface RegistrationApi {
    suspend fun register(r: Registration): Result<String>
}

/**
 * Implementations that accept a (re)issued enroll token — the wizard writes
 * here when the operator pastes a fresh JWT after an expiry/consumption error.
 */
interface EnrollTokenAware {
    var enrollToken: String
}

class EnrollException(message: String) : Exception(message)

/**
 * Production transport: POST /devices/enroll with the panel-issued JWT, then
 * persist the returned random credential in IdentityVault. The token starts
 * from BuildConfig.WG_ENROLL_TOKEN (build-time provisioning) and may be
 * replaced via [EnrollTokenAware] (paste-token retry path).
 */
class ServerRegistrationApi(private val context: Context) : RegistrationApi, EnrollTokenAware {

    @Volatile
    override var enrollToken: String = BuildConfig.WG_ENROLL_TOKEN

    override suspend fun register(r: Registration): Result<String> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val out = EnrollClient.enroll(context, enrollToken, r.name, r.phone)
            if (!out.ok) {
                return@withContext Result.failure(EnrollException(out.error ?: "enroll_failed"))
            }
            IdentityVault(context, AndroidKeystoreKeySource())
                .saveCredential(out.credential!!)
            Result.success(out.deviceId ?: "")
        }
}

/**
 * Fake transport: durable outbox. Appends one JSON line per registration to
 * filesDir/registrations/queue.jsonl and returns the outbox id (UUID).
 */
class FakeRegistrationApi(private val filesDir: File) : RegistrationApi {

    constructor(context: Context) : this(context.filesDir)

    fun queueFile(): File = File(File(filesDir, QUEUE_DIR), QUEUE_FILE).canonicalFile

    private fun queueDir(): File {
        val dir = File(filesDir, QUEUE_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    override suspend fun register(r: Registration): Result<String> =
        suspendCancellableCoroutine { cont ->
            val f = File(queueDir(), QUEUE_FILE)
            var lastFailure: Exception? = null
            for (attempt in 1..MAX_ATTEMPTS) {
                try {
                    val uid = UUID.randomUUID().toString()
                    val line = Wire.encode(r.copy(id = uid))
                    synchronized(FakeRegistrationApi::class.java) {
                        f.appendText(line + "\n")
                    }
                    cont.resume(Result.success(uid))
                    return@suspendCancellableCoroutine
                } catch (e: Exception) {
                    lastFailure = e
                }
            }
            cont.resume(Result.failure(lastFailure ?: IllegalStateException("append failed")))
        }

    companion object {
        const val QUEUE_DIR = "registrations"
        const val QUEUE_FILE = "queue.jsonl"
        const val MAX_ATTEMPTS = 3
    }
}
