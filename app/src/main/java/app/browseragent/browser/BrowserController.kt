package app.browseragent.browser

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import java.net.URLEncoder

private const val TAG = "BrowserController"
private const val EXT_LOCATION = "resource://android/assets/agent/"
private const val EXT_ID = "agent@browseragent.app"
private const val NATIVE_APP = "browser"

private fun JsonObject.str(k: String): String = (this[k] as? JsonPrimitive)?.contentOrNull ?: ""

/**
 * Owns the GeckoView (Firefox engine) session. A bundled WebExtension (assets/agent) runs in
 * every frame of every page and talks to this class over native-messaging ports.
 */
class BrowserController(context: Context) {
    /** The Firefox surface hosted by the UI (name kept so the UI code did not change). */
    val webView: GeckoView = GeckoView(context)
    var url by mutableStateOf("about:blank")

    private val session = GeckoSession()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var loading = false
    private var canGoBackState = false
    private var extensionReady = false
    private var pendingUrl: String? = null

    /** One content-script connection (the top page or an iframe). */
    private class Frame(val port: WebExtension.Port) {
        var ready = false
        var isTop = false
        var url = ""
    }

    private val frames = ArrayList<Frame>()
    private var subFrames: List<Frame> = emptyList()
    private val replies = HashMap<Int, CompletableDeferred<JSONObject>>()
    private var nextRid = 1

    private val portDelegate = object : WebExtension.PortDelegate {
        override fun onPortMessage(message: Any, port: WebExtension.Port) {
            val m = message as? JSONObject ?: return
            when (m.optString("type")) {
                "hello" -> {
                    val f = frames.firstOrNull { it.port === port }
                    if (f != null) {
                        f.ready = true
                        f.isTop = m.optBoolean("top")
                        f.url = m.optString("url")
                    }
                }
                "reply" -> {
                    replies[m.optInt("rid")]?.complete(m.optJSONObject("data") ?: JSONObject())
                }
            }
        }

        override fun onDisconnect(port: WebExtension.Port) {
            frames.removeAll { it.port === port }
        }
    }

