package app.browseragent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.browseragent.agent.AgentViewModel

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

/** Master switches for the AI and for what it may learn about what the user does. */
@Composable
fun AiPrivacyScreen(vm: AgentViewModel) {
    val p = vm.prefs
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Behaviour & privacy", style = MaterialTheme.typography.titleLarge)
        SwitchRow(
            "AI assistant",
            "Off: the AI chat stops working and nothing is sent to any AI provider. The browser works as usual.",
            p.agentEnabled,
        ) { p.chooseAgentEnabled(it) }
        SwitchRow(
            "Share my activity with the AI",
            "On: the AI is told what you do by hand (taps, edited fields, pages you open) so it never repeats " +
                "your steps. Off: it only sees the page it works on, not a log of your actions. Typed values in " +
                "fields for personal data are never shared in either case.",
            p.shareActivity,
        ) { p.chooseShareActivity(it) }
        Text(
            "You can also pause a running agent with the Pause button in the AI panel, and switch activity " +
                "sharing from its \u22ee menu.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
