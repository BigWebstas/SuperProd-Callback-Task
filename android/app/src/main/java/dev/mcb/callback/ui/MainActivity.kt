package dev.mcb.callback.ui

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
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import dev.mcb.callback.data.CallFilter
import dev.mcb.callback.data.QueueRepository
import dev.mcb.callback.data.Settings
import dev.mcb.callback.net.SuperProductivityApi
import dev.mcb.callback.service.CallMonitorService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Single-screen control panel: permissions, write-path config, the capture
 * rule, start/stop, and a live log. No layout XML, matching the spike — the
 * UI is throwaway-simple by design; Part 7's full rule set and template
 * editor are later polish, not this first slice.
 */
class MainActivity : Activity() {

    private val settings by lazy { Settings(this) }
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    private lateinit var hostField: EditText
    private lateinit var portField: EditText
    private lateinit var tokenField: EditText
    private lateinit var projectField: EditText
    private lateinit var filterGroup: RadioGroup
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra(CallMonitorService.EXTRA_LINE)?.let { append(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        root.addView(sectionLabel("Permissions"))
        root.addView(button("Grant permissions") { requestPermissionsIfNeeded() })

        root.addView(sectionLabel("Write path — Super Productivity Local REST API"))
        hostField = labeled(root, "Host or LAN IP", settings.apiHost)
        portField = labeled(root, "Port", settings.apiPort.toString())
        tokenField = labeled(root, "Bearer token", settings.apiToken)
        projectField = labeled(root, "Project ID (optional)", settings.projectId.orEmpty())
        root.addView(button("Save + test connection") { saveAndTest() })

        root.addView(sectionLabel("Which missed calls to capture"))
        filterGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            listOf(
                CallFilter.ALL to "All missed calls",
                CallFilter.KNOWN_ONLY to "Known contacts only",
                CallFilter.UNKNOWN_ONLY to "Unknown numbers only",
            ).forEach { (filter, label) ->
                addView(RadioButton(this@MainActivity).apply {
                    text = label
                    tag = filter
                    isChecked = settings.callFilter == filter
                })
            }
        }
        root.addView(filterGroup)
        root.addView(button("Save rule") {
            val checked = (0 until filterGroup.childCount)
                .map { filterGroup.getChildAt(it) as RadioButton }
                .firstOrNull { it.isChecked }
            settings.callFilter = (checked?.tag as? CallFilter) ?: CallFilter.ALL
            append("rule saved: ${settings.callFilter}")
        })

        root.addView(sectionLabel("Monitor"))
        root.addView(button("Start monitor") {
            CallMonitorService.start(this)
            append("start requested")
        })
        root.addView(button("Stop monitor") {
            CallMonitorService.stop(this)
            append("stop requested")
        })
        root.addView(button("Scan now") {
            CallMonitorService.scanNow(this)
            append("scan requested")
        })
        root.addView(button("Show recent") { showRecent() })
        root.addView(button("Retry failed now") {
            withRepo { append("re-queued ${it.retryFailed()} failed task(s)") }
        })
        root.addView(button("Seed a test call") { withRepo { it.seedFakeCall() } })

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

        setContentView(ScrollView(this).apply { addView(root) })
        append("ready")
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(CallMonitorService.ACTION_LOG)
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

    // --- actions ---------------------------------------------------------------

    private fun saveAndTest() {
        settings.apiHost = hostField.text.toString().trim()
        settings.apiPort = portField.text.toString().trim().toIntOrNull() ?: 3876
        settings.apiToken = tokenField.text.toString().trim()
        settings.projectId = projectField.text.toString().trim()
        append("saved: ${settings.apiHost}:${settings.apiPort}")

        val config = settings.apiConfig()
        if (!config.isConfigured) {
            append("test skipped: host and token are required")
            return
        }
        thread {
            val result = SuperProductivityApi(config).testConnection()
            result.onSuccess { code ->
                append(if (code in 200..299) "test connection -> $code  OK" else "test connection -> $code")
            }.onFailure { e ->
                append("test connection FAILED: ${e.message}")
            }
        }
    }

    private fun showRecent() = withRepo { repo ->
        val rows = repo.recent(20)
        if (rows.isEmpty()) {
            append("no captured calls yet")
        } else {
            append("--- recent (${repo.stateSummary()}) ---")
            rows.forEach { r ->
                append("#${r.id} ${r.state}  ${r.title}  attempts=${r.attempts}" +
                    (r.lastError?.let { "  err=$it" } ?: ""))
            }
        }
    }

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
    }

    // --- tiny view builders (no layout XML, matching the spike) ---------------

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        setPadding(0, 28, 0, 8)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun labeled(parent: LinearLayout, label: String, initial: String): EditText {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val lbl = TextView(this).apply { text = label; textSize = 12f }
        val field = EditText(this).apply {
            setText(initial)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        row.addView(lbl)
        row.addView(field)
        parent.addView(row)
        return field
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    private fun append(line: String) = runOnUiThread {
        logView.append("${clock.format(Date())}  $line\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
