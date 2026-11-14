package app.browseragent.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class HistoryEntry(val url: String, val title: String, val time: Long)

/** One line of a saved chat. kind is the name of agent.Kind. */
@Serializable
data class ChatEntry(val kind: String, val text: String)

@Serializable
data class Chat(
    val id: String,
    val title: String,
    val created: Long,
    val updated: Long,
    val entries: List<ChatEntry>,
)

/** A list kept in a JSON file in the app's private storage, saved in the background. */
private class JsonFileList<T>(private val file: File, private val ser: KSerializer<List<T>>) {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun load(): List<T> =
        runCatching { json.decodeFromString(ser, file.readText()) }.getOrDefault(emptyList())

    fun save(items: List<T>) {
        job?.cancel()
        job = scope.launch {
            delay(800)
            runCatching { file.writeText(json.encodeToString(ser, items)) }
        }
    }
}

/** Browsing history, newest first. */
class HistoryStore(context: Context) {
    private val file = JsonFileList(
        File(context.filesDir, "history.json"),
        ListSerializer(HistoryEntry.serializer()),
    )

    val entries = mutableStateListOf<HistoryEntry>().apply { addAll(file.load()) }

    fun record(url: String, title: String) {
        if (!url.startsWith("http")) return
        val now = System.currentTimeMillis()
        val first = entries.firstOrNull()
        if (first != null && first.url == url && now - first.time < 60_000) {
            if (title.isNotBlank() && title != first.title) {
                entries[0] = first.copy(title = title)
                file.save(entries.toList())
            }
            return
        }
        entries.add(0, HistoryEntry(url, title.ifBlank { url }, now))
        while (entries.size > MAX) entries.removeAt(entries.size - 1)
        file.save(entries.toList())
    }

    fun remove(entry: HistoryEntry) {
        entries.remove(entry)
        file.save(entries.toList())
    }

    fun clear() {
        entries.clear()
        file.save(emptyList())
    }

    private companion object {
        const val MAX = 3000
    }
}

/** Saved AI chats, most recently used first. */
class ChatStore(context: Context) {
    private val file = JsonFileList(
        File(context.filesDir, "chats.json"),
        ListSerializer(Chat.serializer()),
    )

    val chats = mutableStateListOf<Chat>().apply { addAll(file.load()) }

    fun upsert(chat: Chat) {
        val i = chats.indexOfFirst { it.id == chat.id }
        if (i >= 0) chats.removeAt(i)
        chats.add(0, chat)
        while (chats.size > MAX) chats.removeAt(chats.size - 1)
        file.save(chats.toList())
    }

    fun delete(id: String) {
        chats.removeAll { it.id == id }
        file.save(chats.toList())
    }

    fun clear() {
        chats.clear()
        file.save(emptyList())
    }

    private companion object {
        const val MAX = 100
    }
}
