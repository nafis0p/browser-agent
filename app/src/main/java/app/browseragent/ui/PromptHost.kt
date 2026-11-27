package app.browseragent.ui

import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.browseragent.browser.BrowserController
import app.browseragent.browser.BrowserPrompt
import app.browseragent.browser.PromptAnswer
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * The file picker of Android only understands MIME types such as application/pdf, while pages
 * often ask for extensions such as .pdf. Extensions are turned into MIME types; when nothing
 * usable is left every file may be picked.
 */
private fun pickerTypes(raw: List<String>): Array<String> {
    val out = LinkedHashSet<String>()
    for (t0 in raw) {
        val t = t0.trim().lowercase()
        if (t.isEmpty()) continue
        if (t.startsWith(".")) {
            val m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(t.removePrefix("."))
            if (m != null) out.add(m)
        } else if (t.contains('/')) {
            out.add(t)
        }
    }
    return if (out.isEmpty()) arrayOf("*/*") else out.toTypedArray()
}

/**
 * Shows the questions that pages and the browser engine ask, one at a time: JavaScript alert,
 * confirm and prompt, dropdown lists, colour and date inputs, the leave-page question, the
 * permission to use the location, camera or microphone, and the file picker.
 */
@Composable
internal fun BrowserPromptHost(browser: BrowserController) {
    val queue = browser.prompts.queue

    fun finishFile(prompt: BrowserPrompt?, uris: List<Uri>) {
        if (prompt != null && prompt.kind == "file") {
            queue.remove(prompt)
            prompt.answer(PromptAnswer(ok = uris.isNotEmpty(), uris = uris))
        }
    }

    // The pickers are created before anything can return early, so they are always there.
    val single = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        finishFile(queue.firstOrNull(), if (uri != null) listOf(uri) else emptyList())
    }
    val multi = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        finishFile(queue.firstOrNull(), uris)
    }

    val p = queue.firstOrNull()
    LaunchedEffect(p) {
        if (p != null && p.kind == "file") {
            val types = pickerTypes(p.fileTypes)
            try {
                if (p.multiple) multi.launch(types) else single.launch(types)
            } catch (e: Exception) {
                // The picker did not accept the types: offer every file.
                try {
                    if (p.multiple) multi.launch(arrayOf("*/*")) else single.launch(arrayOf("*/*"))
                } catch (e2: Exception) {
                    finishFile(p, emptyList())
                }
            }
        }
    }
    if (p == null || p.kind == "file") return

    fun reply(a: PromptAnswer) {
        queue.remove(p)
        p.answer(a)
    }

    when (p.kind) {
        "alert" -> AlertDialog(
            onDismissRequest = { reply(PromptAnswer(true)) },
            title = { Text(p.host + " says") },
            text = { Text(p.message) },
            confirmButton = { TextButton(onClick = { reply(PromptAnswer(true)) }) { Text("OK") } },
        )
        "confirm" -> AlertDialog(
            onDismissRequest = { reply(PromptAnswer(false)) },
            title = { Text(p.host + " says") },
            text = { Text(p.message) },
            confirmButton = { TextButton(onClick = { reply(PromptAnswer(true)) }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { reply(PromptAnswer(false)) }) { Text("Cancel") } },
        )
        "text" -> TextPromptDialog(p) { reply(it) }
        "choice" -> ChoicePromptDialog(p) { reply(it) }
        "color" -> ColorPromptDialog(p) { reply(it) }
        "datetime" -> DateTimeDialogHost(p) { reply(it) }
        "repost" -> AlertDialog(
            onDismissRequest = { reply(PromptAnswer(false)) },
            title = { Text("Send the form again?") },
            text = { Text("This page was the answer to a form. Reloading it sends the form data to " + p.host + " once more.") },
            confirmButton = { TextButton(onClick = { reply(PromptAnswer(true)) }) { Text("Send") } },
            dismissButton = { TextButton(onClick = { reply(PromptAnswer(false)) }) { Text("Cancel") } },
        )
        "beforeunload" -> AlertDialog(
            onDismissRequest = { reply(PromptAnswer(false)) },
            title = { Text("Leave this page?") },
            text = { Text("Changes you made may not be saved.") },
            confirmButton = { TextButton(onClick = { reply(PromptAnswer(true)) }) { Text("Leave") } },
            dismissButton = { TextButton(onClick = { reply(PromptAnswer(false)) }) { Text("Stay") } },
        )
        else -> AlertDialog(
            // permission and media: a site wants to use the location, camera or microphone
            onDismissRequest = { reply(PromptAnswer(false)) },
            title = { Text(p.host) },
            text = { Text("This site " + p.message + ".") },
            confirmButton = { TextButton(onClick = { reply(PromptAnswer(true)) }) { Text("Allow") } },
            dismissButton = { TextButton(onClick = { reply(PromptAnswer(false)) }) { Text("Block") } },
        )
    }
}

