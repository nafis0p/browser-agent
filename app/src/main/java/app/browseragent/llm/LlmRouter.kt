package app.browseragent.llm

import app.browseragent.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID

data class ToolCall(val id: String, val name: String, val arguments: String)

data class ChatResult(
    val content: String?,
    val toolCalls: List<ToolCall>,
    val via: String,
    val attempts: List<String>,
)

class LlmException(message: String) : Exception(message)

/** Tries each route of a model alias in order; falls back on 429 / 5xx / network errors. */
class LlmRouter(private val store: Store, private val client: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cooldown = HashMap<String, Long>()
    private val fallbackStatuses = setOf(401, 402, 403, 404, 408, 409, 425, 429)

    private class Raw(val code: Int, val body: String, val retryAfter: String?)

    private fun key(provider: String, model: String) = provider.lowercase() + "/" + model

    suspend fun chat(alias: String, messages: List<JsonObject>, tools: JsonArray): ChatResult =
        withContext(Dispatchers.IO) {
            val a = store.aliases.firstOrNull { it.name == alias }
                ?: throw LlmException("No model alias '" + alias + "'. Add one in the Providers tab.")
            val providers = store.providers
            val now = System.currentTimeMillis()
            val ordered = a.routes.filter { (cooldown[key(it.provider, it.model)] ?: 0L) <= now } +
                a.routes.filter { (cooldown[key(it.provider, it.model)] ?: 0L) > now }
            val attempts = ArrayList<String>()

            for (route in ordered) {
                val p = providers.firstOrNull { it.name.equals(route.provider, ignoreCase = true) }
                if (p == null || p.apiKey.isBlank()) {
                    attempts.add(route.provider + ": no API key")
                    continue
                }
                val body = buildJsonObject {
                    put("model", route.model)
                    put("messages", JsonArray(messages))
                    if (tools.isNotEmpty()) {
                        put("tools", tools)
                        put("tool_choice", "auto")
                    }
                }
                val req = Request.Builder()
                    .url(p.baseUrl.trimEnd('/') + "/chat/completions")
                    .header("Authorization", "Bearer " + p.apiKey)
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val raw: Raw? = try {
                    client.newCall(req).execute().use {
                        Raw(it.code, it.body?.string().orEmpty(), it.header("Retry-After"))
                    }
                } catch (e: IOException) {
                    null
                }
                if (raw == null) {
                    attempts.add(p.name + ": network error")
                    continue
                }
                if (raw.code in 200..299) {
                    val parsed = parse(raw.body)
                    if (parsed == null) {
                        attempts.add(p.name + ": bad response")
                        continue
                    }
                    return@withContext ChatResult(
                        parsed.first,
                        parsed.second,
                        p.name + "/" + route.model,
                        attempts,
                    )
                }
                attempts.add(p.name + ": HTTP " + raw.code)
                if (raw.code == 429) {
                    val secs = (raw.retryAfter?.toDoubleOrNull() ?: 30.0).coerceIn(0.0, 300.0)
                    cooldown[key(route.provider, route.model)] = now + (secs * 1000).toLong()
                }
                if (raw.code >= 500 || raw.code in fallbackStatuses) continue
                throw LlmException(p.name + " returned HTTP " + raw.code + ": " + raw.body.take(300))
            }
            throw LlmException("All providers failed: " + attempts.joinToString("; "))
        }

    private fun parse(body: String): Pair<String?, List<ToolCall>>? = try {
        val msg = json.parseToJsonElement(body).jsonObject["choices"]!!.jsonArray[0]
            .jsonObject["message"]!!.jsonObject
        val content = (msg["content"] as? JsonPrimitive)?.contentOrNull
        val calls = (msg["tool_calls"] as? JsonArray)?.map { tc ->
            val o = tc.jsonObject
            val fn = o["function"]!!.jsonObject
            ToolCall(
                id = (o["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: UUID.randomUUID().toString(),
                name = fn["name"]!!.jsonPrimitive.content,
                arguments = (fn["arguments"] as? JsonPrimitive)?.contentOrNull ?: "{}",
            )
        } ?: emptyList()
        Pair(content, calls)
    } catch (e: Exception) {
        null
    }
}
