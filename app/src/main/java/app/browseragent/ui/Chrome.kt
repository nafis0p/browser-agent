package app.browseragent.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.browseragent.agent.AgentViewModel
import app.browseragent.browser.BrowserController
import app.browseragent.data.Shortcut

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

@Composable
internal fun AddressBar(browser: BrowserController) {
    val focus = LocalFocusManager.current
    val cs = MaterialTheme.colorScheme
    var editing by remember { mutableStateOf(false) }
    var field by remember { mutableStateOf(TextFieldValue("")) }
    val shown = if (editing) field else TextFieldValue(if (browser.isHome) "" else shortUrl(browser.url))

    Surface(
        shape = RoundedCornerShape(26.dp),
        color = cs.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).height(48.dp),
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
                    if (browser.isLoading) "✕" else "⟳",
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
private fun NavButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(46.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 22.sp, modifier = Modifier.alpha(if (enabled) 1f else 0.35f))
    }
}

@Composable
private fun AiButton(open: Boolean, busy: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(54.dp).clip(CircleShape).background(cs.primary).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(42.dp),
                color = cs.onPrimary,
                strokeWidth = 2.dp,
            )
        }
        Text(if (open) "✕" else "AI", color = cs.onPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
internal fun MenuRow(label: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = onClick)
}

/** Back, forward, the AI button, home and the menu. Placed at the top or the bottom. */
@Composable
internal fun NavBar(
    vm: AgentViewModel,
    browser: BrowserController,
    aiOpen: Boolean,
    onToggleAi: () -> Unit,
    go: (Page) -> Unit,
) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }

    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            NavButton("←", browser.canGoBack()) { browser.back() }
            NavButton("→", browser.canForward && !browser.isHome) { browser.forward() }
            AiButton(aiOpen, vm.running, onToggleAi)
            NavButton("⌂", !browser.isHome) { browser.showHome() }
            Box {
                NavButton("⋮", true) { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuRow("New AI chat") {
                        menu = false
                        vm.newChat()
                        if (!aiOpen) onToggleAi()
                    }
                    MenuRow("AI chat history") {
                        menu = false
                        go(Page.CHATS)
                    }
                    MenuRow("Browsing history") {
                        menu = false
                        go(Page.HISTORY)
                    }
                    HorizontalDivider()
                    MenuRow("Pin page to start page") {
                        menu = false
                        val u = browser.url
                        if (!browser.isHome && u.startsWith("http")) {
                            vm.saveShortcuts(vm.shortcuts + Shortcut(hostOf(u), u))
                            Toast.makeText(ctx, "Pinned", Toast.LENGTH_SHORT).show()
                        }
                    }
                    MenuRow("Copy link") {
                        menu = false
                        if (!browser.isHome) {
                            clipboard.setText(AnnotatedString(browser.url))
                            Toast.makeText(ctx, "Link copied", Toast.LENGTH_SHORT).show()
                        }
                    }
                    MenuRow("Share link") {
                        menu = false
                        if (!browser.isHome) {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, browser.url)
                            }
                            ctx.startActivity(Intent.createChooser(send, "Share link"))
                        }
                    }
                    MenuRow(if (browser.desktopMode) "✓ Desktop site" else "Desktop site") {
                        menu = false
                        browser.setDesktop(!browser.desktopMode)
                    }
                    HorizontalDivider()
                    MenuRow("Settings") {
                        menu = false
                        go(Page.SETTINGS)
                    }
                }
            }
        }
    }
}
