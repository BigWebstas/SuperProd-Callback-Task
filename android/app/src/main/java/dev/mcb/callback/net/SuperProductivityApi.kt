package dev.mcb.callback.net

import dev.mcb.callback.data.ApiConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** id + title from `GET /projects` — everything else that route returns is dropped. */
data class Project(val id: String, val title: String)

/** id + title from `GET /tags` — everything else that route returns is dropped. */
data class Tag(val id: String, val title: String)

/**
 * Client for Super Productivity's Local REST API — either reached directly
 * over the user's own LAN port-forward (docs/scope.html Part 4b — confirmed
 * working end to end, 2026-08-31) or through a reverse proxy the user puts in
 * front of it (e.g. for HTTPS). See [ApiConfig] for the two host shapes.
 *
 * Two things the direct-LAN path depends on, both proven in the spike:
 *
 *  - The server rejects any `Host` header that isn't a literal `localhost`
 *    (`403 Invalid Host header` otherwise) — a DNS-rebinding guard. We send
 *    that header on every request no matter what [ApiConfig.host]/[port] we
 *    actually connect to — including through an HTTPS reverse proxy, since
 *    TLS SNI/cert checks are driven by the connection's real target, not by
 *    this application-level header override (see [ApiConfig] doc).
 *  - `POST /tasks` (no `/api` prefix) creates a real task on the desktop's
 *    decrypted in-memory state and returns its id in `data.id`.
 */
class SuperProductivityApi(private val config: ApiConfig) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    class ApiException(message: String) : IOException(message)

    /** Common headers for every call — see class doc on the `Host` override. */
    private fun Request.Builder.authHeaders(): Request.Builder =
        header("Host", "localhost").header("Authorization", "Bearer ${config.token}")

    /** Creates a task, returns its remote id. Throws [ApiException] on any non-2xx or transport error. */
    fun createTask(title: String, notes: String): String {
        val body = JSONObject().apply {
            put("title", title)
            put("notes", notes)
            config.projectId?.let { put("projectId", it) }
            config.tagId?.let { put("tagIds", JSONArray().put(it)) }
        }
        val request = Request.Builder()
            .url("${config.baseUrl}/tasks")
            .authHeaders()
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

    /** Live project list for the picker — confirmed working, docs/scope.html Part 4b addendum. */
    fun listProjects(): List<Project> {
        val request = Request.Builder()
            .url("${config.baseUrl}/projects")
            .authHeaders()
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException("HTTP ${resp.code}: ${text.take(200)}")
            val data = JSONObject(text).getJSONArray("data")
            return (0 until data.length()).map { i ->
                val p = data.getJSONObject(i)
                Project(p.getString("id"), p.getString("title"))
            }
        }
    }

    /** Live tag list for the picker — `GET /tags`, same envelope as `/projects`. */
    fun listTags(): List<Tag> {
        val request = Request.Builder()
            .url("${config.baseUrl}/tags")
            .authHeaders()
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException("HTTP ${resp.code}: ${text.take(200)}")
            val data = JSONObject(text).getJSONArray("data")
            return (0 until data.length()).map { i ->
                val t = data.getJSONObject(i)
                Tag(t.getString("id"), t.getString("title"))
            }
        }
    }

    /** Cheap reachability + auth check for a "Test connection" button. */
    fun testConnection(): Result<Int> = runCatching {
        val request = Request.Builder()
            .url("${config.baseUrl}/tasks")
            .authHeaders()
            .get()
            .build()
        client.newCall(request).execute().use { it.code }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
