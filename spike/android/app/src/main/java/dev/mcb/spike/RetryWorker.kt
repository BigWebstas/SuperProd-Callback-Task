package dev.mcb.spike

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Drains stale rows in the retry queue. Periodic (15 min floor, WorkManager's
 * minimum) plus a one-shot kicked off after a failed deep-link delivery.
 *
 * `Worker.doWork` already runs on a background thread, so the blocking
 * [QueueRepository] calls are fine here.
 */
class RetryWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        QueueRepository(applicationContext) { Log.i("MCB", "[retry] $it") }.runRetryPass()
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "mcb-retry-periodic"
        private const val ONE_SHOT = "mcb-retry-oneshot"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RetryWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }

        fun runOnce(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_SHOT,
                androidx.work.ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<RetryWorker>().build(),
            )
        }
    }
}
