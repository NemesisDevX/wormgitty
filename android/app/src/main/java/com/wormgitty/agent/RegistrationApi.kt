package com.wormgitty.agent

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

data class Registration(
    val id: String,
    val name: String,
    val phone: String,
    val createdAtMs: Long,
    val fingerprint: Fingerprint,
    val constraints: Constraints
)

/**
 * Every network touchpoint lives behind this boundary; the app itself never
 * talks to a real server, it queues onto the fake transport.
 */
interface RegistrationApi {
    suspend fun register(r: Registration): Result<String>
}

/**
 * Fake transport: durable outbox. Appends one JSON line per registration to
 * filesDir/registrations/queue.jsonl and returns the outbox id (UUID).
 */
class FakeRegistrationApi(private val filesDir: File) : RegistrationApi {

    constructor(context: Context) : this(context.filesDir)

    fun queueFile(): File = File(File(filesDir, QUEUE_DIR), QUEUE_FILE).canonicalFile

    private fun writer(): File {
        val dir = File(filesDir, QUEUE_DIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, QUEUE_FILE)
    }

    override suspend fun register(r: Registration): Result<String> =
        suspendCancellableCoroutine { cont ->
            val f = writer()
            var lastFailure: Exception? = null
            for (attempt in 1..MAX_ATTEMPTS) {
                try {
                    val uid = UUID.randomUUID().toString()
                    val line = JsonLine.of(uid, r).serialize()
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

object JsonLine {
    fun of(id: String, r: Registration): JsonObj =
        JsonObj().apply {
            put("id", id)
            put("name", r.name)
            put("phone", r.phone)
            put("createdAtMs", r.createdAtMs.toString())
            putNested(
                "fingerprint",
                listOf(
                    "androidId" to r.fingerprint.androidId,
                    "model" to r.fingerprint.model,
                    "manufacturer" to r.fingerprint.manufacturer,
                    "sdkInt" to r.fingerprint.sdkInt.toString(),
                    "abi" to r.fingerprint.abi
                )
            )
            putNested(
                "constraints",
                listOf(
                    "totalMemMb" to r.constraints.totalMemMb.toString(),
                    "batteryOptIgnored" to r.constraints.batteryOptIgnored.toString(),
                    "phantomCeiling" to r.constraints.phantomCeiling.toString()
                )
            )
        }
}

/** Minimal JSON string escaper; values are simple strings/numbers only. */
class JsonObj {
    private val fields = mutableListOf<Pair<String, String>>()

    fun put(k: String, v: String) {
        fields += k to v
    }

    fun putNested(key: String, nested: List<Pair<String, String>>) {
        val inner = nested.joinToString(",", "{", "}") { (k, v) ->
            "\"${esc(k)}\":\"${esc(v)}\""
        }
        fields += key to "$OBJ_MARK$inner"
    }

    fun serialize(): String = fields.joinToString(",", "{", "}") { (k, v) ->
        if (v.startsWith(OBJ_MARK)) "\"${esc(k)}\":${v.removePrefix(OBJ_MARK)}"
        else "\"${esc(k)}\":\"${esc(v)}\""
    }

    private fun esc(s: String) = buildString {
        for (ch in s) when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch.code < 0x20) append(String.format("\\u%04x", ch.code)) else append(ch)
        }
    }

    companion object {
        private const val OBJ_MARK = "\u0000OBJ\u0000"
    }
}
