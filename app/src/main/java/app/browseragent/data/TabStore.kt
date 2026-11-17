package app.browseragent.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** One open tab as it is remembered between app starts. An empty url means: the start page. */
@Serializable
data class SavedTab(val url: String, val title: String)

@Serializable
data class SavedTabs(val tabs: List<SavedTab> = emptyList(), val current: Int = 0)

/** Remembers the open tabs in a small file in the app's private storage. */
class TabStore(context: Context) {
    private val file = File(context.filesDir, "tabs.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun load(): SavedTabs =
        runCatching { json.decodeFromString(SavedTabs.serializer(), file.readText()) }
            .getOrDefault(SavedTabs())

    /** Saves a moment later; a newer call replaces an older one that has not run yet. */
    fun save(state: SavedTabs) {
        job?.cancel()
        job = scope.launch {
            delay(400)
            runCatching { file.writeText(json.encodeToString(SavedTabs.serializer(), state)) }
        }
    }

    /** Saves right now (used when the app goes to the background). */
    fun saveNow(state: SavedTabs) {
        job?.cancel()
        runCatching { file.writeText(json.encodeToString(SavedTabs.serializer(), state)) }
    }
}
