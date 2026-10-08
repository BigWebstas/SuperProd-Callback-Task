package dev.mcb.callback.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import dev.mcb.callback.data.CallFilter
import dev.mcb.callback.data.QueueRepository
import dev.mcb.callback.data.Settings
import dev.mcb.callback.net.Project
import dev.mcb.callback.net.SuperProductivityApi
import dev.mcb.callback.net.Tag
import dev.mcb.callback.net.UpdateChecker
import dev.mcb.callback.service.CallMonitorService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Single-screen control panel: permissions, write-path config, the capture
 * rule, start/stop. Built in code, no layout XML. The look follows
 * PebbleRecorder (sibling repo): a Material 3 DayNight theme with dynamic colors,
 * a centred column, an icon and bold status line up top, a faded version footer.
 * The debug tools and the live log sit behind an "Advanced" toggle.
 */
class MainActivity : AppCompatActivity() {

    private val settings by lazy { Settings(this) }
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    private lateinit var hostField: EditText
    private lateinit var tokenField: EditText
    private lateinit var estimateField: EditText
    private lateinit var projectSpinner: Spinner
    private lateinit var tagButton: MaterialButton
    private lateinit var filterGroup: RadioGroup
    private lateinit var declinedCheck: MaterialSwitch
    private lateinit var statusLabel: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var advancedBox: LinearLayout

    /** Spinner row -> project; index 0 is always Inbox (`null`). */
    private var projectItems: List<Project?> = listOf(null)

    /** Available tags for the picker, populated live from `/tags`. */
    private var availableTags: List<Tag> = emptyList()

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
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        DynamicColors.applyToActivityIfAvailable(this)
        // Draw behind the status/nav bars like PebbleRecorder (bars are made transparent in the theme).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val night = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }

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

        root.addView(button("Start monitor", ICON_PLAY) {
            CallMonitorService.start(this)
            setStatus(true)
            append("start requested")
        })
        root.addView(button("Stop monitor", ICON_STOP) {
            CallMonitorService.stop(this)
            setStatus(false)
            append("stop requested")
        })

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

