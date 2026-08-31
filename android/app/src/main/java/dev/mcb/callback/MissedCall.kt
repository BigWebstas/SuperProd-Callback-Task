package dev.mcb.callback

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Which call-log type produced this row — drives the title prefix. */
enum class CallKind { MISSED, DECLINED }

/** One captured call, as read from [android.provider.CallLog]. */
data class MissedCall(
    val id: Long,               // CallLog.Calls._ID — the dedup key
    val number: String,
    val name: String?,          // contact name, or null if unknown
    val timestampMillis: Long,  // CallLog.Calls.DATE
    val kind: CallKind = CallKind.MISSED,
) {
    val isKnownContact: Boolean get() = name != null

    fun describe(): String {
        val t = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(timestampMillis))
        return "id=$id  [$kind] ${name ?: "?"} <$number>  at $t"
    }

    /** Default title/notes template from docs/scope.html Part 7. Declined calls get the
     *  "Declined, Call back" prefix instead, so they read differently from a true miss. */
    fun defaultTitle(): String {
        val who = name ?: number
        return if (kind == CallKind.DECLINED) "Declined, Call back $who" else "Call back $who"
    }

    fun defaultNotes(): String {
        val t = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(timestampMillis))
        val label = if (kind == CallKind.DECLINED) "Declined" else "Missed"
        return "$label $t · tel:$number"
    }
}
