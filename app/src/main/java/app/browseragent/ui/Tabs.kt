package app.browseragent.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.browseragent.browser.BrowserController
import app.browseragent.browser.PageError

/** The list of open tabs: tap one to switch, the cross closes it. */
@Composable
internal fun TabsScreen(browser: BrowserController, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (browser.newTab()) {
                        onDone()
                    } else {
                        Toast.makeText(ctx, "Tab limit reached", Toast.LENGTH_SHORT).show()
                    }
                },
            ) { Text("+ New tab") }
            OutlinedButton(
                onClick = {
                    browser.closeAllTabs()
                    onDone()
                },
            ) { Text("Close all") }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(browser.tabs) { i, t ->
                val selected = i == browser.currentIndex
                val label = t.title.ifBlank { if (t.isHome) "Start page" else shortUrl(t.url) }
                val sub = if (t.isHome) "New tab" else hostOf(t.url)
                Card(
                    Modifier.fillMaxWidth().clickable {
                        browser.selectTab(i)
                        onDone()
                    },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) cs.primaryContainer else cs.surfaceVariant,
                    ),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            Text(
                                sub,
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        TextButton(onClick = { browser.closeTab(i) }) { Text("\u2715") }
                    }
                }
            }
        }
    }
}

/** Shown instead of a page that could not be opened or does not exist (404). */
@Composable
internal fun ErrorPage(browser: BrowserController, err: PageError) {
    val cs = MaterialTheme.colorScheme
    val notFound = err.kind == "notfound"

    Column(
        Modifier.fillMaxSize().background(cs.background).verticalScroll(rememberScrollState()).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(if (notFound) "404" else "!", fontSize = 72.sp, fontWeight = FontWeight.Bold, color = cs.primary)
        Spacer(Modifier.height(8.dp))
        Text(
            if (notFound) "Page not found" else "Can't open this page",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (notFound) {
                "The server says this page does not exist. The address may be wrong, or the page was moved or removed."
            } else {
                "The site could not be reached. Check your connection and the address, then try again."
            },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            shortUrl(err.url),
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = { browser.reload() }) { Text("Try again") }
        OutlinedButton(onClick = { browser.showHome() }) { Text("Start page") }
        if (notFound) {
            TextButton(onClick = { browser.dismissError() }) { Text("Show the site's own page") }
        }
    }
}
