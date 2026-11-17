package app.browseragent.browser

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.browseragent.data.SavedTab
import app.browseragent.data.SavedTabs
import app.browseragent.data.TabStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import java.net.URLEncoder

private const val TAG = "BrowserController"
private const val EXT_LOCATION = "resource://android/assets/agent/"
private const val EXT_ID = "agent@browseragent.app"
private const val MAX_TABS = 12

/**
 * Manages the browser tabs. One GeckoView shows the current tab; every tab has its own session.
 * The properties and functions below act on the current tab, so the rest of the app can treat
 * the controller like a single browser. The open tabs are remembered between app starts.
 */
class BrowserController(context: Context) {
    /** The Firefox surface hosted by the UI. */
    val webView: GeckoView = GeckoView(context)

    private val runtime: GeckoRuntime = sharedRuntime(context)
    private val tabStore = TabStore(context)

    val tabs = mutableStateListOf<BrowserTab>()

    var currentIndex by mutableStateOf(0)
        private set

    /** True when tabs from the last run were brought back (then the homepage is not opened). */
    var restoredTabs: Boolean = false
        private set

    internal var extensionReady = false
    private var extension: WebExtension? = null
    private var attached = false
    private var nextTabId = 1

    /** Search URL template; the placeholder is replaced by the search text. */
    var searchTemplate: String = "https://duckduckgo.com/?q=%s"

    /** Called with (url, title) when a page has loaded (for the browsing history). */
    var onPageLoaded: ((String, String) -> Unit)? = null

    // What the user did by hand (not the agent), so the agent is never working blind.
    private val userEvents = ArrayList<String>()
    private var agentActive = false
    private var quietUntil = 0L

    val current: BrowserTab
        get() = tabs[currentIndex.coerceIn(0, tabs.lastIndex)]

    // State of the current tab, for the UI.
    val url: String get() = current.url
    val progress: Int get() = current.progress
    val isLoading: Boolean get() = current.isLoading
    val canBack: Boolean get() = current.canBack
    val canForward: Boolean get() = current.canForward
    val desktopMode: Boolean get() = current.desktopMode
    val isHome: Boolean get() = current.isHome
    val pageError: PageError? get() = current.pageError

