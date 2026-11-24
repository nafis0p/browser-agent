package app.browseragent.browser

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// Files from this size on count as large for the setting Ask only for large files.
private const val LARGE_BYTES = 25L * 1024L * 1024L

/**
 * One downloaded file. The state is running, paused, saving, done, failed or canceled.
 * Where the finished file lives: in path (the app's folder) or in uri (a folder the user chose,
 * or the public Downloads folder).
 */
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
    val uri: String = "",
    val where: String = "app",
    val tree: String = "",
    val ask: Boolean = false,
    val part: String = "",
)

/** A question to the user: where should this download be saved. */
class DownloadAsk(val id: String, val name: String, val total: Long, val host: String)

/**
 * Saves the files that websites send as downloads. Firefox hands over the whole response, which
 * is copied to a temporary file while the progress is shown. When it is complete it is moved to
 * the place the user wants: the app's folder, the public Downloads folder, or a folder chosen with
 * the system picker (internal storage or an SD card). A download can be paused and resumed; a
 * paused or interrupted one continues with a new request for the rest of the file when its
 * connection is gone. The list is remembered between app starts.
 */
class Downloads(context: Context) {
    private val appContext: Context = context.applicationContext
    private val sp = appContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    private val folder: File by lazy {
        val base = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(appContext.filesDir, "downloads")
        base.mkdirs()
        base
    }
    private val partDir: File by lazy {
        val base = appContext.getExternalFilesDir("partial") ?: File(appContext.filesDir, "partial")
        base.mkdirs()
        base
    }
    private val listFile = File(appContext.filesDir, "downloads.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(DownloadItem.serializer())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val jobs = ConcurrentHashMap<String, Job>()
    private val paused = ConcurrentHashMap.newKeySet<String>()
    private val savingIds = ConcurrentHashMap.newKeySet<String>()
    private var saveJob: Job? = null
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Newest first. Observable by the UI. */
    val items = mutableStateListOf<DownloadItem>()

    /** Downloads waiting for the user to choose where to save them (the first is shown). */
    val asks = mutableStateListOf<DownloadAsk>()

    // ---- settings (kept in the same preferences file as the other app settings)

    /** always, large or never: when the user is asked where to save. */
    var askMode by mutableStateOf(sp.getString("dl_ask", "never") ?: "never")
        private set

    /** app, public or tree: where files go when the user is not asked. */
    var saveWhere by mutableStateOf(sp.getString("dl_where", "app") ?: "app")
        private set

    /** The chosen folder (an address from the system folder picker) and its name. */
    var saveTree by mutableStateOf(sp.getString("dl_tree", "") ?: "")
        private set
    var saveTreeName by mutableStateOf(sp.getString("dl_tree_name", "") ?: "")
        private set

    init {
        val loaded = runCatching { json.decodeFromString(ser, listFile.readText()) }
            .getOrDefault(emptyList())
        // A download that was running when the app was closed cannot continue by itself. What was
        // already downloaded stays, so it can be resumed.
        items.addAll(
            loaded.map {
                when (it.state) {
                    "running" -> it.copy(state = "failed", error = "Interrupted", ask = false)
                    "saving" -> it.copy(state = "failed", error = "Interrupted while saving", ask = false)
                    else -> it.copy(ask = false)
                }
            },
        )
    }

    fun chooseAskMode(mode: String) {
        askMode = mode
        sp.edit().putString("dl_ask", mode).apply()
    }

    fun chooseSaveWhere(where: String) {
        saveWhere = where
        sp.edit().putString("dl_where", where).apply()
    }

    /** The user picked a folder with the system picker: it becomes the default place. */
    fun chooseSaveTree(uri: String, label: String) {
        saveTree = uri
        saveTreeName = label
        saveWhere = "tree"
        sp.edit().putString("dl_tree", uri).putString("dl_tree_name", label).putString("dl_where", "tree").apply()
    }

    fun runningCount(): Int = items.count { it.state == "running" }

    /** A place that cannot be used falls back to the app's folder. */
    private fun effectiveWhere(where: String, tree: String): String = when {
        where == "tree" && tree.isBlank() -> "app"
        where == "public" && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> "app"
        where == "tree" || where == "public" -> where
        else -> "app"
    }

    // ---------------------------------------------------------------- start

    /** Starts saving the response. Returns the name of the file. */
    fun start(uri: String, headers: Map<String, String>, body: InputStream?): String {
        val mime = header(headers, "content-type").substringBefore(';').trim()
            .ifBlank { "application/octet-stream" }
        val name = uniqueName(fileName(uri, headers, mime))
        val total = header(headers, "content-length").trim().toLongOrNull() ?: -1L
        val id = UUID.randomUUID().toString()
        val part = File(partDir, id + ".part")
        val shouldAsk = when (askMode) {
            "always" -> true
            "large" -> total >= LARGE_BYTES
            else -> false
        }
        val where = effectiveWhere(saveWhere, saveTree)
        items.add(
            0,
            DownloadItem(
                id = id,
                name = name,
                url = uri,
                path = "",
                mime = mime,
                total = total,
                done = 0L,
                state = "running",
                time = System.currentTimeMillis(),
                where = where,
                tree = if (where == "tree") saveTree else "",
                ask = shouldAsk,
                part = part.absolutePath,
            ),
        )
        if (shouldAsk) asks.add(DownloadAsk(id, name, total, uri.substringAfter("://").substringBefore('/')))
        save()

        if (body == null) {
            update(id) { it.copy(state = "failed", error = "The site sent no data") }
            return name
        }
        // The data is copied while the user decides where it goes.
        jobs[id] = scope.launch { guarded(id) { copyLoop(id, body, part, 0L) } }
        return name
    }

    // ---------------------------------------------------------------- controls (main thread)

    /** Stops reading; the connection waits. */
    fun pause(id: String) {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0 || items[i].state != "running") return
        paused.add(id)
        items[i] = items[i].copy(state = "paused")
        save()
    }

