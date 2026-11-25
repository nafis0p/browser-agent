package app.browseragent.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.browseragent.agent.AgentViewModel
import app.browseragent.browser.Bookmarks
import app.browseragent.browser.BrowserController
import app.browseragent.browser.OfflinePages
import app.browseragent.data.Shortcut
import kotlinx.coroutines.launch

/** One of the four round actions in the top row of the menu. */
@Composable
private fun TopAction(
    glyph: String,
    label: String,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp)
            .alpha(if (enabled) 1f else 0.38f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(glyph, fontSize = 22.sp)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/** A menu entry. A disabled entry is greyed out and shows a short hint such as Soon. */
@Composable
private fun SideRow(
    label: String,
    enabled: Boolean = true,
    hint: String = "",
    onClick: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            Modifier.weight(1f),
            color = if (enabled) cs.onSurface else cs.onSurface.copy(alpha = 0.38f),
        )
        if (hint.isNotEmpty()) {
            Text(hint, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant.copy(alpha = 0.7f))
        }
    }
}

/** A menu entry that opens and closes a group of entries below it. */
@Composable
private fun SideAccordion(
    title: String,
    hint: String = "",
    content: @Composable ColumnScope.() -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f))
            if (hint.isNotEmpty()) {
                Text(hint, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant.copy(alpha = 0.7f))
                Spacer(Modifier.width(8.dp))
            }
            Text(if (open) "\u25be" else "\u25b8", color = cs.onSurfaceVariant)
        }
        if (open) {
            Column(Modifier.padding(start = 16.dp)) { content() }
        }
    }
}

/**
 * The browser menu: a panel that slides in from the right (about 78 percent of the screen, at most
 * 340 dp wide). Entries that are not built yet are shown greyed out, so the layout is final.
 */
@Composable
internal fun SideMenu(
    open: Boolean,
    onClose: () -> Unit,
    vm: AgentViewModel,
    browser: BrowserController,
    aiOpen: Boolean,
    onToggleAi: () -> Unit,
    go: (Page) -> Unit,
) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    // The list of extensions may have changed (for example one was added from the add-on site).
    LaunchedEffect(open) {
        if (open) browser.extensions.refresh()
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = open, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClose,
                    ),
            )
        }
        AnimatedVisibility(
            visible = open,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.78f)
                    .widthIn(max = 340.dp)
                    .pointerInput(Unit) {},
                tonalElevation = 6.dp,
            ) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    // Top row: back, forward, save the page for offline reading, reload or stop.
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
                        TopAction("\u2190", "Back", browser.canGoBack(), Modifier.weight(1f)) {
                            onClose()
                            browser.back()
                        }
                        TopAction("\u2192", "Forward", browser.canForward && !browser.isHome, Modifier.weight(1f)) {
                            onClose()
                            browser.forward()
                        }
                        TopAction(
                            "\ud83d\udcbe",
                            "Save",
                            !browser.isHome && browser.pageError == null,
                            Modifier.weight(1f),
                        ) {
                            onClose()
                            scope.launch { savePageOffline(ctx, browser) }
                        }
                        TopAction(
                            if (browser.isLoading) "\u2715" else "\u27f3",
                            if (browser.isLoading) "Stop" else "Reload",
                            !browser.isHome,
                            Modifier.weight(1f),
                        ) {
                            onClose()
                            if (browser.isLoading) browser.stopLoading() else browser.reload()
                        }
                    }
                    HorizontalDivider()

                    SideRow("New tab") {
                        onClose()
                        if (!browser.newTab()) {
                            Toast.makeText(ctx, "Tab limit reached", Toast.LENGTH_SHORT).show()
                        }
                    }
                    SideRow("New private tab", enabled = false, hint = "Soon")
                    SideRow("Tabs (" + browser.tabs.size + ")") {
                        onClose()
                        go(Page.TABS)
                    }
                    HorizontalDivider()

                    // Bookmark or un-bookmark the page that is shown, and the list of bookmarks.
                    val marks = Bookmarks.get(ctx)
                    val canMark = !browser.isHome && browser.pageError == null
                    val marked = canMark && marks.isBookmarked(browser.url)
                    SideRow(if (marked) "\u2605 Remove bookmark" else "\u2606 Bookmark this page", enabled = canMark) {
                        onClose()
                        val now = marks.toggle(browser.current.title, browser.url)
                        Toast.makeText(ctx, if (now) "Bookmarked" else "Bookmark removed", Toast.LENGTH_SHORT).show()
                    }
                    SideRow(if (marks.items.isNotEmpty()) "Bookmarks (" + marks.items.size + ")" else "Bookmarks") {
                        onClose()
                        go(Page.BOOKMARKS)
                    }
                    SideRow("History") {
                        onClose()
                        go(Page.HISTORY)
                    }
                    val running = browser.downloads.runningCount()
                    SideRow(if (running > 0) "Downloads (" + running + " running)" else "Downloads") {
                        onClose()
                        go(Page.DOWNLOADS)
                    }
                    val saved = OfflinePages.get(ctx).items.size
                    SideRow(if (saved > 0) "Offline pages (" + saved + ")" else "Offline pages") {
                        onClose()
                        go(Page.OFFLINE)
                    }
                    HorizontalDivider()

                    SideRow("Find in page", enabled = !browser.isHome) {
                        onClose()
                        browser.openFind()
                    }
                    SideRow("Print", enabled = false, hint = "Soon")
                    SideRow("Share link", enabled = !browser.isHome) {
                        onClose()
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, browser.url)
                        }
                        ctx.startActivity(Intent.createChooser(send, "Share link"))
                    }
                    SideRow("Copy link", enabled = !browser.isHome) {
                        onClose()
                        clipboard.setText(AnnotatedString(browser.url))
                        Toast.makeText(ctx, "Link copied", Toast.LENGTH_SHORT).show()
                    }
                    SideRow("Pin page to start page", enabled = !browser.isHome) {
                        onClose()
                        val u = browser.url
                        if (u.startsWith("http")) {
                            vm.saveShortcuts(vm.shortcuts + Shortcut(hostOf(u), u))
                            Toast.makeText(ctx, "Pinned", Toast.LENGTH_SHORT).show()
                        }
                    }
                    SideRow(if (browser.desktopMode) "\u2713 Desktop site" else "Desktop site", enabled = !browser.isHome) {
                        onClose()
                        browser.setDesktop(!browser.desktopMode)
                    }
                    SideRow("Page zoom", enabled = false, hint = "Soon")
                    HorizontalDivider()

                    if (vm.prefs.agentEnabled) {
                        SideRow("New AI chat") {
                            onClose()
                            vm.newChat()
                            if (!aiOpen) onToggleAi()
                        }
                        SideRow("AI chat history") {
                            onClose()
                            go(Page.CHATS)
                        }
                        HorizontalDivider()
                    }

                    // Each installed extension is listed here: tap it to open its own panel for the
                    // page you are on (for uBlock Origin: this site's switch, what was blocked, the
                    // element picker).
                    SideAccordion("Extensions") {
                        val active = browser.extensions.installed.filter { it.enabled }
                        if (active.isEmpty()) {
                            SideRow("No extensions yet", enabled = false)
                        }
                        for (item in active) {
                            SideRow(item.name) {
                                onClose()
                                if (!browser.extensions.openAction(item.id)) {
                                    Toast.makeText(ctx, item.name + " has no button", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        SideRow("Manage extensions") {
                            onClose()
                            go(Page.EXTENSIONS)
                        }
                    }
                    HorizontalDivider()

                    SideRow("Settings") {
                        onClose()
                        go(Page.SETTINGS)
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}
