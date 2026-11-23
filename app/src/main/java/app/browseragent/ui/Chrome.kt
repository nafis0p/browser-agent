package app.browseragent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.browseragent.agent.AgentViewModel
import app.browseragent.browser.BrowserController

internal fun hostOf(u: String): String =
    u.substringAfter("://").substringBefore('/').removePrefix("www.")

internal fun shortUrl(u: String): String =
    u.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')

/** Thin page-load progress line (keeps its height so the layout does not jump). */
@Composable
internal fun ProgressLine(browser: BrowserController) {
    if (browser.isLoading && !browser.isHome) {
        LinearProgressIndicator(
            progress = { browser.progress / 100f },
            modifier = Modifier.fillMaxWidth().height(2.dp),
        )
    } else {
        Spacer(Modifier.height(2.dp))
    }
}

/**
 * What the address bar is doing: whether the user is editing it and the text in it. The page
 * shows the suggestions under the bar from this, and a suggestion can put its text into it.
 */
class AddressState {
    var editing by mutableStateOf(false)
    var field by mutableStateOf(TextFieldValue(""))
    val focusRequester = FocusRequester()

    /** Puts the text into the bar with the cursor at its end, ready for more words. */
    fun fill(text: String) {
        field = TextFieldValue(text, TextRange(text.length))
        runCatching { focusRequester.requestFocus() }
    }
}

/**
 * The rounded address field. The caller decides how wide it is through the modifier. While it is
 * edited it shows the whole address (selected, so typing replaces it); otherwise a short form.
 */
@Composable
internal fun AddressBar(
    browser: BrowserController,
    state: AddressState,
    modifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current
    val cs = MaterialTheme.colorScheme
    val editing = state.editing
    val shown = if (editing) state.field else TextFieldValue(if (browser.isHome) "" else shortUrl(browser.url))

    Surface(
        shape = RoundedCornerShape(26.dp),
        color = cs.surfaceVariant,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 6.dp).height(46.dp),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!editing && !browser.isHome && browser.url.startsWith("https")) {
                Text("\ud83d\udd12", fontSize = 12.sp)
                Spacer(Modifier.width(6.dp))
            }
            BasicTextField(
                value = shown,
                onValueChange = { state.field = it },
                singleLine = true,
                textStyle = TextStyle(color = cs.onSurface, fontSize = 16.sp),
                cursorBrush = SolidColor(cs.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, keyboardType = KeyboardType.Uri),
                keyboardActions = KeyboardActions(
                    onGo = {
                        if (state.field.text.isNotBlank()) browser.load(state.field.text)
                        focus.clearFocus()
                    },
                ),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (shown.text.isEmpty()) {
                            Text("Search or type URL", color = cs.onSurfaceVariant, fontSize = 16.sp)
                        }
                        inner()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(state.focusRequester)
                    .onFocusChanged { st ->
                        if (st.isFocused) {
                            if (!state.editing) {
                                state.editing = true
                                val t = if (browser.isHome) "" else browser.url
                                state.field = TextFieldValue(t, TextRange(0, t.length))
                            }
                        } else {
                            state.editing = false
                        }
                    },
            )
            if (editing) {
                if (state.field.text.isNotEmpty()) {
                    Text(
                        "\u2715",
                        fontSize = 18.sp,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { state.field = TextFieldValue("") }
                            .padding(8.dp),
                    )
                }
            } else if (!browser.isHome) {
                Text(
                    if (browser.isLoading) "\u2715" else "\u27f3",
                    fontSize = 20.sp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { if (browser.isLoading) browser.stopLoading() else browser.reload() }
                        .padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun NavButton(label: String, enabled: Boolean, size: Dp = 46.dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 22.sp, modifier = Modifier.alpha(if (enabled) 1f else 0.35f))
    }
}

@Composable
private fun TabButton(count: Int, size: Dp, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(size).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(22.dp).border(2.dp, cs.onSurface, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(count.toString(), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AiButton(open: Boolean, busy: Boolean, size: Dp, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(size).clip(CircleShape).background(cs.primary).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(size - 10.dp),
                color = cs.onPrimary,
                strokeWidth = 2.dp,
            )
        }
        Text(if (open) "\u2715" else "AI", color = cs.onPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

@Composable
internal fun MenuRow(label: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = onClick)
}

/**
 * The one bar of the browser, at the top: home, address bar, AI button, tabs, menu.
 * While the address bar is edited it takes the whole width: a back arrow replaces the home
 * button and the other buttons are hidden. The menu opens the side panel (which also has back,
 * forward and reload).
 */
@Composable
internal fun TopBar(
    vm: AgentViewModel,
    browser: BrowserController,
    aiOpen: Boolean,
    onToggleAi: () -> Unit,
    go: (Page) -> Unit,
    onMenu: () -> Unit,
    address: AddressState,
) {
    val focus = LocalFocusManager.current
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (address.editing) {
                NavButton("\u2190", true, 38.dp) { focus.clearFocus() }
            } else {
                NavButton("\u2302", !browser.isHome, 38.dp) { browser.showHome() }
            }
            if (browser.isHome) {
                Spacer(Modifier.weight(1f))
            } else {
                AddressBar(browser, address, Modifier.weight(1f))
            }
            if (!address.editing) {
                if (vm.prefs.agentEnabled) AiButton(aiOpen, vm.running, 42.dp, onToggleAi)
                TabButton(browser.tabs.size, 38.dp) { go(Page.TABS) }
                NavButton("\u22ee", true, 38.dp) { onMenu() }
            }
        }
    }
}
