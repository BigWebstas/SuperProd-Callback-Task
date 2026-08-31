package dev.mcb.spike

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.CallLog
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager

/**
 * Missed-call detection for the spike.
 *
 *  - trigger: TelephonyCallback / PhoneStateListener. RINGING then IDLE with no
 *    OFFHOOK looks like a missed call.
 *  - source of truth: [CallLogReader.newestMissed], queried ~1.5 s after IDLE.
 *  - backup trigger: a ContentObserver on the call-log URI.
 *
 * Dedup and queueing go through [QueueRepository] (Room). Scans and all DB work
 * run on a private background thread, never the main looper.
 */
class CallDetector(
    private val context: Context,
    private val repo: QueueRepository,
    private val onLog: (String) -> Unit,
    private val onMissed: (MissedCall) -> Unit,
) {
    private val telephonyManager = context.getSystemService(TelephonyManager::class.java)
    private val reader = CallLogReader(context)

    private val worker = HandlerThread("mcb-detector").apply { start() }
    private val bg = Handler(worker.looper)

    private var sawRinging = false
    private var sawOffhook = false

    private var telephonyCallback: TelephonyCallback? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var observer: ContentObserver? = null

    fun start() {
        bg.post {
            repo.baselineProcessed(reader.recentMissedIds())
            registerObserver()
        }
        registerTelephony()
    }

    fun stop() {
        bg.removeCallbacksAndMessages(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let { telephonyManager?.unregisterTelephonyCallback(it) }
        }
        @Suppress("DEPRECATION")
        phoneStateListener?.let { telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE) }
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        telephonyCallback = null
        phoneStateListener = null
        observer = null
        worker.quitSafely()
        onLog("detector: stopped")
    }

    /** Manual trigger for the spike UI. */
    fun scanNow() = bg.post { scan("manual") }

    // --- triggers ------------------------------------------------------------

    private fun registerTelephony() {
        if (!has(Manifest.permission.READ_PHONE_STATE)) {
            onLog("detector: READ_PHONE_STATE not granted — state trigger OFF (observer still on)")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = handleState(state)
            }
            telephonyCallback = cb
            telephonyManager?.registerTelephonyCallback(context.mainExecutor, cb)
        } else {
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) = handleState(state)
            }
            phoneStateListener = listener
            @Suppress("DEPRECATION")
            telephonyManager?.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        }
        onLog("detector: telephony trigger ON")
    }

    private fun handleState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                sawRinging = true; sawOffhook = false
                onLog("state: RINGING")
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                sawOffhook = true
                onLog("state: OFFHOOK")
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                onLog("state: IDLE  (ringing=$sawRinging offhook=$sawOffhook)")
                if (sawRinging && !sawOffhook) {
                    bg.postDelayed({ scan("telephony") }, DIALER_WRITE_DELAY_MS)
                }
                sawRinging = false; sawOffhook = false
            }
        }
    }

    private fun registerObserver() {
        val obs = object : ContentObserver(bg) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                bg.postDelayed({ scan("observer") }, DIALER_WRITE_DELAY_MS)
            }
        }
        observer = obs
        context.contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, obs)
        onLog("detector: call-log observer ON")
    }

    // --- shared path (always on the bg thread) ------------------------------

    private fun scan(source: String) {
        val call = reader.newestMissed()
        if (call == null) {
            onLog("scan[$source]: no missed call in log")
            return
        }
        if (!repo.claim(call.id)) return // the other trigger got here first
        onLog("MISSED CALL via $source -> ${call.describe()}")
        repo.enqueue(call)
        onMissed(call)
    }

    private fun has(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val DIALER_WRITE_DELAY_MS = 1_500L
    }
}
