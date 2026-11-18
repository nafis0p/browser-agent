package app.browseragent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// A small Markdown renderer for AI replies: headings, bold, italic, strikethrough, inline code,
// code blocks, bullet and numbered lists, quotes, rules, tables, links and bare URLs.

private sealed class Md {
    class Para(val text: String) : Md()
    class Head(val level: Int, val text: String) : Md()
    class Item(val marker: String, val text: String, val indent: Int) : Md()
    class Code(val text: String) : Md()
    class Quote(val text: String) : Md()

    /** align per column: 0 start, 1 center, 2 end */
    class Table(val header: List<String>, val rows: List<List<String>>, val align: List<Int>) : Md()

    object Rule : Md()
}

private fun splitRow(row: String): List<String> =
    row.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

/** A row such as  | --- | :---: | ---: |  that separates a table header from its body. */
private fun isSeparatorRow(row: String): Boolean {
    if (!row.contains('|') || !row.contains('-')) return false
    val cells = splitRow(row)
    return cells.isNotEmpty() && cells.all { c ->
        c.isNotEmpty() && c.contains('-') && c.all { it == '-' || it == ':' || it == ' ' }
    }
}

/** Length of a table cell without the Markdown marks, to size the columns. */
private fun plainLength(s: String): Int = s.count { it != '*' && it != '`' && it != '~' }

private fun parseBlocks(src: String): List<Md> {
    val out = ArrayList<Md>()
    val lines = src.replace("\r\n", "\n").split("\n")
    val para = StringBuilder()

    fun flush() {
        if (para.isNotEmpty()) {
            out.add(Md.Para(para.toString().trim()))
            para.setLength(0)
        }
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trim()
        val hashes = t.takeWhile { it == '#' }.length
        val numbers = t.takeWhile { it.isDigit() }
        when {
            t.startsWith("```") -> {
                flush()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    code.append(lines[i]).append('\n')
                    i++
                }
                out.add(Md.Code(code.toString().trimEnd()))
            }
            t.isEmpty() -> flush()
            t.contains('|') && i + 1 < lines.size && isSeparatorRow(lines[i + 1]) -> {
                flush()
                val header = splitRow(t)
                val align = splitRow(lines[i + 1]).map { c ->
                    if (c.startsWith(":") && c.endsWith(":")) 1 else if (c.endsWith(":")) 2 else 0
                }
                i += 2
                val rows = ArrayList<List<String>>()
                while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) {
                    rows.add(splitRow(lines[i]))
                    i++
                }
                i-- // the loop below moves on to the first line after the table
                out.add(Md.Table(header, rows, align))
            }
            hashes in 1..6 && t.drop(hashes).startsWith(" ") -> {
                flush()
                out.add(Md.Head(hashes, t.drop(hashes).trim()))
            }
            t == "---" || t == "***" || t == "___" -> {
                flush()
                out.add(Md.Rule)
            }
            t.startsWith("> ") -> {
                flush()
                out.add(Md.Quote(t.drop(2)))
            }
            t.startsWith("- ") || t.startsWith("* ") || t.startsWith("• ") -> {
                flush()
                val indent = (line.length - line.trimStart().length) / 2
                out.add(Md.Item("•", t.drop(2), indent.coerceAtMost(3)))
            }
            numbers.isNotEmpty() && numbers.length <= 3 && t.drop(numbers.length).startsWith(". ") -> {
                flush()
                val indent = (line.length - line.trimStart().length) / 2
                out.add(Md.Item(numbers + ".", t.drop(numbers.length + 2), indent.coerceAtMost(3)))
            }
            else -> {
                if (para.isNotEmpty()) para.append('\n')
                para.append(line.trimEnd())
            }
        }
        i++
    }
    flush()
    return out
}

