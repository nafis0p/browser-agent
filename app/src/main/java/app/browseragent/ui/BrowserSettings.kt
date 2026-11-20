package app.browseragent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.browseragent.agent.AgentViewModel
import app.browseragent.data.ACCENTS
import app.browseragent.data.AppPrefs
import app.browseragent.data.CustomEngine
import app.browseragent.data.SEARCH_ENGINES
import app.browseragent.data.Shortcut
import java.util.UUID

private data class SRow(val id: String, val title: String, val url: String)

/**
 * Turns the address of a search results page into a search template (with %s).
 * Example: https://example.com/search?q=test  becomes  https://example.com/search?q=%s
 * Returns null when the search word cannot be found.
 */
internal fun toSearchTemplate(raw: String): String? {
    val url = raw.trim()
    if (!url.startsWith("http")) return null
    if (url.contains("%s")) return url

    val q = url.substringAfter('?', "").substringBefore('#')
    if (q.isNotEmpty()) {
        val base = url.substringBefore('?')
        val parts = q.split('&')
        val known = listOf(
            "q", "query", "search", "s", "k", "text", "term", "keyword", "keywords",
            "wd", "p", "searchterm", "search_query", "field-keywords",
        )
        // 1) a well-known search parameter
        for ((i, p) in parts.withIndex()) {
            if (p.contains('=') && p.substringBefore('=').lowercase() in known) {
                val np = parts.toMutableList()
                np[i] = p.substringBefore('=') + "=%s"
                return base + "?" + np.joinToString("&")
            }
        }
        // 2) the parameter whose value is the word 'test'
        for ((i, p) in parts.withIndex()) {
            if (p.contains('=') && p.substringAfter('=').equals("test", ignoreCase = true)) {
                val np = parts.toMutableList()
                np[i] = p.substringBefore('=') + "=%s"
                return base + "?" + np.joinToString("&")
            }
        }
    }
    // 3) the word 'test' somewhere in the path, e.g. https://example.com/search/test
    val idx = url.lowercase().lastIndexOf("test")
    if (idx > url.indexOf("://") + 3) {
        return url.substring(0, idx) + "%s" + url.substring(idx + 4)
    }
    return null
}

/** A likely suggestions address for a few well-known sites, so the user does not have to find it. */
internal fun guessSuggestUrl(raw: String): String? {
    val host = hostOf(raw.trim()).lowercase()
    return when {
        host.endsWith("youtube.com") ->
            "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q=%s"
        host.endsWith("wikipedia.org") ->
            "https://" + host + "/w/api.php?action=opensearch&search=%s&limit=8&namespace=0&format=json"
        host.startsWith("amazon.") || host.contains(".amazon.") ->
            "https://completion.amazon.com/search/complete?q=%s&search-alias=aps&mkt=1"
        else -> null
    }
}

@Composable
private fun Choice(selected: Boolean, label: String, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

@Composable
private fun EngineRow(name: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(name)
    }
}

