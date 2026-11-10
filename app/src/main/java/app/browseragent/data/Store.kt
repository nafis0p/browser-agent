package app.browseragent.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Settings persisted in encrypted shared preferences (keys never stored in plain text). */
class Store(context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "secure_store",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val providerSer = ListSerializer(Provider.serializer())
    private val aliasSer = ListSerializer(ModelAlias.serializer())
    private val mcpSer = ListSerializer(McpServer.serializer())

    var providers: List<Provider>
        get() = load("providers", providerSer) ?: defaultProviders()
        set(value) = save("providers", providerSer, value)

    var aliases: List<ModelAlias>
        get() = load("aliases", aliasSer) ?: defaultAliases()
        set(value) = save("aliases", aliasSer, value)

    var mcpServers: List<McpServer>
        get() = load("mcp", mcpSer) ?: emptyList()
        set(value) = save("mcp", mcpSer, value)

    var activeAlias: String?
        get() = prefs.getString("active", null) ?: aliases.firstOrNull()?.name
        set(value) {
            prefs.edit().putString("active", value).apply()
        }

    private fun <T> load(key: String, ser: KSerializer<T>): T? =
        prefs.getString(key, null)?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() }

    private fun <T> save(key: String, ser: KSerializer<T>, value: T) {
        prefs.edit().putString(key, json.encodeToString(ser, value)).apply()
    }

    private fun defaultProviders() = listOf(
        Provider("gemini", "Gemini", "https://generativelanguage.googleapis.com/v1beta/openai"),
        Provider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1"),
        Provider("groq", "Groq", "https://api.groq.com/openai/v1"),
    )

    // Model ids are examples: check each provider's current list.
    private fun defaultAliases() = listOf(
        ModelAlias(
            "gemini-flash",
            listOf(
                Route("Gemini", "gemini-2.0-flash"),
                Route("OpenRouter", "google/gemini-2.0-flash-001"),
            ),
        ),
        ModelAlias(
            "llama-3.3-70b",
            listOf(
                Route("Groq", "llama-3.3-70b-versatile"),
                Route("OpenRouter", "meta-llama/llama-3.3-70b-instruct"),
            ),
        ),
    )
}
