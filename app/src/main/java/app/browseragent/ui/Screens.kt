package app.browseragent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.browseragent.agent.AgentViewModel
import app.browseragent.data.McpServer
import app.browseragent.data.ModelAlias
import app.browseragent.data.Provider
import app.browseragent.data.Route
import java.util.UUID

@Composable
private fun Field(
    label: String,
    value: String,
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
    )
}

private data class AliasEdit(val id: String, val name: String, val routes: String)

@Composable
internal fun ProvidersScreen(vm: AgentViewModel) {
    var providers by remember { mutableStateOf(vm.store.providers) }
    var aliasEdits by remember {
        mutableStateOf(
            vm.store.aliases.map { a ->
                AliasEdit(
                    UUID.randomUUID().toString(),
                    a.name,
                    a.routes.joinToString("\n") { r -> r.provider + ": " + r.model },
                )
            },
        )
    }
    var saved by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Providers", style = MaterialTheme.typography.titleLarge)
        Text(
            "Any OpenAI-compatible API. Keys are stored encrypted on this device. " +
                "Use models that support tool calling.",
            style = MaterialTheme.typography.bodySmall,
        )
        for (p in providers) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Field("Name", p.name) { v ->
                        providers = providers.map { if (it.id == p.id) it.copy(name = v) else it }
                        saved = false
                    }
                    Field("Base URL (e.g. https://api.groq.com/openai/v1)", p.baseUrl) { v ->
                        providers = providers.map { if (it.id == p.id) it.copy(baseUrl = v) else it }
                        saved = false
                    }
                    Field("API key", p.apiKey, secret = true) { v ->
                        providers = providers.map { if (it.id == p.id) it.copy(apiKey = v) else it }
                        saved = false
                    }
                    TextButton(
                        onClick = {
                            providers = providers.filter { it.id != p.id }
                            saved = false
                        },
                    ) { Text("Delete provider") }
                }
            }
        }
        OutlinedButton(
            onClick = {
                providers = providers + Provider(UUID.randomUUID().toString(), "", "https://")
                saved = false
            },
        ) { Text("Add provider") }

        HorizontalDivider()

        Text("Models", style = MaterialTheme.typography.titleLarge)
        Text(
            "Each model is tried route by route until one works (429 or errors fall through to the next). " +
                "One route per line, written as  Provider: model-id",
            style = MaterialTheme.typography.bodySmall,
        )
        for (e in aliasEdits) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Field("Model name", e.name) { v ->
                        aliasEdits = aliasEdits.map { if (it.id == e.id) it.copy(name = v) else it }
                        saved = false
                    }
                    OutlinedTextField(
                        value = e.routes,
                        onValueChange = { v ->
                            aliasEdits = aliasEdits.map { if (it.id == e.id) it.copy(routes = v) else it }
                            saved = false
                        },
                        label = { Text("Routes (Provider: model-id)") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(
                        onClick = {
                            aliasEdits = aliasEdits.filter { it.id != e.id }
                            saved = false
                        },
                    ) { Text("Delete model") }
                }
            }
        }
        OutlinedButton(
            onClick = {
                aliasEdits = aliasEdits + AliasEdit(UUID.randomUUID().toString(), "", "")
                saved = false
            },
        ) { Text("Add model") }

        Button(
            onClick = {
                val cleanProviders = providers.filter { it.name.isNotBlank() && it.baseUrl.isNotBlank() }
                val cleanAliases = aliasEdits.filter { it.name.isNotBlank() }.map { e ->
                    ModelAlias(
                        e.name.trim(),
                        e.routes.lines().mapNotNull { line ->
                            val i = line.indexOf(':')
                            if (i <= 0) {
                                null
                            } else {
                                val r = Route(line.substring(0, i).trim(), line.substring(i + 1).trim())
                                if (r.model.isEmpty()) null else r
                            }
                        },
                    )
                }
                vm.saveSettings(cleanProviders, cleanAliases)
                saved = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }
        if (saved) Text("Saved.", color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
internal fun McpScreen(vm: AgentViewModel) {
    var servers by remember { mutableStateOf(vm.store.mcpServers) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("MCP servers", style = MaterialTheme.typography.titleLarge)
        Text(
            "Remote servers only (a Streamable HTTP URL). Local stdio servers cannot run on a phone. " +
                "Tools that are not marked read-only ask for your confirmation before they run.",
            style = MaterialTheme.typography.bodySmall,
        )
        for (s in servers) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Field("Name", s.name) { v ->
                        servers = servers.map { if (it.id == s.id) it.copy(name = v) else it }
                    }
                    Field("Server URL", s.url) { v ->
                        servers = servers.map { if (it.id == s.id) it.copy(url = v) else it }
                    }
                    Field("Header name", s.headerName) { v ->
                        servers = servers.map { if (it.id == s.id) it.copy(headerName = v) else it }
                    }
                    Field("Header value (e.g. Bearer your-token)", s.headerValue, secret = true) { v ->
                        servers = servers.map { if (it.id == s.id) it.copy(headerValue = v) else it }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = s.enabled,
                            onCheckedChange = { c ->
                                servers = servers.map { if (it.id == s.id) it.copy(enabled = c) else it }
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Enabled")
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { servers = servers.filter { it.id != s.id } }) {
                            Text("Delete")
                        }
                    }
                }
            }
        }
        OutlinedButton(
            onClick = {
                servers = servers + McpServer(UUID.randomUUID().toString(), "", "https://")
            },
        ) { Text("Add server") }

        Button(
            onClick = {
                vm.store.mcpServers = servers.filter { it.name.isNotBlank() && it.url.isNotBlank() }
                vm.testMcp()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save and test connection") }
        if (vm.mcpStatus.isNotBlank()) Text(vm.mcpStatus, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))
    }
}
