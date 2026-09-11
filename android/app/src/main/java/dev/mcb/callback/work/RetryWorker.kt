package dev.mcb.callback.work

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import dev.mcb.callback.data.QueueRepository
import java.util.concurrent.TimeUnit

/**
 * Drains stale PENDING rows by retrying delivery. Periodic only (15 min floor,
 * WorkManager's minimum) — there's no external ack to wait on any more; each
 * delivery attempt either succeeds (SENT) or fails and stays PENDING, with no
 * attempt cap, so a row just keeps getting retried here until it lands. (A
 * faster path also exists: [dev.mcb.callback.health.HealthChecker]'s recovery
 * callback runs a retry pass immediately once Super Productivity is reachable
 * again, instead of waiting out this worker's 15-minute floor.)
 */
class RetryWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        QueueRepository(applicationContext) { Log.i(TAG, "[retry] $it") }.runRetryPass()
        return Result.success()
    }

    companion object {
        private const val TAG = "Callback"
        private const val PERIODIC = "callback-retry-periodic"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RetryWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}
