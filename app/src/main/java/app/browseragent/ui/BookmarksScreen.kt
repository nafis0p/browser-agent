package app.browseragent.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.browseragent.browser.Bookmark
import app.browseragent.browser.Bookmarks
import app.browseragent.browser.BrowserController

/** The bookmarks: search, open, open in a new tab, change, copy the link, delete. */
@Composable
internal fun BookmarksScreen(browser: BrowserController, onOpen: () -> Unit) {
    val ctx: Context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val store = remember { Bookmarks.get(ctx) }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Bookmark?>(null) }
    val shown = store.items.filter {
        query.isBlank() || it.title.contains(query, ignoreCase = true) || it.url.contains(query, ignoreCase = true)
    }
    val here = if (browser.isHome || browser.pageError != null) "" else browser.url

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Search bookmarks") },
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        if (here.isNotBlank() && !store.isBookmarked(here)) {
            OutlinedButton(
                onClick = {
                    store.add(browser.current.title, here)
                    Toast.makeText(ctx, "Bookmarked", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text("\u2606  Bookmark the page you are on") }
        }
        if (shown.isEmpty()) {
            Text(
                if (store.items.isEmpty()) {
                    "No bookmarks yet. Open a page and choose Bookmark this page in the menu."
                } else {
                    "No bookmark matches."
                },
                Modifier.padding(16.dp),
            )
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(shown, key = { it.id }) { b ->
                var menu by remember { mutableStateOf(false) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            browser.load(b.url)
                            onOpen()
                        }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("\u2605", Modifier.padding(end = 12.dp), color = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(b.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        Text(
                            hostOf(b.url),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box {
                        TextButton(onClick = { menu = true }) { Text("\u22ee") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            MenuRow("Open in a new tab") {
                                menu = false
                                if (browser.newTab(b.url)) {
                                    onOpen()
                                } else {
                                    Toast.makeText(ctx, "Tab limit reached", Toast.LENGTH_SHORT).show()
                                }
                            }
                            MenuRow("Change\u2026") {
                                menu = false
                                editing = b
                            }
                            MenuRow("Copy link") {
                                menu = false
                                clipboard.setText(AnnotatedString(b.url))
                                Toast.makeText(ctx, "Link copied", Toast.LENGTH_SHORT).show()
                            }
                            MenuRow("Delete") {
                                menu = false
                                store.remove(b.id)
                            }
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }

    val target = editing
    if (target != null) {
        var title by remember(target.id) { mutableStateOf(target.title) }
        var address by remember(target.id) { mutableStateOf(target.url) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Change bookmark") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        singleLine = true,
                        label = { Text("Name") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        singleLine = true,
                        label = { Text("Address") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        store.edit(target.id, title, address)
                        editing = null
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}
