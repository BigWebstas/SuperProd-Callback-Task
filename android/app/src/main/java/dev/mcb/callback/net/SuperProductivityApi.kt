package dev.mcb.callback.net

import dev.mcb.callback.data.ApiConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client for Super Productivity's Local REST API, reached over the user's own
 * LAN port-forward (docs/scope.html Part 4b — confirmed working end to end,
 * 2026-08-31). Two things this depends on, both proven in the spike:
 *
 *  - The server rejects any `Host` header that isn't a literal `localhost`
 *    (`403 Invalid Host header` otherwise) — a DNS-rebinding guard. We must
 *    send that header on every request no matter what [ApiConfig.host]/[port]
 *    we actually connect to.
 *  - `POST /tasks` (no `/api` prefix) creates a real task on the desktop's
 *    decrypted in-memory state and returns its id in `data.id`.
 */
class SuperProductivityApi(private val config: ApiConfig) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    class ApiException(message: String) : IOException(message)

    /** Creates a task, returns its remote id. Throws [ApiException] on any non-2xx or transport error. */
    fun createTask(title: String, notes: String): String {
        val body = JSONObject().apply {
            put("title", title)
            put("notes", notes)
            config.projectId?.let { put("projectId", it) }
        }
        val request = Request.Builder()
            .url("${config.baseUrl}/tasks")
            .header("Host", "localhost") // see class doc — required, not optional
            .header("Authorization", "Bearer ${config.token}")
            .post(body.toString().toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw ApiException("HTTP ${resp.code}: ${text.take(200)}")
            }
            val id = runCatching { JSONObject(text).getJSONObject("data").getString("id") }
                .getOrNull()
            return id ?: throw ApiException("no task id in response: ${text.take(200)}")
        }
    }

    /** Cheap reachability + auth check for a "Test connection" button. */
    fun testConnection(): Result<Int> = runCatching {
        val request = Request.Builder()
            .url("${config.baseUrl}/tasks")
            .header("Host", "localhost")
            .header("Authorization", "Bearer ${config.token}")
            .get()
            .build()
        client.newCall(request).execute().use { it.code }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
