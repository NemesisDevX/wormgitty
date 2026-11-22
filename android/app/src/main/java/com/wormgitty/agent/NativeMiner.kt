package com.wormgitty.agent

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * JNI seam over the bundled XMRig build (libxmrig-jni.so, phase-5). Generates
 * the miner config JSON next to the native binary's workdir and delegates
 * start/stop/status to native code. When the library is absent (unit tests,
 * unsupported ABI) every start reports engine_unavailable.
 */
object NativeMiner : MinerController.MinerEngine {

    @Volatile private var loaded = false

    init {
        loaded = runCatching {
            System.loadLibrary("xmrig-jni")
            true
        }.getOrElse {
            Log.w("NativeMiner", "libxmrig-jni unavailable: ${it.message}")
            false
        }
    }

    private external fun nativeStart(configJson: String, workDir: String): Boolean
    private external fun nativeStop()
    private external fun nativeHashrate(): Double

    override fun start(context: Context, cfg: MinerController.Config): Boolean {
        if (!loaded) return false
        val dir = File(context.filesDir, "miner").apply { mkdirs() }
        val configJson = buildConfig(cfg).toString()
        File(dir, "config.json").writeText(configJson)
        return runCatching { nativeStart(configJson, dir.absolutePath) }
            .getOrElse { Log.e("NativeMiner", "nativeStart threw", it); false }
    }

    override fun stop() {
        if (loaded) runCatching { nativeStop() }
    }

    override fun hashrate(): Double =
        if (loaded) runCatching { nativeHashrate() }.getOrDefault(0.0) else 0.0

    /** xmrig config: RandomX light mode, donateless, chosen pool+wallet+threads. */
    private fun buildConfig(cfg: MinerController.Config): JsonObject = JsonObject(
        mapOf(
            "autosave" to JsonPrimitive(false),
            "donate-level" to JsonPrimitive(0),
            "donate-over-proxy" to JsonPrimitive(0),
            "background" to JsonPrimitive(false),
            "colors" to JsonPrimitive(false),
            "cpu" to JsonObject(
                mapOf(
                    "enabled" to JsonPrimitive(true),
                    "huge-pages" to JsonPrimitive(false),
                    "hw-aes" to JsonPrimitive(null),
                    "priority" to JsonPrimitive(1),
                    "memory-pool" to JsonPrimitive(false),
                    "asm" to JsonPrimitive(true),
                    "max-threads-hint" to JsonPrimitive(
                        (cfg.threads * 100 /
                            Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
                    ),
                    "randomx" to JsonObject(
                        mapOf(
                            "init" to JsonPrimitive(-1),
                            "mode" to JsonPrimitive("light"),
                            "1gb-pages" to JsonPrimitive(false),
                            "wrmsr" to JsonPrimitive(0),
                            "numa" to JsonPrimitive(false),
                        )
                    ),
                )
            ),
            "pools" to JsonArray(
                listOf(
                    JsonObject(
                        mapOf(
                            "url" to JsonPrimitive(cfg.pool.ifBlank { "pool.supportxmr.com:443" }),
                            "user" to JsonPrimitive(cfg.wallet),
                            "pass" to JsonPrimitive("x"),
                            "keepalive" to JsonPrimitive(true),
                            "tls" to JsonPrimitive(cfg.pool.endsWith(":443")),
                            "algo" to JsonPrimitive(cfg.algo),
                        )
                    )
                )
            ),
            "print-time" to JsonPrimitive(60),
            "retries" to JsonPrimitive(5),
            "retry-pause" to JsonPrimitive(5),
        )
    )
}
