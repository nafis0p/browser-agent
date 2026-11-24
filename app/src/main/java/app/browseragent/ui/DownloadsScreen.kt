package app.browseragent.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import app.browseragent.browser.DownloadItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.Locale

private fun human(b: Long): String = when {
    b < 1024L -> b.toString() + " B"
    b < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", b / 1024.0)
    b < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
    else -> String.format(Locale.US, "%.2f GB", b / 1073741824.0)
}

/** The address of the saved file: a content address for a chosen folder, otherwise a shared link. */
private fun fileUri(ctx: Context, item: DownloadItem): Uri =
    if (item.uri.isNotBlank()) {
        Uri.parse(item.uri)
    } else {
        FileProvider.getUriForFile(ctx, ctx.packageName + ".files", File(item.path))
    }

private fun openFile(ctx: Context, item: DownloadItem) {
    try {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(fileUri(ctx, item), item.mime)
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
            .putExtra(Intent.EXTRA_STREAM, fileUri(ctx, item))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Share file"))
    } catch (e: Exception) {
        Toast.makeText(ctx, "Could not share this file", Toast.LENGTH_SHORT).show()
    }
}

private fun openSaved(ctx: Context, item: DownloadItem): InputStream? =
    if (item.uri.isNotBlank()) {
        ctx.contentResolver.openInputStream(Uri.parse(item.uri))
    } else {
        File(item.path).inputStream()
    }

/** The picked folder keeps working after the app is closed. */
private fun keepAccess(ctx: Context, uri: Uri) {
    runCatching {
        ctx.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }
}

/** The name of a folder from the system picker, for example Download or the name of an SD card. */
private fun treeLabel(ctx: Context, tree: Uri): String {
    val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    val name = runCatching {
        ctx.contentResolver.query(
            doc,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()
    return name?.takeIf { it.isNotBlank() } ?: tree.lastPathSegment.orEmpty()
}

private fun usableWhere(where: String, tree: String): String = when {
    where == "tree" && tree.isBlank() -> "app"
    where == "public" && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> "app"
    where == "tree" || where == "public" -> where
    else -> "app"
}

@Composable
private fun RadioLine(selected: Boolean, label: String, hint: String = "", onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(label)
            if (hint.isNotEmpty()) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** When to ask where to save, and the default place. */
@Composable
private fun DownloadSettings(browser: BrowserController) {
    val ctx = LocalContext.current
    val d = browser.downloads
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            keepAccess(ctx, uri)
            d.chooseSaveTree(uri.toString(), treeLabel(ctx, uri))
        }
    }

    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Ask where to save", style = MaterialTheme.typography.titleSmall)
            RadioLine(d.askMode == "always", "Always ask") { d.chooseAskMode("always") }
            RadioLine(d.askMode == "large", "Only for large files", "25 MB and bigger") { d.chooseAskMode("large") }
            RadioLine(d.askMode == "never", "Never ask", "Save to the default place below") { d.chooseAskMode("never") }

            Spacer(Modifier.height(10.dp))
            Text("Default place", style = MaterialTheme.typography.titleSmall)
            RadioLine(
                d.saveWhere == "app",
                "App folder",
                "Private to this app. Use Share or Save a copy to take files out.",
            ) { d.chooseSaveWhere("app") }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                RadioLine(
                    d.saveWhere == "public",
                    "Downloads folder",
                    "On the phone's internal storage. Shows in the Files app.",
                ) { d.chooseSaveWhere("public") }
            }
            RadioLine(
                d.saveWhere == "tree" && d.saveTree.isNotBlank(),
                if (d.saveTree.isBlank()) "A folder I choose" else "Folder: " + d.saveTreeName,
                "Internal storage or an SD card.",
            ) {
                if (d.saveTree.isBlank()) picker.launch(null) else d.chooseSaveWhere("tree")
            }
            TextButton(onClick = { picker.launch(null) }) {
                Text(if (d.saveTree.isBlank()) "Choose a folder\u2026" else "Change the folder\u2026")
            }
        }
    }
}

/**
 * The question while a file downloads: its name and where it goes. The download already runs in
 * the background, so nothing is lost while the user decides.
 */
