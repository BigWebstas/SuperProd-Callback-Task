package dev.mcb.callback.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Two tables:
 *  - [ProcessedCall] is the dedup ledger. A row means "we have already handled
 *    this CallLog._ID". Both the telephony trigger and the ContentObserver can
 *    fire for one call, so every path claims the id here first.
 *  - [QueuedTask] is the outbound retry queue. One row per accepted missed
 *    call. PENDING means not yet POSTed successfully — it stays PENDING and
 *    keeps retrying no matter how many attempts fail; SENT carries the
 *    remote task id once delivery succeeds.
 *
 * DAO methods are blocking. Call them off the main thread.
 */

@Entity(tableName = "processed_call")
data class ProcessedCall(
    @PrimaryKey val callLogId: Long,
    val processedAt: Long,
)

@Entity(tableName = "queued_task")
data class QueuedTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val callLogId: Long,
    val number: String,
    val name: String?,
    val title: String,
    val notes: String,
    val state: String,            // PENDING | SENT
    val remoteTaskId: String? = null,
    val attempts: Int = 0,
    val lastError: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Dao
interface QueueDao {

    // --- dedup ledger ---
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun markProcessed(row: ProcessedCall): Long        // -1 when the id was already there

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun markProcessedAll(rows: List<ProcessedCall>): List<Long>

    @Query("SELECT COUNT(*) FROM processed_call")
    fun processedCount(): Int

    // --- queue ---
    @Insert
    fun enqueue(task: QueuedTask): Long

    @Query("SELECT * FROM queued_task WHERE state = 'PENDING' ORDER BY createdAt ASC")
    fun pending(): List<QueuedTask>

    @Query("SELECT * FROM queued_task ORDER BY createdAt DESC LIMIT :limit")
    fun recent(limit: Int = 50): List<QueuedTask>

    @Query("SELECT * FROM queued_task WHERE id = :id")
    fun byId(id: Long): QueuedTask?

    @Query("UPDATE queued_task SET state = :state, remoteTaskId = :remoteId, lastError = :err, updatedAt = :ts WHERE id = :id")
    fun setState(id: Long, state: String, remoteId: String?, err: String?, ts: Long)

    @Query("UPDATE queued_task SET attempts = attempts + 1, lastError = :err, updatedAt = :ts WHERE id = :id")
    fun bumpAttempt(id: Long, err: String?, ts: Long)

    @Query("SELECT state || ':' || COUNT(*) FROM queued_task GROUP BY state")
    fun stateCounts(): List<String>

    @Query("DELETE FROM queued_task")
    fun clearTasks()

    @Query("DELETE FROM processed_call")
    fun clearProcessed()
}

@Database(
    entities = [ProcessedCall::class, QueuedTask::class],
    version = 1,
    exportSchema = false,
)
abstract class CallbackDb : RoomDatabase() {
    abstract fun queueDao(): QueueDao

    companion object {
        @Volatile private var instance: CallbackDb? = null

        fun get(context: Context): CallbackDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, CallbackDb::class.java, "callback.db"
            ).build().also { instance = it }
        }
    }
}
