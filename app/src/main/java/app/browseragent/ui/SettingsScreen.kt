package app.browseragent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.browseragent.agent.AgentViewModel
import app.browseragent.agent.DEFAULT_SYSTEM_PROMPT
import app.browseragent.agent.VAULT_KEYS
import app.browseragent.agent.isSecretKey
import java.util.UUID

private data class VRow(val id: String, val key: String, val value: String, val custom: Boolean)

@Composable
private fun SField(
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

@Composable
fun SettingsScreen(vm: AgentViewModel) {
    var prompt by remember { mutableStateOf(vm.store.systemPrompt ?: DEFAULT_SYSTEM_PROMPT) }
    var promptNote by remember { mutableStateOf("") }

    var rows by remember {
        mutableStateOf(
            run {
                val saved = vm.store.vault
                val fixed = VAULT_KEYS.map { VRow(it, it, saved[it].orEmpty(), false) }
                val extra = saved.filterKeys { it !in VAULT_KEYS }
                    .map { (k, v) -> VRow(UUID.randomUUID().toString(), k, v, true) }
                fixed + extra
            },
        )
    }
    var vaultNote by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---------------------------------------------------------------- system prompt
        Text("AI instructions", style = MaterialTheme.typography.titleLarge)
        Text(
            "This is the system prompt: it tells the AI how to behave. The tool rules, your personal-data " +
                "placeholders and the list of finished actions are always added after it automatically. " +
                "Changes apply from your next message.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = prompt,
            onValueChange = {
                prompt = it
                promptNote = ""
            },
            label = { Text("System prompt") },
            minLines = 8,
            maxLines = 14,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val p = prompt.trim()
                    vm.store.systemPrompt =
                        if (p.isEmpty() || p == DEFAULT_SYSTEM_PROMPT.trim()) null else prompt
                    promptNote = "Saved."
                },
            ) { Text("Save prompt") }
            OutlinedButton(
                onClick = {
                    prompt = DEFAULT_SYSTEM_PROMPT
                    vm.store.systemPrompt = null
                    promptNote = "Reset to the default prompt."
                },
            ) { Text("Reset to default") }
        }
        if (promptNote.isNotEmpty()) Text(promptNote, color = MaterialTheme.colorScheme.primary)

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // ---------------------------------------------------------------- personal data
        Text("Personal data", style = MaterialTheme.typography.titleLarge)
        Text(
            "The AI never sees these values. It writes placeholders such as \$email or \$password and the " +
                "app fills in the real value. Passwords and card details ask for your approval every time. " +
                "Stored values are also replaced by their placeholder in everything the AI reads. " +
                "Values are stored encrypted on this device.",
            style = MaterialTheme.typography.bodySmall,
        )
        for (r in rows) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (r.custom) {
                        SField("Placeholder name (letters, digits, _)", r.key) { v ->
                            rows = rows.map { if (it.id == r.id) it.copy(key = v) else it }
                            vaultNote = ""
                        }
                    }
                    SField(
                        if (r.custom) "Value" else "\$" + r.key,
                        r.value,
                        secret = isSecretKey(r.key),
                    ) { v ->
                        rows = rows.map { if (it.id == r.id) it.copy(value = v) else it }
                        vaultNote = ""
                    }
                    if (r.custom) {
                        TextButton(
                            onClick = {
                                rows = rows.filter { it.id != r.id }
                                vaultNote = ""
                            },
                        ) { Text("Delete") }
                    }
                }
            }
        }
        OutlinedButton(
            onClick = {
                rows = rows + VRow(UUID.randomUUID().toString(), "", "", true)
                vaultNote = ""
            },
        ) { Text("Add custom placeholder") }

        Button(
            onClick = {
                val map = LinkedHashMap<String, String>()
                for (r in rows) {
                    val k = r.key.trim().lowercase().replace(Regex("[^a-z0-9_]"), "_").trim('_')
                    if (k.isNotEmpty() && r.value.isNotEmpty()) map[k] = r.value
                }
                vm.store.vault = map
                vaultNote = "Saved " + map.size + " value(s)."
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save personal data") }
        TextButton(
            onClick = {
                rows = rows.map { it.copy(value = "") }
                vm.store.vault = emptyMap()
                vaultNote = "All personal data cleared."
            },
        ) { Text("Clear all personal data") }
        if (vaultNote.isNotEmpty()) Text(vaultNote, color = MaterialTheme.colorScheme.primary)

        Spacer(Modifier.height(24.dp))
    }
}
