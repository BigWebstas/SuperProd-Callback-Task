package dev.mcb.callback.data

import android.content.Context

enum class CallFilter { ALL, KNOWN_ONLY, UNKNOWN_ONLY }

/**
 * User-configured write-path target: Super Productivity's Local REST API,
 * reached over a LAN port-forward (docs/scope.html Part 4b). The client always
 * sends a literal `Host: localhost` header regardless of [host]/[port] — the
 * server 403s anything else (DNS-rebinding guard).
 */
data class ApiConfig(
    val host: String,
    val port: Int,
    val token: String,
    val projectId: String?,
) {
    val baseUrl: String get() = "http://$host:$port"
    val isConfigured: Boolean get() = host.isNotBlank() && token.isNotBlank()
}

/** Thin SharedPreferences wrapper. Not encrypted — matches the spike; the bearer
 * token is only as sensitive as LAN access to the user's own task list. */
class Settings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("callback_settings", Context.MODE_PRIVATE)

    var apiHost: String
        get() = prefs.getString(KEY_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HOST, value).apply()

    var apiPort: Int
        get() = prefs.getInt(KEY_PORT, 3876)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var apiToken: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var projectId: String?
        get() = prefs.getString(KEY_PROJECT, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_PROJECT, value).apply()

    var callFilter: CallFilter
        get() = CallFilter.valueOf(prefs.getString(KEY_FILTER, CallFilter.ALL.name)!!)
        set(value) = prefs.edit().putString(KEY_FILTER, value.name).apply()

    /** So BootReceiver knows whether to restart the service after a reboot. */
    var serviceEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    fun apiConfig() = ApiConfig(apiHost, apiPort, apiToken, projectId)

    companion object {
        private const val KEY_HOST = "api_host"
        private const val KEY_PORT = "api_port"
        private const val KEY_TOKEN = "api_token"
        private const val KEY_PROJECT = "project_id"
        private const val KEY_FILTER = "call_filter"
        private const val KEY_ENABLED = "service_enabled"
    }
}
