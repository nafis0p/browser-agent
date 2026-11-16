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
import app.browseragent.data.AppPrefs

private enum class SPage { HUB, BROWSER, AI, PROVIDERS, FRAMEWORK, PROMPT, MCP, PRIVACY }

/** Where Back goes: AI sub-pages return to "AI settings", the rest to the main list. */
private fun parentOf(p: SPage): SPage = when (p) {
    SPage.HUB -> SPage.HUB
    SPage.BROWSER, SPage.AI -> SPage.HUB
    else -> SPage.AI
}

/**
 * All settings pages live here.
 * Settings
 *   Browser & appearance
 *   AI settings
 *     Providers & models / Agent framework / Instructions & personal data / MCP servers / Behaviour & privacy
 */
@Composable
fun SettingsNav(vm: AgentViewModel, prefs: AppPrefs, onExit: () -> Unit) {
    var page by remember { mutableStateOf(SPage.HUB) }
    BackHandler(enabled = page != SPage.HUB) { page = parentOf(page) }

    val title = when (page) {
        SPage.HUB -> "Settings"
        SPage.BROWSER -> "Browser & appearance"
        SPage.AI -> "AI settings"
        SPage.PROVIDERS -> "AI settings \u203a Providers & models"
        SPage.FRAMEWORK -> "AI settings \u203a Agent framework"
        SPage.PROMPT -> "AI settings \u203a Instructions & data"
        SPage.MCP -> "AI settings \u203a MCP servers"
        SPage.PRIVACY -> "AI settings \u203a Behaviour & privacy"
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { if (page == SPage.HUB) onExit() else page = parentOf(page) }) {
                Text("\u2190 Back")
            }
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.weight(1f)) {
            when (page) {
                SPage.HUB -> MainHub { page = it }
                SPage.AI -> AiHub { page = it }
                SPage.BROWSER -> BrowserSettingsScreen(vm, prefs)
                SPage.PROVIDERS -> ProvidersScreen(vm)
                SPage.FRAMEWORK -> TeamSettingsScreen(vm)
                SPage.PROMPT -> SettingsScreen(vm)
                SPage.MCP -> McpScreen(vm)
                SPage.PRIVACY -> AiPrivacyScreen(vm)
            }
        }
    }
}

@Composable
private fun MainHub(go: (SPage) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HubRow(
            "Browser & appearance",
            "Bar positions, homepage, search engines, theme, accent colour, start-page shortcuts",
        ) { go(SPage.BROWSER) }
        HubRow(
            "AI settings",
            "Providers, agent framework, instructions, tools, behaviour and privacy",
        ) { go(SPage.AI) }
    }
}

@Composable
private fun AiHub(go: (SPage) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HubRow(
            "Providers & models",
            "API keys and the fallback routes between providers",
        ) { go(SPage.PROVIDERS) }
        HubRow(
            "Agent framework",
            "One agent, or a team: planner + executor + checker, each with its own model and instructions",
        ) { go(SPage.FRAMEWORK) }
        HubRow(
            "Instructions & personal data",
            "System prompt, and the placeholders for email, password, phone, card...",
        ) { go(SPage.PROMPT) }
        HubRow(
            "MCP servers",
            "Extra tools for the AI",
        ) { go(SPage.MCP) }
        HubRow(
            "Behaviour & privacy",
            "Turn the AI on or off, and choose whether it is told what you do by hand",
        ) { go(SPage.PRIVACY) }
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
