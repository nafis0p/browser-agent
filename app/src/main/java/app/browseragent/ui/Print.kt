package app.browseragent.ui

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.widget.Toast
import app.browseragent.browser.BrowserController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoSession
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.coroutines.resume

/** Hands a finished PDF to the print system of Android, which shows its own print screen. */
private class PdfPrintAdapter(private val data: ByteArray, private val name: String) : PrintDocumentAdapter() {
    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes?,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(name)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
            .build()
        callback.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pages: Array<PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback,
    ) {
        try {
            FileOutputStream(destination.fileDescriptor).use { it.write(data) }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback.onWriteFailed(e.message)
        }
    }
}

/** Firefox turns the page into a PDF. Returns null when that does not work. */
private suspend fun pdfOf(session: GeckoSession): ByteArray? {
    val stream: InputStream? = suspendCancellableCoroutine { cont ->
        try {
            session.saveAsPdf().accept(
                { s -> if (cont.isActive) cont.resume(s) },
                { if (cont.isActive) cont.resume(null) },
            )
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(null)
        }
    }
    if (stream == null) return null
    return withContext(Dispatchers.IO) { stream.use { it.readBytes() } }
}

/**
 * Prints the page of the shown tab. The print screen of Android opens, where a printer can be
 * chosen or the page can be saved as a PDF.
 */
internal suspend fun printCurrentPage(ctx: Context, browser: BrowserController) {
    val tab = browser.current
    if (tab.isHome || tab.pageError != null) {
        Toast.makeText(ctx, "There is no page to print", Toast.LENGTH_SHORT).show()
        return
    }
    Toast.makeText(ctx, "Preparing the page for printing\u2026", Toast.LENGTH_SHORT).show()
    val bytes = pdfOf(tab.session)
    if (bytes == null || bytes.isEmpty()) {
        Toast.makeText(ctx, "Could not prepare the page for printing", Toast.LENGTH_LONG).show()
        return
    }
    val manager = ctx.getSystemService(Context.PRINT_SERVICE) as? PrintManager
    if (manager == null) {
        Toast.makeText(ctx, "Printing is not available on this phone", Toast.LENGTH_LONG).show()
        return
    }
    val name = tab.title.ifBlank { hostOf(tab.url) }.ifBlank { "page" }
    manager.print(name, PdfPrintAdapter(bytes, name), null)
}
