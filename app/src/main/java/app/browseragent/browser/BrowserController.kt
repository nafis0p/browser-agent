package app.browseragent.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import app.browseragent.data.SavedTab
import app.browseragent.data.SavedTabs
import app.browseragent.data.TabStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import java.io.InputStream
import java.net.URLEncoder

private const val TAG = "BrowserController"
private const val EXT_LOCATION = "resource://android/assets/agent/"
private const val EXT_ID = "agent@browseragent.app"
private const val MAX_TABS = 12

// At most this many tabs keep a running Firefox session; the longest unused ones go to sleep.
private const val MAX_AWAKE_TABS = 5

// Pull to refresh needs a long pull, so a quick scroll can never reload the page by accident.
private const val REFRESH_PULL_DP = 240

/** What the user long-pressed on a page: a link, an image, or both (an image inside a link). */
class ContextInfo(val linkUri: String?, val imageUri: String?, val label: String)

/**
 * Manages the browser tabs. One GeckoView shows the current tab; every tab has its own session.
 * The properties and functions below act on the current tab, so the rest of the app can treat
 * the controller like a single browser. The open tabs are remembered between app starts.
 *
 * Tabs that are not shown go to sleep after a while (their Firefox session is closed to free
 * memory) and wake up when they are shown.
 *
 * While the AI works it is pinned to one tab (the one that was shown when it started, or the
 * one it opened or switched to itself). If the user leaves that tab, the AI waits for the user.
 */
class BrowserController(context: Context) {
    private val appContext: Context = context.applicationContext

    /** The Firefox surface. It sits inside the container below. */
    val webView: GeckoView = GeckoView(context)

