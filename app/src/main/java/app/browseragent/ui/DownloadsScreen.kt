package app.browseragent.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.browseragent.browser.BrowserController
import app.browseragent.browser.DownloadItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private fun human(b: Long): String = when {
    b < 1024L -> b.toString() + " B"
    b < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", b / 1024.0)
    b < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
    else -> String.format(Locale.US, "%.2f GB", b / 1073741824.0)
}

private fun fileUri(ctx: Context, path: String): Uri =
    FileProvider.getUriForFile(ctx, ctx.packageName + ".files", File(path))

private fun openFile(ctx: Context, item: DownloadItem) {
    try {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(fileUri(ctx, item.path), item.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(ctx, "No app can open this file", Toast.LENGTH_SHORT).show()
    }
}

private fun shareFile(ctx: Context, item: DownloadItem) {
    try {
        val send = Intent(Intent.ACTION_SEND)
            .setType(item.mime)
            .putExtra(Intent.EXTRA_STREAM, fileUri(ctx, item.path))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Share file"))
    } catch (e: Exception) {
        Toast.makeText(ctx, "Could not share this file", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun DownloadRow(
    item: DownloadItem,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val sub = when (item.state) {
        "running" ->
            if (item.total > 0) {
                human(item.done) + " of " + human(item.total) + "  \u00b7  " + (item.done * 100 / item.total) + "%"
            } else {
                human(item.done)
            }
        "done" -> "Done  \u00b7  " + human(item.done) + "  \u00b7  " + hostOf(item.url)
        "canceled" -> "Canceled"
        else -> "Failed: " + item.error
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
        Text(
            sub,
            style = MaterialTheme.typography.bodySmall,
            color = if (item.state == "failed") cs.error else cs.onSurfaceVariant,
        )
        if (item.state == "running") {
            if (item.total > 0) {
                LinearProgressIndicator(
                    progress = { (item.done.toFloat() / item.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            when (item.state) {
                "running" -> TextButton(onClick = onCancel) { Text("Cancel") }
                "done" -> {
                    TextButton(onClick = onOpen) { Text("Open") }
                    TextButton(onClick = onShare) { Text("Share") }
                    TextButton(onClick = onSave) { Text("Save to\u2026") }
                    TextButton(onClick = onDelete) { Text("Delete") }
                }
                else -> TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

/** The list of downloaded files: open, share, copy to another place, or delete them. */
@Composable
internal fun DownloadsScreen(browser: BrowserController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val d = browser.downloads
    var saveTarget by remember { mutableStateOf<DownloadItem?>(null) }

    // Lets the user pick any folder or app (Drive, SD card...) to keep a copy of the file.
    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val item = saveTarget
        if (uri != null && item != null) {
            scope.launch(Dispatchers.IO) {
                val ok = runCatching {
                    ctx.contentResolver.openOutputStream(uri)?.use { out ->
                        File(item.path).inputStream().use { it.copyTo(out) }
                    }
                }.isSuccess
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, if (ok) "Saved" else "Could not save the file", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { d.clearList() }) { Text("Clear list (keeps files)") }
        }
        if (d.items.isEmpty()) {
            Text(
                "No downloads yet. Files you download from websites appear here.",
                Modifier.padding(16.dp),
            )
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(d.items, key = { it.id }) { item ->
                DownloadRow(
                    item = item,
                    onOpen = { openFile(ctx, item) },
                    onShare = { shareFile(ctx, item) },
                    onSave = {
                        saveTarget = item
                        saver.launch(item.name)
                    },
                    onDelete = { d.delete(item.id) },
                    onCancel = { d.cancel(item.id) },
                )
                HorizontalDivider()
            }
        }
    }
}
