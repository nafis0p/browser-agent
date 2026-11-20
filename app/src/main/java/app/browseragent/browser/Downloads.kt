package app.browseragent.browser

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.webkit.MimeTypeMap
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One downloaded file. The state is running, done, failed or canceled. */
@Serializable
data class DownloadItem(
    val id: String,
    val name: String,
    val url: String,
    val path: String,
    val mime: String,
    val total: Long,
    val done: Long,
    val state: String,
    val error: String = "",
    val time: Long,
    val kind: String = "file",
)

/**
 * Saves the files that websites send as downloads. Firefox hands over the whole response, which
 * is copied to a file in the app's download folder while the progress is shown in the list.
 * The list is remembered between app starts.
 */
class Downloads(context: Context) {
    private val folder: File by lazy {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads")
        base.mkdirs()
        base
    }
    private val listFile = File(context.filesDir, "downloads.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(DownloadItem.serializer())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val jobs = ConcurrentHashMap<String, Job>()
    private var saveJob: Job? = null

    /** Newest first. Observable by the UI. */
    val items = mutableStateListOf<DownloadItem>()

    init {
        val loaded = runCatching { json.decodeFromString(ser, listFile.readText()) }
            .getOrDefault(emptyList())
        // A download that was running when the app was closed cannot continue.
        items.addAll(
            loaded.map { if (it.state == "running") it.copy(state = "failed", error = "Interrupted") else it },
        )
    }

    fun runningCount(): Int = items.count { it.state == "running" }

    /** Starts saving the response. Returns the name of the file. */
    fun start(uri: String, headers: Map<String, String>, body: InputStream?): String {
        val mime = header(headers, "content-type").substringBefore(';').trim()
            .ifBlank { "application/octet-stream" }
        val name = uniqueName(fileName(uri, headers, mime))
        val target = File(folder, name)
        val total = header(headers, "content-length").trim().toLongOrNull() ?: -1L
        val id = UUID.randomUUID().toString()
        items.add(
            0,
            DownloadItem(id, name, uri, target.absolutePath, mime, total, 0L, "running", "", System.currentTimeMillis()),
        )
        save()

        if (body == null) {
            update(id) { it.copy(state = "failed", error = "The site sent no data") }
            return name
        }
        jobs[id] = scope.launch {
            val part = File(folder, name + ".part")
            try {
                var done = 0L
                var lastUi = 0L
                body.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            ensureActive()
                            val now = System.currentTimeMillis()
                            if (now - lastUi > 350) {
                                lastUi = now
                                val progress = done
                                update(id) { it.copy(done = progress) }
                            }
                        }
                    }
                }
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
                val finished = done
                update(id) { it.copy(done = finished, total = finished, state = "done") }
            } catch (e: CancellationException) {
                part.delete()
                update(id) { it.copy(state = "canceled") }
                throw e
            } catch (e: Exception) {
                part.delete()
                val why = e.message ?: "Download failed"
                update(id) { it.copy(state = "failed", error = why) }
            } finally {
                jobs.remove(id)
            }
        }
        return name
    }

    fun cancel(id: String) {
        jobs[id]?.cancel()
    }

    /** Removes the entry and deletes the file. */
    fun delete(id: String) {
        cancel(id)
        val item = items.firstOrNull { it.id == id } ?: return
        runCatching { File(item.path).delete() }
        items.removeAll { it.id == id }
        save()
    }

    /** Removes the finished entries from the list. The files stay on the device. */
    fun clearList() {
        items.removeAll { it.state != "running" }
        save()
    }

    // ---------------------------------------------------------------- helpers

    private fun update(id: String, change: (DownloadItem) -> DownloadItem) {
        main.post {
            val i = items.indexOfFirst { it.id == id }
            if (i >= 0) {
                items[i] = change(items[i])
                if (items[i].state != "running") save()
            }
        }
    }

    private fun save() {
        val snapshot = items.toList()
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            runCatching { listFile.writeText(json.encodeToString(ser, snapshot)) }
        }
    }

    private fun header(headers: Map<String, String>, key: String): String =
        headers.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value.orEmpty()

    private fun fileName(uri: String, headers: Map<String, String>, mime: String): String {
        var name = ""
        val disposition = header(headers, "content-disposition")
        for (raw in disposition.split(';')) {
            val part = raw.trim()
            val lower = part.lowercase()
            if (lower.startsWith("filename*=")) {
                val value = part.substringAfter('=').substringAfter("''", part.substringAfter('='))
                name = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
                break
            }
            if (lower.startsWith("filename=")) {
                name = part.substringAfter('=').trim().trim('"')
            }
        }
        if (name.isBlank()) {
            name = uri.substringBefore('?').substringBefore('#').substringAfterLast('/')
            name = runCatching { URLDecoder.decode(name, "UTF-8") }.getOrDefault(name)
        }
        // Keep letters, digits and a few harmless characters only.
        name = name.filter { it.isLetterOrDigit() || it in " ._-()+,&'" }.trim().take(120)
        if (name.isBlank()) name = "download"
        if (!name.contains('.')) {
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            if (ext != null) name = name + "." + ext
        }
        return name
    }

    private fun uniqueName(name: String): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var candidate = name
        var n = 1
        while (File(folder, candidate).exists() || File(folder, candidate + ".part").exists()) {
            candidate = base + " (" + n + ")" + ext
            n++
        }
        return candidate
    }
}