    /** What the UI hosts: the Firefox view wrapped in a pull-to-refresh layout. */
    val container: SwipeRefreshLayout = SwipeRefreshLayout(context).apply {
        addView(
            webView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    /** Files the websites send as downloads. */
    val downloads = Downloads(context)

    private val runtime: GeckoRuntime = sharedRuntime(context)
    private val tabStore = TabStore(context)

    /** Add-ons (extensions) such as an ad blocker. */
    val extensions = Extensions(runtime)

    val tabs = mutableStateListOf<BrowserTab>()

    var currentIndex by mutableStateOf(0)
        private set

    /** True when tabs from the last run were brought back (then the homepage is not opened). */
    var restoredTabs: Boolean = false
        private set

    /** Set while the user long-presses a link or an image (the screen shows a menu for it). */
    var contextMenu by mutableStateOf<ContextInfo?>(null)
        private set

    /** The id of the tab the AI works in right now (null when the AI is not running). */
    var agentTabId by mutableStateOf<Int?>(null)
        private set

    // Find in page: the bar is open, which match is selected, how many there are.
    var findOpen by mutableStateOf(false)
        private set
    var findCurrent by mutableStateOf(0)
        private set
    var findTotal by mutableStateOf(0)
        private set
    var findMissing by mutableStateOf(false)
        private set

    internal var extensionReady = false
    private var extension: WebExtension? = null
    private var attached = false
    private var shown: BrowserTab? = null
    private var nextTabId = 1
    private var lastRevive = 0L

    /** The tab of the last snapshot the AI took. Ids from a snapshot only work in that tab. */
    private var lastSnapTab = -1

    /** Search URL template; the placeholder is replaced by the search text. */
    var searchTemplate: String = "https://duckduckgo.com/?q=%s"

    /** Called with (url, title) when a page has loaded (for the browsing history). */
    var onPageLoaded: ((String, String) -> Unit)? = null

    /** Minutes without use after which a background tab goes to sleep (0: never). */
    var sleepAfterMinutes: () -> Int = { 10 }

    // What the user did by hand (not the agent), so the agent is never working blind.
    private val userEvents = ArrayList<String>()
    private var agentActive = false
    private var quietUntil = 0L

    private val sleepHandler = Handler(Looper.getMainLooper())
    private val sleepCheck = object : Runnable {
        override fun run() {
            sweepSleepyTabs()
            sleepHandler.postDelayed(this, 60_000)
        }
    }

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

    /** True while the AI works in a tab that the user is not looking at. */
    val agentAway: Boolean
        get() {
            val id = agentTabId ?: return false
            val i = tabs.indexOfFirst { it.id == id }
            return i >= 0 && i != currentIndex
        }

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

        // Pull to refresh: a long pull, only at the very top of a page that is not loading and
        // did not just finish loading. Everything else is a normal scroll. A load that hangs for
        // a long time does not block it.
        val pull = (REFRESH_PULL_DP * context.resources.displayMetrics.density).toInt()
        container.setDistanceToTriggerSync(pull)
        container.setOnChildScrollUpCallback { _, _ ->
            val t = current
            val since = System.currentTimeMillis() - t.lastLoadAt
            t.scrollOffset > 0 ||
                !t.domAtTop ||
                (t.isLoading && since < 10_000) ||
                since < 1500
        }
        container.setOnRefreshListener {
            current.reload()
            container.postDelayed({ container.isRefreshing = false }, 700)
        }

        sleepHandler.postDelayed(sleepCheck, 60_000)
        extensions.refresh()

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

    /** Tells the add-ons which tab is the active one (they use it for their per-page features). */
    private fun notifyTabActive(tab: BrowserTab, active: Boolean) {
        if (!tab.isOpened()) return
        runCatching { runtime.webExtensionController.setTabActive(tab.session, active) }
    }

    /**
     * Shows the tab in the Firefox view (and wakes it up if it was asleep). The tab that was shown
     * before becomes inactive and the new one active, otherwise Firefox may keep painting the
     * old tab.
     */
    private fun attach(tab: BrowserTab) {
        val now = System.currentTimeMillis()
        shown?.let { notifyTabActive(it, false) }
        shown?.lastUsed = now
        shown?.setActive(false)
        tab.lastUsed = now
        tab.ensureOpen(extension)
        if (attached) webView.releaseSession()
        webView.setSession(tab.session)
        tab.setActive(true)
        notifyTabActive(tab, true)
        shown = tab
        attached = true
    }

    /**
     * A page stopped responding, or its process crashed or was ended by Android to free memory.
     * The tab gets a fresh Firefox session and loads its page again. Returns false when this
     * already happened a moment ago (so a page that keeps failing cannot cause a loop).
     */
    internal fun reviveTab(tab: BrowserTab): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastRevive < 15_000) return false
        lastRevive = now
        val visible = tab === shown
        if (visible) {
            webView.releaseSession()
            attached = false
        }
        tab.resetSession()
        if (visible) {
            attach(tab)
            Toast.makeText(appContext, "The page stopped responding and was reloaded", Toast.LENGTH_SHORT).show()
        } else if (tab.id == agentTabId) {
            tab.ensureOpen(extension)
        }
        return true
    }

    internal fun recordPage(url: String, title: String) {
        onPageLoaded?.invoke(url, title)
    }

