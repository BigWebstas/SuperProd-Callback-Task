package dev.mcb.spike

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract

/**
 * Reads missed calls from the system call log — the *source of truth* for the
 * spike. The telephony state change is only the trigger.
 */
class CallLogReader(private val context: Context) {

    /** Newest row where TYPE = MISSED_TYPE, or null. Resolves a contact name if possible. */
    fun newestMissed(): MissedCall? {
        if (!has(Manifest.permission.READ_CALL_LOG)) return null

        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.CACHED_NAME,
        )
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            "${CallLog.Calls.TYPE} = ?",
            arrayOf(CallLog.Calls.MISSED_TYPE.toString()),
            // LIMIT in the sort clause is honoured by the call-log provider.
            "${CallLog.Calls.DATE} DESC LIMIT 1",
        )?.use { c ->
            if (!c.moveToFirst()) return null
            val id = c.getLong(0)
            val number = c.getString(1).orEmpty()
            val date = c.getLong(2)
            val duration = c.getLong(3)
            val cachedName = c.getString(4)
            val name = cachedName?.takeIf { it.isNotBlank() } ?: lookupContact(number)
            return MissedCall(id, number, name, date, duration)
        }
        return null
    }

    /** Ids of recent missed calls, used to baseline so history is not re-queued. */
    fun recentMissedIds(limit: Int = 500): Set<Long> {
        if (!has(Manifest.permission.READ_CALL_LOG)) return emptySet()
        val ids = HashSet<Long>()
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls._ID),
            "${CallLog.Calls.TYPE} = ?",
            arrayOf(CallLog.Calls.MISSED_TYPE.toString()),
            "${CallLog.Calls.DATE} DESC LIMIT $limit",
        )?.use { c ->
            while (c.moveToNext()) ids.add(c.getLong(0))
        }
        return ids
    }

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
