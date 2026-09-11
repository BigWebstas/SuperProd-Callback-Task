package dev.mcb.callback.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dev.mcb.callback.data.CallFilter
import dev.mcb.callback.data.QueueRepository
import dev.mcb.callback.data.Settings
import dev.mcb.callback.net.Project
import dev.mcb.callback.net.SuperProductivityApi
import dev.mcb.callback.net.Tag
import dev.mcb.callback.service.CallMonitorService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Single-screen control panel: permissions, write-path config, the capture
 * rule, start/stop. Built in code, no layout XML. The look follows
 * PebbleRecorder (sibling repo): a plain AppCompat DayNight theme, a centred
 * column, an icon and bold status line up top, a faded version footer. The
 * debug tools and the live log sit behind an "Advanced" toggle.
 */
class MainActivity : AppCompatActivity() {

    private val settings by lazy { Settings(this) }
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    private lateinit var hostField: EditText
    private lateinit var tokenField: EditText
    private lateinit var estimateField: EditText
    private lateinit var projectSpinner: Spinner
    private lateinit var tagSpinner: Spinner
    private lateinit var filterGroup: RadioGroup
    private lateinit var declinedCheck: CheckBox
    private lateinit var statusLabel: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var advancedBox: LinearLayout

    /** Spinner row -> project; index 0 is always Inbox (`null`). */
    private var projectItems: List<Project?> = listOf(null)

    /** Spinner row -> tag; index 0 is always "No tag" (`null`). */
    private var tagItems: List<Tag?> = listOf(null)

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val line = intent?.getStringExtra(CallMonitorService.EXTRA_LINE) ?: return
            append(line)
            // The service is the actual source of truth for its own lifecycle —
            // resync off these two lines rather than trust only the button taps.
            when (line) {
                "call monitor started" -> setStatus(true)
                "call monitor stopped" -> setStatus(false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        root.addView(android.widget.ImageView(this).apply {
            setImageResource(dev.mcb.callback.R.drawable.ic_callback)
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)).apply {
                bottomMargin = dp(12)
            }
        })

