package app.browseragent.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.browseragent.browser.BrowserController
import app.browseragent.browser.BrowserPrompt
import app.browseragent.browser.PromptAnswer

/**
 * Shows the questions that pages and the browser engine ask, one at a time: JavaScript alert,
 * confirm and prompt, dropdown lists, the leave-page question, the permission to use the location,
 * camera or microphone, and the file picker.
 */
@Composable
internal fun BrowserPromptHost(browser: BrowserController) {
    val queue = browser.prompts.queue

    fun finishFile(prompt: BrowserPrompt?, uris: List<Uri>) {
        if (prompt != null && prompt.kind == "file") {
            queue.remove(prompt)
            prompt.answer(PromptAnswer(ok = uris.isNotEmpty(), uris = uris))
        }
    }

    // The pickers are created before anything can return early, so they are always there.
    val single = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        finishFile(queue.firstOrNull(), if (uri != null) listOf(uri) else emptyList())
    }
    val multi = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        finishFile(queue.firstOrNull(), uris)
    }

    val p = queue.firstOrNull()
    LaunchedEffect(p) {
        if (p != null && p.kind == "file") {
            val types = p.fileTypes.filter { it.isNotBlank() }.ifEmpty { listOf("*/*") }.toTypedArray()
            if (p.multiple) multi.launch(types) else single.launch(types)
        }
    }
    if (p == null || p.kind == "file") return

    fun reply(a: PromptAnswer) {
        queue.remove(p)
        p.answer(a)
    }

    when (p.kind) {
        "alert" -> AlertDialog(
            onDismissRequest = { reply(PromptAnswer(true)) },
            title = { Text(p.host + " says") },
            text = { Text(p.message) },
            confirmButton = { TextButton(onClick = { reply(PromptAnswer(true)) }) { Text("OK") } },
        )
        "confirm" -> AlertDialog(
            onDismissRequest = { reply(PromptAnswer(false)) },
            title = { Text(p.host + " says") },
            text = { Text(p.message) },
            confirmButton = { TextButton(onClick = { reply(PromptAnswer(true)) }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { reply(PromptAnswer(false)) }) { Text("Cancel") } },
        )
        "text" -> TextPromptDialog(p) { reply(it) }
        "choice" -> ChoicePromptDialog(p) { reply(it) }
        "beforeunload" -> AlertDialog(
            onDismissRequest = { reply(PromptAnswer(false)) },
            title = { Text("Leave this page?") },
            text = { Text("Changes you made may not be saved.") },
            confirmButton = { TextButton(onClick = { reply(PromptAnswer(true)) }) { Text("Leave") } },
            dismissButton = { TextButton(onClick = { reply(PromptAnswer(false)) }) { Text("Stay") } },
        )
        else -> AlertDialog(
            // permission and media: a site wants to use the location, camera or microphone
            onDismissRequest = { reply(PromptAnswer(false)) },
            title = { Text(p.host) },
            text = { Text("This site " + p.message + ".") },
            confirmButton = { TextButton(onClick = { reply(PromptAnswer(true)) }) { Text("Allow") } },
            dismissButton = { TextButton(onClick = { reply(PromptAnswer(false)) }) { Text("Block") } },
        )
    }
}

@Composable
private fun TextPromptDialog(p: BrowserPrompt, reply: (PromptAnswer) -> Unit) {
    var text by remember(p) { mutableStateOf(p.defaultText) }
    AlertDialog(
        onDismissRequest = { reply(PromptAnswer(false)) },
        title = { Text(p.host + " says") },
        text = {
            Column {
                if (p.message.isNotBlank()) Text(p.message, Modifier.padding(bottom = 8.dp))
                OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { reply(PromptAnswer(true, text = text)) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = { reply(PromptAnswer(false)) }) { Text("Cancel") } },
    )
}

/** A dropdown or list from a page. One choice answers right away; several need OK. */
@Composable
private fun ChoicePromptDialog(p: BrowserPrompt, reply: (PromptAnswer) -> Unit) {
    val picked = remember(p) { mutableStateListOf<String>().also { l -> l.addAll(p.options.filter { it.selected }.map { it.id }) } }
    AlertDialog(
        onDismissRequest = { reply(PromptAnswer(false)) },
        title = { if (p.message.isNotBlank()) Text(p.message) },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(p.options) { o ->
                    if (o.header) {
                        Text(
                            o.label,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth().padding(start = (o.depth * 12).dp, top = 10.dp, bottom = 4.dp),
                        )
                    } else {
                        val on = picked.contains(o.id)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = o.enabled) {
                                    if (p.multiple) {
                                        if (on) picked.remove(o.id) else picked.add(o.id)
                                    } else {
                                        reply(PromptAnswer(true, ids = listOf(o.id)))
                                    }
                                }
                                .padding(start = (o.depth * 12).dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (p.multiple) {
                                Checkbox(checked = on, onCheckedChange = null, enabled = o.enabled)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                o.label,
                                Modifier.weight(1f).padding(vertical = 12.dp),
                                fontWeight = if (!p.multiple && on) FontWeight.Bold else FontWeight.Normal,
                                color = if (o.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (p.multiple) {
                TextButton(onClick = { reply(PromptAnswer(true, ids = picked.toList())) }) { Text("OK") }
            }
        },
        dismissButton = { TextButton(onClick = { reply(PromptAnswer(false)) }) { Text("Cancel") } },
    )
}
