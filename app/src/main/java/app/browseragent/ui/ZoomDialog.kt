package app.browseragent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.browseragent.browser.BrowserController
import kotlin.math.roundToInt

private fun stepped(zoom: Double, change: Double): Double = ((zoom + change) * 10).roundToInt() / 10.0

/** Zoom of the page you are on. It is remembered for the whole site. */
@Composable
internal fun ZoomDialog(browser: BrowserController, onClose: () -> Unit) {
    val tab = browser.current
    val zoom = tab.zoom
    val percent = (zoom * 100).roundToInt()

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Page zoom") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    hostOf(tab.url) + "  \u00b7  remembered for this site",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { tab.chooseZoom(stepped(zoom, -0.1)) }) { Text("\u2212") }
                    Text(
                        percent.toString() + "%",
                        Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    OutlinedButton(onClick = { tab.chooseZoom(stepped(zoom, 0.1)) }) { Text("+") }
                }
                Slider(
                    value = zoom.toFloat().coerceIn(0.5f, 3f),
                    onValueChange = { tab.chooseZoom((it * 20).roundToInt() / 20.0) },
                    valueRange = 0.5f..3f,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (p in listOf(0.75, 1.0, 1.5, 2.0)) {
                        val on = (zoom * 100).roundToInt() == (p * 100).roundToInt()
                        if (on) {
                            Button(onClick = { tab.chooseZoom(p) }) { Text((p * 100).roundToInt().toString() + "%") }
                        } else {
                            OutlinedButton(onClick = { tab.chooseZoom(p) }) { Text((p * 100).roundToInt().toString() + "%") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
        dismissButton = { TextButton(onClick = { tab.chooseZoom(1.0) }) { Text("Reset") } },
    )
}
