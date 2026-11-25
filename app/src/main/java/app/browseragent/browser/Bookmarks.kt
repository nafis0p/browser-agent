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

/** A bookmarked page. */
@Serializable
data class Bookmark(
    val id: String,
    val title: String,
    val url: String,
    val time: Long,
)

/**
 * The bookmarks, kept in a small file. Use get() to reach the one shared instance. All changes
 * must be made on the main thread (the list is observed by the screens).
 */
class Bookmarks private constructor(context: Context) {
    private val file = File(context.filesDir, "bookmarks.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(Bookmark.serializer())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null

    /** Newest first. */
    val items = mutableStateListOf<Bookmark>()

    init {
        items.addAll(runCatching { json.decodeFromString(ser, file.readText()) }.getOrDefault(emptyList()))
    }

    // Two addresses that differ only by a trailing slash or a fragment are the same page.
    private fun key(url: String): String = url.substringBefore('#').trimEnd('/')

    fun find(url: String): Bookmark? {
        val k = key(url)
        return items.firstOrNull { key(it.url) == k }
    }

    fun isBookmarked(url: String): Boolean = find(url) != null

    fun add(title: String, url: String) {
        if (url.isBlank() || find(url) != null) return
        items.add(0, Bookmark(UUID.randomUUID().toString(), title.trim().ifBlank { url }, url, System.currentTimeMillis()))
        save()
    }

    fun remove(id: String) {
        items.removeAll { it.id == id }
        save()
    }

    fun edit(id: String, title: String, url: String) {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0 || url.isBlank()) return
        items[i] = items[i].copy(title = title.trim().ifBlank { url }, url = url.trim())
        save()
    }

    /** Adds the page, or removes it when it is already bookmarked. Returns true when it is bookmarked now. */
    fun toggle(title: String, url: String): Boolean {
        val existing = find(url)
        if (existing != null) {
            remove(existing.id)
            return false
        }
        add(title, url)
        return true
    }

    private fun save() {
        val snapshot = items.toList()
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(300)
            runCatching { file.writeText(json.encodeToString(ser, snapshot)) }
        }
    }

    companion object {
        @Volatile
        private var instance: Bookmarks? = null

        fun get(context: Context): Bookmarks =
            instance ?: synchronized(this) {
                instance ?: Bookmarks(context.applicationContext).also { instance = it }
            }
    }
}
