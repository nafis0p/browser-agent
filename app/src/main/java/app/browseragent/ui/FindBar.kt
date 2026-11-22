package app.browseragent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.browseragent.browser.BrowserController

/** The find in page bar: type a word, see how many matches there are, go to the next or previous. */
@Composable
internal fun FindBar(browser: BrowserController) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val count = when {
        text.isBlank() -> ""
        browser.findMissing -> "0/0"
        browser.findTotal < 0 -> browser.findCurrent.toString() + "/?"
        else -> browser.findCurrent.toString() + "/" + browser.findTotal
    }

    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    browser.find(it)
                },
                singleLine = true,
                isError = text.isNotBlank() && browser.findMissing,
                placeholder = { Text("Find in page") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { browser.findStep(false) }),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            if (count.isNotEmpty()) {
                Text(count, Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.labelLarge)
            }
            TextButton(onClick = { browser.findStep(true) }, enabled = text.isNotBlank()) { Text("\u25b2") }
            TextButton(onClick = { browser.findStep(false) }, enabled = text.isNotBlank()) { Text("\u25bc") }
            TextButton(onClick = { browser.closeFind() }) { Text("\u2715") }
        }
    }
}