private fun inlineMd(src: String, link: Color, codeBg: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < src.length) {
        val c = src[i]
        if (src.startsWith("**", i)) {
            val j = src.indexOf("**", i + 2)
            if (j > i + 2) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(inlineMd(src.substring(i + 2, j), link, codeBg))
                }
                i = j + 2
                continue
            }
        }
        if (src.startsWith("~~", i)) {
            val j = src.indexOf("~~", i + 2)
            if (j > i + 2) {
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    append(inlineMd(src.substring(i + 2, j), link, codeBg))
                }
                i = j + 2
                continue
            }
        }
        if (c == '`') {
            val j = src.indexOf('`', i + 1)
            if (j > i + 1) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) {
                    append(src.substring(i + 1, j))
                }
                i = j + 1
                continue
            }
        }
        if (c == '*' && i + 1 < src.length && src[i + 1] != ' ' && src[i + 1] != '*') {
            val j = src.indexOf('*', i + 1)
            if (j > i + 1 && src[j - 1] != ' ') {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(inlineMd(src.substring(i + 1, j), link, codeBg))
                }
                i = j + 1
                continue
            }
        }
        if (c == '[') {
            val close = src.indexOf("](", i + 1)
            val end = if (close > 0) src.indexOf(')', close + 2) else -1
            if (close > i && end > close) {
                val label = src.substring(i + 1, close)
                val url = src.substring(close + 2, end)
                withStyle(SpanStyle(color = link, textDecoration = TextDecoration.Underline)) {
                    append(label.ifBlank { url })
                }
                if (label.isNotBlank() && label != url) append(" (" + url + ")")
                i = end + 1
                continue
            }
        }
        if (src.startsWith("http://", i) || src.startsWith("https://", i)) {
            var j = i
            while (j < src.length && !src[j].isWhitespace() && src[j] != ')' && src[j] != ']') j++
            withStyle(SpanStyle(color = link, textDecoration = TextDecoration.Underline)) {
                append(src.substring(i, j))
            }
            i = j
            continue
        }
        append(c)
        i++
    }
}

@Composable
private fun TableLine(
    cells: List<String>,
    widths: List<Dp>,
    align: List<Int>,
    header: Boolean,
    color: Color,
    link: Color,
    codeBg: Color,
    border: Color,
) {
    Row(if (header) Modifier.background(color.copy(alpha = 0.08f)) else Modifier) {
        for (j in widths.indices) {
            Text(
                inlineMd(cells.getOrNull(j).orEmpty(), link, codeBg),
                color = color,
                fontSize = 13.sp,
                fontWeight = if (header) FontWeight.Bold else null,
                textAlign = when (align.getOrNull(j)) {
                    1 -> TextAlign.Center
                    2 -> TextAlign.End
                    else -> TextAlign.Start
                },
                modifier = Modifier
                    .width(widths[j])
                    .border(0.5.dp, border)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

/** A real table: header row, bordered cells, Markdown inside the cells, scrolls sideways when wide. */
@Composable
private fun MdTable(t: Md.Table, color: Color, link: Color, codeBg: Color) {
    val border = color.copy(alpha = 0.25f)
    val all = listOf(t.header) + t.rows
    val cols = all.maxOf { it.size }
    val widths = (0 until cols).map { j ->
        val longest = all.maxOf { r -> plainLength(r.getOrNull(j).orEmpty()) }
        (longest * 8 + 24).coerceIn(80, 260).dp
    }
    Column(
        Modifier
            .horizontalScroll(rememberScrollState())
            .border(1.dp, border, RoundedCornerShape(6.dp)),
    ) {
        TableLine(t.header, widths, t.align, true, color, link, codeBg, border)
        for (r in t.rows) {
            TableLine(r, widths, t.align, false, color, link, codeBg, border)
        }
    }
}

@Composable
internal fun MarkdownText(text: String, color: Color, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val blocks = remember(text) { parseBlocks(text) }
    val codeBg = cs.onSurface.copy(alpha = 0.10f)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (b in blocks) {
            when (b) {
                is Md.Para -> Text(inlineMd(b.text, cs.primary, codeBg), color = color)
                is Md.Head -> Text(
                    inlineMd(b.text, cs.primary, codeBg),
                    color = color,
                    fontWeight = FontWeight.Bold,
                    fontSize = when (b.level) {
                        1 -> 20.sp
                        2 -> 18.sp
                        else -> 16.sp
                    },
                )
                is Md.Item -> Row(Modifier.padding(start = (b.indent * 14).dp)) {
                    Text(b.marker + " ", color = color)
                    Text(inlineMd(b.text, cs.primary, codeBg), color = color, modifier = Modifier.weight(1f))
                }
                is Md.Code -> Surface(shape = RoundedCornerShape(8.dp), color = codeBg) {
                    Text(
                        b.text,
                        color = color,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(8.dp),
                    )
                }
                is Md.Table -> MdTable(b, color, cs.primary, codeBg)
                is Md.Quote -> Text("▎ " + b.text, color = color.copy(alpha = 0.75f), fontStyle = FontStyle.Italic)
                Md.Rule -> HorizontalDivider(Modifier.fillMaxWidth())
            }
        }
    }
}
