package app.browseragent.browser

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** A page that was saved for offline reading. The file is one self-contained HTML file. */
@Serializable
data class OfflineItem(
    val id: String,
    val title: String,
    val url: String,
    val time: Long,
    val file: String,
    val size: Long,
)

/**
 * The pages saved for offline reading: a folder with one HTML file per page and a small index
 * (title, original address, date, size). Use get() to reach the one shared instance.
 */
class OfflinePages private constructor(context: Context) {
    private val dir = File(context.filesDir, "offline").also { it.mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(OfflineItem.serializer())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null

    /** Newest first. Observable by the screens. */
    val items = mutableStateListOf<OfflineItem>()

    init {
        val loaded = runCatching { json.decodeFromString(ser, indexFile.readText()) }.getOrDefault(emptyList())
        // An entry whose file is gone is forgotten.
        items.addAll(loaded.filter { File(it.file).exists() })
    }

    /** An id and an empty file for a page that is about to be saved. */
    fun newFile(): Pair<String, File> {
        val id = UUID.randomUUID().toString()
        return Pair(id, File(dir, id + ".html"))
    }

    fun add(item: OfflineItem) {
        items.add(0, item)
        save()
    }

    fun delete(id: String) {
        val item = items.firstOrNull { it.id == id } ?: return
        runCatching { File(item.file).delete() }
        items.removeAll { it.id == id }
        save()
    }

    /** The address to open the saved page in a tab. */
    fun fileUrl(item: OfflineItem): String = "file://" + item.file

    private fun save() {
        val snapshot = items.toList()
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(300)
            runCatching { indexFile.writeText(json.encodeToString(ser, snapshot)) }
        }
    }

    companion object {
        @Volatile
        private var instance: OfflinePages? = null

        fun get(context: Context): OfflinePages =
            instance ?: synchronized(this) {
                instance ?: OfflinePages(context.applicationContext).also { instance = it }
            }
    }
}