        root.addView(label("Tags (applied to every callback task)"))
        tagButton = MaterialButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(320), WRAP_CONTENT)
            setOnClickListener { showTagPickerDialog() }
        }
        root.addView(tagButton)
        setTagItems(initialTagItems())

        root.addView(button("Save + test connection", ICON_SAVE) { saveAndTest() })

        root.addView(heading("Which missed calls to capture"))
        filterGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
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
        declinedCheck = MaterialSwitch(this).apply {
            text = "Also capture declined calls (title prefixed \"Declined, Call back\")"
            isChecked = settings.captureDeclined
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                topMargin = dp(12)
            }
        }
        root.addView(declinedCheck)
        root.addView(button("Save rule", ICON_SAVE) {
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

        val advancedToggle = button("Show advanced", ICON_EXPAND) { }
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
        advancedBox.addView(button("Grant permissions", ICON_LOCK) { requestPermissionsIfNeeded() })
        advancedBox.addView(button("Scan now", ICON_SEARCH) {
            CallMonitorService.scanNow(this)
            append("scan requested")
        })
        advancedBox.addView(button("Show recent", ICON_LIST) { showRecent() })
        advancedBox.addView(button("Retry now", ICON_REFRESH) {
            withRepo { append("retry pass requested") ; it.runRetryPass() }
        })
        advancedBox.addView(button("Seed a test call", ICON_BUG) { withRepo { it.seedFakeCall() } })
        advancedBox.addView(button("Check for updates", ICON_UPDATE) { checkForUpdates() })
        advancedBox.addView(button("Clear log", ICON_CLEAR) { logView.text = "" })

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

        setContentView(ScrollView(this).apply {
            addView(root)
            // Edge-to-edge draws under the bars; pad the scroll area so content clears them.
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsetsCompat.CONSUMED
            }
        })
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

    /** Manual only — asks GitHub for the latest release and offers to open it if newer. */
    private fun checkForUpdates() {
        append("checking for updates…")
        thread {
            runCatching { UpdateChecker().latest() }
                .onSuccess { release ->
                    val current = versionName()
                    if (UpdateChecker.isNewer(release.tag, current)) {
                        append("update available: ${release.tag}")
                        runOnUiThread {
                            MaterialAlertDialogBuilder(this)
                                .setTitle("Update available")
                                .setMessage("${release.tag} is out; you have v$current.")
                                .setPositiveButton("Open release") { _, _ ->
                                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.url)))
                                }
                                .setNegativeButton("Later", null)
                                .show()
                        }
                    } else {
                        append("up to date (v$current)")
                        runOnUiThread { Toast.makeText(this, "You're up to date", Toast.LENGTH_SHORT).show() }
                    }
                }
                .onFailure { e -> append("update check failed: ${e.message}") }
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

    // --- tag picker (multi-select) -------------------------------------------

    /** Saved tags from settings (so current choices show before /tags loads). */
    private fun initialTagItems(): List<Tag> {
        val titles = settings.tagTitles
        return settings.tagIds.map { id -> Tag(id, titles[id] ?: id) }
    }

    private fun setTagItems(tags: List<Tag>) {
        val serverIds = tags.map { it.id }.toSet()
        val preserved = settings.tagIds.filter { it !in serverIds }.map { id ->
            Tag(id, settings.tagTitles[id] ?: id)
        }
        availableTags = tags + preserved
        // Keep tagTitles in sync with any updated titles from server
        val updatedTitles = settings.tagTitles.toMutableMap()
        tags.forEach { tag ->
            if (tag.id in settings.tagIds) {
                updatedTitles[tag.id] = tag.title
            }
        }
        if (updatedTitles != settings.tagTitles) {
            settings.tagTitles = updatedTitles
        }
        updateTagButtonText()
    }

    private fun updateTagButtonText() {
        val selectedIds = settings.tagIds
        val selectedTitles = if (selectedIds.isEmpty()) {
            emptyList()
        } else {
            val inAvailable = availableTags.filter { it.id in selectedIds }.map { it.title }
            val availableIds = availableTags.map { it.id }.toSet()
            val extra = selectedIds.filter { it !in availableIds }.map { settings.tagTitles[it] ?: it }
            inAvailable + extra
        }
        tagButton.text = if (selectedTitles.isEmpty()) {
            "No tags"
        } else {
            selectedTitles.joinToString(", ")
        }
    }

    private fun showTagPickerDialog() {
        if (availableTags.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Tags")
                .setMessage("No tags loaded yet. Make sure Super Productivity is running and tap 'Save + test connection' to load tags.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val tagLabels = availableTags.map { it.title }.toTypedArray()
        val currentIds = settings.tagIds
        val checkedItems = BooleanArray(availableTags.size) { i ->
            availableTags[i].id in currentIds
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Select tags")
            .setMultiChoiceItems(tagLabels, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("OK") { _, _ ->
                val newSelected = availableTags.filterIndexed { index, _ -> checkedItems[index] }
                val newIds = newSelected.map { it.id }.toSet()
                val newTitles = newSelected.associate { it.id to it.title }
                if (newIds != settings.tagIds) {
                    settings.tagIds = newIds
                    settings.tagTitles = newTitles
                    updateTagButtonText()
                    append("tags -> ${if (newIds.isEmpty()) "none" else newSelected.joinToString { it.title }}")
                }
            }
            .setNeutralButton("Clear all") { _, _ ->
                if (settings.tagIds.isNotEmpty()) {
                    settings.tagIds = emptySet()
                    settings.tagTitles = emptyMap()
                    updateTagButtonText()
                    append("tags -> none")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** GET /tags and refill the tag list. Same envelope and flow as [loadProjects]. */
    private fun loadTags() {
        val config = settings.apiConfig()
        if (!config.isConfigured) return
        thread {
            runCatching { SuperProductivityApi(config).listTags() }
                .onSuccess { tags ->
                    runOnUiThread { setTagItems(tags) }
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

    private fun button(label: String, icon: Int, onClick: () -> Unit) = MaterialButton(this).apply {
        text = label
        if (icon != 0) {
            setIconResource(icon)
            iconPadding = dp(8)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        }
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

    private companion object {
        val ICON_LOCK = dev.mcb.callback.R.drawable.ic_lock_open
        val ICON_SAVE = dev.mcb.callback.R.drawable.ic_save
        val ICON_PLAY = dev.mcb.callback.R.drawable.ic_play
        val ICON_STOP = dev.mcb.callback.R.drawable.ic_stop
        val ICON_EXPAND = dev.mcb.callback.R.drawable.ic_expand_more
        val ICON_SEARCH = dev.mcb.callback.R.drawable.ic_search
        val ICON_LIST = dev.mcb.callback.R.drawable.ic_list
        val ICON_REFRESH = dev.mcb.callback.R.drawable.ic_refresh
        val ICON_BUG = dev.mcb.callback.R.drawable.ic_bug
        val ICON_CLEAR = dev.mcb.callback.R.drawable.ic_clear_all
        val ICON_UPDATE = dev.mcb.callback.R.drawable.ic_system_update
    }
}
