package app.browseragent.agent

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.browseragent.browser.BrowserController
import app.browseragent.data.AppPrefs
import app.browseragent.data.Chat
import app.browseragent.data.ChatEntry
import app.browseragent.data.ChatStore
import app.browseragent.data.HistoryStore
import app.browseragent.data.ModelAlias
import app.browseragent.data.Provider
import app.browseragent.data.Shortcut
import app.browseragent.data.Store
import app.browseragent.llm.LlmRouter
import app.browseragent.mcp.McpHub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.UUID
import java.util.concurrent.TimeUnit

class AgentViewModel(app: Application) : AndroidViewModel(app), AgentUi {
    val store = Store(app)
    val history = HistoryStore(app)
    val chats = ChatStore(app)

    /** Interface and privacy preferences (one shared instance for the whole app). */
    val prefs = AppPrefs(app)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
    private val router = LlmRouter(store, http)
    private val hub = McpHub(http)

    private var browser: BrowserController? = null
    private var agent: Agent? = null
    private var orchestrator: Orchestrator? = null
    private var job: Job? = null
    private var pending: CompletableDeferred<Boolean>? = null

    // The last thing the executor said (the team's checker reads it).
    private var lastAssistant = ""

    // The chat that is currently open (saved automatically).
    private var chatId = UUID.randomUUID().toString()
    private var chatCreated = System.currentTimeMillis()
    private var pendingContext: String? = null

    val entries = mutableStateListOf<LogEntry>()

    var running by mutableStateOf(false)
        private set

    /** True while the agent is paused between two steps. */
    var paused by mutableStateOf(false)
        private set

    var pendingConfirm by mutableStateOf<String?>(null)
        private set

    var aliases by mutableStateOf(store.aliases)
        private set

    var activeAlias by mutableStateOf(store.activeAlias)
        private set

    var mcpStatus by mutableStateOf("")
        private set

    // Appearance and browser settings (observable so the UI updates at once).
    // The functions that change them are named with the prefixes choose or save on purpose: a
    // function called setAccent would clash with the generated setter of the property.
    var themeMode by mutableStateOf(store.themeMode)
        private set

    var accent by mutableStateOf(store.accent)
        private set

    var searchEngine by mutableStateOf(store.searchEngine)
        private set

    var shortcuts by mutableStateOf(store.shortcuts)
        private set

    fun attach(b: BrowserController) {
        if (browser !== b) {
            browser = b
            b.searchTemplate = store.searchTemplate()
            b.onPageLoaded = { url, title -> history.record(url, title) }
            agent = Agent(router, b, hub, store, this, prefs)
            orchestrator = Orchestrator(
                router,
                b,
                store,
                this,
                { lastAssistant },
                { lastAssistant = "" },
            )
        }
    }

    override fun log(kind: Kind, text: String) {
        if (kind == Kind.ASSISTANT) lastAssistant = text
        entries.add(LogEntry(kind, text))
    }

    override suspend fun confirm(message: String): Boolean {
        val d = CompletableDeferred<Boolean>()
        pending = d
        pendingConfirm = message
        val answer = d.await()
        pendingConfirm = null
        return answer
    }

    fun answerConfirm(ok: Boolean) {
        pending?.complete(ok)
    }

    // ---------------------------------------------------------------- chats

    private fun persistChat() {
        val first = entries.firstOrNull { it.kind == Kind.USER } ?: return
        chats.upsert(
            Chat(
                id = chatId,
                title = first.text.trim().take(48),
                created = chatCreated,
                updated = System.currentTimeMillis(),
                entries = entries.takeLast(400).map { ChatEntry(it.kind.name, it.text) },
            ),
        )
    }

    /** A short transcript so a continued chat keeps some memory of what was said. */
    private fun transcriptOf(list: List<LogEntry>): String =
        list
            .filter { it.kind == Kind.USER || it.kind == Kind.ASSISTANT }
            .takeLast(12)
            .joinToString("\n") {
                (if (it.kind == Kind.USER) "User: " else "Assistant: ") + it.text.take(500)
            }

    private fun startFreshChat() {
        agent?.reset()
        entries.clear()
        chatId = UUID.randomUUID().toString()
        chatCreated = System.currentTimeMillis()
        pendingContext = null
    }

