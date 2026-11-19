package app.browseragent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.browseragent.agent.Kind
import app.browseragent.agent.LogEntry

// The building blocks of the AI chat: bubbles, the steps accordion, the empty state and the
// edit box. The panel itself is in AiPanel.kt.

internal fun chatText(entries: List<LogEntry>): String =
    entries
        .filter { it.kind == Kind.USER || it.kind == Kind.ASSISTANT || it.kind == Kind.ERROR }
        .joinToString("\n\n") { (if (it.kind == Kind.USER) "You: " else "AI: ") + it.text }

/**
 * Messages are shown as bubbles; runs of tool and info lines are folded into one steps accordion.
 * index is the position of the message in the full list of entries (needed for editing).
 */
internal sealed class ChatItem {
    class Msg(val index: Int, val e: LogEntry) : ChatItem()
    class Steps(val start: Int, val lines: List<LogEntry>) : ChatItem()
}

internal fun groupEntries(entries: List<LogEntry>): List<ChatItem> {
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

/** Collapsible list of what the agent did (like the steps shown in the Claude app). */
@Composable
internal fun StepsGroup(lines: List<LogEntry>, expanded: Boolean, onToggle: () -> Unit) {
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
internal fun EmptyChat(modifier: Modifier, onPick: (String) -> Unit) {
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
internal fun EditBox(
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
internal fun Bubble(
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
