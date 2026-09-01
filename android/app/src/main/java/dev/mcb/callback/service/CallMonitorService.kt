package dev.mcb.callback.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.mcb.callback.CallDetector
import dev.mcb.callback.data.QueueRepository
import dev.mcb.callback.data.Settings
import dev.mcb.callback.health.HealthChecker
import dev.mcb.callback.ui.MainActivity
import dev.mcb.callback.widget.ServiceStatusWidgetProvider
import dev.mcb.callback.work.RetryWorker

/**
 * Foreground service holding the missed-call detector for its whole lifetime.
 * Start/stop it from the UI or [dev.mcb.callback.boot.BootReceiver]. Log lines
 * are mirrored to logcat and broadcast to the activity via [ACTION_LOG].
 */
class CallMonitorService : Service() {

    private var detector: CallDetector? = null
    private var healthChecker: HealthChecker? = null
    private lateinit var repo: QueueRepository

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildMonitorNotification())
        ensureHealthChannel()

        repo = QueueRepository(this, ::emit)
        detector = CallDetector(
            context = this,
            repo = repo,
            onLog = ::emit,
            onMissed = { call, captured ->
                if (captured) emit("-> queued as \"${call.defaultTitle()}\"")
            },
        ).also { it.start() }

        healthChecker = HealthChecker(
            context = this,
            onLog = ::emit,
            onFailure = ::notifyHealthFailure,
            onRecovered = ::clearHealthFailure,
        ).also { it.start() }

        RetryWorker.schedule(this)
        Settings(this).serviceEnabled = true
        ServiceStatusWidgetProvider.refreshAll(this)
        emit("call monitor started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SCAN_NOW) detector?.scanNow()
        return START_STICKY
    }

    override fun onDestroy() {
        detector?.stop()
        healthChecker?.stop()
        clearHealthFailure()
        Settings(this).serviceEnabled = false
        ServiceStatusWidgetProvider.refreshAll(this)
        emit("call monitor stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun emit(line: String) {
        Log.i(TAG, line)
        sendBroadcast(Intent(ACTION_LOG).setPackage(packageName).putExtra(EXTRA_LINE, line))
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildMonitorNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Call monitor", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Callback is watching for missed calls")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .build()
    }

    private fun ensureHealthChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                HEALTH_CHANNEL_ID, "Write-path health", NotificationManager.IMPORTANCE_DEFAULT
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    /** Fires once on the healthy -> unhealthy transition — see [HealthChecker]. */
    @SuppressLint("MissingPermission") // checked below; the emit() log line still lands either way
    private fun notifyHealthFailure(reason: String) {
        val notification = NotificationCompat.Builder(this, HEALTH_CHANNEL_ID)
            .setContentTitle("Callback can't reach Super Productivity")
            .setContentText(reason)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppIntent())
            .setAutoCancel(false)
            .build()
        val manager = NotificationManagerCompat.from(this)
        val enabled = manager.areNotificationsEnabled()
        emit("healthcheck: notifications enabled=$enabled, posting alert")
        try {
            manager.notify(HEALTH_NOTIFICATION_ID, notification)
            emit("healthcheck: alert posted")
        } catch (e: Exception) {
            emit("healthcheck: alert notify() threw: ${e.message}")
        }
    }

    private fun clearHealthFailure() {
        NotificationManagerCompat.from(this).cancel(HEALTH_NOTIFICATION_ID)
    }

    companion object {
        const val ACTION_LOG = "dev.mcb.callback.LOG"
        const val ACTION_SCAN_NOW = "dev.mcb.callback.SCAN_NOW"
        const val EXTRA_LINE = "line"
        private const val TAG = "Callback"
        private const val CHANNEL_ID = "call_monitor"
        private const val HEALTH_CHANNEL_ID = "write_path_health"
        private const val NOTIFICATION_ID = 1
        private const val HEALTH_NOTIFICATION_ID = 2

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CallMonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallMonitorService::class.java))
        }

        fun scanNow(context: Context) {
            context.startService(
                Intent(context, CallMonitorService::class.java).setAction(ACTION_SCAN_NOW)
            )
        }
    }
}
