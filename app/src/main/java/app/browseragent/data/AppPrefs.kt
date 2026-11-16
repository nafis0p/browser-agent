package app.browseragent.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A search engine added by the user. template contains %s where the search text goes. */
@Serializable
data class CustomEngine(val name: String, val template: String)

/**
 * Interface and privacy preferences, observable by the UI. (Functions that change them are named
 * choose *save* so they never clash with the generated property setters.)
 */
class AppPrefs(context: Context) {
    private val sp = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val engineSer = ListSerializer(CustomEngine.serializer())

    /** True: the navigation bar (back, forward, AI, home, menu) is at the top. */
    var navBarTop by mutableStateOf(sp.getBoolean("nav_top", false))
        private set

    /** True: the address bar is at the bottom. */
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

    fun saveEngines(list: List<CustomEngine>) {
        customEngines = list
        sp.edit().putString("engines", json.encodeToString(engineSer, list)).apply()
    }

    private fun loadEngines(): List<CustomEngine> =
        runCatching { json.decodeFromString(engineSer, sp.getString("engines", "") ?: "") }
            .getOrDefault(emptyList())
}
