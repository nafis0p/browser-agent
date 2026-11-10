package app.browseragent.data

import kotlinx.serialization.Serializable

/** An OpenAI-compatible API endpoint. */
@Serializable
data class Provider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String = "",
)

/** One hop in a fallback chain: provider name + that provider's own model id. */
@Serializable
data class Route(val provider: String, val model: String)

/** A client-facing model name with an ordered list of routes to try. */
@Serializable
data class ModelAlias(val name: String, val routes: List<Route>)

/** A remote MCP server (Streamable HTTP). */
@Serializable
data class McpServer(
    val id: String,
    val name: String,
    val url: String,
    val headerName: String = "Authorization",
    val headerValue: String = "",
    val enabled: Boolean = true,
)