    /** A tab received a file that Firefox cannot show: save it as a download. */
    internal fun startDownload(uri: String, headers: Map<String, String>, body: InputStream?) {
        val name = downloads.start(uri, headers, body)
        Toast.makeText(appContext, "Downloading " + name, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- tab sleep

    /**
     * Puts tabs to sleep: first those unused for longer than the set time, then the longest unused
     * ones when more than a few tabs are awake. The shown tab and the tab of the AI never sleep.
     */
    private fun sweepSleepyTabs() {
        val minutes = sleepAfterMinutes()
        val now = System.currentTimeMillis()
        if (minutes > 0) {
            for (t in tabs) {
                if (t === shown || t.id == agentTabId || !t.isOpened()) continue
                if (now - t.lastUsed > minutes * 60_000L) t.sleep()
            }
        }
        var extra = tabs.count { it.isOpened() } - MAX_AWAKE_TABS
        if (extra > 0) {
            val candidates = tabs
                .filter { it !== shown && it.id != agentTabId && it.isOpened() }
                .sortedBy { it.lastUsed }
            for (t in candidates) {
                if (extra <= 0) break
                t.sleep()
                extra--
            }
        }
    }

    /** Puts every tab that is not in use to sleep (used when the phone is low on memory). */
    fun sleepBackgroundTabs() {
        for (t in tabs) {
            if (t !== shown && t.id != agentTabId) t.sleep()
        }
    }

    // ---------------------------------------------------------------- long press menu

    internal fun showContextMenu(info: ContextInfo) {
        contextMenu = info
    }

    fun dismissContextMenu() {
        contextMenu = null
    }

    // ---------------------------------------------------------------- find in page

    fun openFind() {
        if (current.isHome) return
        findOpen = true
        findCurrent = 0
        findTotal = 0
        findMissing = false
    }

    fun closeFind() {
        if (findOpen) current.clearFind()
        findOpen = false
        findCurrent = 0
        findTotal = 0
        findMissing = false
    }

    /** Starts a new search for the text (an empty text clears the matches). */
    fun find(query: String) {
        if (query.isBlank()) {
            current.clearFind()
            findCurrent = 0
            findTotal = 0
            findMissing = false
            return
        }
        current.findInPage(query, false) { found, cur, total ->
            findMissing = !found
            findCurrent = cur
            findTotal = total
        }
    }

    /** Goes to the next or the previous match of the last search. */
    fun findStep(backwards: Boolean) {
        current.findInPage(null, backwards) { found, cur, total ->
            findMissing = !found
            findCurrent = cur
            findTotal = total
        }
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

    private fun tabLabel(t: BrowserTab): String =
        t.title.ifBlank { if (t.isHome) "Start page" else t.url }.take(40)

    /** Shows a tab without telling the AI about it. */
    private fun showTab(index: Int) {
        closeFind()
        currentIndex = index
        attach(tabs[index])
        tabsChanged()
    }

    /** Opens a new tab (the start page, or the given address). False when too many tabs are open. */
    fun newTab(raw: String? = null): Boolean {
        if (tabs.size >= MAX_TABS) return false
        val tab = BrowserTab(nextTabId++, this, runtime)
        tabs.add(tab)
        showTab(tabs.size - 1)
        if (raw != null) tab.load(normalize(raw))
        noteUser("opened a new tab")
        return true
    }

    /** Opens an address in a new tab behind the current one (like a long press on a link). */
    fun openInBackground(raw: String): Boolean {
        if (tabs.size >= MAX_TABS) return false
        val tab = BrowserTab(nextTabId++, this, runtime)
        tabs.add(tab)
        tab.ensureOpen(extension)
        tab.load(normalize(raw))
        tabsChanged()
        return true
    }

    fun selectTab(index: Int) {
        if (index !in tabs.indices) return
        val changed = index != currentIndex
        showTab(index)
        if (changed) noteUser("switched to tab " + (index + 1) + " of " + tabs.size + " ('" + tabLabel(tabs[index]) + "')")
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
            showTab(minOf(index, tabs.size - 1))
        } else {
            currentIndex = tabs.indexOf(cur)
            tabsChanged()
        }
        victim.destroy()
        noteUser("closed a tab")
    }

    /** Closes every tab and leaves one fresh tab with the start page. */
    fun closeAllTabs() {
        findOpen = false
        val old = tabs.toList()
        val fresh = BrowserTab(nextTabId++, this, runtime)
        tabs.clear()
        tabs.add(fresh)
        currentIndex = 0
        attach(fresh)
        for (t in old) t.destroy()
        tabsChanged()
        noteUser("closed all tabs")
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
        closeFind()
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
        sleepHandler.removeCallbacks(sleepCheck)
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

    // ---------------------------------------------------------------- the AI's tab

    /** The AI starts working: it is pinned to the tab that is shown now. */
    fun beginAgent() {
        agentTabId = current.id
        lastSnapTab = -1
    }

    /** The AI is done. */
    fun endAgent() {
        agentTabId = null
    }

    /** The tab the AI works in. When that tab was closed, the AI continues in the shown tab. */
    private fun agentTab(): BrowserTab {
        val id = agentTabId
        if (id != null) {
            val t = tabs.firstOrNull { it.id == id }
            if (t != null) return t
            agentTabId = current.id
        }
        return current
    }

    fun agentTabVisible(): Boolean = !agentAway

    /** Brings the tab of the AI to the front. */
    fun showAgentTab() {
        val id = agentTabId ?: return
        val i = tabs.indexOfFirst { it.id == id }
        if (i >= 0) showTab(i)
    }

    // ---------------------------------------------------------------- agent API

    private fun footer(t: BrowserTab): String =
        "Tab: t" + t.id + " (" + (tabs.indexOf(t) + 1) + " of " + tabs.size + ")\n"

    private suspend fun snapshotOf(t: BrowserTab): String {
        lastSnapTab = t.id
        return t.snapshot() + footer(t)
    }

    suspend fun navigate(raw: String): String = asAgent {
        val t = agentTab()
        withContext(Dispatchers.Main) { t.load(normalize(raw)) }
        t.settle()
        snapshotOf(t)
    }

    suspend fun goBack(): String = asAgent {
        val t = agentTab()
        val ok = withContext(Dispatchers.Main) {
            if (t.canBack) {
                t.goBackNow()
                true
            } else {
                false
            }
        }
        if (!ok) return@asAgent "Cannot go back: no history in this tab.\n" + footer(t)
        t.settle()
        snapshotOf(t)
    }

    suspend fun snapshot(): String = snapshotOf(agentTab())

    suspend fun act(op: String, id: String, arg: String): String = asAgent {
        val t = agentTab()
        if (lastSnapTab != t.id) {
            return@asAgent "ERROR: the ids you have belong to another tab. Call snapshot first.\n" + footer(t)
        }
        val out = t.act(op, id, arg)
        lastSnapTab = t.id
        out + footer(t)
    }

    // ---- tab tools for the AI

    fun listTabs(): String {
        val sb = StringBuilder("Open tabs (" + tabs.size + "):\n")
        for ((i, t) in tabs.withIndex()) {
            val name = t.title.ifBlank { if (t.isHome) "Start page" else t.url }.take(60)
            val where = if (t.isHome) "start page" else t.url.take(80)
            sb.append("[t").append(t.id).append("] ").append(name).append(" - ").append(where)
            if (i == currentIndex) sb.append("  (shown)")
            if (t.id == agentTabId) sb.append("  (your tab)")
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun parseTabId(text: String): Int? =
        text.trim().removePrefix("t").removePrefix("T").toIntOrNull()

    suspend fun openTabForAgent(raw: String?): String = asAgent {
        val address = raw?.takeIf { it.isNotBlank() }
        val opened = withContext(Dispatchers.Main) { newTab(address) }
        if (!opened) {
            return@asAgent "ERROR: too many tabs are open (the limit is 12). Close one with close_tab first."
        }
        val t = current
        agentTabId = t.id
        if (address != null) t.settle()
        snapshotOf(t)
    }

    suspend fun switchTabForAgent(idText: String): String = asAgent {
        val id = parseTabId(idText)
            ?: return@asAgent "ERROR: use a tab id like t3.\n" + listTabs()
        val i = tabs.indexOfFirst { it.id == id }
        if (i < 0) return@asAgent "ERROR: there is no tab t" + id + ".\n" + listTabs()
        withContext(Dispatchers.Main) { showTab(i) }
        agentTabId = id
        val t = tabs[i]
        t.settle()
        snapshotOf(t)
    }

    suspend fun closeTabForAgent(idText: String): String = asAgent {
        val id = parseTabId(idText)
            ?: return@asAgent "ERROR: use a tab id like t3.\n" + listTabs()
        val i = tabs.indexOfFirst { it.id == id }
        if (i < 0) return@asAgent "ERROR: there is no tab t" + id + ".\n" + listTabs()
        withContext(Dispatchers.Main) { closeTab(i) }
        if (agentTabId == id || tabs.none { it.id == agentTabId }) agentTabId = current.id
        lastSnapTab = -1
        "Closed tab t" + id + ".\n" + listTabs() +
            "You are now in tab t" + current.id + ". Call snapshot to see it.\n"
    }

    companion object {
        private var sharedRuntimeInstance: GeckoRuntime? = null

        /** GeckoRuntime must be created once per process. */
        private fun sharedRuntime(context: Context): GeckoRuntime {
            val existing = sharedRuntimeInstance
            if (existing != null) return existing
            // The web API lets the add-on site (addons.mozilla.org) install add-ons directly.
            val settings = GeckoRuntimeSettings.Builder()
                .extensionsWebAPIEnabled(true)
                .build()
            val created = GeckoRuntime.create(context.applicationContext, settings)
            sharedRuntimeInstance = created
            return created
        }
    }
}
