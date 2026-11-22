package com.wormgitty.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicReference

/**
 * Mining lifecycle owner: validates commands, drives the native engine seam,
 * reports state for heartbeats, and enforces the mandatory auto-stops
 * (thermal ≥45°C, battery <15% while discharging, screen-off/doze ≥10 min).
 *
 * The actual hashing lives in [MinerEngine]; phase-5 supplies the XMRig JNI
 * build — until then [NativeMiner] reports "engine_unavailable" and starts
 * fail honestly instead of pretending to hash.
 */
object MinerController {

    enum class State { STOPPED, STARTING, RUNNING, STOPPING, ERROR }

    private val state = AtomicReference(State.STOPPED)
    @Volatile private var engine: MinerEngine = NativeMiner
    @Volatile private var stopReason: String? = null
    @Volatile private var config: Config = Config()
    @Volatile private var lastTransitionAt: Long = 0L
    @Volatile private var screenOffSince: Long = 0L
    @Volatile private var watchdog: Thread? = null
    @Volatile private var screenReceiver: BroadcastReceiver? = null

    data class Config(
        val algo: String = "rx/0",
        val pool: String = "",
        val wallet: String = "",
        val threads: Int = defaultThreads(),
    )

    interface MinerEngine {
        /** Blocking start; returns false when the engine can't run. */
        fun start(context: Context, cfg: Config): Boolean
        fun stop()
        /** Samples/sec, 0 when idle. */
        fun hashrate(): Double
    }

    fun state(): State = state.get()
    fun stopReason(): String? = stopReason
    fun hashrate(): Double = if (state.get() == State.RUNNING) engine.hashrate() else 0.0

    /** androidTest seam: swap the engine; production always uses [NativeMiner]. */
    internal fun swapEngineForTest(e: MinerEngine?) {
        engine = e ?: NativeMiner
    }

    internal fun resetForTest(context: Context) {
        if (state.get() != State.STOPPED) stop(context, "test")
        stopReason = null
    }

    /** Threads default = half the cores, capped at 4. */
    fun defaultThreads(): Int =
        (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)

    /** mine_start {algo,pool,wallet,threads} — rejects while already active. */
    fun start(context: Context, payload: JsonObject?): Pair<String, String> {
        if (!state.compareAndSet(State.STOPPED, State.STARTING) &&
            !state.compareAndSet(State.ERROR, State.STARTING)
        ) {
            return "failed" to "already_running"
        }
        val cfg = Config(
            algo = payload?.get("algo")?.jsonPrimitive?.content ?: "rx/0",
            pool = payload?.get("pool")?.jsonPrimitive?.content ?: "",
            wallet = payload?.get("wallet")?.jsonPrimitive?.content ?: "",
            threads = (payload?.get("threads")?.jsonPrimitive?.content?.toIntOrNull()
                ?: defaultThreads()).coerceIn(1, 8),
        )
        config = cfg
        stopReason = null
        MinerFgs.start(context)
        val ok = runCatching { engine.start(context, cfg) }.getOrElse {
            Log.e("MinerController", "engine start threw", it); false
        }
        if (!ok) {
            state.set(State.ERROR)
            stopReason = "engine_unavailable"
            MinerFgs.stop(context)
            return "failed" to "engine_unavailable"
        }
        state.set(State.RUNNING)
        lastTransitionAt = System.currentTimeMillis()
        armWatchdog(context)
        return "executed" to "algo=${cfg.algo} threads=${cfg.threads}"
    }

    /** mine_stop — operator-initiated; also used internally by auto-stops. */
    fun stop(context: Context, reason: String = "manual"): Pair<String, String> {
        val cur = state.get()
        if (cur == State.STOPPED) return "failed" to "not_running"
        if (!state.compareAndSet(cur, State.STOPPING)) return "failed" to "busy"
        stopReason = reason
        disarmWatchdog(context)
        runCatching { engine.stop() }
        MinerFgs.stop(context)
        state.set(if (reason == "error") State.ERROR else State.STOPPED)
        return "executed" to reason
    }

    /* --------------------------- auto-stop watchdogs --------------------------- */

    private fun armWatchdog(context: Context) {
        val appCtx = context.applicationContext
        // Screen-off/doze: 10 continuous minutes in the background kills mining.
        screenOffSince = if (isScreenOn(appCtx)) 0L else System.currentTimeMillis()
        val rcv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                screenOffSince = when (i.action) {
                    Intent.ACTION_SCREEN_ON -> 0L
                    Intent.ACTION_SCREEN_OFF -> System.currentTimeMillis()
                    else -> screenOffSince
                }
            }
        }
        screenReceiver = rcv
        runCatching {
            appCtx.registerReceiver(
                rcv,
                IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) }
            )
        }
        watchdog = Thread {
            while (state.get() == State.RUNNING) {
                try {
                    Thread.sleep(15_000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                checkGuards(appCtx)?.let { reason ->
                    Log.w("MinerController", "auto-stop: $reason")
                    stop(appCtx, reason)
                    return@Thread
                }
            }
        }.also { it.name = "miner-watchdog"; it.start() }
    }

    private fun disarmWatchdog(context: Context) {
        watchdog?.interrupt()
        watchdog = null
        screenReceiver?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
        screenReceiver = null
    }

    /** Returns the first violated guard, null when all clear. */
    private fun checkGuards(context: Context): String? {
        // Thermal: sticky battery intent temperature (°C ×10) — works on every API.
        val batt = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tempTenths = batt?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        if (tempTenths >= 450) return "thermal_45c"
        val pct = batt?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batt?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = batt?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        if (pct in 0..14 && scale > 0 && !charging && pct * 100 / scale < 15) return "battery_low"
        val offFor = if (screenOffSince == 0L) 0L else System.currentTimeMillis() - screenOffSince
        if (offFor >= 10 * 60_000) return "doze_screen_off"
        if (!isScreenOn(context) && screenOffSince == 0L) {
            screenOffSince = System.currentTimeMillis()
        }
        return null
    }

    private fun isScreenOn(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isInteractive
}