/** Homepage, search engines and suggestions, theme, accent colour, shortcuts, data clearing. */
@Composable
fun BrowserSettingsScreen(vm: AgentViewModel, prefs: AppPrefs) {
    val cs = MaterialTheme.colorScheme
    var homepage by remember { mutableStateOf(vm.store.homepage) }
    var newName by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("") }
    var newSuggest by remember { mutableStateOf("") }
    var rows by remember {
        mutableStateOf(vm.shortcuts.map { SRow(UUID.randomUUID().toString(), it.title, it.url) })
    }
    var note by remember { mutableStateOf("") }

    // Fill in a likely suggestions address for well-known sites.
    LaunchedEffect(newUrl) {
        if (newSuggest.isBlank()) {
            guessSuggestUrl(newUrl)?.let { newSuggest = it }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---------------------------------------------------------------- homepage
        Text("Homepage", style = MaterialTheme.typography.titleLarge)
        Text(
            "The page that opens when you start the app and have no open tabs. Leave it empty to show the built-in start page.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = homepage,
            onValueChange = { homepage = it },
            singleLine = true,
            placeholder = { Text("https://example.com") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                vm.store.homepage = homepage.trim()
                note = "Homepage saved."
            },
        ) { Text("Save homepage") }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // ---------------------------------------------------------------- search engines
        Text("Search engine", style = MaterialTheme.typography.titleLarge)
        for (e in SEARCH_ENGINES) {
            EngineRow(e.name, vm.searchEngine == e.key, Modifier.fillMaxWidth()) {
                vm.chooseSearch(e.key, "")
            }
        }
        for (c in prefs.customEngines) {
            val selected = vm.searchEngine == "custom" && vm.store.customSearchUrl == c.template
            Row(verticalAlignment = Alignment.CenterVertically) {
                EngineRow(
                    if (c.suggest.isBlank()) c.name + "  (no suggestions)" else c.name,
                    selected,
                    Modifier.weight(1f),
                ) { vm.chooseSearch("custom", c.template) }
                TextButton(
                    onClick = {
                        prefs.saveEngines(prefs.customEngines.filter { it != c })
                        if (selected) vm.chooseSearch("duckduckgo", "")
                    },
                ) { Text("Delete") }
            }
        }

        Text("Search suggestions", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "While you type in the address bar, the search engine is asked for ideas. Off: nothing you type " +
                    "is sent before you press Go.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = prefs.suggestionsOn, onCheckedChange = { prefs.chooseSuggestions(it) })
        }

        Text("Add a search engine", style = MaterialTheme.typography.titleMedium)
        Text(
            "Search for the word test on that site, copy the address of the results page and paste it " +
                "below. The app finds the search word and replaces it with %s for you. You can also paste " +
                "an address that already contains %s.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = newName,
            onValueChange = { newName = it },
            singleLine = true,
            label = { Text("Name (optional)") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = newUrl,
            onValueChange = { newUrl = it },
            singleLine = true,
            label = { Text("Address of a results page") },
            placeholder = { Text("https://example.com/search?q=test") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (newUrl.isNotBlank()) {
            val preview = toSearchTemplate(newUrl)
            Text(
                if (preview != null) {
                    "Will search: " + preview
                } else {
                    "Could not find the search word. Add %s where the search text goes."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (preview != null) cs.primary else cs.error,
            )
        }
        OutlinedTextField(
            value = newSuggest,
            onValueChange = { newSuggest = it },
            singleLine = true,
            label = { Text("Suggestions address (optional)") },
            placeholder = { Text("https://example.com/suggest?q=%s") },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "The address that returns ideas while you type, with %s for the typed text. It must answer in " +
                "the common OpenSearch format. Leave it empty for no suggestions.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick = {
                val t = toSearchTemplate(newUrl)
                if (t != null) {
                    val name = newName.trim().ifBlank { hostOf(t) }
                    prefs.saveEngines(prefs.customEngines + CustomEngine(name, t, newSuggest.trim()))
                    vm.chooseSearch("custom", t)
                    newName = ""
                    newUrl = ""
                    newSuggest = ""
                    note = "Added '" + name + "' and selected it."
                }
            },
            enabled = toSearchTemplate(newUrl) != null,
        ) { Text("Add search engine") }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // ---------------------------------------------------------------- theme + accent
        Text("Theme", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((key, label) in listOf("system" to "System", "light" to "Light", "dark" to "Dark")) {
                Choice(vm.themeMode == key, label) { vm.chooseTheme(key) }
            }
        }
        Text("Accent colour", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (a in ACCENTS) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(a.argb))
                        .then(
                            if (vm.accent == a.key) {
                                Modifier.border(3.dp, cs.onBackground, CircleShape)
                            } else {
                                Modifier
                            },
                        )
                        .clickable { vm.chooseAccent(a.key) },
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // ---------------------------------------------------------------- shortcuts
        Text("Start-page shortcuts", style = MaterialTheme.typography.titleLarge)
        Text(
            "You can also pin the open page from the menu in the browser.",
            style = MaterialTheme.typography.bodySmall,
        )
        for (r in rows) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = r.title,
                        onValueChange = { v ->
                            rows = rows.map { if (it.id == r.id) it.copy(title = v) else it }
                        },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = r.url,
                        onValueChange = { v ->
                            rows = rows.map { if (it.id == r.id) it.copy(url = v) else it }
                        },
                        label = { Text("Address") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { rows = rows.filter { it.id != r.id } }) { Text("Remove") }
                }
            }
        }
        OutlinedButton(
            onClick = { rows = rows + SRow(UUID.randomUUID().toString(), "", "https://") },
        ) { Text("Add shortcut") }
        Button(
            onClick = {
                vm.saveShortcuts(
                    rows.filter { it.title.isNotBlank() && it.url.isNotBlank() }
                        .map { Shortcut(it.title.trim(), it.url.trim()) },
                )
                note = "Shortcuts saved."
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save shortcuts") }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // ---------------------------------------------------------------- data
        Text("Your data", style = MaterialTheme.typography.titleLarge)
        TextButton(
            onClick = {
                vm.history.clear()
                note = "Browsing history cleared."
            },
        ) { Text("Clear browsing history") }
        TextButton(
            onClick = {
                vm.chats.clear()
                note = "All saved AI chats deleted."
            },
        ) { Text("Delete all saved AI chats") }

        if (note.isNotEmpty()) Text(note, color = cs.primary)
        Spacer(Modifier.height(24.dp))
    }
}
