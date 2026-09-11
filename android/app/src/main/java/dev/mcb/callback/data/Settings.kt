package dev.mcb.callback.data

import android.content.Context

enum class CallFilter { ALL, KNOWN_ONLY, UNKNOWN_ONLY }

/**
 * User-configured write-path target: Super Productivity's Local REST API.
 * Three shapes for [host], one field — no separate port field:
 *
 *  - a bare LAN IP/hostname (e.g. `192.168.1.20`) — reached directly over a
 *    LAN port-forward (docs/scope.html Part 4b) using [DEFAULT_LAN_PORT] and
 *    plain HTTP.
 *  - `host:port` (e.g. `192.168.1.20:9876`) — same, with an explicit port.
 *  - a full `http(s)://...` URL (e.g. `https://sp.example.net`) — reached
 *    through a reverse proxy the user put in front of the same Local REST
 *    API; its port is whatever the URL/scheme says, so there's nothing to
 *    configure separately.
 *
 * The direct-LAN shapes always send a literal `Host: localhost` header — the
 * server 403s any request whose `Host` header isn't that (DNS-rebinding
 * guard). A proxy URL does *not* get that override — confirmed against a
 * real IIS/ARR proxy: forcing it client-side doesn't just skip TLS SNI
 * (that's driven by the connection target, so it'd survive), it also breaks
 * the proxy's own HTTP-level host-header site routing, landing the request
 * on the wrong site (404). The proxy is expected to rewrite the Host header
 * to `localhost` itself on the backend leg, after it's done routing on the
 * real domain.
 */
data class ApiConfig(
    val host: String,
    val token: String,
    val projectId: String?,
    val tagId: String?,
    /** Applied to every created task's `timeEstimate`, in minutes. 0 = omit. */
    val defaultEstimateMinutes: Int,
) {
    private val trimmedHost: String get() = host.trim()
    private val isProxyUrl: Boolean get() = trimmedHost.contains("://")

    val baseUrl: String
        get() = when {
            isProxyUrl -> trimmedHost.trimEnd('/')
            trimmedHost.contains(":") -> "http://$trimmedHost"
            else -> "http://$trimmedHost:$DEFAULT_LAN_PORT"
        }

    /** Whether to force `Host: localhost` on every request — see class doc. */
    val useLocalhostHostHeader: Boolean get() = !isProxyUrl

    val isConfigured: Boolean get() = host.isNotBlank() && token.isNotBlank()

    companion object {
        const val DEFAULT_LAN_PORT = 3876
    }
}

/** Thin SharedPreferences wrapper. Not encrypted — matches the spike; the bearer
 * token is only as sensitive as LAN access to the user's own task list. */
class Settings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("callback_settings", Context.MODE_PRIVATE)

    var apiHost: String
        get() = prefs.getString(KEY_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HOST, value).apply()

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

    /** Applied to every created task's `timeEstimate`. 0 = don't set one. */
    var defaultEstimateMinutes: Int
        get() = prefs.getInt(KEY_ESTIMATE, 30)
        set(value) = prefs.edit().putInt(KEY_ESTIMATE, value).apply()

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

    fun apiConfig() = ApiConfig(apiHost, apiToken, projectId, tagId, defaultEstimateMinutes)

    companion object {
        private const val KEY_HOST = "api_host"
        private const val KEY_TOKEN = "api_token"
        private const val KEY_PROJECT = "project_id"
        private const val KEY_PROJECT_TITLE = "project_title"
        private const val KEY_TAG = "tag_id"
        private const val KEY_TAG_TITLE = "tag_title"
        private const val KEY_ESTIMATE = "default_estimate_minutes"
        private const val KEY_FILTER = "call_filter"
        private const val KEY_DECLINED = "capture_declined"
        private const val KEY_ENABLED = "service_enabled"
    }
}
