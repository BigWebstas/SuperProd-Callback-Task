package dev.mcb.callback.net

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The newest GitHub release: its tag (e.g. `v0.3.0`) and the page to open. */
data class Release(val tag: String, val url: String)

/**
 * Asks GitHub for the repo's latest release. Manual-only on purpose: the app
 * makes no background calls to GitHub, so a check happens only when the user
 * taps the button.
 */
class UpdateChecker(
    private val repo: String = "BigWebstas/SuperProd-Callback-Task",
    private val baseUrl: String = "https://api.github.com",
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Throws [IOException] on transport errors or a non-2xx reply. */
    fun latest(): Release {
        val request = Request.Builder()
            .url("$baseUrl/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val json = JSONObject(text)
            return Release(json.getString("tag_name"), json.getString("html_url"))
        }
    }

    companion object {
        /** True when [tag] (`v0.3.0` or `0.3.0`) is a higher version than [current]. */
        fun isNewer(tag: String, current: String): Boolean {
            val a = parts(tag)
            val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        private fun parts(version: String): List<Int> =
            version.removePrefix("v").split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    }
}
