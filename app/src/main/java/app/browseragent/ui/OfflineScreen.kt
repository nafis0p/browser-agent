package app.browseragent.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.core.content.FileProvider
import app.browseragent.browser.BrowserController
import app.browseragent.browser.OfflineItem
import app.browseragent.browser.OfflinePages
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private fun sizeText(b: Long): String = when {
    b < 1024L * 1024L -> (b / 1024L).toString() + " KB"
    else -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
}

/**
 * Saves the page of the shown tab for offline reading: one HTML file with its styles, images and
 * fonts inside. A message tells the user how it went.
 */
internal suspend fun savePageOffline(ctx: Context, browser: BrowserController) {
    val tab = browser.current
    if (tab.isHome || tab.pageError != null) {
        Toast.makeText(ctx, "There is no page to save", Toast.LENGTH_SHORT).show()
        return
    }
    val pages = OfflinePages.get(ctx)
    val (id, file) = pages.newFile()
    Toast.makeText(ctx, "Saving the page\u2026", Toast.LENGTH_SHORT).show()
    val meta = tab.captureOffline(file)
    if (meta != null && meta.optBoolean("ok") && file.length() > 0L) {
        val address = meta.optString("url")
        val title = meta.optString("title").ifBlank { shortUrl(address) }
        pages.add(OfflineItem(id, title, address, System.currentTimeMillis(), file.absolutePath, file.length()))
        val skipped = meta.optInt("skipped", 0)
        val extra = if (skipped > 0) " (" + skipped + " files could not be included)" else ""
        Toast.makeText(ctx, "Saved for offline reading" + extra, Toast.LENGTH_LONG).show()
    } else {
        file.delete()
        val why = meta?.optString("msg").orEmpty()
        Toast.makeText(ctx, "Could not save this page" + (if (why.isNotBlank()) ": " + why else ""), Toast.LENGTH_LONG).show()
    }
}

/** The pages saved for offline reading: open, share, delete. */
@Composable
internal fun OfflineScreen(browser: BrowserController, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val pages = remember { OfflinePages.get(ctx) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    var confirm by remember { mutableStateOf<OfflineItem?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        if (pages.items.isEmpty()) {
            Text(
                "No saved pages yet. Open a page and choose Save in the menu: it is kept as a file you can read without a connection.",
                Modifier.padding(16.dp),
            )
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(pages.items, key = { it.id }) { item ->
                var menu by remember { mutableStateOf(false) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            browser.load(pages.fileUrl(item))
                            onOpen()
                        }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        Text(
                            hostOf(item.url) + "  \u00b7  " + fmt.format(Date(item.time)) + "  \u00b7  " + sizeText(item.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box {
                        TextButton(onClick = { menu = true }) { Text("\u22ee") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            MenuRow("Open the original page") {
                                menu = false
                                browser.load(item.url)
                                onOpen()
                            }
                            MenuRow("Copy original link") {
                                menu = false
                                clipboard.setText(AnnotatedString(item.url))
                                Toast.makeText(ctx, "Link copied", Toast.LENGTH_SHORT).show()
                            }
                            MenuRow("Share the file") {
                                menu = false
                                try {
                                    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", File(item.file))
                                    val send = Intent(Intent.ACTION_SEND)
                                        .setType("text/html")
                                        .putExtra(Intent.EXTRA_STREAM, uri)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    ctx.startActivity(Intent.createChooser(send, "Share saved page"))
                                } catch (e: Exception) {
                                    Toast.makeText(ctx, "Could not share this file", Toast.LENGTH_SHORT).show()
                                }
                            }
                            MenuRow("Delete") {
                                menu = false
                                confirm = item
                            }
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }

    val ask = confirm
    if (ask != null) {
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Delete the saved page?") },
            text = { Text(ask.title) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pages.delete(ask.id)
                        confirm = null
                    },
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}
