package dev.mcb.callback.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.mcb.callback.data.Settings
import dev.mcb.callback.service.CallMonitorService

/** Restarts the call monitor after a reboot, only if it was running before. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Settings(context).serviceEnabled) {
            CallMonitorService.start(context)
        }
    }
}
