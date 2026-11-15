package app.browseragent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.browseragent.agent.AgentViewModel
import app.browseragent.agent.DEFAULT_CHECKER_PROMPT
import app.browseragent.agent.DEFAULT_EXECUTOR_PROMPT
import app.browseragent.agent.DEFAULT_PLANNER_PROMPT

/** One role of the team: which model it uses and what its instructions are. */
@Composable
private fun RoleCard(
    title: String,
    help: String,
    aliasInit: String,
    promptInit: String?,
    default: String,
    aliasNames: List<String>,
    onSave: (String, String?) -> Unit,
) {
    var alias by remember { mutableStateOf(aliasInit) }
    var prompt by remember { mutableStateOf(promptInit ?: default) }
    var menu by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(help, style = MaterialTheme.typography.bodySmall)
            Box {
                OutlinedButton(onClick = { menu = true }) {
                    Text("Model: " + alias.ifBlank { "active model" })
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Use the active model") },
                        onClick = {
                            alias = ""
                            menu = false
                            note = ""
                        },
                    )
                    for (n in aliasNames) {
                        DropdownMenuItem(
                            text = { Text(n) },
                            onClick = {
                                alias = n
                                menu = false
                                note = ""
                            },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = prompt,
                onValueChange = {
                    prompt = it
                    note = ""
                },
                label = { Text("Instructions") },
                minLines = 5,
                maxLines = 10,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val p = prompt.trim()
                        onSave(alias, if (p.isEmpty() || p == default.trim()) null else prompt)
                        note = "Saved."
                    },
                ) { Text("Save") }
                OutlinedButton(
                    onClick = {
                        prompt = default
                        onSave(alias, null)
                        note = "Instructions reset to the default."
                    },
                ) { Text("Reset instructions") }
            }
            if (note.isNotEmpty()) Text(note, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Choose between one agent and the planner + executor + checker team. */
@Composable
fun TeamSettingsScreen(vm: AgentViewModel) {
    var mode by remember { mutableStateOf(vm.store.teamMode) }
    var rounds by remember { mutableStateOf(vm.store.maxFixRounds) }
    val aliasNames = vm.aliases.map { it.name }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Agent team", style = MaterialTheme.typography.titleLarge)
        Text(
            "Single agent: one AI does everything. Planner + executor + checker: a planner writes the " +
                "steps, the executor does them in the browser, and a checker verifies the result and sends " +
                "the executor back to fix problems. It uses more model calls, but catches mistakes and " +
                "repeated actions. Each role can use a different model.",
            style = MaterialTheme.typography.bodySmall,
        )

        if (mode == "single") {
            Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Single agent (selected)") }
            OutlinedButton(
                onClick = {
                    mode = "team"
                    vm.store.teamMode = "team"
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Planner + executor + checker") }
        } else {
            OutlinedButton(
                onClick = {
                    mode = "single"
                    vm.store.teamMode = "single"
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Single agent") }
            Button(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                Text("Planner + executor + checker (selected)")
            }
        }

        if (mode == "team") {
            RoleCard(
                title = "Planner",
                help = "Writes the steps and says how to know the goal is done. Never uses the browser.",
                aliasInit = vm.store.plannerAlias,
                promptInit = vm.store.plannerPrompt,
                default = DEFAULT_PLANNER_PROMPT,
                aliasNames = aliasNames,
            ) { a, p ->
                vm.store.plannerAlias = a
                vm.store.plannerPrompt = p
            }
            RoleCard(
                title = "Executor",
                help = "Does the steps in the browser. Needs a model that supports tool calling.",
                aliasInit = vm.store.executorAlias,
                promptInit = vm.store.executorPrompt,
                default = DEFAULT_EXECUTOR_PROMPT,
                aliasNames = aliasNames,
            ) { a, p ->
                vm.store.executorAlias = a
                vm.store.executorPrompt = p
            }
            RoleCard(
                title = "Checker",
                help = "Reads the page afterwards and answers PASS or FAIL. Never uses the browser.",
                aliasInit = vm.store.checkerAlias,
                promptInit = vm.store.checkerPrompt,
                default = DEFAULT_CHECKER_PROMPT,
                aliasNames = aliasNames,
            ) { a, p ->
                vm.store.checkerAlias = a
                vm.store.checkerPrompt = p
            }

            Text("Fix attempts after a failed check", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (n in 0..3) {
                    if (rounds == n) {
                        Button(onClick = {}) { Text(n.toString()) }
                    } else {
                        OutlinedButton(
                            onClick = {
                                rounds = n
                                vm.store.maxFixRounds = n
                            },
                        ) { Text(n.toString()) }
                    }
                }
            }
        }
    }
}
