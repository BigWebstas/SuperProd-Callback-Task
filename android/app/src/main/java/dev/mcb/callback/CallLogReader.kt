package dev.mcb.callback

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract

/**
 * Reads captured calls from the system call log — the *source of truth*. The
 * telephony state change is only the trigger. Missed calls (rang out
 * unanswered) are always in scope; declined calls (the user rejected the
 * ring) are opt-in via [includeDeclined], per docs/scope.html Part 1's note
 * that REJECTED_TYPE is an optional extension.
 */
class CallLogReader(private val context: Context) {

    /** Newest capturable row, or null. Resolves a contact name if possible. */
    fun newestCapturable(includeDeclined: Boolean): MissedCall? {
        if (!has(Manifest.permission.READ_CALL_LOG)) return null

        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.DATE,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
        )
        context.contentResolver.query(
            limitedUri(1),
            projection,
            selection(includeDeclined),
            selectionArgs(includeDeclined),
            "${CallLog.Calls.DATE} DESC",
        )?.use { c ->
            if (!c.moveToFirst()) return null
            val id = c.getLong(0)
            val number = c.getString(1).orEmpty()
            val date = c.getLong(2)
            val cachedName = c.getString(3)
            val kind = kindOf(c.getInt(4))
            val name = cachedName?.takeIf { it.isNotBlank() } ?: lookupContact(number)
            return MissedCall(id, number, name, date, kind)
        }
        return null
    }

    /** Ids of recent capturable calls, used to baseline so history is not re-queued. */
    fun recentCapturableIds(includeDeclined: Boolean, limit: Int = 500): Set<Long> {
        if (!has(Manifest.permission.READ_CALL_LOG)) return emptySet()
        val ids = HashSet<Long>()
        context.contentResolver.query(
            limitedUri(limit),
            arrayOf(CallLog.Calls._ID),
            selection(includeDeclined),
            selectionArgs(includeDeclined),
            "${CallLog.Calls.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext()) ids.add(c.getLong(0))
        }
        return ids
    }

    private fun selection(includeDeclined: Boolean): String =
        if (includeDeclined) "${CallLog.Calls.TYPE} IN (?, ?)" else "${CallLog.Calls.TYPE} = ?"

    private fun selectionArgs(includeDeclined: Boolean): Array<String> =
        if (includeDeclined) {
            arrayOf(CallLog.Calls.MISSED_TYPE.toString(), CallLog.Calls.REJECTED_TYPE.toString())
        } else {
            arrayOf(CallLog.Calls.MISSED_TYPE.toString())
        }

    private fun kindOf(type: Int): CallKind =
        if (type == CallLog.Calls.REJECTED_TYPE) CallKind.DECLINED else CallKind.MISSED

    /**
     * Appending "LIMIT n" to the sort-order string used to work but newer platform
     * builds reject it (`IllegalArgumentException: Invalid token LIMIT`) as part of
     * tightened selection/sort validation (confirmed on Android 17 in the spike).
     * The provider-sanctioned way to cap rows is the "limit" query parameter.
     */
    private fun limitedUri(limit: Int): Uri =
        CallLog.Calls.CONTENT_URI.buildUpon()
            .appendQueryParameter("limit", limit.toString())
            .build()

    private fun lookupContact(number: String): String? {
        if (number.isBlank() || !has(Manifest.permission.READ_CONTACTS)) return null
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)
        )
        context.contentResolver.query(
            uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    private fun has(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