    /**
     * Continues a paused download. If its connection is still there it simply reads on. Otherwise,
     * and for a failed one, a new request asks the server for the rest of the file.
     */
    fun resume(id: String) {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) return
        val item = items[i]
        if (item.state == "paused" && jobs.containsKey(id)) {
            paused.remove(id)
            items[i] = item.copy(state = "running")
            save()
            return
        }
        if (item.state != "paused" && item.state != "failed") return

        val part = if (item.part.isNotBlank()) File(item.part) else File(partDir, id + ".part")
        val have = if (part.exists()) part.length() else 0L
        if (have > 0L && item.total > 0L && have >= item.total) {
            // Everything was downloaded: only saving it to its place failed or was interrupted.
            items[i] = item.copy(state = "saving", error = "", part = part.absolutePath, done = have)
            afterTransfer(id)
            return
        }
        if (!item.url.startsWith("http")) {
            items[i] = item.copy(state = "failed", error = "This download cannot be resumed")
            return
        }
        paused.remove(id)
        items[i] = item.copy(state = "running", error = "", part = part.absolutePath, done = have)
        save()
        jobs[id] = scope.launch { guarded(id) { rangeTransfer(items.first { it.id == id }, part, have) } }
    }

    /** Stops a running or paused download and throws away what was downloaded. */
    fun cancel(id: String) {
        paused.remove(id)
        jobs[id]?.cancel()
        asks.removeAll { it.id == id }
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) return
        val item = items[i]
        if (item.state == "running" || item.state == "paused") {
            if (item.part.isNotBlank()) runCatching { File(item.part).delete() }
            items[i] = item.copy(state = "canceled", part = "", ask = false)
            save()
        }
    }

    /** Removes the entry and deletes the file. */
    fun delete(id: String) {
        paused.remove(id)
        jobs[id]?.cancel()
        asks.removeAll { it.id == id }
        val item = items.firstOrNull { it.id == id } ?: return
        if (item.part.isNotBlank()) runCatching { File(item.part).delete() }
        if (item.path.isNotBlank()) runCatching { File(item.path).delete() }
        if (item.uri.isNotBlank()) deleteContent(Uri.parse(item.uri))
        items.removeAll { it.id == id }
        save()
    }

    /** Removes the finished entries from the list. The files stay on the device. */
    fun clearList() {
        items.removeAll { it.state != "running" && it.state != "paused" && it.state != "saving" }
        save()
    }

    /** The user chose a name and a place for a download that was waiting. */
    fun answerAsk(id: String, name: String, where: String, tree: String) {
        asks.removeAll { it.id == id }
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) return
        val place = effectiveWhere(where, tree)
        val clean = cleanName(name).ifBlank { items[i].name }
        items[i] = items[i].copy(name = clean, where = place, tree = if (place == "tree") tree else "", ask = false)
        save()
        // If everything is downloaded already, it can be saved right now.
        if (items[i].state == "saving") startSaving(items[i])
    }

    /** The user does not want this download after all. */
    fun cancelAsk(id: String) {
        delete(id)
    }

    // ---------------------------------------------------------------- transfer

    /** Runs one transfer and sets the state at its end. The block returns the size in bytes. */
    private suspend fun guarded(id: String, block: suspend () -> Long) {
        try {
            val size = block()
            update(id) { it.copy(done = size, total = size, state = "saving", error = "") }
            main.post { afterTransfer(id) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val why = e.message ?: "Download failed"
            update(id) { it.copy(state = "failed", error = why) }
        } finally {
            jobs.remove(id)
            paused.remove(id)
        }
    }

    /** Everything is downloaded: save it to its place, unless the user still has to choose it. */
    private fun afterTransfer(id: String) {
        val item = items.firstOrNull { it.id == id } ?: return
        if (item.state != "saving" || item.ask) return
        startSaving(item)
    }

    private fun startSaving(item: DownloadItem) {
        if (!savingIds.add(item.id)) return
        scope.launch { saveFinal(item) }
    }

    private fun saveFinal(item: DownloadItem) {
        try {
            val part = File(item.part)
            if (!part.exists()) throw IOException("the downloaded data is gone")
            when (item.where) {
                "public" -> {
                    val saved = saveToPublic(part, item.name, item.mime)
                    part.delete()
                    update(item.id) { it.copy(uri = saved, path = "", state = "done", part = "", error = "") }
                }
                "tree" -> {
                    val saved = saveToTree(part, Uri.parse(item.tree), item.name, item.mime)
                    part.delete()
                    update(item.id) { it.copy(uri = saved, path = "", state = "done", part = "", error = "") }
                }
                else -> {
                    val target = File(folder, uniqueName(item.name))
                    if (!part.renameTo(target)) {
                        part.copyTo(target, overwrite = true)
                        part.delete()
                    }
                    update(item.id) {
                        it.copy(path = target.absolutePath, name = target.name, uri = "", state = "done", part = "", error = "")
                    }
                }
            }
        } catch (e: Exception) {
            val why = "Could not save: " + (e.message ?: "unknown error")
            update(item.id) { it.copy(state = "failed", error = why) }
        } finally {
            savingIds.remove(item.id)
        }
    }

    /** Into the public Downloads folder (visible in the Files app). Android 10 and newer. */
    private fun saveToPublic(part: File, name: String, mime: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) throw IOException("not available on this Android version")
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("the Downloads folder refused the file")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("could not open the new file")
            out.use { o -> part.inputStream().use { it.copyTo(o) } }
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return uri.toString()
    }

    /** Into a folder chosen with the system picker (internal storage or an SD card). */
    private fun saveToTree(part: File, tree: Uri, name: String, mime: String): String {
        val resolver = appContext.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(resolver, parent, mime, name)
            ?: throw IOException("could not create the file in that folder")
        try {
            val out = resolver.openOutputStream(doc) ?: throw IOException("could not open the new file")
            out.use { o -> part.inputStream().use { it.copyTo(o) } }
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, doc) }
            throw e
        }
        return doc.toString()
    }

    private fun deleteContent(uri: Uri) {
        val resolver = appContext.contentResolver
        val ok = runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)
        if (!ok) runCatching { resolver.delete(uri, null, null) }
    }

    /** Reads the stream into the partial file. While paused nothing is read. Returns the size. */
    private suspend fun copyLoop(id: String, input: InputStream, part: File, offset: Long): Long {
        var done = offset
        var lastUi = 0L
        input.use { ins ->
            FileOutputStream(part, offset > 0L).use { out ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    while (paused.contains(id)) delay(200)
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    currentCoroutineContext().ensureActive()
                    val now = System.currentTimeMillis()
                    if (now - lastUi > 350) {
                        lastUi = now
                        val progress = done
                        update(id) { it.copy(done = progress) }
                    }
                }
            }
        }
        return done
    }

    /** A new request for the rest of the file (or for all of it when the server cannot do that). */
    private suspend fun rangeTransfer(item: DownloadItem, part: File, have: Long): Long {
        val builder = Request.Builder().url(item.url)
        if (have > 0L) builder.header("Range", "bytes=" + have + "-")
        val resp = http.newCall(builder.build()).execute()
        if (!resp.isSuccessful) {
            val code = resp.code
            resp.close()
            throw IOException("The server answered HTTP " + code + ". A file that needs a login cannot be resumed.")
        }
        val partial = resp.code == 206
        val offset = if (partial) have else 0L
        val body = resp.body ?: run {
            resp.close()
            throw IOException("The server sent no data")
        }
        val total = if (partial) {
            resp.header("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull() ?: -1L
        } else {
            body.contentLength()
        }
        update(item.id) { it.copy(total = total, done = offset) }
        return copyLoop(item.id, body.byteStream(), part, offset)
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

    /** Keeps letters, digits and a few harmless characters only. */
    private fun cleanName(raw: String): String =
        raw.filter { it.isLetterOrDigit() || it in " ._-()+,&'" }.trim().take(120)

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
        name = cleanName(name)
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
        while (File(folder, candidate).exists()) {
            candidate = base + " (" + n + ")" + ext
            n++
        }
        return candidate
    }
}
