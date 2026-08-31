package dev.mcb.callback.rules

import dev.mcb.callback.MissedCall
import dev.mcb.callback.data.CallFilter

/**
 * Pure function: call + settings -> keep or drop. Docs/scope.html Part 7 lists
 * the full rule set (quiet hours, per-contact lists, min ring duration); this
 * is the first slice — known vs. unknown caller only.
 */
object RuleEngine {

    fun shouldCapture(call: MissedCall, filter: CallFilter): Boolean = when (filter) {
        CallFilter.ALL -> true
        CallFilter.KNOWN_ONLY -> call.isKnownContact
        CallFilter.UNKNOWN_ONLY -> !call.isKnownContact
    }
}
