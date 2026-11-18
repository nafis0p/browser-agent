package app.browseragent.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.browseragent.agent.AgentViewModel
import app.browseragent.agent.Kind
import app.browseragent.agent.LogEntry

internal fun chatText(entries: List<LogEntry>): String =
    entries
        .filter { it.kind == Kind.USER || it.kind == Kind.ASSISTANT || it.kind == Kind.ERROR }
        .joinToString("\n\n") { (if (it.kind == Kind.USER) "You: " else "AI: ") + it.text }

/**
 * Messages are shown as bubbles; runs of tool and info lines are folded into one steps accordion.
 * index is the position of the message in the full list of entries (needed for editing).
 */
private sealed class ChatItem {
    class Msg(val index: Int, val e: LogEntry) : ChatItem()
    class Steps(val start: Int, val lines: List<LogEntry>) : ChatItem()
}

private fun groupEntries(entries: List<LogEntry>): List<ChatItem> {
    val out = ArrayList<ChatItem>()
    var current: ArrayList<LogEntry>? = null
    var currentStart = 0
    for ((idx, e) in entries.withIndex()) {
        if (e.kind == Kind.TOOL || e.kind == Kind.INFO) {
            val list = current ?: ArrayList<LogEntry>().also {
                current = it
                currentStart = idx
            }
            list.add(e)
        } else {
            val done = current
            if (done != null) {
                out.add(ChatItem.Steps(currentStart, done))
                current = null
            }
            out.add(ChatItem.Msg(idx, e))
        }
    }
    val tail = current
    if (tail != null) out.add(ChatItem.Steps(currentStart, tail))
    return out
}

@Composable
internal fun AiPanel(vm: AgentViewModel, modifier: Modifier, onClose: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    // Index of the message that is being edited (-1: none) and its text.
    var editing by remember { mutableStateOf(-1) }
    var editText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    val groups = remember(vm.entries.size) { groupEntries(vm.entries.toList()) }
    val opened = remember { mutableStateMapOf<Int, Boolean>() }
    val enabled = vm.prefs.agentEnabled

    fun copy(text: String) {
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(vm.entries.size) {
        if (groups.isNotEmpty() && editing < 0) listState.animateScrollToItem(groups.size - 1)
    }

    Surface(modifier = modifier, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    TextButton(onClick = { menuOpen = true }) {
                        Text("\u2726 " + (vm.activeAlias ?: "No model"), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                if (vm.running) {
                    TextButton(onClick = { if (vm.paused) vm.resume() else vm.pause() }) {
                        Text(if (vm.paused) "Resume" else "Pause")
                    }
                }
                Box {
                    TextButton(onClick = { moreOpen = true }) { Text("\u22ee") }
                    DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                        MenuRow("Copy all") {
                            moreOpen = false
                            copy(chatText(vm.entries))
                        }
                        MenuRow("New chat") {
                            moreOpen = false
                            editing = -1
                            vm.newChat()
                        }
                        MenuRow(
                            if (vm.prefs.shareActivity) "\u2713 Share my activity with AI" else "Share my activity with AI",
                        ) {
                            moreOpen = false
                            vm.prefs.chooseShareActivity(!vm.prefs.shareActivity)
                        }
                    }
                }
                TextButton(onClick = onClose) { Text("\u2715") }
            }

            if (!enabled) {
                Text(
                    "The AI assistant is turned off. Turn it on in Settings > AI settings > Behaviour & privacy.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(16.dp),
                )
            } else {
                if (vm.entries.isEmpty()) {
                    EmptyChat(Modifier.weight(1f).fillMaxWidth()) { vm.send(it) }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(groups) { g ->
                            when (g) {
                                is ChatItem.Msg -> Bubble(
                                    e = g.e,
                                    busy = vm.running,
                                    editing = editing == g.index,
                                    editText = editText,
                                    onEditText = { editText = it },
                                    onStartEdit = {
                                        editing = g.index
                                        editText = g.e.text
                                    },
                                    onCancel = { editing = -1 },
                                    onSave = {
                                        if (g.e.kind == Kind.USER) {
                                            vm.editAndResend(g.index, editText)
                                        } else {
                                            vm.editText(g.index, editText)
                                        }
                                        editing = -1
                                    },
                                    onCopy = { copy(it) },
                                )
                                is ChatItem.Steps -> {
                                    val isLast = g === groups.last()
                                    val open = opened[g.start] ?: (isLast && vm.running)
                                    StepsGroup(g.lines, open) { opened[g.start] = !open }
                                }
                            }
                        }
                        item {
                            if (!vm.running && vm.entries.any { it.kind == Kind.USER }) {
                                TextButton(
                                    onClick = {
                                        editing = -1
                                        vm.retryLast()
                                    },
                                ) { Text("\u21bb Retry") }
                            }
                        }
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
}

/** Collapsible list of what the agent did (like the steps shown in the Claude app). */
@Composable
private fun StepsGroup(lines: List<LogEntry>, expanded: Boolean, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val actions = lines.count { it.kind == Kind.TOOL }
    val last = lines.lastOrNull { it.kind == Kind.TOOL }?.text?.substringBefore('(')
        ?: lines.last().text.take(40)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = cs.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.clickable(onClick = onToggle).padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (expanded) "\u25be" else "\u25b8", color = cs.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (actions == 0) "Details" else actions.toString() + (if (actions == 1) " step" else " steps"),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
                if (!expanded) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        last,
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                for (l in lines) {
                    Text(
                        (if (l.kind == Kind.TOOL) "\u203a " else "\u2022 ") + l.text,
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
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

/** The text box shown in place of a message while it is edited. */
@Composable
private fun EditBox(
    text: String,
    onText: (String) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    saveLabel: String,
) {
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text,
            onValueChange = onText,
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 10,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = onSave) { Text(saveLabel) }
        }
    }
}

@Composable
private fun Bubble(
    e: LogEntry,
    busy: Boolean,
    editing: Boolean,
    editText: String,
    onEditText: (String) -> Unit,
    onStartEdit: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    when (e.kind) {
        Kind.USER -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            if (editing) {
                EditBox(editText, onEditText, onCancel, onSave, "Save & resend")
            } else {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = cs.primaryContainer,
                    modifier = Modifier.fillMaxWidth(0.85f),
                ) {
                    SelectionContainer {
                        Text(e.text, Modifier.padding(10.dp), color = cs.onPrimaryContainer)
                    }
                }
                Row {
                    TextButton(onClick = { onCopy(e.text) }) {
                        Text("Copy", style = MaterialTheme.typography.labelSmall)
                    }
                    if (!busy) {
                        TextButton(onClick = onStartEdit) {
                            Text("Edit", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        Kind.ASSISTANT -> Column(Modifier.fillMaxWidth(0.97f)) {
            if (editing) {
                EditBox(editText, onEditText, onCancel, onSave, "Save")
            } else {
                Surface(shape = RoundedCornerShape(16.dp), color = cs.surfaceVariant) {
                    SelectionContainer {
                        MarkdownText(e.text, cs.onSurfaceVariant, Modifier.padding(10.dp))
                    }
                }
                Row {
                    TextButton(onClick = { onCopy(e.text) }) {
                        Text("Copy", style = MaterialTheme.typography.labelSmall)
                    }
                    if (!busy) {
                        TextButton(onClick = onStartEdit) {
                            Text("Edit", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
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
        else -> {}
    }
}
