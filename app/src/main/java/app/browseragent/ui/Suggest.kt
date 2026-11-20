package app.browseragent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.browseragent.agent.AgentViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Search suggestions while typing. Every built-in engine has an address that returns ideas for
 * the typed text; a custom engine can have its own. The text is sent to that engine only when
 * suggestions are switched on in the settings.
 */
internal val BUILTIN_SUGGEST: Map<String, String> = mapOf(
    "duckduckgo" to "https://duckduckgo.com/ac/?q=%s&type=list",
    "google" to "https://suggestqueries.google.com/complete/search?client=firefox&q=%s",
    "brave" to "https://search.brave.com/api/suggest?q=%s",
    "bing" to "https://api.bing.com/osjson.aspx?query=%s",
    "startpage" to "https://www.startpage.com/osuggestions?q=%s",
    "ecosia" to "https://ac.ecosia.org/autocomplete?q=%s&type=list",
)

/** One row in the suggestion list. The label is shown, the value is what is opened or searched. */
internal class Suggestion(val label: String, val value: String, val glyph: String)

private val client: OkHttpClient = OkHttpClient.Builder()
    .callTimeout(4, TimeUnit.SECONDS)
    .build()

/** The suggestions address of the search engine that is selected now (empty: none). */
internal fun suggestUrlFor(vm: AgentViewModel): String {
    val key = vm.searchEngine
    if (key == "custom") {
        val template = vm.store.customSearchUrl
        return vm.prefs.customEngines.firstOrNull { it.template == template }?.suggest.orEmpty()
    }
    return BUILTIN_SUGGEST[key].orEmpty()
}

/** Most search engines answer in the OpenSearch format: [query, [suggestion, ...], ...]. */
private fun parseSuggestions(body: String): List<String> {
    if (body.isBlank()) return emptyList()
    val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonArray ?: return emptyList()
    val second = root.getOrNull(1)
    if (second is JsonArray) {
        return second.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { it.isNotBlank() }.take(8)
    }
    // DuckDuckGo without the list option answers [{"phrase": "..."}, ...].
    return root.mapNotNull { ((it as? JsonObject)?.get("phrase") as? JsonPrimitive)?.contentOrNull }.take(8)
}

internal suspend fun fetchSuggestions(template: String, query: String): List<String> =
    withContext(Dispatchers.IO) {
        if (template.isBlank() || !template.contains("%s") || query.isBlank()) {
            return@withContext emptyList()
        }
        try {
            val url = template.replace("%s", URLEncoder.encode(query, "UTF-8"))
            val request = Request.Builder().url(url).header("Accept", "application/json").build()
            val body = client.newCall(request).execute().use {
                if (it.isSuccessful) it.body?.string().orEmpty() else ""
            }
            parseSuggestions(body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

/** Asks the search engine, a moment after the typing stopped. */
@Composable
internal fun rememberSuggestions(vm: AgentViewModel, query: String, active: Boolean): List<String> {
    var result by remember { mutableStateOf<List<String>>(emptyList()) }
    val template = suggestUrlFor(vm)
    val on = vm.prefs.suggestionsOn
    LaunchedEffect(query, active, template, on) {
        if (!active || !on || query.trim().length < 2) {
            result = emptyList()
            return@LaunchedEffect
        }
        delay(200)
        result = fetchSuggestions(template, query.trim())
    }
    return result
}

/** Pages from the history that match the text, followed by the search engine's suggestions. */
@Composable
internal fun suggestionList(vm: AgentViewModel, query: String, active: Boolean): List<Suggestion> {
    val remote = rememberSuggestions(vm, query, active)
    val q = query.trim()
    if (!active || q.length < 2) return emptyList()
    val fromHistory = vm.history.entries
        .filter { it.title.contains(q, ignoreCase = true) || it.url.contains(q, ignoreCase = true) }
        .distinctBy { it.url }
        .take(3)
        .map { Suggestion(it.title.ifBlank { it.url }, it.url, "\u21ba") }
    val fromEngine = remote
        .filter { !it.equals(q, ignoreCase = true) }
        .take(6)
        .map { Suggestion(it, it, "\ud83d\udd0d") }
    return fromHistory + fromEngine
}

@Composable
internal fun SuggestionRows(items: List<Suggestion>, onPick: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        for (s in items) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(s.value) }
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(s.glyph, Modifier.width(30.dp))
                Text(s.label, maxLines = 1, overflow = TextOverflow.Ellipsis, color = cs.onSurface)
            }
        }
    }
}