    init {
        // Bring back the tabs of the last run. They stay asleep until they are shown.
        val saved = tabStore.load()
        val list = saved.tabs.take(MAX_TABS)
        if (list.isEmpty()) {
            tabs.add(BrowserTab(nextTabId++, this, runtime))
            currentIndex = 0
        } else {
            for (s in list) {
                tabs.add(BrowserTab(nextTabId++, this, runtime, s.url.ifBlank { null }, s.title))
            }
            currentIndex = saved.current.coerceIn(0, tabs.lastIndex)
            restoredTabs = true
        }
        attach(tabs[currentIndex])

        // Pages are loaded only after the extension is installed, so its content script
        // is present from the first page on.
        runtime.webExtensionController
            .ensureBuiltIn(EXT_LOCATION, EXT_ID)
            .accept(
                { ext ->
                    if (ext != null) {
                        extension = ext
                        for (t in tabs) {
                            if (t.isOpened()) t.installBridge(ext)
                        }
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
        for (t in tabs) {
            if (t.isOpened()) t.onExtensionReady()
        }
    }

    /** Shows the tab in the Firefox view (and wakes it up if it was asleep). */
    private fun attach(tab: BrowserTab) {
        tab.ensureOpen(extension)
        if (attached) webView.releaseSession()
        webView.setSession(tab.session)
        attached = true
    }

    internal fun recordPage(url: String, title: String) {
        onPageLoaded?.invoke(url, title)
    }

    // ---------------------------------------------------------------- remembering tabs

    private fun tabsState(): SavedTabs = SavedTabs(
        tabs.map { SavedTab(if (it.isHome) "" else it.url, it.title) },
        currentIndex,
    )

    /** Called by tabs when something worth remembering changed. */
    internal fun tabsChanged() {
        if (tabs.isNotEmpty()) tabStore.save(tabsState())
    }

    /** Saves the tabs immediately (the app is going to the background). */
    fun persistTabs() {
        if (tabs.isNotEmpty()) tabStore.saveNow(tabsState())
    }

    // ---------------------------------------------------------------- tabs

    /** Opens a new tab (the start page, or the given address). False when too many tabs are open. */
    fun newTab(raw: String? = null): Boolean {
        if (tabs.size >= MAX_TABS) return false
        val tab = BrowserTab(nextTabId++, this, runtime)
        tabs.add(tab)
        selectTab(tabs.size - 1)
        if (raw != null) tab.load(normalize(raw))
        return true
    }

    fun selectTab(index: Int) {
        if (index !in tabs.indices) return
        currentIndex = index
        attach(tabs[index])
        tabsChanged()
    }

    fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        if (tabs.size == 1) {
            closeAllTabs()
            return
        }
        val victim = tabs[index]
        val cur = tabs[currentIndex.coerceIn(0, tabs.lastIndex)]
        tabs.removeAt(index)
        if (victim === cur) {
            selectTab(minOf(index, tabs.size - 1))
        } else {
            currentIndex = tabs.indexOf(cur)
            tabsChanged()
        }
        victim.destroy()
    }

    /** Closes every tab and leaves one fresh tab with the start page. */
    fun closeAllTabs() {
        val old = tabs.toList()
        val fresh = BrowserTab(nextTabId++, this, runtime)
        tabs.clear()
        tabs.add(fresh)
        currentIndex = 0
        attach(fresh)
        for (t in old) t.destroy()
        tabsChanged()
    }

    // ---------------------------------------------------------------- navigation (current tab)

    fun normalize(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return "about:blank"
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*").matches(t)) return t
        if (t.startsWith("about:")) return t
        if (!t.contains(' ') && t.contains('.')) return "https://" + t
        return searchTemplate.replace("%s", URLEncoder.encode(t, "UTF-8"))
    }

    /** Main thread only. Opens the address, or searches for the text. */
    fun load(raw: String) {
        current.load(normalize(raw))
    }

    /** Shows the start page (the open page stays loaded underneath). */
    fun showHome() {
        current.showHome()
    }

    fun canGoBack(): Boolean = current.canBack || (current.isHome && current.url != "about:blank")

    fun back() {
        current.back()
    }

    fun forward() {
        current.forward()
    }

    fun reload() {
        current.reload()
    }

    fun stopLoading() {
        current.stop()
    }

    fun setDesktop(enabled: Boolean) {
        current.setDesktop(enabled)
    }

    /** Hides the error page and shows the page the site sent anyway. */
    fun dismissError() {
        current.dismissError()
    }

    fun destroy() {
        persistTabs()
        for (t in tabs) t.destroy()
    }

    // ---------------------------------------------------------------- user activity

    internal fun noteUser(text: String) {
        if (agentActive || System.currentTimeMillis() < quietUntil) return
        if (userEvents.lastOrNull() == text) return
        if (userEvents.size >= 40) userEvents.removeAt(0)
        userEvents.add(text)
    }

    /** Things the user did by hand since the last call (cleared when read). */
    fun drainUserEvents(): List<String> {
        val out = ArrayList(userEvents)
        userEvents.clear()
        return out
    }

    /** Runs an agent action; browser events caused by it are not reported as user activity. */
    private suspend fun <T> asAgent(block: suspend () -> T): T {
        agentActive = true
        try {
            return block()
        } finally {
            agentActive = false
            quietUntil = System.currentTimeMillis() + 1500
        }
    }

    // ---------------------------------------------------------------- agent API (current tab)

    suspend fun navigate(raw: String): String = asAgent {
        val t = current
        withContext(Dispatchers.Main) { t.load(normalize(raw)) }
        t.settle()
        t.snapshot()
    }

    suspend fun goBack(): String = asAgent {
        val t = current
        val ok = withContext(Dispatchers.Main) {
            if (t.canBack) {
                t.goBackNow()
                true
            } else {
                false
            }
        }
        if (!ok) return@asAgent "Cannot go back: no history."
        t.settle()
        t.snapshot()
    }

    suspend fun snapshot(): String = current.snapshot()

    suspend fun act(op: String, id: String, arg: String): String = asAgent {
        current.act(op, id, arg)
    }

    companion object {
        private var sharedRuntimeInstance: GeckoRuntime? = null

        /** GeckoRuntime must be created once per process. */
        private fun sharedRuntime(context: Context): GeckoRuntime {
            val existing = sharedRuntimeInstance
            if (existing != null) return existing
            val created = GeckoRuntime.create(context.applicationContext)
            sharedRuntimeInstance = created
            return created
        }
    }
}
