package app.browseragent.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** A site pinned on the start page. */
@Serializable
data class Shortcut(val title: String, val url: String)

/** template contains %s where the search text goes. */
data class SearchEngine(val key: String, val name: String, val template: String)

val SEARCH_ENGINES: List<SearchEngine> = listOf(
    SearchEngine("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    SearchEngine("google", "Google", "https://www.google.com/search?q=%s"),
    SearchEngine("brave", "Brave Search", "https://search.brave.com/search?q=%s"),
    SearchEngine("bing", "Bing", "https://www.bing.com/search?q=%s"),
    SearchEngine("startpage", "Startpage", "https://www.startpage.com/do/search?q=%s"),
    SearchEngine("ecosia", "Ecosia", "https://www.ecosia.org/search?q=%s"),
)

/** argb is 0xAARRGGBB. */
data class Accent(val key: String, val name: String, val argb: Long)

val ACCENTS: List<Accent> = listOf(
    Accent("orange", "Orange", 0xFFFB542B),
    Accent("blue", "Blue", 0xFF1A73E8),
    Accent("violet", "Violet", 0xFF7C4DFF),
    Accent("green", "Green", 0xFF2E7D32),
    Accent("pink", "Pink", 0xFFD81B60),
    Accent("teal", "Teal", 0xFF00796B),
)

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
    private val vaultSer = MapSerializer(String.serializer(), String.serializer())
    private val shortcutSer = ListSerializer(Shortcut.serializer())

    var providers: List<Provider>
        get() = load("providers", providerSer) ?: defaultProviders()
        set(value) = save("providers", providerSer, value)

    var aliases: List<ModelAlias>
        get() = load("aliases", aliasSer) ?: defaultAliases()
        set(value) = save("aliases", aliasSer, value)

    var mcpServers: List<McpServer>
        get() = load("mcp", mcpSer) ?: emptyList()
        set(value) = save("mcp", mcpSer, value)

    /** Private values (email, password, phone, card...) the AI can only use through placeholders. */
    var vault: Map<String, String>
        get() = load("vault", vaultSer) ?: emptyMap()
        set(value) = save("vault", vaultSer, value)

    /** Custom system prompt; null means: use the built-in default. */
    var systemPrompt: String?
        get() = prefs.getString("system_prompt", null)
        set(value) {
            prefs.edit().putString("system_prompt", value).apply()
        }

    /** Model used for the current run only (team roles). Never saved. */
    var aliasOverride: String? = null

    var activeAlias: String?
        get() = aliasOverride ?: prefs.getString("active", null) ?: aliases.firstOrNull()?.name
        set(value) {
            prefs.edit().putString("active", value).apply()
        }

    // ---------------------------------------------------------------- agent team

    /** single, or team (planner + executor + checker). */
    var teamMode: String
        get() = prefs.getString("team_mode", "single") ?: "single"
        set(value) {
            prefs.edit().putString("team_mode", value).apply()
        }

    /** How many times the checker may send the executor back to fix problems. */
    var maxFixRounds: Int
        get() = prefs.getInt("fix_rounds", 1)
        set(value) {
            prefs.edit().putInt("fix_rounds", value).apply()
        }

    /** Model alias per role; empty means: the active model. */
    var plannerAlias: String
        get() = prefs.getString("planner_alias", "") ?: ""
        set(value) {
            prefs.edit().putString("planner_alias", value).apply()
        }

    var executorAlias: String
        get() = prefs.getString("executor_alias", "") ?: ""
        set(value) {
            prefs.edit().putString("executor_alias", value).apply()
        }

    var checkerAlias: String
        get() = prefs.getString("checker_alias", "") ?: ""
        set(value) {
            prefs.edit().putString("checker_alias", value).apply()
        }

    /** Role instructions; null means: the built-in default. */
    var plannerPrompt: String?
        get() = prefs.getString("planner_prompt", null)
        set(value) {
            prefs.edit().putString("planner_prompt", value).apply()
        }

    var executorPrompt: String?
        get() = prefs.getString("executor_prompt", null)
        set(value) {
            prefs.edit().putString("executor_prompt", value).apply()
        }

    var checkerPrompt: String?
        get() = prefs.getString("checker_prompt", null)
        set(value) {
            prefs.edit().putString("checker_prompt", value).apply()
        }

    // ---------------------------------------------------------------- browser settings

    /** Empty means: show the built-in start page. */
    var homepage: String
        get() = prefs.getString("homepage", "") ?: ""
        set(value) {
            prefs.edit().putString("homepage", value).apply()
        }

    /** A key of SEARCH_ENGINES, or "custom". */
    var searchEngine: String
        get() = prefs.getString("search_engine", "duckduckgo") ?: "duckduckgo"
        set(value) {
            prefs.edit().putString("search_engine", value).apply()
        }

    var customSearchUrl: String
        get() = prefs.getString("custom_search", "") ?: ""
        set(value) {
            prefs.edit().putString("custom_search", value).apply()
        }

    /** system, light or dark. */
    var themeMode: String
        get() = prefs.getString("theme", "system") ?: "system"
        set(value) {
            prefs.edit().putString("theme", value).apply()
        }

    /** A key of ACCENTS. */
    var accent: String
        get() = prefs.getString("accent", "orange") ?: "orange"
        set(value) {
            prefs.edit().putString("accent", value).apply()
        }

    var shortcuts: List<Shortcut>
        get() = load("shortcuts", shortcutSer) ?: defaultShortcuts()
        set(value) = save("shortcuts", shortcutSer, value)

    /** The search URL with %s for the query, from the chosen search engine. */
    fun searchTemplate(): String {
        if (searchEngine == "custom") {
            val c = customSearchUrl
            if (c.contains("%s")) return c
        }
        return SEARCH_ENGINES.firstOrNull { it.key == searchEngine }?.template ?: SEARCH_ENGINES[0].template
    }

    private fun <T> load(key: String, ser: KSerializer<T>): T? =
        prefs.getString(key, null)?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() }

    private fun <T> save(key: String, ser: KSerializer<T>, value: T) {
        prefs.edit().putString(key, json.encodeToString(ser, value)).apply()
    }

    private fun defaultShortcuts() = listOf(
        Shortcut("Wikipedia", "https://www.wikipedia.org"),
        Shortcut("YouTube", "https://m.youtube.com"),
        Shortcut("GitHub", "https://github.com"),
        Shortcut("Reddit", "https://www.reddit.com"),
        Shortcut("Gmail", "https://mail.google.com"),
        Shortcut("Maps", "https://maps.google.com"),
    )

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