    private val messageDelegate = object : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            frames.add(Frame(port))
            port.setDelegate(portDelegate)
        }
    }

    init {
        // Workaround recommended by the GeckoView quick start guide.
        session.setContentDelegate(object : GeckoSession.ContentDelegate {})

        session.setProgressDelegate(
            object : GeckoSession.ProgressDelegate {
                override fun onPageStart(session: GeckoSession, url: String) {
                    loading = true
                    this@BrowserController.url = url
                }

                override fun onPageStop(session: GeckoSession, success: Boolean) {
                    loading = false
                }
            },
        )
        session.setNavigationDelegate(
            object : GeckoSession.NavigationDelegate {
                override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                    canGoBackState = canGoBack
                }
            },
        )

        val runtime = runtime(context)
        session.open(runtime)
        webView.setSession(session)

        // Pages are loaded only after the extension is installed, so its content script
        // is present from the first page on.
        runtime.webExtensionController
            .ensureBuiltIn(EXT_LOCATION, EXT_ID)
            .accept(
                { ext ->
                    if (ext != null) {
                        session.webExtensionController.setMessageDelegate(ext, messageDelegate, NATIVE_APP)
                    }
                    markReady()
                },
                { err ->
                    Log.e(TAG, "bridge extension failed to install", err)
                    markReady()
                },
            )
    }

    private fun markReady() {
        extensionReady = true
        val u = pendingUrl
        pendingUrl = null
        if (u != null) session.loadUri(u)
    }

    fun normalize(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return "about:blank"
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*").matches(t)) return t
        if (t.startsWith("about:")) return t
        if (!t.contains(' ') && t.contains('.')) return "https://" + t
        return "https://duckduckgo.com/?q=" + URLEncoder.encode(t, "UTF-8")
    }

    /** Main thread only. */
    fun load(raw: String) {
        val target = normalize(raw)
        if (extensionReady) session.loadUri(target) else pendingUrl = target
    }

    fun canGoBack(): Boolean = canGoBackState

    fun back() {
        session.goBack()
    }

    fun destroy() {
        session.close()
    }

    // ---------------------------------------------------------------- agent API

    suspend fun navigate(raw: String): String {
        withContext(Dispatchers.Main) { load(raw) }
        settle()
        return snapshot()
    }

    suspend fun goBack(): String {
        val ok = withContext(Dispatchers.Main) {
            if (canGoBackState) {
                session.goBack()
                true
            } else {
                false
            }
        }
        if (!ok) return "Cannot go back: no history."
        settle()
        return snapshot()
    }

    /** Text snapshot of the top page plus up to 6 iframes. */
    suspend fun snapshot(): String {
        val top = topFrame()
            ?: return "ERROR: the page is not ready (browser bridge not connected yet). Wait a moment and call snapshot again."
        val data = ask(top, JSONObject().put("op", "snapshot").put("prefix", ""), 8000)
            ?: return "ERROR: the page did not respond. Try again."

        val subs = frames.filter { it.ready && !it.isTop }.take(6)
        subFrames = subs
        val elements = data.optJSONArray("elements") ?: JSONArray()
        for ((i, f) in subs.withIndex()) {
            val d = ask(f, JSONObject().put("op", "snapshot").put("prefix", "f" + (i + 1)), 1500) ?: continue
            val els = d.optJSONArray("elements") ?: continue
            for (k in 0 until els.length()) {
                val o = els.optJSONObject(k) ?: continue
                o.put("frame", f.url.take(60))
                elements.put(o)
            }
        }
        data.put("elements", elements)

        val pageUrl = data.optString("url")
        if (pageUrl.isNotEmpty()) url = pageUrl
        return try {
            format(json.parseToJsonElement(data.toString()).jsonObject)
        } catch (e: Exception) {
            "ERROR: could not read the page."
        }
    }

    /** op: click | fill | select | enter | scroll. Returns the result plus a fresh snapshot. */
    suspend fun act(op: String, id: String, arg: String): String {
        val frame: Frame? = if (op != "scroll" && id.startsWith("f")) {
            val n = id.drop(1).takeWhile { it.isDigit() }.toIntOrNull()
            if (n == null) null else subFrames.getOrNull(n - 1)
        } else {
            topFrame()
        }
        if (frame == null) return "ERROR: that element's frame is gone. Call snapshot and use fresh ids."

        val res = ask(frame, JSONObject().put("op", op).put("id", id).put("arg", arg), 8000)
            ?: return "ERROR: the page did not respond.\n\n" + snapshot()
        if (!res.optBoolean("ok")) return "ERROR: " + res.optString("msg") + "\n\n" + snapshot()
        settle()
        return "OK: " + res.optString("msg") + "\n\n" + snapshot()
    }

    // ---------------------------------------------------------------- internals

    private suspend fun topFrame(): Frame? {
        repeat(40) {
            val f = frames.firstOrNull { it.ready && it.isTop }
            if (f != null) return f
            delay(150)
        }
        return null
    }

    private suspend fun ask(frame: Frame, msg: JSONObject, timeoutMs: Long): JSONObject? =
        withContext(Dispatchers.Main) {
            val rid = nextRid++
            msg.put("rid", rid)
            val d = CompletableDeferred<JSONObject>()
            replies[rid] = d
            try {
                frame.port.postMessage(msg)
                withTimeoutOrNull(timeoutMs) { d.await() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } finally {
                replies.remove(rid)
            }
        }

    private suspend fun settle() {
        delay(500)
        val start = System.currentTimeMillis()
        while (loading && System.currentTimeMillis() - start < 15_000) delay(150)
        delay(300)
    }

    private fun format(o: JsonObject): String {
        val sb = StringBuilder()
        sb.append("URL: ").append(o.str("url")).append('\n')
        sb.append("Title: ").append(o.str("title")).append('\n')
        sb.append("Scroll: ").append(o.str("scrollY")).append(" / ").append(o.str("scrollMax")).append('\n')
        sb.append("<page_text untrusted>\n").append(o.str("text")).append("\n</page_text>\n")
        sb.append("Interactive elements (use these ids):\n")
        val els = (o["elements"] as? JsonArray) ?: JsonArray(emptyList())
        for (el in els) {
            val e = el.jsonObject
            sb.append('[').append(e.str("id")).append("] ").append(e.str("role"))
            val label = e.str("label")
            if (label.isNotEmpty()) sb.append(' ').append('"').append(label).append('"')
            if (e.containsKey("value")) sb.append(" value=").append('"').append(e.str("value")).append('"')
            (e["checked"] as? JsonPrimitive)?.booleanOrNull?.let { sb.append(if (it) " checked" else " unchecked") }
            if (e.containsKey("disabled")) sb.append(" disabled")
            if (e.containsKey("href")) sb.append(" href=").append(e.str("href"))
            (e["options"] as? JsonArray)?.let { arr ->
                sb.append(" options=[")
                    .append(arr.joinToString(" | ") { (it as? JsonPrimitive)?.contentOrNull ?: "" })
                    .append(']')
            }
            if (e.containsKey("frame")) sb.append(" (iframe ").append(e.str("frame")).append(')')
            if (e.containsKey("off")) sb.append(" (offscreen)")
            sb.append('\n')
        }
        if ((o["truncated"] as? JsonPrimitive)?.booleanOrNull == true) {
            sb.append("(more elements omitted; scroll or act to reveal them)\n")
        }
        return sb.toString().take(16000)
    }

    companion object {
        private var sharedRuntime: GeckoRuntime? = null

        /** GeckoRuntime must be created once per process. */
        private fun runtime(context: Context): GeckoRuntime {
            val existing = sharedRuntime
            if (existing != null) return existing
            val created = GeckoRuntime.create(context.applicationContext)
            sharedRuntime = created
            return created
        }
    }
}
