package app.browseragent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.browseragent.agent.AgentViewModel
import app.browseragent.data.ACCENTS
import app.browseragent.data.SEARCH_ENGINES
import app.browseragent.data.Shortcut
import java.util.UUID

private data class SRow(val id: String, val title: String, val url: String)

@Composable
private fun EngineRow(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(name)
    }
}

/** Homepage, search engine, theme, accent colour, start-page shortcuts, data clearing. */
@Composable
fun BrowserSettingsScreen(vm: AgentViewModel) {
    val cs = MaterialTheme.colorScheme
    var homepage by remember { mutableStateOf(vm.store.homepage) }
    var custom by remember { mutableStateOf(vm.store.customSearchUrl) }
    var rows by remember {
        mutableStateOf(vm.shortcuts.map { SRow(UUID.randomUUID().toString(), it.title, it.url) })
    }
    var note by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---------------------------------------------------------------- homepage
        Text("Homepage", style = MaterialTheme.typography.titleLarge)
        Text(
            "The page that opens when you start the app. Leave it empty to show the built-in start page.",
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
                note = "Homepage saved. It applies the next time you open the app."
            },
        ) { Text("Save homepage") }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // ---------------------------------------------------------------- search engine
        Text("Search engine", style = MaterialTheme.typography.titleLarge)
        for (e in SEARCH_ENGINES) {
            EngineRow(e.name, vm.searchEngine == e.key) { vm.chooseSearch(e.key, custom) }
        }
        EngineRow("Custom", vm.searchEngine == "custom") { vm.chooseSearch("custom", custom) }
        if (vm.searchEngine == "custom") {
            OutlinedTextField(
                value = custom,
                onValueChange = {
                    custom = it
                    vm.chooseSearch("custom", it)
                },
                singleLine = true,
                label = { Text("Search URL, with %s where the search text goes") },
                placeholder = { Text("https://example.com/search?q=%s") },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // ---------------------------------------------------------------- theme + accent
        Text("Theme", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((key, label) in listOf("system" to "System", "light" to "Light", "dark" to "Dark")) {
                if (vm.themeMode == key) {
                    Button(onClick = { vm.chooseTheme(key) }) { Text(label) }
                } else {
                    OutlinedButton(onClick = { vm.chooseTheme(key) }) { Text(label) }
                }
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
            "You can also pin the open page from the \u22ee menu in the browser.",
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
