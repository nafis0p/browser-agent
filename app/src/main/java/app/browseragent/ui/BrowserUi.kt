package app.browseragent.ui

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.browseragent.agent.AgentViewModel
import app.browseragent.agent.Kind
import app.browseragent.browser.BrowserController
import app.browseragent.data.AppPrefs
import java.text.DateFormat
import java.util.Date

internal enum class Page { BROWSER, HISTORY, CHATS, SETTINGS }

private fun titleOf(p: Page): String = when (p) {
    Page.HISTORY -> "History"
    Page.CHATS -> "AI chats"
    Page.SETTINGS -> "Settings"
    Page.BROWSER -> ""
}

// ==================================================================== root

@Composable
fun AppRoot(vm: AgentViewModel, browser: BrowserController, prefs: AppPrefs) {
    var page by remember { mutableStateOf(Page.BROWSER) }
    var aiOpen by remember { mutableStateOf(false) }

    BackHandler(enabled = page != Page.BROWSER) { page = Page.BROWSER }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // The browser stays composed so the Firefox view keeps its state.
        BrowserPage(
            vm = vm,
            browser = browser,
            prefs = prefs,
            visible = page == Page.BROWSER,
            aiOpen = aiOpen,
            onToggleAi = { aiOpen = !aiOpen },
            go = { page = it },
        )
        if (page != Page.BROWSER) {
            Surface(Modifier.fillMaxSize().pointerInput(Unit) {}) {
                when (page) {
                    Page.SETTINGS -> SettingsNav(vm, prefs) { page = Page.BROWSER }
                    else -> Column(Modifier.fillMaxSize()) {
                        SubBar(titleOf(page)) { page = Page.BROWSER }
                        Box(Modifier.weight(1f)) {
                            when (page) {
                                Page.HISTORY -> HistoryScreen(vm, browser) { page = Page.BROWSER }
                                Page.CHATS -> ChatsScreen(vm) {
                                    page = Page.BROWSER
                                    aiOpen = true
                                }
                                else -> {}
                            }
                        }
                    }
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
private fun SubBar(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) { Text("← Back") }
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ==================================================================== browser page

@Composable
private fun BrowserPage(
    vm: AgentViewModel,
    browser: BrowserController,
    prefs: AppPrefs,
    visible: Boolean,
    aiOpen: Boolean,
    onToggleAi: () -> Unit,
    go: (Page) -> Unit,
) {
    val showWeb = visible && !browser.isHome
    Column(Modifier.fillMaxSize()) {
        if (prefs.navBarTop) NavBar(vm, browser, aiOpen, onToggleAi, go)
        if (!prefs.addressBarBottom) {
            AddressBar(browser)
            ProgressLine(browser)
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { browser.webView },
                update = { v -> v.visibility = if (showWeb) View.VISIBLE else View.INVISIBLE },
                modifier = Modifier.fillMaxSize(),
            )
            if (browser.isHome) {
                StartPage(vm, browser, onAskAi = { if (!aiOpen) onToggleAi() })
            }
        }

        if (aiOpen) {
            AiPanel(vm, Modifier.weight(1f).fillMaxWidth(), onClose = onToggleAi)
        }
        if (prefs.addressBarBottom) {
            ProgressLine(browser)
            AddressBar(browser)
        }
        if (!prefs.navBarTop) NavBar(vm, browser, aiOpen, onToggleAi, go)
    }
}

// ==================================================================== history screens

@Composable
private fun HistoryScreen(vm: AgentViewModel, browser: BrowserController, onOpen: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    val shown = vm.history.entries.filter {
        query.isBlank() || it.title.contains(query, ignoreCase = true) || it.url.contains(query, ignoreCase = true)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Search history") },
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { confirmClear = true }) { Text("Clear") }
        }
        if (shown.isEmpty()) {
            Text("No history yet.", Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(shown) { h ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable {
                            browser.load(h.url)
                            onOpen()
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(h.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        Text(
                            hostOf(h.url) + "  ·  " + fmt.format(Date(h.time)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = { vm.history.remove(h) }) { Text("✕") }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear browsing history?") },
            text = { Text("This removes all saved pages from the history.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.history.clear()
                        confirmClear = false
                    },
                ) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ChatsScreen(vm: AgentViewModel, onOpen: () -> Unit) {
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Button(
            onClick = {
                vm.newChat()
                onOpen()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("New AI chat") }
        if (vm.chats.chats.isEmpty()) {
            Text(
                "No saved chats yet. Your AI chats are saved here automatically.",
                Modifier.padding(16.dp),
            )
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(vm.chats.chats) { c ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable {
                            vm.openChat(c)
                            onOpen()
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            c.title.ifBlank { "(empty chat)" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Medium,
                        )
                        val n = c.entries.count { it.kind == Kind.USER.name }
                        Text(
                            fmt.format(Date(c.updated)) + "  ·  " + n + " message(s)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { vm.deleteChat(c.id) }) { Text("✕") }
                }
            }
        }
    }
}
