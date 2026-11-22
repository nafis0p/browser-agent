package app.browseragent.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.browseragent.browser.Amo
import app.browseragent.browser.AmoAddon
import app.browseragent.browser.BrowserController
import app.browseragent.browser.ExtItem
import kotlinx.coroutines.delay
import org.mozilla.geckoview.GeckoView

private fun usersText(n: Long): String = when {
    n >= 1_000_000L -> String.format(java.util.Locale.US, "%.1fM users", n / 1_000_000.0)
    n >= 1_000L -> (n / 1_000L).toString() + "k users"
    n > 0L -> n.toString() + " users"
    else -> ""
}

/** A permission in plain words. */
private fun describePermission(p: String): String = when (p) {
    "webRequest" -> "See the requests that pages make"
    "webRequestBlocking" -> "Block or change the requests that pages make"
    "declarativeNetRequest", "declarativeNetRequestWithHostAccess" -> "Block requests with rules"
    "tabs" -> "See the addresses and titles of your tabs"
    "activeTab" -> "Use the page you are on when you tap it"
    "storage", "unlimitedStorage" -> "Keep its own data on your device"
    "cookies" -> "Read and change cookies"
    "history" -> "Read your browsing history"
    "bookmarks" -> "Read and change your bookmarks"
    "downloads" -> "Download files"
    "notifications" -> "Show notifications"
    "contextMenus" -> "Add entries to the long-press menu"
    "clipboardRead" -> "Read what you copied"
    "clipboardWrite" -> "Copy text for you"
    "webNavigation" -> "See how you move between pages"
    "management" -> "Manage other add-ons"
    "proxy" -> "Control how the browser connects"
    "privacy" -> "Change privacy settings"
    "nativeMessaging" -> "Talk to other apps on your device"
    "scripting" -> "Run scripts in pages"
    "alarms" -> "Run in the background on a timer"
    else -> p
}

private fun describeOrigin(o: String): String =
    if (o == "<all_urls>" || o == "*://*/*" || o == "http://*/*" || o == "https://*/*") {
        "Read and change your data on all websites"
    } else {
        "Read and change your data on " + o.substringAfter("://").substringBefore('/').removePrefix("*.")
    }

/** Firefox asks before an add-on is installed. This is where the user decides. */
@Composable
internal fun ExtensionPromptDialog(browser: BrowserController) {
    val p = browser.extensions.prompt ?: return
    val lines = (p.origins.map { describeOrigin(it) } + p.permissions.map { describePermission(it) }).distinct()
    AlertDialog(
        onDismissRequest = { browser.extensions.answerPrompt(false) },
        title = { Text("Add " + p.name + "?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (lines.isEmpty()) {
                    Text("It asks for no special permissions.")
                } else {
                    Text("It will be able to:")
                    for (l in lines) Text("\u2022 " + l, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "Only add extensions you trust.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { browser.extensions.answerPrompt(true) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = { browser.extensions.answerPrompt(false) }) { Text("Cancel") } },
    )
}

/**
 * The panel of an extension's button, full screen (for uBlock Origin: the power button for this
 * site, the blocked counters, the element picker). The page behind it is hidden meanwhile.
 */
@Composable
internal fun ExtensionPopupScreen(browser: BrowserController) {
    val popup = browser.extensions.popup ?: return
    BackHandler { browser.extensions.closePopup() }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { browser.extensions.closePopup() }) { Text("\u2715 Close") }
                Text(
                    popup.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            key(popup.session) {
                AndroidView(
                    factory = { ctx ->
                        GeckoView(ctx).also { it.setSession(popup.session) }
                    },
                    onRelease = { it.releaseSession() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun InstalledRow(item: ExtItem, browser: BrowserController, onOpenTab: () -> Unit) {
    var confirmRemove by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (item.version.isNotBlank()) {
                        Text(
                            "Version " + item.version,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(checked = item.enabled, onCheckedChange = { browser.extensions.setEnabled(item, it) })
            }
            if (item.description.isNotBlank()) {
                Text(
                    item.description,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row {
                val options = item.optionsUrl
                if (options != null && options.isNotBlank()) {
                    TextButton(
                        onClick = {
                            browser.newTab(options)
                            onOpenTab()
                        },
                    ) { Text("Settings") }
                }
                TextButton(onClick = { confirmRemove = true }) { Text("Remove") }
            }
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove " + item.name + "?") },
            text = { Text("The extension and its data are deleted from this browser.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemove = false
                        browser.extensions.uninstall(item)
                    },
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

/** Manage extensions: the installed ones, and two ways to add more. */
@Composable
internal fun ExtensionsScreen(browser: BrowserController, onGetMore: () -> Unit, onOpenTab: () -> Unit) {
    val ext = browser.extensions
    LaunchedEffect(Unit) { ext.refresh() }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onGetMore, modifier = Modifier.fillMaxWidth()) { Text("Find extensions for Android") }
        OutlinedButton(
            onClick = {
                browser.newTab("https://addons.mozilla.org/android/")
                onOpenTab()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Open addons.mozilla.org") }
        if (ext.installed.isEmpty()) {
            Text(
                "No extensions yet. An ad blocker such as uBlock Origin is a good first choice.",
                Modifier.padding(8.dp),
            )
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ext.installed, key = { it.id }) { item -> InstalledRow(item, browser, onOpenTab) }
        }
    }
}

/** Add-ons from Mozilla's add-on site that work on Android. */
@Composable
internal fun ExtensionStoreScreen(browser: BrowserController) {
    val ctx = LocalContext.current
    val ext = browser.extensions
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<AmoAddon>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) { ext.refresh() }
    LaunchedEffect(query) {
        loading = true
        if (query.isBlank()) {
            val blocker = Amo.bySlug("ublock-origin")
            val popular = Amo.popular()
            results = listOfNotNull(blocker) + popular.filter { it.slug != "ublock-origin" }
        } else {
            delay(400)
            results = Amo.search(query.trim())
        }
        loading = false
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Search extensions for Android") },
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        val busy = ext.busy
        if (busy != null) {
            Text("Adding " + busy + "...", Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.primary)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        } else if (results.isEmpty()) {
            Text("Nothing found. Check your connection or try another word.", Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(results, key = { it.slug }) { a ->
                val have = ext.isInstalled(a.guid)
                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(a.name, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val meta = listOf(usersText(a.users), if (a.slug == "ublock-origin") "Recommended ad blocker" else "")
                                .filter { it.isNotBlank() }
                                .joinToString("  \u00b7  ")
                            if (meta.isNotBlank()) {
                                Text(
                                    meta,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        if (have) {
                            OutlinedButton(onClick = {}, enabled = false) { Text("Added") }
                        } else {
                            Button(
                                enabled = busy == null,
                                onClick = {
                                    ext.install(a.xpiUrl, a.name) { err ->
                                        Toast.makeText(
                                            ctx,
                                            if (err == null) a.name + " added" else "Could not add: " + err,
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                },
                            ) { Text("Add") }
                        }
                    }
                    if (a.summary.isNotBlank()) {
                        Text(
                            a.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