@Composable
private fun TextPromptDialog(p: BrowserPrompt, reply: (PromptAnswer) -> Unit) {
    var text by remember(p) { mutableStateOf(p.defaultText) }
    AlertDialog(
        onDismissRequest = { reply(PromptAnswer(false)) },
        title = { Text(p.host + " says") },
        text = {
            Column {
                if (p.message.isNotBlank()) Text(p.message, Modifier.padding(bottom = 8.dp))
                OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { reply(PromptAnswer(true, text = text)) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = { reply(PromptAnswer(false)) }) { Text("Cancel") } },
    )
}

/** A dropdown or list from a page. One choice answers right away; several need OK. */
@Composable
private fun ChoicePromptDialog(p: BrowserPrompt, reply: (PromptAnswer) -> Unit) {
    val picked = remember(p) { mutableStateListOf<String>().also { l -> l.addAll(p.options.filter { it.selected }.map { it.id }) } }
    AlertDialog(
        onDismissRequest = { reply(PromptAnswer(false)) },
        title = { if (p.message.isNotBlank()) Text(p.message) },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(p.options) { o ->
                    if (o.header) {
                        Text(
                            o.label,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth().padding(start = (o.depth * 12).dp, top = 10.dp, bottom = 4.dp),
                        )
                    } else {
                        val on = picked.contains(o.id)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = o.enabled) {
                                    if (p.multiple) {
                                        if (on) picked.remove(o.id) else picked.add(o.id)
                                    } else {
                                        reply(PromptAnswer(true, ids = listOf(o.id)))
                                    }
                                }
                                .padding(start = (o.depth * 12).dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (p.multiple) {
                                Checkbox(checked = on, onCheckedChange = null, enabled = o.enabled)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                o.label,
                                Modifier.weight(1f).padding(vertical = 12.dp),
                                fontWeight = if (!p.multiple && on) FontWeight.Bold else FontWeight.Normal,
                                color = if (o.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (p.multiple) {
                TextButton(onClick = { reply(PromptAnswer(true, ids = picked.toList())) }) { Text("OK") }
            }
        },
        dismissButton = { TextButton(onClick = { reply(PromptAnswer(false)) }) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- colour

private val PALETTE = listOf(
    "#000000", "#ffffff", "#9e9e9e", "#f44336", "#ff9800", "#ffeb3b",
    "#4caf50", "#009688", "#2196f3", "#3f51b5", "#9c27b0", "#e91e63", "#795548",
)

private fun parseColor(s: String): Int =
    runCatching { android.graphics.Color.parseColor(if (s.startsWith("#")) s else "#" + s) and 0xFFFFFF }
        .getOrDefault(0)

/** The colour picker for a colour input: a preview, red, green and blue sliders, and swatches. */
@Composable
private fun ColorPromptDialog(p: BrowserPrompt, reply: (PromptAnswer) -> Unit) {
    val start = parseColor(p.defaultText)
    var r by remember(p) { mutableStateOf((start shr 16) and 255) }
    var g by remember(p) { mutableStateOf((start shr 8) and 255) }
    var b by remember(p) { mutableStateOf(start and 255) }
    val hex = String.format(Locale.US, "#%02x%02x%02x", r, g, b)
    val swatches = (p.options.map { it.id } + PALETTE).distinct()

    fun setAll(c: Int) {
        r = (c shr 16) and 255
        g = (c shr 8) and 255
        b = c and 255
    }

    AlertDialog(
        onDismissRequest = { reply(PromptAnswer(false)) },
        title = { Text("Pick a colour") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(Color(r, g, b))
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(hex, style = MaterialTheme.typography.titleLarge)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 10.dp)) {
                    for (s in swatches) {
                        val c = parseColor(s)
                        Box(
                            Modifier
                                .padding(end = 8.dp)
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color((c shr 16) and 255, (c shr 8) and 255, c and 255))
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                .clickable { setAll(c) },
                        )
                    }
                }
                Text("Red", style = MaterialTheme.typography.labelMedium)
                Slider(value = r.toFloat(), onValueChange = { r = it.toInt() }, valueRange = 0f..255f)
                Text("Green", style = MaterialTheme.typography.labelMedium)
                Slider(value = g.toFloat(), onValueChange = { g = it.toInt() }, valueRange = 0f..255f)
                Text("Blue", style = MaterialTheme.typography.labelMedium)
                Slider(value = b.toFloat(), onValueChange = { b = it.toInt() }, valueRange = 0f..255f)
            }
        },
        confirmButton = { TextButton(onClick = { reply(PromptAnswer(true, text = hex)) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = { reply(PromptAnswer(false)) }) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- date and time

private fun parseDate(s: String): LocalDate = runCatching { LocalDate.parse(s.take(10)) }.getOrDefault(LocalDate.now())

private fun parseTime(s: String): LocalTime = runCatching { LocalTime.parse(s.take(5)) }.getOrDefault(LocalTime.now())

private fun parseDateTime(s: String): LocalDateTime =
    runCatching { LocalDateTime.parse(s.take(16)) }.getOrDefault(LocalDateTime.now())

private fun parseMonth(s: String): LocalDate =
    runCatching { YearMonth.parse(s.take(7)).atDay(1) }.getOrDefault(LocalDate.now())

private fun limitDates(d: DatePickerDialog, min: String, max: String) {
    runCatching {
        if (min.isNotBlank()) {
            d.datePicker.minDate = LocalDate.parse(min.take(10)).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
    }
    runCatching {
        if (max.isNotBlank()) {
            d.datePicker.maxDate = LocalDate.parse(max.take(10)).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
    }
}

/**
 * Date, month, week, time and date-and-time inputs use the pickers of Android. The answer is
 * written the way the page expects it, for example 2025-03-04, 2025-03, 2025-W10, 14:30 or
 * 2025-03-04T14:30.
 */
@Composable
private fun DateTimeDialogHost(p: BrowserPrompt, reply: (PromptAnswer) -> Unit) {
    val ctx = LocalContext.current
    DisposableEffect(p) {
        var shown: Dialog? = null

        fun cancel() {
            reply(PromptAnswer(false))
        }

        fun timeDialog(initial: LocalTime, datePart: String?) {
            val d = TimePickerDialog(
                ctx,
                { _, h, m ->
                    val t = String.format(Locale.US, "%02d:%02d", h, m)
                    reply(PromptAnswer(true, text = if (datePart == null) t else datePart + "T" + t))
                },
                initial.hour,
                initial.minute,
                true,
            )
            d.setOnCancelListener { cancel() }
            shown = d
            d.show()
        }

        fun dateDialog(initial: LocalDate, then: (LocalDate) -> Unit) {
            val d = DatePickerDialog(
                ctx,
                { _, y, m, day -> then(LocalDate.of(y, m + 1, day)) },
                initial.year,
                initial.monthValue - 1,
                initial.dayOfMonth,
            )
            limitDates(d, p.minValue, p.maxValue)
            d.setOnCancelListener { cancel() }
            shown = d
            d.show()
        }

        when (p.dtType) {
            "time" -> timeDialog(parseTime(p.defaultText), null)
            "datetime-local" -> {
                val start = parseDateTime(p.defaultText)
                dateDialog(start.toLocalDate()) { date -> timeDialog(start.toLocalTime(), date.toString()) }
            }
            "month" -> dateDialog(parseMonth(p.defaultText)) { date ->
                reply(PromptAnswer(true, text = String.format(Locale.US, "%04d-%02d", date.year, date.monthValue)))
            }
            "week" -> dateDialog(LocalDate.now()) { date ->
                val weeks = WeekFields.ISO
                val text = String.format(
                    Locale.US,
                    "%04d-W%02d",
                    date.get(weeks.weekBasedYear()),
                    date.get(weeks.weekOfWeekBasedYear()),
                )
                reply(PromptAnswer(true, text = text))
            }
            else -> dateDialog(parseDate(p.defaultText)) { date -> reply(PromptAnswer(true, text = date.toString())) }
        }

        onDispose { runCatching { shown?.dismiss() } }
    }
}