        statusLabel = TextView(this).apply {
            text = statusText(CallMonitorService.isRunning)
            textSize = 18f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        root.addView(statusLabel)

        root.addView(heading("Permissions"))
        root.addView(button("Grant permissions") { requestPermissionsIfNeeded() })

        root.addView(heading("Write path — Super Productivity Local REST API"))
        hostField = field(root, "Host[:port], LAN IP[:port], or https://proxy-domain", settings.apiHost)
        tokenField = field(root, "Bearer token", settings.apiToken)
        estimateField = field(root, "Default task time (minutes, 0 = none)", settings.defaultEstimateMinutes.toString())

        root.addView(label("Project"))
        projectSpinner = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(320), WRAP_CONTENT)
        }
        root.addView(projectSpinner)
        setProjectItems(initialProjectItems())
        projectSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val picked = projectItems.getOrNull(position)
                // The adapter fires this once on attach and again on every rebuild;
                // only write when the choice actually differs from what's stored.
                if (picked?.id == settings.projectId) return
                settings.projectId = picked?.id
                settings.projectTitle = picked?.title
                append("project -> ${picked?.title ?: "Inbox"}")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        root.addView(label("Tag (applied to every callback task)"))
        tagSpinner = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(320), WRAP_CONTENT)
        }
        root.addView(tagSpinner)
        setTagItems(initialTagItems())
        tagSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val picked = tagItems.getOrNull(position)
                // Same as the project spinner: the adapter fires this on attach and on
                // every rebuild; only write when the choice actually changed.
                if (picked?.id == settings.tagId) return
                settings.tagId = picked?.id
                settings.tagTitle = picked?.title
                append("tag -> ${picked?.title ?: "none"}")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        root.addView(button("Save + test connection") { saveAndTest() })

        root.addView(heading("Which missed calls to capture"))
        filterGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            listOf(
                CallFilter.ALL to "All missed calls",
                CallFilter.KNOWN_ONLY to "Known contacts only",
                CallFilter.UNKNOWN_ONLY to "Unknown numbers only",
            ).forEach { (filter, text) ->
                addView(RadioButton(this@MainActivity).apply {
                    // RadioGroup only enforces mutual exclusion between children that have
                    // a real view id — without one, more than one button can show checked.
                    id = View.generateViewId()
                    this.text = text
                    tag = filter
                    isChecked = settings.callFilter == filter
                })
            }
        }
        root.addView(filterGroup)
        declinedCheck = CheckBox(this).apply {
            text = "Also capture declined calls (title prefixed \"Declined, Call back\")"
            isChecked = settings.captureDeclined
        }
        root.addView(declinedCheck)
        root.addView(button("Save rule") {
            val checked = (0 until filterGroup.childCount)
                .map { filterGroup.getChildAt(it) as RadioButton }
                .firstOrNull { it.isChecked }
            settings.callFilter = (checked?.tag as? CallFilter) ?: CallFilter.ALL

            val wasDeclined = settings.captureDeclined
            settings.captureDeclined = declinedCheck.isChecked
            append("rule saved: ${settings.callFilter}, declined=${settings.captureDeclined}")
            if (settings.captureDeclined && !wasDeclined) {
                // otherwise every already-declined call in history looks "new" to the
                // detector's next scan and floods in at once.
                withRepo { it.baselineDeclinedHistory() }
            }
        })

        root.addView(heading("Monitor"))
        root.addView(button("Start monitor") {
            CallMonitorService.start(this)
            setStatus(true)
            append("start requested")
        })
        root.addView(button("Stop monitor") {
            CallMonitorService.stop(this)
            setStatus(false)
            append("stop requested")
        })

        val advancedToggle = button("Show advanced") { }
        root.addView(advancedToggle)
        advancedBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        advancedToggle.setOnClickListener {
            val show = advancedBox.visibility != View.VISIBLE
            advancedBox.visibility = if (show) View.VISIBLE else View.GONE
            advancedToggle.text = if (show) "Hide advanced" else "Show advanced"
        }
        advancedBox.addView(button("Scan now") {
            CallMonitorService.scanNow(this)
            append("scan requested")
        })
        advancedBox.addView(button("Show recent") { showRecent() })
        advancedBox.addView(button("Retry now") {
            withRepo { append("retry pass requested") ; it.runRetryPass() }
        })
        advancedBox.addView(button("Seed a test call") { withRepo { it.seedFakeCall() } })
        advancedBox.addView(button("Clear log") { logView.text = "" })

        logView = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            movementMethod = ScrollingMovementMethod()
        }
        logScroll = ScrollView(this).apply {
            addView(logView)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(220)).apply {
                topMargin = dp(8)
            }
        }
        advancedBox.addView(logScroll)
        root.addView(advancedBox)

        root.addView(TextView(this).apply {
            text = "Callback v${versionName()}"
            textSize = 12f
            alpha = 0.6f
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(root) })
        append("ready")
    }

    override fun onStart() {
        super.onStart()
        // Resync in case the service was started/stopped elsewhere (the notification,
        // BootReceiver, an adb command) while this activity wasn't in the foreground
        // to see the log broadcast.
        setStatus(CallMonitorService.isRunning)
        loadProjects()
        loadTags()
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
        settings.apiToken = tokenField.text.toString().trim()
        settings.defaultEstimateMinutes = estimateField.text.toString().trim().toIntOrNull() ?: 0

        val config = settings.apiConfig()
        append("saved: ${config.baseUrl}")
        if (!config.isConfigured) {
            append("test skipped: host and token are required")
            return
        }
        loadProjects()
        loadTags()
        thread {
            val result = SuperProductivityApi(config).testConnection()
            result.onSuccess { code ->
                append(if (code in 200..299) "test connection -> $code  OK" else "test connection -> $code")
            }.onFailure { e ->
                append("test connection FAILED: ${e.message}")
            }
        }
    }

    private fun statusText(running: Boolean) = if (running) "● Running" else "○ Stopped"

    private fun setStatus(running: Boolean) = runOnUiThread {
        statusLabel.text = statusText(running)
    }

    // --- project dropdown ----------------------------------------------------

    /** Inbox, plus the saved project (so the current choice shows before /projects loads). */
    private fun initialProjectItems(): List<Project?> = buildList {
        add(null)
        val id = settings.projectId
        if (id != null) add(Project(id, settings.projectTitle ?: id))
    }

    private fun setProjectItems(items: List<Project?>) {
        projectItems = items
        val labels = items.map { it?.title ?: "Inbox (no project)" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        projectSpinner.adapter = adapter
        val selected = items.indexOfFirst { it?.id == settings.projectId }
        projectSpinner.setSelection(if (selected >= 0) selected else 0)
    }

    /** GET /projects and refill the dropdown — confirmed live, docs/scope.html Part 4b addendum. */
    private fun loadProjects() {
        val config = settings.apiConfig()
        if (!config.isConfigured) return
        thread {
            runCatching { SuperProductivityApi(config).listProjects() }
                .onSuccess { projects ->
                    runOnUiThread { setProjectItems(listOf<Project?>(null) + projects) }
                }
                .onFailure { e -> append("load projects failed: ${e.message}") }
        }
    }

    // --- tag dropdown ------------------------------------------------------

    /** "No tag", plus the saved tag (so the current choice shows before /tags loads). */
    private fun initialTagItems(): List<Tag?> = buildList {
        add(null)
        val id = settings.tagId
        if (id != null) add(Tag(id, settings.tagTitle ?: id))
    }

    private fun setTagItems(items: List<Tag?>) {
        tagItems = items
        val labels = items.map { it?.title ?: "No tag" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        tagSpinner.adapter = adapter
        val selected = items.indexOfFirst { it?.id == settings.tagId }
        tagSpinner.setSelection(if (selected >= 0) selected else 0)
    }

    /** GET /tags and refill the dropdown. Same envelope and flow as [loadProjects]. */
    private fun loadTags() {
        val config = settings.apiConfig()
        if (!config.isConfigured) return
        thread {
            runCatching { SuperProductivityApi(config).listTags() }
                .onSuccess { tags ->
                    runOnUiThread { setTagItems(listOf<Tag?>(null) + tags) }
                }
                .onFailure { e -> append("load tags failed: ${e.message}") }
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

    // --- tiny view builders (no layout XML) ---------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        setPadding(0, dp(24), 0, dp(8))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        gravity = Gravity.CENTER
        alpha = 0.7f
        layoutParams = LinearLayout.LayoutParams(dp(320), WRAP_CONTENT)
        setPadding(0, dp(10), 0, dp(2))
    }

    private fun field(parent: LinearLayout, labelText: String, initial: String): EditText {
        parent.addView(label(labelText))
        return EditText(this).apply {
            setText(initial)
            layoutParams = LinearLayout.LayoutParams(dp(320), WRAP_CONTENT)
            parent.addView(this)
        }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = dp(12)
        }
        setOnClickListener { onClick() }
    }

    private fun append(line: String) = runOnUiThread {
        logView.append("${clock.format(Date())}  $line\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