    /**
     * Sends a message to the AI. hiddenNote is added for the AI only (it is not shown in the chat).
     */
    fun send(goal: String, hiddenNote: String? = null) {
        val a = agent ?: return
        if (running) return
        if (!prefs.agentEnabled) {
            entries.add(LogEntry(Kind.INFO, "The AI assistant is turned off (Settings > AI settings > Behaviour & privacy)."))
            return
        }
        running = true
        entries.add(LogEntry(Kind.USER, goal))
        persistChat()
        val ctx = pendingContext
        pendingContext = null
        val note = if (hiddenNote == null) "" else "\n\n" + hiddenNote
        val full = if (ctx == null) {
            goal + note
        } else {
            "[Earlier in this conversation]\n" + ctx + "\n\n[New message]\n" + goal + note
        }
        val team = orchestrator
        job = viewModelScope.launch {
            try {
                if (store.teamMode == "team" && team != null) team.run(a, full) else a.run(full)
            } catch (e: CancellationException) {
                entries.add(LogEntry(Kind.INFO, "Stopped."))
                throw e
            } catch (e: Exception) {
                entries.add(LogEntry(Kind.ERROR, e.message ?: e.toString()))
            } finally {
                running = false
                paused = false
                a.pauseRun(false)
                pending?.complete(false)
                pendingConfirm = null
                store.aliasOverride = null
                persistChat()
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    fun pause() {
        paused = true
        agent?.pauseRun(true)
    }

    fun resume() {
        paused = false
        agent?.pauseRun(false)
    }

    // ---------------------------------------------------------------- retry and edit

    /**
     * Forgets everything from the message at this position on. The AI keeps a short memory of
     * what came before it.
     */
    private fun rewindTo(index: Int) {
        val before = entries.take(index)
        pendingContext = transcriptOf(before).ifBlank { null }
        agent?.reset()
        while (entries.size > index) entries.removeAt(entries.size - 1)
    }

    /** Throws away the last answer and asks the AI again with the same message. */
    fun retryLast() {
        if (running) return
        val u = entries.indexOfLast { it.kind == Kind.USER }
        if (u < 0) return
        val goal = entries[u].text
        rewindTo(u)
        send(
            goal,
            "[Retry] Your previous answer to this request was discarded. The page may already show part " +
                "of what you did: check it first, then give a better answer.",
        )
    }

    /** Replaces one of your messages and asks again; everything after it is discarded. */
    fun editAndResend(index: Int, text: String) {
        if (running || text.isBlank()) return
        if (index !in entries.indices || entries[index].kind != Kind.USER) return
        rewindTo(index)
        send(text.trim())
    }

    /** Replaces the text of a message without asking again (used for the AI's own replies). */
    fun editText(index: Int, text: String) {
        if (running || index !in entries.indices) return
        entries[index] = LogEntry(entries[index].kind, text)
        pendingContext = transcriptOf(entries.toList()).ifBlank { null }
        agent?.reset()
        persistChat()
    }

    fun newChat() {
        stop()
        persistChat()
        startFreshChat()
    }

    fun openChat(c: Chat) {
        stop()
        persistChat()
        entries.clear()
        for (e in c.entries) {
            val k = runCatching { Kind.valueOf(e.kind) }.getOrNull() ?: continue
            entries.add(LogEntry(k, e.text))
        }
        chatId = c.id
        chatCreated = c.created
        agent?.reset()
        pendingContext = transcriptOf(entries.toList()).ifBlank { null }
    }

    fun deleteChat(id: String) {
        chats.delete(id)
        if (id == chatId) {
            stop()
            startFreshChat()
        }
    }

    // ---------------------------------------------------------------- settings

    fun setActive(name: String?) {
        activeAlias = name
        store.activeAlias = name
    }

    fun saveSettings(providers: List<Provider>, newAliases: List<ModelAlias>) {
        store.providers = providers
        store.aliases = newAliases
        aliases = newAliases
        if (newAliases.none { it.name == activeAlias }) setActive(newAliases.firstOrNull()?.name)
    }

    fun chooseTheme(mode: String) {
        themeMode = mode
        store.themeMode = mode
    }

    fun chooseAccent(key: String) {
        accent = key
        store.accent = key
    }

    fun chooseSearch(key: String, customUrl: String) {
        searchEngine = key
        store.searchEngine = key
        store.customSearchUrl = customUrl
        browser?.searchTemplate = store.searchTemplate()
    }

    fun saveShortcuts(list: List<Shortcut>) {
        shortcuts = list
        store.shortcuts = list
    }

    fun testMcp() {
        viewModelScope.launch {
            mcpStatus = "Connecting..."
            val lines = ArrayList<String>()
            hub.load(store.mcpServers.filter { it.enabled }, verbose = true) { lines.add(it) }
            mcpStatus = if (lines.isEmpty()) "No enabled servers." else lines.joinToString("\n")
        }
    }
}
