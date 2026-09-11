package dev.mcb.callback.data

import android.content.Context

enum class CallFilter { ALL, KNOWN_ONLY, UNKNOWN_ONLY }

/**
 * User-configured write-path target: Super Productivity's Local REST API.
 * Two shapes for [host]:
 *
 *  - a bare LAN IP/hostname (e.g. `192.168.1.20`) — reached directly over a
 *    LAN port-forward (docs/scope.html Part 4b) using [port] and plain HTTP.
 *  - a full `http(s)://...` URL (e.g. `https://sp.example.net`) — reached
 *    through a reverse proxy the user put in front of the same Local REST
 *    API. [port] is ignored in this shape.
 *
 * Either way the server 403s any request whose `Host` header isn't a literal
 * `localhost` (DNS-rebinding guard), so the client always sends that header
 * regardless of [host]/[port]. This is safe even over HTTPS through a proxy:
 * TLS SNI and certificate hostname verification are driven by the connection
 * target (the URL's authority — the proxy's real domain), not by this
 * application-level `Host` header override, so overriding it doesn't break
 * routing or cert matching. It does mean the proxy is receiving `Host:
 * localhost` on the request it forwards, which is exactly what Super
 * Productivity's own guard requires downstream.
 */
data class ApiConfig(
    val host: String,
    val port: Int,
    val token: String,
    val projectId: String?,
    val tagId: String?,
) {
    private val trimmedHost: String get() = host.trim()
    private val isProxyUrl: Boolean get() = trimmedHost.contains("://")

    val baseUrl: String
        get() = if (isProxyUrl) trimmedHost.trimEnd('/') else "http://$trimmedHost:$port"

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

    /** Display name for [projectId], picked live from `/projects` — see [ui.MainActivity]. */
    var projectTitle: String?
        get() = prefs.getString(KEY_PROJECT_TITLE, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_PROJECT_TITLE, value).apply()

    /** One tag id applied to every callback task, picked live from `/tags`. Null = no tag. */
    var tagId: String?
        get() = prefs.getString(KEY_TAG, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_TAG, value).apply()

    /** Display name for [tagId], picked live from `/tags` — see [ui.MainActivity]. */
    var tagTitle: String?
        get() = prefs.getString(KEY_TAG_TITLE, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_TAG_TITLE, value).apply()

    var callFilter: CallFilter
        get() = CallFilter.valueOf(prefs.getString(KEY_FILTER, CallFilter.ALL.name)!!)
        set(value) = prefs.edit().putString(KEY_FILTER, value.name).apply()

    /** Missed calls are always captured; declined (REJECTED_TYPE) is opt-in. */
    var captureDeclined: Boolean
        get() = prefs.getBoolean(KEY_DECLINED, false)
        set(value) = prefs.edit().putBoolean(KEY_DECLINED, value).apply()

    /** So BootReceiver knows whether to restart the service after a reboot. */
    var serviceEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    fun apiConfig() = ApiConfig(apiHost, apiPort, apiToken, projectId, tagId)

    companion object {
        private const val KEY_HOST = "api_host"
        private const val KEY_PORT = "api_port"
        private const val KEY_TOKEN = "api_token"
        private const val KEY_PROJECT = "project_id"
        private const val KEY_PROJECT_TITLE = "project_title"
        private const val KEY_TAG = "tag_id"
        private const val KEY_TAG_TITLE = "tag_title"
        private const val KEY_FILTER = "call_filter"
        private const val KEY_DECLINED = "capture_declined"
        private const val KEY_ENABLED = "service_enabled"
    }
}
