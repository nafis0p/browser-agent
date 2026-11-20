package app.browseragent.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A search engine added by the user: the search address and, optionally, an address that returns
 * suggestions while typing. Both contain %s where the typed text goes.
 */
@Serializable
data class CustomEngine(val name: String, val template: String, val suggest: String = "")

/** Interface and privacy preferences that the screens can observe. */
class AppPrefs(context: Context) {
    private val sp = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val engineSer = ListSerializer(CustomEngine.serializer())

    // No longer used by the screens (the navigation bar is always at the top), kept so that
    // older saved settings still load.
    var navBarTop by mutableStateOf(sp.getBoolean("nav_top", true))
        private set

    var addressBarBottom by mutableStateOf(sp.getBoolean("addr_bottom", false))
        private set

    var customEngines by mutableStateOf(loadEngines())
        private set

    /** False: the AI assistant is switched off (nothing is sent to any AI provider). */
    var agentEnabled by mutableStateOf(sp.getBoolean("agent_enabled", true))
        private set

    /** False: what the user clicks, types and opens by hand is not reported to the AI. */
    var shareActivity by mutableStateOf(sp.getBoolean("share_activity", true))
        private set

    /** True: while typing in the address bar, the search engine is asked for suggestions. */
    var suggestionsOn by mutableStateOf(sp.getBoolean("suggestions_on", true))
        private set

    fun chooseNavTop(top: Boolean) {
        navBarTop = top
        sp.edit().putBoolean("nav_top", top).apply()
    }

    fun chooseAddressBottom(bottom: Boolean) {
        addressBarBottom = bottom
        sp.edit().putBoolean("addr_bottom", bottom).apply()
    }

    fun chooseAgentEnabled(on: Boolean) {
        agentEnabled = on
        sp.edit().putBoolean("agent_enabled", on).apply()
    }

    fun chooseShareActivity(on: Boolean) {
        shareActivity = on
        sp.edit().putBoolean("share_activity", on).apply()
    }

    fun chooseSuggestions(on: Boolean) {
        suggestionsOn = on
        sp.edit().putBoolean("suggestions_on", on).apply()
    }

    fun saveEngines(list: List<CustomEngine>) {
        customEngines = list
        sp.edit().putString("engines", json.encodeToString(engineSer, list)).apply()
    }

    private fun loadEngines(): List<CustomEngine> =
        runCatching { json.decodeFromString(engineSer, sp.getString("engines", "") ?: "") }
            .getOrDefault(emptyList())
}
