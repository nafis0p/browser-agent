package app.browseragent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.browseragent.agent.AgentViewModel

private enum class SPage { HUB, BROWSER, TEAM, PROMPT, PROVIDERS, MCP }

/**
 * All settings pages live here. To add a page: add an SPage value, a title and a Hub row.
 * Back goes from a page to this list, and from the list back to the browser.
 */
@Composable
fun SettingsNav(vm: AgentViewModel, onExit: () -> Unit) {
    var page by remember { mutableStateOf(SPage.HUB) }
    BackHandler(enabled = page != SPage.HUB) { page = SPage.HUB }

    val title = when (page) {
        SPage.HUB -> "Settings"
        SPage.BROWSER -> "Browser & appearance"
        SPage.TEAM -> "Agent team"
        SPage.PROMPT -> "AI instructions & personal data"
        SPage.PROVIDERS -> "Providers & models"
        SPage.MCP -> "MCP servers"
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { if (page == SPage.HUB) onExit() else page = SPage.HUB }) {
                Text("← Back")
            }
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.weight(1f)) {
            when (page) {
                SPage.HUB -> Hub { page = it }
                SPage.BROWSER -> BrowserSettingsScreen(vm)
                SPage.TEAM -> TeamSettingsScreen(vm)
                SPage.PROMPT -> SettingsScreen(vm)
                SPage.PROVIDERS -> ProvidersScreen(vm)
                SPage.MCP -> McpScreen(vm)
            }
        }
    }
}

@Composable
private fun Hub(go: (SPage) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HubRow(
            "Browser & appearance",
            "Homepage, search engine, theme, accent colour, start-page shortcuts",
        ) { go(SPage.BROWSER) }
        HubRow(
            "Agent team",
            "One agent, or a planner + executor + checker with their own models and instructions",
        ) { go(SPage.TEAM) }
        HubRow(
            "AI instructions & personal data",
            "System prompt, and the placeholders for email, password, phone, card...",
        ) { go(SPage.PROMPT) }
        HubRow(
            "Providers & models",
            "API keys and fallback routes between providers",
        ) { go(SPage.PROVIDERS) }
        HubRow(
            "MCP servers",
            "Extra tools for the AI",
        ) { go(SPage.MCP) }
    }
}

@Composable
private fun HubRow(title: String, sub: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
