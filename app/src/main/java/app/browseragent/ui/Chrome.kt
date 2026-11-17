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

/** The rounded address field. The caller decides how wide it is through the modifier. */
@Composable
internal fun AddressBar(browser: BrowserController, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    val cs = MaterialTheme.colorScheme
    var editing by remember { mutableStateOf(false) }
    var field by remember { mutableStateOf(TextFieldValue("")) }
    val shown = if (editing) field else TextFieldValue(if (browser.isHome) "" else shortUrl(browser.url))

    Surface(
        shape = RoundedCornerShape(26.dp),
        color = cs.surfaceVariant,
        modifier = modifier.padding(horizontal = 6.dp, vertical = 6.dp).height(48.dp),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!browser.isHome && browser.url.startsWith("https")) {
                Text("\ud83d\udd12", fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
            }
            BasicTextField(
                value = shown,
                onValueChange = { field = it },
                singleLine = true,
                textStyle = TextStyle(color = cs.onSurface, fontSize = 16.sp),
                cursorBrush = SolidColor(cs.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, keyboardType = KeyboardType.Uri),
                keyboardActions = KeyboardActions(
                    onGo = {
                        if (field.text.isNotBlank()) browser.load(field.text)
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
                modifier = Modifier.weight(1f).onFocusChanged { st ->
                    if (st.isFocused) {
                        editing = true
                        val t = if (browser.isHome) "" else browser.url
                        field = TextFieldValue(t, TextRange(0, t.length))
                    } else {
                        editing = false
                    }
                },
            )
            if (!browser.isHome) {
                Text(
                    if (browser.isLoading) "\u2715" else "\u27f3",
                    fontSize = 20.sp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { if (browser.isLoading) browser.stopLoading() else browser.reload() }
                        .padding(10.dp),
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
private fun TabButton(count: Int, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(46.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(24.dp).border(2.dp, cs.onSurface, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(count.toString(), fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                modifier = Modifier.size(size - 12.dp),
                color = cs.onPrimary,
                strokeWidth = 2.dp,
            )
        }
        Text(if (open) "\u2715" else "AI", color = cs.onPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
internal fun MenuRow(label: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = onClick)
}

/** Bottom bar: back, forward, AI, start page, tabs, menu. The menu opens the side panel. */
@Composable
internal fun NavBar(
    vm: AgentViewModel,
    browser: BrowserController,
    aiOpen: Boolean,
    onToggleAi: () -> Unit,
    go: (Page) -> Unit,
    onMenu: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            NavButton("\u2190", browser.canGoBack()) { browser.back() }
            NavButton("\u2192", browser.canForward && !browser.isHome) { browser.forward() }
            if (vm.prefs.agentEnabled) AiButton(aiOpen, vm.running, 54.dp, onToggleAi)
            NavButton("\u2302", !browser.isHome) { browser.showHome() }
            TabButton(browser.tabs.size) { go(Page.TABS) }
            NavButton("\u22ee", true) { onMenu() }
        }
    }
}

/** Top bar when the navigation bar is at the top: back, forward, address, AI, menu in one row. */
@Composable
internal fun TopBar(
    vm: AgentViewModel,
    browser: BrowserController,
    aiOpen: Boolean,
    onToggleAi: () -> Unit,
    onMenu: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavButton("\u2190", browser.canGoBack(), 38.dp) { browser.back() }
            NavButton("\u2192", browser.canForward && !browser.isHome, 38.dp) { browser.forward() }
            if (browser.isHome) {
                Spacer(Modifier.weight(1f))
            } else {
                AddressBar(browser, Modifier.weight(1f))
            }
            if (vm.prefs.agentEnabled) AiButton(aiOpen, vm.running, 42.dp, onToggleAi)
            NavButton("\u22ee", true, 38.dp) { onMenu() }
        }
    }
}
