package dev.mcb.callback.health

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import dev.mcb.callback.data.Settings
import dev.mcb.callback.net.SuperProductivityApi

/**
 * Pings the configured Local REST API every [INTERVAL_MS] while the monitor
 * is running. [onFailure] fires once on the healthy -> unhealthy transition
 * (not on every failed check, or an outage would spam a notification every
 * minute); [onRecovered] fires once on the way back. A `PeriodicWorkRequest`
 * can't do this — WorkManager floors periodic work at 15 minutes — so this
 * runs its own loop on the foreground service's lifetime instead, the same
 * way [dev.mcb.callback.CallDetector] owns its background thread.
 */
class HealthChecker(
    context: Context,
    private val onLog: (String) -> Unit,
    private val onFailure: (reason: String) -> Unit,
    private val onRecovered: () -> Unit,
) {
    private val settings = Settings(context)
    private val worker = HandlerThread("callback-healthcheck").apply { start() }
    private val bg = Handler(worker.looper)
    private var wasHealthy = true

    private val loop = object : Runnable {
        override fun run() {
            check()
            bg.postDelayed(this, INTERVAL_MS)
        }
    }

    fun start() = bg.post(loop)

    fun stop() {
        bg.removeCallbacksAndMessages(null)
        worker.quitSafely()
    }

    private fun check() {
        val config = settings.apiConfig()
        if (!config.isConfigured) return // nothing to check until host + token are set

        val result = SuperProductivityApi(config).testConnection()
        val healthy = result.getOrNull()?.let { it in 200..299 } == true

        if (healthy) {
            if (!wasHealthy) {
                onLog("healthcheck: recovered")
                onRecovered()
            }
        } else {
            val reason = result.fold(
                onSuccess = { code -> "HTTP $code" },
                onFailure = { e -> e.message ?: "unreachable" },
            )
            if (wasHealthy) {
                onLog("healthcheck: FAILED — $reason")
                onFailure(reason)
            }
        }
        wasHealthy = healthy
    }

    companion object {
        private const val INTERVAL_MS = 60_000L
    }
}
