package app.browseragent.ui

import android.content.Intent
import android.view.View
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.browseragent.agent.AgentViewModel
import app.browseragent.agent.Kind
import app.browseragent.agent.LogEntry
import app.browseragent.browser.BrowserController
import app.browseragent.data.Shortcut
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

private enum class Page { BROWSER, HISTORY, CHATS, SETTINGS }

private fun titleOf(p: Page): String = when (p) {
    Page.HISTORY -> "History"
    Page.CHATS -> "AI chats"
    Page.SETTINGS -> "Settings"
    Page.BROWSER -> ""
}

private fun hostOf(u: String): String =
    u.substringAfter("://").substringBefore('/').removePrefix("www.")

private fun shortUrl(u: String): String =
    u.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')

private fun greeting(): String {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return if (h < 12) "Good morning" else if (h < 18) "Good afternoon" else "Good evening"
}

private fun chatText(entries: List<LogEntry>): String =
    entries
        .filter { it.kind == Kind.USER || it.kind == Kind.ASSISTANT || it.kind == Kind.ERROR }
        .joinToString("\n\n") { (if (it.kind == Kind.USER) "You: " else "AI: ") + it.text }

// ==================================================================== root

@Composable
fun AppRoot(vm: AgentViewModel, browser: BrowserController) {
    var page by remember { mutableStateOf(Page.BROWSER) }
    var aiOpen by remember { mutableStateOf(false) }

    BackHandler(enabled = page != Page.BROWSER) { page = Page.BROWSER }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // The browser stays composed so the Firefox view keeps its state.
        BrowserPage(
            vm = vm,
            browser = browser,
            visible = page == Page.BROWSER,
            aiOpen = aiOpen,
            onToggleAi = { aiOpen = !aiOpen },
            go = { page = it },
        )
        if (page != Page.BROWSER) {
            Surface(Modifier.fillMaxSize().pointerInput(Unit) {}) {
                when (page) {
                    Page.SETTINGS -> SettingsNav(vm) { page = Page.BROWSER }
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
    visible: Boolean,
    aiOpen: Boolean,
    onToggleAi: () -> Unit,
    go: (Page) -> Unit,
) {
    val showWeb = visible && !browser.isHome
    Column(Modifier.fillMaxSize()) {
        AddressBar(browser)
        if (browser.isLoading && !browser.isHome) {
            LinearProgressIndicator(
                progress = { browser.progress / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
        } else {
            Spacer(Modifier.height(2.dp))
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
        BottomBar(vm, browser, aiOpen, onToggleAi, go)
    }
}

@Composable
private fun AddressBar(browser: BrowserController) {
    val focus = LocalFocusManager.current
    val cs = MaterialTheme.colorScheme
    var editing by remember { mutableStateOf(false) }
    var field by remember { mutableStateOf(TextFieldValue("")) }
    val shown = if (editing) field else TextFieldValue(if (browser.isHome) "" else shortUrl(browser.url))

    Surface(
        shape = RoundedCornerShape(26.dp),
        color = cs.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).height(48.dp),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!browser.isHome && browser.url.startsWith("https")) {
                Text("\ud83d\udd12", fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
            }
            BasicTextField(
                value = shown,
                onValueChange = { field = it },
                singleLine = true,
                textStyle = TextStyle(color = cs.onSurface, fontSize = 16.sp),
                cursorBrush = SolidColor(cs.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, keyboardType = KeyboardType.Uri),
                keyboardActions = KeyboardActions(
                    onGo = {
                        if (field.text.isNotBlank()) browser.load(field.text)
                        focus.clearFocus()
                    },
                ),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (shown.text.isEmpty()) {
                            Text("Search or type URL", color = cs.onSurfaceVariant, fontSize = 16.sp)
                        }
                        inner()
                    }
                },
                modifier = Modifier.weight(1f).onFocusChanged { st ->
                    if (st.isFocused) {
                        editing = true
                        val t = if (browser.isHome) "" else browser.url
                        field = TextFieldValue(t, TextRange(0, t.length))
                    } else {
                        editing = false
                    }
                },
            )
            if (!browser.isHome) {
                Text(
                    if (browser.isLoading) "✕" else "⟳",
                    fontSize = 20.sp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { if (browser.isLoading) browser.stopLoading() else browser.reload() }
                        .padding(10.dp),
                )
            }
        }
    }
}

// ==================================================================== start page

@Composable
private fun StartPage(vm: AgentViewModel, browser: BrowserController, onAskAi: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    val recent = vm.history.entries.distinctBy { it.url }.take(5)

    Column(
        Modifier.fillMaxSize().background(cs.background).verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Text(greeting(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            placeholder = { Text("Search or type URL") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, keyboardType = KeyboardType.Uri),
            keyboardActions = KeyboardActions(onGo = { if (query.isNotBlank()) browser.load(query) }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))

        for (row in vm.shortcuts.chunked(3)) {
            Row(Modifier.fillMaxWidth()) {
                for (s in row) {
                    ShortcutTile(s, Modifier.weight(1f)) { browser.load(s.url) }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Spacer(Modifier.height(20.dp))

        Card(Modifier.fillMaxWidth().clickable(onClick = onAskAi)) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(cs.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("AI", color = cs.onPrimary, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Ask AI to browse for you", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Search, read pages and fill forms. You stay in control.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
        }

        if (recent.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text(
                "Recently visited",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.fillMaxWidth(),
            )
            for (h in recent) {
                Column(
                    Modifier.fillMaxWidth().clickable { browser.load(h.url) }.padding(vertical = 8.dp),
                ) {
                    Text(h.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text(
                        hostOf(h.url),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ShortcutTile(s: Shortcut, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(cs.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                s.title.take(1).uppercase(),
                color = cs.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(s.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ==================================================================== bottom bar

@Composable
private fun NavButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(46.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 22.sp, modifier = Modifier.alpha(if (enabled) 1f else 0.35f))
    }
}

@Composable
private fun AiButton(open: Boolean, busy: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(54.dp).clip(CircleShape).background(cs.primary).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(42.dp),
                color = cs.onPrimary,
                strokeWidth = 2.dp,
            )
        }
        Text(if (open) "✕" else "AI", color = cs.onPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = onClick)
}

@Composable
private fun BottomBar(
    vm: AgentViewModel,
    browser: BrowserController,
    aiOpen: Boolean,
    onToggleAi: () -> Unit,
    go: (Page) -> Unit,
) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }

    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            NavButton("←", browser.canGoBack()) { browser.back() }
            NavButton("→", browser.canForward && !browser.isHome) { browser.forward() }
            AiButton(aiOpen, vm.running, onToggleAi)
            NavButton("⌂", !browser.isHome) { browser.showHome() }
            Box {
                NavButton("⋮", true) { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuRow("New AI chat") {
                        menu = false
                        vm.newChat()
                        if (!aiOpen) onToggleAi()
                    }
                    MenuRow("AI chat history") {
                        menu = false
                        go(Page.CHATS)
                    }
                    MenuRow("Browsing history") {
                        menu = false
                        go(Page.HISTORY)
                    }
                    HorizontalDivider()
                    MenuRow("Pin page to start page") {
                        menu = false
                        val u = browser.url
                        if (!browser.isHome && u.startsWith("http")) {
                            vm.saveShortcuts(vm.shortcuts + Shortcut(hostOf(u), u))
                            Toast.makeText(ctx, "Pinned", Toast.LENGTH_SHORT).show()
                        }
                    }
                    MenuRow("Copy link") {
                        menu = false
                        if (!browser.isHome) {
                            clipboard.setText(AnnotatedString(browser.url))
                            Toast.makeText(ctx, "Link copied", Toast.LENGTH_SHORT).show()
                        }
                    }
                    MenuRow("Share link") {
                        menu = false
                        if (!browser.isHome) {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, browser.url)
                            }
                            ctx.startActivity(Intent.createChooser(send, "Share link"))
                        }
                    }
                    MenuRow(if (browser.desktopMode) "✓ Desktop site" else "Desktop site") {
                        menu = false
                        browser.setDesktop(!browser.desktopMode)
                    }
                    HorizontalDivider()
                    MenuRow("Settings") {
                        menu = false
                        go(Page.SETTINGS)
                    }
                }
            }
        }
    }
}

// ==================================================================== AI panel

@Composable
private fun AiPanel(vm: AgentViewModel, modifier: Modifier, onClose: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current

    fun copy(text: String) {
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(vm.entries.size) {
        if (vm.entries.isNotEmpty()) listState.animateScrollToItem(vm.entries.size - 1)
    }

    Surface(modifier = modifier, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    TextButton(onClick = { menuOpen = true }) {
                        Text("✦ " + (vm.activeAlias ?: "No model"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        vm.aliases.forEach { a ->
                            MenuRow(a.name) {
                                vm.setActive(a.name)
                                menuOpen = false
                            }
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { copy(chatText(vm.entries)) }) { Text("Copy all") }
                TextButton(onClick = { vm.newChat() }) { Text("New") }
                TextButton(onClick = onClose) { Text("✕") }
            }

            if (vm.entries.isEmpty()) {
                EmptyChat(Modifier.weight(1f).fillMaxWidth()) { vm.send(it) }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(vm.entries) { e -> Bubble(e) { copy(it) } }
                }
            }

            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask or tell the AI what to do") },
                    maxLines = 3,
                    shape = RoundedCornerShape(24.dp),
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
}

@Composable
private fun EmptyChat(modifier: Modifier, onPick: (String) -> Unit) {
    val ideas = listOf(
        "Summarize this page" to "Read the current page and give me a short summary.",
        "What can I do here?" to "Look at this page and tell me which actions are possible here.",
        "Fill the form on this page" to "Find the form on this page and fill it in with my personal data placeholders.",
    )
    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("What should I do?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        for ((label, prompt) in ideas) {
            FilledTonalButton(onClick = { onPick(prompt) }, modifier = Modifier.padding(vertical = 2.dp)) {
                Text(label)
            }
        }
    }
}

@Composable
private fun Bubble(e: LogEntry, onCopy: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    when (e.kind) {
        Kind.USER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = cs.primaryContainer,
                modifier = Modifier.fillMaxWidth(0.85f),
            ) {
                SelectionContainer {
                    Text(e.text, Modifier.padding(10.dp), color = cs.onPrimaryContainer)
                }
            }
        }
        Kind.ASSISTANT -> Column(Modifier.fillMaxWidth(0.95f)) {
            Surface(shape = RoundedCornerShape(16.dp), color = cs.surfaceVariant) {
                SelectionContainer {
                    Text(e.text, Modifier.padding(10.dp), color = cs.onSurfaceVariant)
                }
            }
            TextButton(onClick = { onCopy(e.text) }) {
                Text("Copy", style = MaterialTheme.typography.labelSmall)
            }
        }
        Kind.ERROR -> Column {
            Surface(shape = RoundedCornerShape(12.dp), color = cs.errorContainer) {
                SelectionContainer {
                    Text(e.text, Modifier.padding(10.dp), color = cs.onErrorContainer)
                }
            }
            TextButton(onClick = { onCopy(e.text) }) {
                Text("Copy", style = MaterialTheme.typography.labelSmall)
            }
        }
        Kind.TOOL, Kind.INFO -> Text(
            (if (e.kind == Kind.TOOL) "› " else "• ") + e.text,
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
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
