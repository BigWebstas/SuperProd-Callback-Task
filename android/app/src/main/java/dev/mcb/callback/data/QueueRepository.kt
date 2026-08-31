package dev.mcb.callback.data

import android.content.Context
import android.util.Log
import dev.mcb.callback.MissedCall
import dev.mcb.callback.net.SuperProductivityApi

/**
 * The single door to the dedup ledger and the retry queue, and the delivery
 * path to Super Productivity. All methods block; callers are already off the
 * main thread (CallDetector's background handler, WorkManager, or an explicit
 * background thread from the UI).
 */
class QueueRepository(
    private val context: Context,
    private val onLog: (String) -> Unit = {},
) {
    private val dao = CallbackDb.get(context).queueDao()
    private val settings = Settings(context)

    companion object {
        const val PENDING = "PENDING"
        const val SENT = "SENT"
        const val FAILED = "FAILED"
        const val MAX_ATTEMPTS = 8
        const val STALE_AFTER_MS = 60_000L
        private const val TAG = "Callback"
    }

    // --- dedup ---------------------------------------------------------------

    /** Record ids already in the call log so history is never re-queued. Idempotent. */
    fun baselineProcessed(callLogIds: Set<Long>) {
        if (callLogIds.isEmpty()) return
        val now = System.currentTimeMillis()
        dao.markProcessedAll(callLogIds.map { ProcessedCall(it, now) })
        onLog("baselined ${callLogIds.size} call-log ids (total processed=${dao.processedCount()})")
    }

    /** true if this id is new and was just claimed; false if already handled. */
    fun claim(callLogId: Long): Boolean =
        dao.markProcessed(ProcessedCall(callLogId, System.currentTimeMillis())) != -1L

    // --- queue + delivery -----------------------------------------------------

    /** Enqueues the call, then makes one immediate delivery attempt. */
    fun enqueueAndDeliver(call: MissedCall) {
        val now = System.currentTimeMillis()
        val title = call.defaultTitle()
        val notes = call.defaultNotes()
        val id = dao.enqueue(
            QueuedTask(
                callLogId = call.id,
                number = call.number,
                name = call.name,
                title = title,
                notes = notes,
                state = PENDING,
                createdAt = now,
                updatedAt = now,
            )
        )
        onLog("enqueued task #$id for call ${call.id}")
        deliver(id, title, notes)
    }

    /** One delivery attempt for a queued row. Safe to call repeatedly (idempotent per attempt). */
    private fun deliver(rowId: Long, title: String, notes: String) {
        val config = settings.apiConfig()
        if (!config.isConfigured) {
            onLog("task #$rowId not delivered: API host/token not configured yet")
            return
        }
        try {
            val remoteId = SuperProductivityApi(config).createTask(title, notes)
            dao.setState(rowId, SENT, remoteId, null, System.currentTimeMillis())
            onLog("task #$rowId -> SENT (remote id $remoteId)")
        } catch (e: Exception) {
            val next = (dao.byId(rowId)?.attempts ?: 0) + 1
            dao.bumpAttempt(rowId, System.currentTimeMillis())
            if (next >= MAX_ATTEMPTS) {
                dao.setState(rowId, FAILED, null, e.message, System.currentTimeMillis())
                onLog("task #$rowId -> FAILED after $next attempts: ${e.message}")
            } else {
                onLog("task #$rowId delivery failed, attempt $next, still PENDING: ${e.message}")
            }
            Log.w(TAG, "delivery failed for task #$rowId", e)
        }
    }

    /** Retries every stale PENDING row. Called by RetryWorker and the UI. */
    fun runRetryPass() {
        val now = System.currentTimeMillis()
        val stale = dao.pending().filter { now - it.updatedAt > STALE_AFTER_MS }
        if (stale.isEmpty()) {
            onLog("retry pass: nothing stale")
            return
        }
        stale.forEach { deliver(it.id, it.title, it.notes) }
    }

    fun retryFailed(): Int {
        val n = dao.retryAllFailed(System.currentTimeMillis())
        onLog("re-queued $n FAILED tasks")
        return n
    }

    fun recent(limit: Int = 50): List<QueuedTask> = dao.recent(limit)

    fun stateSummary(): String = dao.stateCounts().joinToString(", ").ifBlank { "empty" }

    fun clearAll() {
        dao.clearTasks()
        dao.clearProcessed()
        onLog("cleared queue and dedup ledger")
    }

    fun seedFakeCall() {
        val fake = MissedCall(
            id = -System.currentTimeMillis(), // negative so it never collides with a real _ID
            number = "+1555${(1000000..9999999).random()}",
            name = "Seed Caller",
            timestampMillis = System.currentTimeMillis(),
        )
        claim(fake.id)
        enqueueAndDeliver(fake)
    }
}
