package dev.mcb.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD

/**
 * Foreground service that owns the loopback [BridgeServer] for its whole lifetime.
 * Start / stop it from [MainActivity]. Log lines are mirrored to logcat (tag MCB)
 * and broadcast to the activity via [ACTION_LOG].
 */
class LoopbackService : Service() {

    private var server: BridgeServer? = null
    private var detector: CallDetector? = null
    private lateinit var repo: QueueRepository

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())

        repo = QueueRepository(this, ::emit)

        server = BridgeServer(repo, ::emit).also {
            try {
                it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                emit("server up on ${BridgeServer.HOST}:${BridgeServer.PORT}")
            } catch (e: Exception) {
                emit("server FAILED to start: ${e.message}")
            }
        }

        detector = CallDetector(
            context = this,
            repo = repo,
            onLog = ::emit,
            onMissed = { call -> emit("detector reported call ${call.id}") },
        ).also { it.start() }

        RetryWorker.schedule(this)
        emit("retry worker scheduled (15 min)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SCAN_NOW) detector?.scanNow()
        return START_STICKY
    }

    override fun onDestroy() {
        detector?.stop()
        server?.stop()
        emit("server stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun emit(line: String) {
        Log.i("MCB", line)
        sendBroadcast(
            Intent(ACTION_LOG).setPackage(packageName).putExtra(EXTRA_LINE, line)
        )
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Loopback bridge", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MCB spike bridge")
            .setContentText("Serving ${BridgeServer.HOST}:${BridgeServer.PORT}")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_LOG = "dev.mcb.spike.LOG"
        const val ACTION_SCAN_NOW = "dev.mcb.spike.SCAN_NOW"
        const val EXTRA_LINE = "line"
        private const val CHANNEL_ID = "loopback"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, LoopbackService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LoopbackService::class.java))
        }

        fun scanNow(context: Context) {
            context.startService(
                Intent(context, LoopbackService::class.java).setAction(ACTION_SCAN_NOW)
            )
        }
    }
}
