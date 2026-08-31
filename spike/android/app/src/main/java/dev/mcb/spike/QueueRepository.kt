package dev.mcb.spike

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.mcb.spike.data.CallbackDb
import dev.mcb.spike.data.ProcessedCall
import dev.mcb.spike.data.QueuedTask
import org.json.JSONArray
import org.json.JSONObject

/**
 * The single door to the dedup ledger and the retry queue. All methods block;
 * callers are already off the main thread (NanoHTTPD worker, CallDetector's
 * background handler, WorkManager, or an explicit `thread { }` in the UI).
 */
class QueueRepository(
    context: Context,
    private val onLog: (String) -> Unit = {},
) {
    private val dao = CallbackDb.get(context).queueDao()

    companion object {
        const val PENDING = "PENDING"
        const val SENT = "SENT"
        const val FAILED = "FAILED"
        const val MAX_ATTEMPTS = 5
        const val STALE_AFTER_MS = 60_000L
    }

    // --- dedup ---------------------------------------------------------------

    /** Record ids already in the call log so history is never enqueued. Idempotent. */
    fun baselineProcessed(callLogIds: Set<Long>) {
        if (callLogIds.isEmpty()) return
        val now = System.currentTimeMillis()
        dao.markProcessedAll(callLogIds.map { ProcessedCall(it, now) })
        onLog("baselined ${callLogIds.size} call-log ids (total processed=${dao.processedCount()})")
    }

    /** true if this id is new and was just claimed; false if already handled. */
    fun claim(callLogId: Long): Boolean =
        dao.markProcessed(ProcessedCall(callLogId, System.currentTimeMillis())) != -1L

    // --- queue ------------------------------------------------------------

    fun enqueue(call: MissedCall, source: String = "calllog") {
        val now = System.currentTimeMillis()
        val id = dao.enqueue(
            QueuedTask(
                callLogId = call.id,
                payload = call.toJson(source).toString(),
                state = PENDING,
                createdAt = now,
                updatedAt = now,
            )
        )
        onLog("enqueued task #$id for call ${call.id}")
    }

    /** PENDING rows as the plugin sees them, each with our row id attached. */
    fun pendingCallsJson(): JSONArray {
        val arr = JSONArray()
        dao.pending().forEach { row ->
            arr.put(JSONObject(row.payload).put("taskRowId", row.id))
        }
        return arr
    }

    /** Ack from the plugin. It echoes the call `id` string, not our row id. */
    fun ackByCallId(callId: String): Boolean {
        val row = dao.pending().firstOrNull {
            JSONObject(it.payload).optString("id") == callId
        } ?: return false
        dao.setState(row.id, SENT, null, System.currentTimeMillis())
        onLog("ack call $callId -> task #${row.id} SENT")
        return true
    }

    fun health(): JSONObject {
        val counts = dao.stateCounts().joinToString(", ").ifBlank { "empty" }
        return JSONObject()
            .put("ok", true)
            .put("pending", dao.pending().size)
            .put("counts", counts)
    }

    fun snapshot(): String {
        val rows = dao.all()
        if (rows.isEmpty()) return "queue empty"
        return rows.joinToString("\n") { r ->
            "#${r.id} ${r.state} call=${r.callLogId} attempts=${r.attempts}" +
                (r.lastError?.let { "  err=$it" } ?: "")
        }
    }

    // --- retry ----------------------------------------------------------------

    /** Age stale PENDING rows. Called by [RetryWorker] and the UI. */
    fun runRetryPass() {
        val now = System.currentTimeMillis()
        val stale = dao.pending().filter { now - it.updatedAt > STALE_AFTER_MS }
        if (stale.isEmpty()) {
            onLog("retry pass: nothing stale")
            return
        }
        stale.forEach { row ->
            val next = row.attempts + 1
            if (next >= MAX_ATTEMPTS) {
                dao.setState(row.id, FAILED, "no ack after $next passes", now)
                onLog("task #${row.id} -> FAILED")
            } else {
                dao.bumpAttempt(row.id, now)
                onLog("task #${row.id} still pending, attempt $next")
            }
        }
    }

    fun retryFailed(): Int {
        val n = dao.retryAllFailed(System.currentTimeMillis())
        onLog("re-queued $n FAILED tasks")
        return n
    }

    fun clearAll() {
        dao.clearTasks()
        dao.clearProcessed()
        onLog("cleared queue and dedup ledger")
    }

    // --- deep-link delivery (channel B1 / Path A) ---------------------------

    /**
     * Fire the create-task deep link for the oldest PENDING row. Success -> SENT.
     * ActivityNotFoundException (SP missing / updating) -> bump attempt, stay
     * PENDING, and let [RetryWorker] pick it up; FAILED once attempts run out.
     */
    fun deliverOldestViaDeepLink(context: Context) {
        val row = dao.pending().firstOrNull()
        if (row == null) {
            onLog("deep-link: nothing pending")
            return
        }
        val call = JSONObject(row.payload)
        val name = call.optString("name").takeIf { it.isNotBlank() && it != "null" }
        val number = call.optString("number")
        val title = "Call back ${name ?: number}"
        val notes = "Missed $number · sp-cb:${call}"

        val uri = Uri.parse("com.super-productivity.app://create-task").buildUpon()
            .appendQueryParameter("title", title)
            .appendQueryParameter("notes", notes)
            .build()
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            dao.setState(row.id, SENT, null, System.currentTimeMillis())
            onLog("task #${row.id} delivered via deep link -> SENT")
        } catch (e: ActivityNotFoundException) {
            val next = row.attempts + 1
            if (next >= MAX_ATTEMPTS) {
                dao.setState(row.id, FAILED, "ActivityNotFound x$next", System.currentTimeMillis())
                onLog("task #${row.id} -> FAILED (Super Productivity not installed?)")
            } else {
                dao.bumpAttempt(row.id, System.currentTimeMillis())
                onLog("task #${row.id} deep link failed, attempt $next, still PENDING")
                RetryWorker.runOnce(context)
            }
        }
    }

    fun seedFakeCall() {
        val fake = MissedCall(
            id = -System.currentTimeMillis(), // negative so it never collides with a real _ID
            number = "+1555${(1000000..9999999).random()}",
            name = "Seed Caller",
            timestampMillis = System.currentTimeMillis(),
            durationSec = 0,
        )
        claim(fake.id)
        enqueue(fake, source = "seed")
    }
}
