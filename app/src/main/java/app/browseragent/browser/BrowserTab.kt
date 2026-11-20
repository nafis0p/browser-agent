package app.browseragent.browser

import android.os.Handler
import android.os.Looper
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
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebResponse

internal const val NATIVE_APP = "browser"

/** Why a page is not shown normally. The kind is either network or notfound. */
class PageError(val kind: String, val url: String)

private fun JsonObject.str(k: String): String = (this[k] as? JsonPrimitive)?.contentOrNull ?: ""

/**
 * One browser tab: its own Firefox session and its own connection to the page scripts.
 * The BrowserController keeps the list of tabs and decides which one is shown.
 *
 * A tab restored from the last run stays asleep (no Firefox session yet) until it is shown.
 */
class BrowserTab internal constructor(
    val id: Int,
    private val owner: BrowserController,
    private val runtime: GeckoRuntime,
    restoredUrl: String? = null,
    restoredTitle: String = "",
) {
    val session = GeckoSession()

    var url by mutableStateOf("about:blank")
    var title by mutableStateOf("")
    var progress by mutableStateOf(0)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var canBack by mutableStateOf(false)
        private set
    var canForward by mutableStateOf(false)
        private set
    var desktopMode by mutableStateOf(false)
        private set

    /** True while this tab shows the start page instead of a web page. */
    var isHome by mutableStateOf(true)
        private set

    /** Set when the page could not be loaded or does not exist (404). */
    var pageError by mutableStateOf<PageError?>(null)
        private set

    /** How far the page is scrolled down, in pixels (pull to refresh only works at the top). */
    @Volatile
    internal var scrollOffset = 0

    private val json = Json { ignoreUnknownKeys = true }
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var loading = false
    private var opened = false
    private var pendingUrl: String? = null
    private var userStopped = false
    private var lastStopFailed = false

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
                "page" -> {
                    val pageUrl = m.optString("url")
                    val pageTitle = m.optString("title")
                    if (pageTitle.isNotEmpty()) title = pageTitle
                    if (m.optInt("status", 0) == 404) {
                        pageError = PageError("notfound", pageUrl)
                    } else {
                        owner.recordPage(pageUrl, pageTitle)
                    }
                    owner.tabsChanged()
                }
                "user" -> {
                    val label = m.optString("label")
                    val text = when (m.optString("kind")) {
                        "click" -> "clicked '" + label + "'"
                        "changed" -> "changed '" + label + "' to '" + m.optString("value") + "'"
                        "typed" -> "typed in '" + label + "' (hidden)"
                        "submit" -> "submitted a form ('" + label + "')"
                        else -> m.optString("kind") + " '" + label + "'"
                    }
                    if (owner.current === this@BrowserTab) owner.noteUser(text)
                }
            }
        }

        override fun onDisconnect(port: WebExtension.Port) {
            frames.removeAll { it.port === port }
        }
    }

    internal val messageDelegate = object : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            frames.add(Frame(port))
            port.setDelegate(portDelegate)
        }
    }

    init {
        // A response Firefox cannot show (a ZIP, an APK, an attachment) is a download.
        session.setContentDelegate(
            object : GeckoSession.ContentDelegate {
                override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                    owner.startDownload(response.uri, response.headers, response.body)
                }
            },
        )

        session.setProgressDelegate(
            object : GeckoSession.ProgressDelegate {
                override fun onPageStart(session: GeckoSession, url: String) {
                    loading = true
                    isLoading = true
                    progress = 0
                    pageError = null
                    lastStopFailed = false
                    userStopped = false
                    scrollOffset = 0
                    this@BrowserTab.url = url
                    if (owner.current === this@BrowserTab) owner.noteUser("opened " + url.take(100))
                }

                override fun onPageStop(session: GeckoSession, success: Boolean) {
                    loading = false
                    isLoading = false
                    progress = 100
                    if (!success && !userStopped) {
                        lastStopFailed = true
                        // Wait a moment: a newer load may already be starting.
                        handler.postDelayed(
                            {
                                if (lastStopFailed && !loading && pageError == null) {
                                    pageError = PageError("network", this@BrowserTab.url)
                                }
                            },
                            600,
                        )
                    }
                    owner.tabsChanged()
                }

                override fun onProgressChange(session: GeckoSession, value: Int) {
                    progress = value
                }
            },
        )
        session.setNavigationDelegate(
            object : GeckoSession.NavigationDelegate {
                override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                    canBack = canGoBack
                }

                override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                    canForward = canGoForward
                }
            },
        )
        session.setScrollDelegate(
            object : GeckoSession.ScrollDelegate {
                override fun onScrollChanged(session: GeckoSession, scrollX: Int, scrollY: Int) {
                    scrollOffset = scrollY
                }
            },
        )

        // A tab restored from the last run: it shows its old address and loads when it is opened.
        if (!restoredUrl.isNullOrBlank()) {
            url = restoredUrl
            title = restoredTitle
            isHome = false
            pendingUrl = restoredUrl
        }
    }

    // ---------------------------------------------------------------- setup and navigation

    internal fun isOpened(): Boolean = opened

    /** Starts the Firefox session of a tab that was asleep. Called when the tab is shown. */
    internal fun ensureOpen(ext: WebExtension?) {
        if (opened) return
        opened = true
        session.open(runtime)
        if (ext != null) installBridge(ext)
        if (owner.extensionReady) onExtensionReady()
    }

    /** The tab that is shown is active, every other tab is inactive. */
    internal fun setActive(active: Boolean) {
        if (opened) session.setActive(active)
    }

    internal fun installBridge(ext: WebExtension) {
        session.webExtensionController.setMessageDelegate(ext, messageDelegate, NATIVE_APP)
    }

    /** Called when the page-script extension is installed: starts a load that was waiting. */
    internal fun onExtensionReady() {
        val u = pendingUrl
        pendingUrl = null
        if (u != null) session.loadUri(u)
    }

    /** Main thread only. The address must already be normalized. */
    fun load(target: String) {
        isHome = false
        pageError = null
        if (opened && owner.extensionReady) session.loadUri(target) else pendingUrl = target
    }

    fun showHome() {
        isHome = true
        owner.tabsChanged()
    }

    fun dismissError() {
        pageError = null
    }

    fun back() {
        if (isHome && url != "about:blank") {
            isHome = false
        } else {
            session.goBack()
        }
    }

    /** Goes back in the session history only (used by the agent). */
    fun goBackNow() {
        session.goBack()
    }

    fun forward() {
        session.goForward()
    }

    fun reload() {
        pageError = null
        session.reload()
    }

    fun stop() {
        userStopped = true
        session.stop()
    }

    fun setDesktop(enabled: Boolean) {
        desktopMode = enabled
        val s = session.settings
        s.setUserAgentMode(
            if (enabled) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE,
        )
        s.setViewportMode(
            if (enabled) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE,
        )
        session.reload()
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        if (opened) session.close()
    }

    // ---------------------------------------------------------------- agent API

    /** Text snapshot of the top page plus up to 6 iframes. */
    suspend fun snapshot(): String {
        if (isHome) {
            return "URL: about:home\nTitle: Start page\n<page_text untrusted>\n" +
                "The browser start page is showing; no web page is open. Use navigate to open a site.\n" +
                "</page_text>\nInteractive elements (use these ids):\n"
        }
        val err = pageError
        if (err != null) {
            val what = if (err.kind == "notfound") {
                "404 Not Found: this page does not exist."
            } else {
                "The page could not be loaded (network error)."
            }
            return "URL: " + err.url + "\nTitle: Error\n<page_text untrusted>\n" + what +
                " Open another address with navigate.\n</page_text>\nInteractive elements (use these ids):\n"
        }
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

    /** op: click, fill, select, enter or scroll. Returns the result plus a fresh snapshot. */
    suspend fun act(op: String, id: String, arg: String): String {
        if (isHome) return "ERROR: the start page is showing; open a site with navigate first."
        if (pageError != null) return "ERROR: this page failed to load; open another address with navigate."
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

    /** Waits until the page has finished loading (at most about 15 seconds). */
    suspend fun settle() {
        delay(500)
        val start = System.currentTimeMillis()
        while (loading && System.currentTimeMillis() - start < 15_000) delay(150)
        delay(300)
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
}