@Composable
internal fun DownloadAskDialog(browser: BrowserController) {
    val d = browser.downloads
    val ask = d.asks.firstOrNull() ?: return
    val ctx = LocalContext.current
    var name by remember(ask.id) { mutableStateOf(ask.name) }
    var where by remember(ask.id) { mutableStateOf(usableWhere(d.saveWhere, d.saveTree)) }
    var tree by remember(ask.id) { mutableStateOf(d.saveTree) }
    var treeName by remember(ask.id) { mutableStateOf(d.saveTreeName) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            keepAccess(ctx, uri)
            tree = uri.toString()
            treeName = treeLabel(ctx, uri)
            where = "tree"
        }
    }

    AlertDialog(
        onDismissRequest = { },
        title = { Text("Save file") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("File name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                val size = if (ask.total > 0) human(ask.total) + "  \u00b7  " else ""
                Text(
                    size + ask.host,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                Text("Save to", style = MaterialTheme.typography.titleSmall)
                RadioLine(where == "app", "App folder") { where = "app" }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    RadioLine(where == "public", "Downloads folder", "Internal storage, shows in the Files app") {
                        where = "public"
                    }
                }
                if (tree.isNotBlank()) {
                    RadioLine(where == "tree", "Folder: " + treeName) { where = "tree" }
                }
                TextButton(onClick = { picker.launch(null) }) {
                    Text("Choose another folder (internal storage or SD card)\u2026")
                }
            }
        },
        confirmButton = { Button(onClick = { d.answerAsk(ask.id, name, where, tree) }) { Text("Download") } },
        dismissButton = { TextButton(onClick = { d.cancelAsk(ask.id) }) { Text("Cancel") } },
    )
}

@Composable
private fun DownloadRow(
    item: DownloadItem,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCopyLink: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val progress = if (item.total > 0) {
        human(item.done) + " of " + human(item.total) + "  \u00b7  " + (item.done * 100 / item.total) + "%"
    } else {
        human(item.done)
    }
    val place = when {
        item.uri.isBlank() -> "App folder"
        item.where == "public" -> "Downloads folder"
        else -> "Chosen folder"
    }
    val sub = when (item.state) {
        "running" -> progress
        "paused" -> "Paused  \u00b7  " + progress
        "saving" -> if (item.ask) "Waiting for you to choose where to save" else "Saving\u2026"
        "done" -> "Done  \u00b7  " + human(item.done) + "  \u00b7  " + place
        "canceled" -> "Canceled"
        else -> "Failed: " + item.error
    }
    val active = item.state == "running" || item.state == "paused"

    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
        Text(
            sub,
            style = MaterialTheme.typography.bodySmall,
            color = if (item.state == "failed") cs.error else cs.onSurfaceVariant,
        )
        if (active || item.state == "saving") {
            if (item.total > 0 && item.state != "saving") {
                LinearProgressIndicator(
                    progress = { (item.done.toFloat() / item.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            } else if (item.state == "running" || item.state == "saving") {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            when (item.state) {
                "running" -> {
                    TextButton(onClick = onPause) { Text("Pause") }
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
                "paused" -> {
                    TextButton(onClick = onResume) { Text("Resume") }
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
                "done" -> {
                    TextButton(onClick = onOpen) { Text("Open") }
                    TextButton(onClick = onShare) { Text("Share") }
                }
                "failed" -> {
                    if (item.url.startsWith("http")) TextButton(onClick = onResume) { Text("Resume") }
                    TextButton(onClick = onDelete) { Text("Delete") }
                }
                "canceled" -> TextButton(onClick = onDelete) { Text("Delete") }
                else -> {}
            }
            Spacer(Modifier.weight(1f))
            Box {
                TextButton(onClick = { menu = true }) { Text("\u22ee") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuRow("Copy link") {
                        menu = false
                        onCopyLink()
                    }
                    if (item.state == "done") {
                        MenuRow("Save a copy to\u2026") {
                            menu = false
                            onSave()
                        }
                    }
                    if (item.state != "saving") {
                        MenuRow("Delete") {
                            menu = false
                            onDelete()
                        }
                    }
                }
            }
        }
    }
}

/** The list of downloaded files: pause, resume, open, share, copy the link, delete. */
@Composable
internal fun DownloadsScreen(browser: BrowserController) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val d = browser.downloads
    var saveTarget by remember { mutableStateOf<DownloadItem?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    // Lets the user pick any folder or app (Drive, SD card...) to keep a copy of the file.
    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val item = saveTarget
        if (uri != null && item != null) {
            scope.launch(Dispatchers.IO) {
                val ok = runCatching {
                    ctx.contentResolver.openOutputStream(uri)?.use { out ->
                        openSaved(ctx, item)?.use { it.copyTo(out) }
                    }
                }.isSuccess
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, if (ok) "Saved" else "Could not save the file", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { showSettings = !showSettings }) {
                Text(if (showSettings) "Hide settings" else "Settings")
            }
            TextButton(onClick = { d.clearList() }) { Text("Clear list (keeps files)") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (showSettings) {
                item { DownloadSettings(browser) }
            }
            if (d.items.isEmpty()) {
                item {
                    Text(
                        "No downloads yet. Files you download from websites appear here.",
                        Modifier.padding(16.dp),
                    )
                }
            }
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
                    onPause = { d.pause(item.id) },
                    onResume = { d.resume(item.id) },
                    onCopyLink = {
                        clipboard.setText(AnnotatedString(item.url))
                        Toast.makeText(ctx, "Download link copied", Toast.LENGTH_SHORT).show()
                    },
                )
                HorizontalDivider()
            }
        }
    }
}
