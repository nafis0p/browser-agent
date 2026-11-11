package app.browseragent.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.URLEncoder
import kotlin.coroutines.resume

private fun JsonObject.str(k: String): String = (this[k] as? JsonPrimitive)?.contentOrNull ?: ""

/** Owns the in-app WebView and exposes snapshot / action functions for the agent. */
class BrowserController(context: Context) {
    val webView = WebView(context)
    var url by mutableStateOf("about:blank")

    @Volatile
    private var loading = false
    private val json = Json { ignoreUnknownKeys = true }

    init {
        configure()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure() {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(false)
        // Look like regular mobile Chrome rather than an embedded WebView.
        s.userAgentString = s.userAgentString
            .replace("; wv", "")
            .replace(Regex("Version/\\d+\\.\\d+ "), "")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, pageUrl: String, favicon: Bitmap?) {
                loading = true
                this@BrowserController.url = pageUrl
            }

            override fun onPageFinished(view: WebView, pageUrl: String) {
                loading = false
                this@BrowserController.url = pageUrl
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme ?: return true
                // Block intent:, market:, custom app schemes, etc.
                return scheme != "http" && scheme != "https" && scheme != "about"
            }
        }
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
        loading = true
        webView.loadUrl(normalize(raw))
    }

    suspend fun navigate(raw: String): String {
        withContext(Dispatchers.Main) { load(raw) }
        settle()
        return snapshot()
    }

    suspend fun goBack(): String {
        val ok = withContext(Dispatchers.Main) {
            if (webView.canGoBack()) {
                loading = true
                webView.goBack()
                true
            } else {
                false
            }
        }
        if (!ok) return "Cannot go back: no history."
        settle()
        return snapshot()
    }

    suspend fun snapshot(): String {
        val raw = decode(eval(SNAPSHOT_JS))
        return try {
            format(json.parseToJsonElement(raw).jsonObject)
        } catch (e: Exception) {
            "ERROR: could not read the page. " + raw.take(200)
        }
    }

    /** op: click | fill | select | enter | scroll. Returns result + a fresh snapshot. */
    suspend fun act(op: String, id: String, arg: String): String {
        val js = "(function(op,id,arg){" + ACT_JS + "})(" + q(op) + "," + q(id) + "," + q(arg) + ")"
        val raw = decode(eval(js))
        val res = try {
            json.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            null
        }
        val ok = (res?.get("ok") as? JsonPrimitive)?.booleanOrNull == true
        val msg = (res?.get("msg") as? JsonPrimitive)?.contentOrNull ?: raw
        if (!ok) return "ERROR: " + msg + "\n\n" + snapshot()
        settle()
        return "OK: " + msg + "\n\n" + snapshot()
    }

    private suspend fun settle() {
        delay(400)
        val start = System.currentTimeMillis()
        while (loading && System.currentTimeMillis() - start < 15_000) delay(150)
        delay(400)
    }

    private suspend fun eval(js: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<String> { cont ->
            webView.evaluateJavascript(js) { result ->
                if (cont.isActive) cont.resume(result ?: "null")
            }
        }
    }

    private fun decode(r: String): String = try {
        json.parseToJsonElement(r).jsonPrimitive.content
    } catch (e: Exception) {
        r
    }

    private fun q(s: String): String = JsonPrimitive(s).toString()

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
            if (e.containsKey("off")) sb.append(" (offscreen)")
            sb.append('\n')
        }
        if ((o["truncated"] as? JsonPrimitive)?.booleanOrNull == true) {
            sb.append("(more elements omitted; scroll or act to reveal them)\n")
        }
        return sb.toString().take(16000)
    }
}
