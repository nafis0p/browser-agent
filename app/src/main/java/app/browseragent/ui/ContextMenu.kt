package app.browseragent.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.browseragent.browser.BrowserController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

// The download keeps running after the menu is closed, so it must not belong to the menu.
private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private val saveClient = OkHttpClient()

/** Downloads an image from its address and adds it to the Downloads list. */
private fun saveImage(ctx: Context, browser: BrowserController, url: String) {
    saveScope.launch {
        try {
            val resp = saveClient.newCall(Request.Builder().url(url).build()).execute()
            if (!resp.isSuccessful) {
                resp.close()
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, "Could not download the image", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            val headers = resp.headers.names().associateWith { resp.header(it).orEmpty() }
            val stream = resp.body?.byteStream()
            withContext(Dispatchers.Main) { browser.startDownload(url, headers, stream) }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(ctx, "Could not download the image", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
private fun MenuLine(text: String, enabled: Boolean = true, onClick: () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    Text(
        text,
        color = if (enabled) cs.onSurface else cs.onSurface.copy(alpha = 0.38f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 13.dp),
    )
}

/** What appears after a long press on a link or an image. */
@Composable
internal fun ContextMenuDialog(browser: BrowserController) {
    val info = browser.contextMenu ?: return
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val link = info.linkUri
    val image = info.imageUri

    fun close() = browser.dismissContextMenu()

    fun copy(text: String, what: String) {
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(ctx, what + " copied", Toast.LENGTH_SHORT).show()
        close()
    }

    fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        close()
        ctx.startActivity(Intent.createChooser(send, "Share"))
    }

    fun inNewTab(address: String) {
        val ok = browser.openInBackground(address)
        Toast.makeText(ctx, if (ok) "Opened in a new tab" else "Tab limit reached", Toast.LENGTH_SHORT).show()
        close()
    }

    AlertDialog(
        onDismissRequest = { close() },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { close() }) { Text("Cancel") } },
        title = {
            Text(
                info.label.ifBlank { hostOf(link ?: image ?: "") },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (link != null) {
                    MenuLine("Open in new tab") { inNewTab(link) }
                    MenuLine("Open in this tab") {
                        close()
                        browser.load(link)
                    }
                    MenuLine("Open in private tab", enabled = false)
                    MenuLine("Copy link address") { copy(link, "Link") }
                    MenuLine("Share link") { share(link) }
                }
                if (link != null && image != null) HorizontalDivider()
                if (image != null) {
                    MenuLine("Open image in new tab") { inNewTab(image) }
                    MenuLine("View image") {
                        close()
                        browser.load(image)
                    }
                    MenuLine("Save image") {
                        saveImage(ctx, browser, image)
                        Toast.makeText(ctx, "Saving the image", Toast.LENGTH_SHORT).show()
                        close()
                    }
                    MenuLine("Copy image address") { copy(image, "Image address") }
                }
            }
        },
    )
}
