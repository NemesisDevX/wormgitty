package com.wormgitty.agent

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Periodic inventory scan — reports the three photo-category counts to the
 * backend so the dashboard pull modal always shows live numbers.
 */
class PullWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        return try {
            CommandLink(applicationContext).reportInventory()
            Result.success()
        } catch (_: Throwable) {
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "wg_photo_scan"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<PullWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
