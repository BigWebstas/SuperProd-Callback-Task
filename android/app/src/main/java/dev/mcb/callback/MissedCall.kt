package dev.mcb.callback

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One missed call, as read from [android.provider.CallLog]. */
data class MissedCall(
    val id: Long,               // CallLog.Calls._ID — the dedup key
    val number: String,
    val name: String?,          // contact name, or null if unknown
    val timestampMillis: Long,  // CallLog.Calls.DATE
) {
    val isKnownContact: Boolean get() = name != null

    fun describe(): String {
        val t = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(timestampMillis))
        return "id=$id  ${name ?: "?"} <$number>  at $t"
    }

    /** Default title/notes template from docs/scope.html Part 7. */
    fun defaultTitle(): String = "Call back ${name ?: number}"

    fun defaultNotes(): String {
        val t = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(timestampMillis))
        return "Missed $t · tel:$number"
    }
}
