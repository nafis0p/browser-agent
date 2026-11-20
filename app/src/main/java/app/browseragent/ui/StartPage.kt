package app.browseragent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.browseragent.agent.AgentViewModel
import app.browseragent.browser.BrowserController
import app.browseragent.data.Shortcut
import java.util.Calendar

private fun greeting(): String {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return if (h < 12) "Good morning" else if (h < 18) "Good afternoon" else "Good evening"
}

@Composable
internal fun StartPage(
    vm: AgentViewModel,
    browser: BrowserController,
    showAi: Boolean,
    onAskAi: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    val recent = vm.history.entries.distinctBy { it.url }.take(5)
    val suggestions = suggestionList(vm, query, true)

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
        if (suggestions.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) {
                SuggestionRows(suggestions) { value -> browser.load(value) }
            }
        }
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

        if (showAi) {
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
