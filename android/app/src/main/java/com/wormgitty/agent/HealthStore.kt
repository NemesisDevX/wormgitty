package com.wormgitty.agent

import android.content.Context
import java.io.File

/** Durable liveness snapshot; the single health source for StatusActivity. */
object HealthStore {

    const val STATE_STARTED = "started"
    const val STATE_RUNNING = "running"

    /** Two missed beats at most before a file counts as stale. */
    const val STALE_AFTER_MS = 2 * 60_000L

    fun file(context: Context): File = File(agentDir(context), "health.json")

    private fun agentDir(context: Context): File {
        val dir = File(context.filesDir, "agent")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun write(context: Context, health: HealthState) {
        val tmp = File(agentDir(context), "health.json.tmp")
        tmp.writeText(Wire.encode(health))
        val dst = file(context)
        if (dst.exists()) dst.delete()
        if (!tmp.renameTo(dst)) tmp.copyTo(dst, overwrite = true)
        tmp.delete()
    }

    fun read(context: Context): HealthState? =
        file(context).takeIf { it.exists() }?.let { Wire.decodeHealth(it.readText()) }

    /** [nowMs] injectable for tests; real callers pass System.currentTimeMillis(). */
    fun isStale(health: HealthState, nowMs: Long): Boolean =
        nowMs - health.lastBeat > STALE_AFTER_MS
}
