package app.browseragent.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// A small Markdown renderer for AI replies: headings, bold, italic, strikethrough, inline code,
// code blocks, bullet and numbered lists, quotes, rules, simple tables, links and bare URLs.

private sealed class Md {
    class Para(val text: String) : Md()
    class Head(val level: Int, val text: String) : Md()
    class Item(val marker: String, val text: String, val indent: Int) : Md()
    class Code(val text: String) : Md()
    class Quote(val text: String) : Md()
    object Rule : Md()
}

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
            t.startsWith("|") -> {
                flush()
                val rows = ArrayList<String>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    val row = lines[i].trim()
                    val isSeparator = row.replace("|", "").replace("-", "").replace(":", "").replace(" ", "").isEmpty()
                    if (!isSeparator) {
                        rows.add(row.trim('|').split("|").joinToString("  |  ") { it.trim() })
                    }
                    i++
                }
                i--
                out.add(Md.Code(rows.joinToString("\n")))
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
                is Md.Quote -> Text("▎ " + b.text, color = color.copy(alpha = 0.75f), fontStyle = FontStyle.Italic)
                Md.Rule -> HorizontalDivider(Modifier.fillMaxWidth())
            }
        }
    }
}
