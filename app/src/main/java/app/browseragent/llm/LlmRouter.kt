package app.browseragent.llm

import app.browseragent.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ToolCall(val id: String, val name: String, val arguments: String)

data class ChatResult(
    val content: String?,
    val toolCalls: List<ToolCall>,
    val via: String,
    val attempts: List<String>,
)

class LlmException(message: String) : Exception(message)

// Google's documented value for a function call that has no real thought signature (for example
// one that was made by another model). Gemini 3 refuses a history without a signature.
private const val DUMMY_SIGNATURE = "skip_thought_signature_validator"

// A busy server (HTTP 500, 502, 503, 504) is tried again this many times before the next provider.
private const val MAX_RETRIES = 2

/** Tries each route of a model alias in order; falls back on 429 / 5xx / network errors. */
class LlmRouter(private val store: Store, private val client: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cooldown = HashMap<String, Long>()
    private val fallbackStatuses = setOf(401, 402, 403, 404, 408, 409, 425, 429)

    /** The thought signature Gemini gave to a tool call, and the route that produced it. */
    private class Sig(val signature: String, val via: String)

    private val signatures = ConcurrentHashMap<String, Sig>()

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
                val via = p.name + "/" + route.model
                val gemini = p.baseUrl.contains("googleapis.com", ignoreCase = true)
                val body = buildJsonObject {
                    put("model", route.model)
                    put("messages", JsonArray(adapt(messages, gemini, via)))
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

                // A busy or briefly broken server often works a moment later: try again before
                // giving up on this route.
                var last: Raw? = null
                var tries = 0
                while (true) {
                    last = try {
                        client.newCall(req).execute().use {
                            Raw(it.code, it.body?.string().orEmpty(), it.header("Retry-After"))
                        }
                    } catch (e: IOException) {
                        null
                    }
                    val c = last?.code ?: 0
                    val busy = last == null || c == 500 || c == 502 || c == 503 || c == 504
                    if (!busy || tries >= MAX_RETRIES) break
                    tries++
                    delay(1500L * tries * tries)
                }
                val raw = last
                if (raw == null) {
                    attempts.add(p.name + ": network error")
                    continue
                }
                if (raw.code in 200..299) {
                    val parsed = parse(raw.body, via)
                    if (parsed == null) {
                        attempts.add(p.name + ": bad response")
                        continue
                    }
                    return@withContext ChatResult(
                        parsed.first,
                        parsed.second,
                        via,
                        attempts,
                    )
                }
                attempts.add(p.name + ": HTTP " + raw.code + hint(raw.body))
                if (raw.code == 429) {
                    val secs = (raw.retryAfter?.toDoubleOrNull() ?: 30.0).coerceIn(0.0, 300.0)
                    cooldown[key(route.provider, route.model)] = now + (secs * 1000).toLong()
                }
                if (raw.code >= 500 || raw.code in fallbackStatuses) continue
                throw LlmException(p.name + " returned HTTP " + raw.code + ": " + raw.body.take(300))
            }
            throw LlmException("All providers failed: " + attempts.joinToString("; "))
        }

    /** The short reason a provider gave in its error answer, for example that the model is busy. */
    private fun hint(body: String): String {
        val msg = runCatching {
            val root = json.parseToJsonElement(body)
            val o = (if (root is JsonArray) root.firstOrNull() else root) as? JsonObject
            ((o?.get("error") as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
        }.getOrNull()
        return if (msg.isNullOrBlank()) "" else " (" + msg.take(110) + ")"
    }

    /**
     * Makes the tool calls of earlier assistant messages fit the provider that gets them now.
     * Gemini needs a thought signature on every function call (the real one when this same model
     * made the call, a placeholder otherwise). Other providers never see the field.
     */
    private fun adapt(messages: List<JsonObject>, gemini: Boolean, via: String): List<JsonObject> {
        if (messages.none { it["tool_calls"] != null }) return messages
        return messages.map { m ->
            val calls = m["tool_calls"] as? JsonArray ?: return@map m
            val fixed = calls.map { c ->
                val o = c.jsonObject
                val base = o.filterKeys { it != "extra_content" }
                if (!gemini) {
                    JsonObject(base)
                } else {
                    val id = (o["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    val known = signatures[id]
                    val signature = if (known != null && known.via == via) known.signature else DUMMY_SIGNATURE
                    val extra = buildJsonObject {
                        putJsonObject("google") { put("thought_signature", signature) }
                    }
                    JsonObject(base + ("extra_content" to extra))
                }
            }
            JsonObject(m + ("tool_calls" to JsonArray(fixed)))
        }
    }

    private fun parse(body: String, via: String): Pair<String?, List<ToolCall>>? = try {
        val msg = json.parseToJsonElement(body).jsonObject["choices"]!!.jsonArray[0]
            .jsonObject["message"]!!.jsonObject
        val content = (msg["content"] as? JsonPrimitive)?.contentOrNull
        val calls = (msg["tool_calls"] as? JsonArray)?.map { tc ->
            val o = tc.jsonObject
            val fn = o["function"]!!.jsonObject
            val id = (o["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString()
            // Gemini: remember the thought signature so it can be sent back with the history.
            val signature = (((o["extra_content"] as? JsonObject)?.get("google") as? JsonObject)
                ?.get("thought_signature") as? JsonPrimitive)?.contentOrNull
            if (!signature.isNullOrBlank()) {
                if (signatures.size > 400) signatures.clear()
                signatures[id] = Sig(signature, via)
            }
            ToolCall(
                id = id,
                name = fn["name"]!!.jsonPrimitive.content,
                arguments = (fn["arguments"] as? JsonPrimitive)?.contentOrNull ?: "{}",
            )
        } ?: emptyList()
        Pair(content, calls)
    } catch (e: Exception) {
        null
    }
}
