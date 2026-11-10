package app.browseragent.mcp

import app.browseragent.data.McpServer
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient

data class McpEntry(
    val exposedName: String,
    val serverName: String,
    val client: McpClient,
    val tool: McpTool,
)

/** Connects to the enabled MCP servers and exposes their tools (cached between runs). */
class McpHub(private val http: OkHttpClient) {
    private val cache = HashMap<String, McpClient>()

    suspend fun load(
        servers: List<McpServer>,
        verbose: Boolean = false,
        log: (String) -> Unit,
    ): List<McpEntry> {
        val out = ArrayList<McpEntry>()
        for (s in servers) {
            val key = listOf(s.id, s.url, s.headerName, s.headerValue).joinToString("|")
            try {
                val c = cache.getOrPut(key) { McpClient(s, http) }
                val fresh = !c.connected
                val tools = if (fresh) c.connect() else c.tools
                if (fresh || verbose) log("MCP " + s.name + ": " + tools.size + " tools")
                for (t in tools) out.add(McpEntry(exposedName(s.name, t.name), s.name, c, t))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                cache.remove(key)
                log("MCP " + s.name + " failed: " + e.message)
            }
        }
        return out
    }

    private fun exposedName(server: String, tool: String): String =
        ("mcp_" + server + "_" + tool).replace(Regex("[^A-Za-z0-9_-]"), "_").take(64)
}
