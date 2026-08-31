package dev.mcb.spike

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Bare control panel for the spike. Start the bridge, watch the log, poke the
 * routes. No AppCompat, no layout XML — this is throwaway.
 */
class MainActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra(LoopbackService.EXTRA_LINE)?.let { append(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        root.addView(button("Grant permissions") { requestPermissionsIfNeeded() })
        root.addView(button("Start bridge + detector") {
            requestPermissionsIfNeeded()
            LoopbackService.start(this)
            append("start requested")
        })
        root.addView(button("Stop bridge") {
            LoopbackService.stop(this)
            append("stop requested")
        })
        root.addView(button("Show newest missed call") { showNewestMissed() })
        root.addView(button("Scan now (via service)") {
            LoopbackService.scanNow(this)
            append("scan requested")
        })
        root.addView(button("Self-test  GET /pending") { selfTest("/pending", "GET") })
        root.addView(button("Seed a fake call") { selfTest("/debug/seed", "POST") })
        root.addView(button("Show queue") { withRepo { append("\n" + it.snapshot()) } })
        root.addView(button("Deliver oldest via deep link") { withRepo { it.deliverOldestViaDeepLink(this) } })
        root.addView(button("Retry pass now") { withRepo { it.runRetryPass() } })
        root.addView(button("Re-queue FAILED") { withRepo { it.retryFailed() } })
        root.addView(button("Clear queue + dedup") { withRepo { it.clearAll() } })

        logView = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            movementMethod = ScrollingMovementMethod()
        }
        logScroll = ScrollView(this).apply {
            addView(logView)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        }
        root.addView(logScroll)

        setContentView(root)
        append("ready. bridge target: http://${BridgeServer.HOST}:${BridgeServer.PORT}")
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(LoopbackService.ACTION_LOG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(logReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(logReceiver) }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    private fun selfTest(path: String, method: String) = thread {
        val url = "http://${BridgeServer.HOST}:${BridgeServer.PORT}$path"
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 2000
                readTimeout = 2000
                if (method == "POST") doOutput = true
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            append("self-test $method $path -> $code  $body")
            conn.disconnect()
        } catch (e: Exception) {
            append("self-test $method $path FAILED: ${e.message}")
        }
    }

    private fun showNewestMissed() = thread {
        val call = CallLogReader(this).newestMissed()
        append(if (call == null) "no missed call in log (or READ_CALL_LOG denied)" else "newest: ${call.describe()}")
    }

    /** Repo touches the DB, so always off the main thread. */
    private fun withRepo(block: (QueueRepository) -> Unit) = thread {
        runCatching { block(QueueRepository(applicationContext) { append(it) }) }
            .onFailure { append("repo error: ${it.message}") }
    }

    private fun requestPermissionsIfNeeded() {
        val wanted = buildList {
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.READ_CONTACTS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

        if (wanted.isEmpty()) {
            append("all permissions granted")
        } else {
            append("requesting: ${wanted.joinToString { it.substringAfterLast('.') }}")
            requestPermissions(wanted.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissions.forEachIndexed { i, p ->
            val ok = grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED
            append("  ${p.substringAfterLast('.')}: ${if (ok) "granted" else "denied"}")
        }
        append("restart the bridge so the detector picks up new permissions")
    }

    private fun append(line: String) = runOnUiThread {
        logView.append("${clock.format(Date())}  $line\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
