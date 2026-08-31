package dev.mcb.spike

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * Spike loopback server. Bound to 127.0.0.1 only. Backed by [QueueRepository],
 * so what it serves survives a restart.
 *
 * Routes:
 *   GET  /health        -> { ok, pending, counts }
 *   GET  /pending       -> { calls: [ { id, number, name, timestamp, rule, taskRowId } ] }
 *   POST /ack   {id}     -> { removed }        move the row to SENT
 *   POST /debug/seed     -> { pending }        enqueue a fake call
 */
class BridgeServer(
    private val repo: QueueRepository,
    private val onLog: (String) -> Unit,
) : NanoHTTPD(HOST, PORT) {

    companion object {
        const val HOST = "127.0.0.1"
        const val PORT = 47623
    }

    override fun serve(session: IHTTPSession): Response {
        val remote = session.remoteIpAddress
        onLog("${session.method} ${session.uri} from $remote")

        if (remote != "127.0.0.1" && remote != "::1" && remote != "0:0:0:0:0:0:0:1") {
            return json(Response.Status.FORBIDDEN, JSONObject().put("error", "loopback only"))
        }

        return when {
            session.method == Method.GET && session.uri == "/health" ->
                json(Response.Status.OK, repo.health())

            session.method == Method.GET && session.uri == "/pending" ->
                json(Response.Status.OK, JSONObject().put("calls", repo.pendingCallsJson()))

            session.method == Method.POST && session.uri == "/ack" -> {
                val id = JSONObject(readBody(session).ifBlank { "{}" }).optString("id")
                val removed = repo.ackByCallId(id)
                json(Response.Status.OK, JSONObject().put("removed", removed))
            }

            session.method == Method.POST && session.uri == "/debug/seed" -> {
                repo.seedFakeCall()
                json(Response.Status.OK, repo.health())
            }

            else -> json(Response.Status.NOT_FOUND, JSONObject().put("error", "no route"))
        }
    }

    private fun readBody(session: IHTTPSession): String {
        val files = HashMap<String, String>()
        return try {
            session.parseBody(files)
            files["postData"] ?: ""
        } catch (e: Exception) {
            onLog("parseBody failed: ${e.message}")
            ""
        }
    }

    private fun json(status: Response.Status, obj: JSONObject): Response =
        newFixedLengthResponse(status, "application/json", obj.toString()).apply {
            // Harmless if PluginAPI.request goes through native HTTP; needed if it is a WebView fetch.
            addHeader("Access-Control-Allow-Origin", "*")
            addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        }
}
