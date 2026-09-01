package dev.mcb.callback.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dev.mcb.callback.R
import dev.mcb.callback.data.Settings
import dev.mcb.callback.ui.MainActivity

/**
 * Home-screen widget showing whether the call monitor is running. The OS's
 * own `updatePeriodMillis` floor is 30 minutes — too slow to be useful — so
 * [CallMonitorService] pushes a refresh directly via [refreshAll] right after
 * it flips [Settings.serviceEnabled], the same signal the in-app status
 * label uses.
 */
class ServiceStatusWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> updateOne(context, manager, id) }
    }

    private fun updateOne(context: Context, manager: AppWidgetManager, id: Int) {
        val running = Settings(context).serviceEnabled
        val openApp = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val label = context.getString(
            if (running) R.string.widget_status_running else R.string.widget_status_stopped
        )
        val views = RemoteViews(context.packageName, R.layout.widget_service_status).apply {
            setTextViewText(R.id.widget_status_text, label)
            setOnClickPendingIntent(R.id.widget_root, openApp)
        }
        manager.updateAppWidget(id, views)
    }

    companion object {
        /** Call right after [Settings.serviceEnabled] changes. */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, ServiceStatusWidgetProvider::class.java)
            )
            if (ids.isNotEmpty()) {
                ServiceStatusWidgetProvider().onUpdate(context, manager, ids)
            }
        }
    }
}
