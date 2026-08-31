package dev.mcb.spike

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One missed call, as read from [android.provider.CallLog]. */
data class MissedCall(
    val id: Long,               // CallLog.Calls._ID — the dedup key
    val number: String,
    val name: String?,          // contact name, or null
    val timestampMillis: Long,  // CallLog.Calls.DATE
    val durationSec: Long,      // CallLog.Calls.DURATION (0 for a true missed call)
) {
    fun describe(): String {
        val t = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(timestampMillis))
        return "id=$id  ${name ?: "?"} <$number>  at $t"
    }

    fun toJson(source: String = "calllog"): JSONObject = JSONObject().apply {
        put("id", id.toString())
        put("number", number)
        put("name", name ?: JSONObject.NULL)
        put("timestamp", timestampMillis)
        put("rule", "unknown")
        put("source", source)
    }
}
