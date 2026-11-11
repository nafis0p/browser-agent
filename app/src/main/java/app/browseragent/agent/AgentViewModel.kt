package app.browseragent.agent

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.browseragent.browser.BrowserController
import app.browseragent.data.ModelAlias
import app.browseragent.data.Provider
import app.browseragent.data.Store
import app.browseragent.llm.LlmRouter
import app.browseragent.mcp.McpHub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AgentViewModel(app: Application) : AndroidViewModel(app), AgentUi {
    val store = Store(app)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
    private val router = LlmRouter(store, http)
    private val hub = McpHub(http)

    private var browser: BrowserController? = null
    private var agent: Agent? = null
    private var job: Job? = null
    private var pending: CompletableDeferred<Boolean>? = null

    val entries = mutableStateListOf<LogEntry>()

    var running by mutableStateOf(false)
        private set

    var pendingConfirm by mutableStateOf<String?>(null)
        private set

    var aliases by mutableStateOf(store.aliases)
        private set

    var activeAlias by mutableStateOf(store.activeAlias)
        private set

    var mcpStatus by mutableStateOf("")
        private set

    fun attach(b: BrowserController) {
        if (browser !== b) {
            browser = b
            agent = Agent(router, b, hub, store, this)
        }
    }

    override fun log(kind: Kind, text: String) {
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

    fun send(goal: String) {
        val a = agent ?: return
        if (running) return
        running = true
        entries.add(LogEntry(Kind.USER, goal))
        job = viewModelScope.launch {
            try {
                a.run(goal)
            } catch (e: CancellationException) {
                entries.add(LogEntry(Kind.INFO, "Stopped."))
                throw e
            } catch (e: Exception) {
                entries.add(LogEntry(Kind.ERROR, e.message ?: e.toString()))
            } finally {
                running = false
                pending?.complete(false)
                pendingConfirm = null
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    fun newChat() {
        stop()
        agent?.reset()
        entries.clear()
    }

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

    fun testMcp() {
        viewModelScope.launch {
            mcpStatus = "Connecting..."
            val lines = ArrayList<String>()
            hub.load(store.mcpServers.filter { it.enabled }, verbose = true) { lines.add(it) }
            mcpStatus = if (lines.isEmpty()) "No enabled servers." else lines.joinToString("\n")
        }
    }
}
