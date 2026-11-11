package app.browseragent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.browseragent.agent.AgentViewModel
import app.browseragent.agent.Kind
import app.browseragent.browser.BrowserController
import app.browseragent.data.McpServer
import app.browseragent.data.ModelAlias
import app.browseragent.data.Provider
import app.browseragent.data.Route
import java.util.UUID

@Composable
fun AppRoot(vm: AgentViewModel, browser: BrowserController) {
    var screen by remember { mutableStateOf(0) }
    val labels = listOf("Browser", "Providers", "MCP")
    val icons = listOf("🌐", "🔑", "🔌")

    Scaffold(
        bottomBar = {
            NavigationBar {
                labels.forEachIndexed { i, label ->
                    NavigationBarItem(
                        selected = screen == i,
                        onClick = { screen = i },
                        icon = { Text(icons[i]) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            // The browser stays composed so the WebView keeps its state.
            BrowserScreen(vm, browser)
            if (screen != 0) {
                Surface(Modifier.fillMaxSize().pointerInput(Unit) {}) {
                    if (screen == 1) ProvidersScreen(vm) else McpScreen(vm)
                }
            }
        }
    }

    val ask = vm.pendingConfirm
    if (ask != null) {
        AlertDialog(
            onDismissRequest = { vm.answerConfirm(false) },
            title = { Text("Confirm action") },
            text = { Text(ask) },
            confirmButton = { TextButton(onClick = { vm.answerConfirm(true) }) { Text("Allow") } },
            dismissButton = { TextButton(onClick = { vm.answerConfirm(false) }) { Text("Deny") } },
        )
    }
}

@Composable
private fun BrowserScreen(vm: AgentViewModel, browser: BrowserController) {
    Column(Modifier.fillMaxSize()) {
        UrlBar(browser)
        AndroidView(
            factory = { browser.webView },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        AgentPanel(vm)
    }
}

@Composable
private fun UrlBar(browser: BrowserController) {
    var text by remember(browser.url) { mutableStateOf(browser.url) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, keyboardType = KeyboardType.Uri),
            keyboardActions = KeyboardActions(onGo = { browser.load(text) }),
        )
        TextButton(onClick = { browser.load(text) }) { Text("Go") }
    }
}

@Composable
private fun AgentPanel(vm: AgentViewModel) {
    var input by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(vm.entries.size) {
        if (vm.entries.isNotEmpty()) listState.animateScrollToItem(vm.entries.size - 1)
    }

    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(onClick = { menuOpen = true }) { Text(vm.activeAlias ?: "No model") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    vm.aliases.forEach { a ->
                        DropdownMenuItem(
                            text = { Text(a.name) },
                            onClick = {
                                vm.setActive(a.name)
                                menuOpen = false
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { vm.newChat() }) { Text("New chat") }
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide log" else "Show log") }
        }

        if (expanded) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().height(180.dp)) {
                items(vm.entries) { e ->
                    val color = when (e.kind) {
                        Kind.USER -> MaterialTheme.colorScheme.primary
                        Kind.ASSISTANT -> MaterialTheme.colorScheme.onSurface
                        Kind.TOOL -> MaterialTheme.colorScheme.onSurfaceVariant
                        Kind.INFO -> MaterialTheme.colorScheme.tertiary
                        Kind.ERROR -> MaterialTheme.colorScheme.error
                    }
                    val prefix = when (e.kind) {
                        Kind.USER -> "You: "
                        Kind.TOOL -> "> "
                        Kind.INFO -> "- "
                        Kind.ERROR -> "! "
                        Kind.ASSISTANT -> ""
                    }
                    Text(
                        prefix + e.text,
                        color = color,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Tell the agent what to do") },
                maxLines = 3,
            )
            Spacer(Modifier.width(8.dp))
            if (vm.running) {
                Button(onClick = { vm.stop() }) { Text("Stop") }
            } else {
                Button(
                    onClick = {
                        if (input.isNotBlank()) {
                            vm.send(input.trim())
                            input = ""
                        }
                    },
                ) { Text("Send") }
            }
        }
    }
}

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
private fun ProvidersScreen(vm: AgentViewModel) {
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
private fun McpScreen(vm: AgentViewModel) {
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
