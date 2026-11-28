package app.browseragent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.browseragent.agent.AgentViewModel
import app.browseragent.browser.BrowserController

/** The start page of a private tab: dark, with a short explanation and a search box. */
@Composable
internal fun PrivateStartPage(vm: AgentViewModel, browser: BrowserController) {
    var query by remember { mutableStateOf("") }
    val suggestions = suggestionList(vm, query, true)

    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFB39DFF))) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(28.dp))
                Text("\ud83d\udd76\ufe0f", fontSize = 56.sp)
                Spacer(Modifier.height(8.dp))
                Text("Private tab", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
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
                Spacer(Modifier.height(24.dp))
                Text(
                    "Pages you open here are not added to your history. Cookies and site data are deleted when the private tabs are closed, and the app does not keep these tabs for the next start.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Downloads, bookmarks and saved pages stay on your phone. Your internet provider and the sites you visit can still see your visit. The AI does not work in private tabs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
