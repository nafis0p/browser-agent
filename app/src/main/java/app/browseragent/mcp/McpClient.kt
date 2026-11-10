package app.browseragent.mcp

import app.browseragent.data.McpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

data class McpTool(
    val name: String,
    val description: String,
    val schema: JsonObject,
    val readOnly: Boolean,
)

/** Minimal MCP client for remote servers over Streamable HTTP (JSON or SSE replies). */
class McpClient(private val server: McpServer, private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private var sessionId: String? = null
    private var nextId = 1

    var connected = false
        private set
    var tools: List<McpTool> = emptyList()
        private set

    suspend fun connect(): List<McpTool> = withContext(Dispatchers.IO) {
        sessionId = null
        nextId = 1
        rpc(
            "initialize",
            buildJsonObject {
                put("protocolVersion", "2025-03-26")
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") {
                    put("name", "browser-agent")
                    put("version", "0.1.0")
                }
            },
        )
        send(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "notifications/initialized")
            },
            null,
        )
        val res = rpc("tools/list", buildJsonObject {})
        tools = (res["tools"] as? JsonArray)?.map { parseTool(it.jsonObject) } ?: emptyList()
        connected = true
        tools
    }

    suspend fun callTool(name: String, args: JsonObject): String = withContext(Dispatchers.IO) {
        val res = rpc(
            "tools/call",
            buildJsonObject {
                put("name", name)
                put("arguments", args)
            },
        )
        val text = (res["content"] as? JsonArray)?.joinToString("\n") { c ->
            (c.jsonObject["text"] as? JsonPrimitive)?.contentOrNull ?: c.toString()
        } ?: res.toString()
        if ((res["isError"] as? JsonPrimitive)?.booleanOrNull == true) "ERROR: " + text else text
    }

    private fun parseTool(t: JsonObject): McpTool {
        val readOnly = ((t["annotations"] as? JsonObject)?.get("readOnlyHint") as? JsonPrimitive)
            ?.booleanOrNull == true
        return McpTool(
            name = t["name"]!!.jsonPrimitive.content,
            description = (t["description"] as? JsonPrimitive)?.contentOrNull ?: "",
            schema = (t["inputSchema"] as? JsonObject) ?: buildJsonObject { put("type", "object") },
            readOnly = readOnly,
        )
    }

    private fun rpc(method: String, params: JsonObject): JsonObject {
        val id = nextId++
        val req = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        val resp = send(req, id) ?: throw IOException("No response from MCP server")
        (resp["error"] as? JsonObject)?.let { throw IOException("MCP error: " + it.toString().take(300)) }
        return (resp["result"] as? JsonObject) ?: buildJsonObject {}
    }

    /** POST one JSON-RPC message. id == null means notification (no reply expected). */
    private fun send(body: JsonObject, id: Int?): JsonObject? {
        val b = Request.Builder()
            .url(server.url)
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", "2025-03-26")
        if (server.headerValue.isNotBlank()) {
            b.header(server.headerName.ifBlank { "Authorization" }, server.headerValue)
        }
        sessionId?.let { b.header("Mcp-Session-Id", it) }
        b.post(body.toString().toRequestBody("application/json".toMediaType()))
        http.newCall(b.build()).execute().use { resp ->
            resp.header("Mcp-Session-Id")?.let { sessionId = it }
            if (!resp.isSuccessful) {
                throw IOException("HTTP " + resp.code + ": " + resp.body?.string().orEmpty().take(200))
            }
            if (id == null) return null
            val text = resp.body?.string().orEmpty()
            return parseBody(text, resp.header("Content-Type").orEmpty(), id)
        }
    }

    private fun parseBody(raw: String, contentType: String, id: Int): JsonObject? {
        val text = raw.replace("\r\n", "\n")
        if (contentType.contains("text/event-stream")) {
            for (event in text.split("\n\n")) {
                val data = event.lines()
                    .filter { it.startsWith("data:") }
                    .joinToString("\n") { it.removePrefix("data:").trim() }
                if (data.isBlank()) continue
                val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                if (matches(obj, id)) return obj
            }
            return null
        }
        val el = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return null
        if (el is JsonArray) {
            return el.firstOrNull { it is JsonObject && matches(it, id) } as? JsonObject
        }
        return (el as? JsonObject)?.takeIf { matches(it, id) }
    }

    private fun matches(obj: JsonObject, id: Int): Boolean =
        (obj["id"] as? JsonPrimitive)?.longOrNull == id.toLong()
}
