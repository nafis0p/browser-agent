package app.browseragent.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.browseragent.agent.AgentViewModel
import app.browseragent.agent.Kind

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
    // Rebuilt on every change: an edited message keeps the list size, so it must not be cached by size.
    val groups = groupEntries(vm.entries.toList())
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
