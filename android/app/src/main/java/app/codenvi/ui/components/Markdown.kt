package app.codenvi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codenvi.ui.theme.MonoStyle
import app.codenvi.ui.theme.Tokens

/** Blocks of the small built-in Markdown renderer (docs/design.md §10.3): headings, lists, quotes, code blocks. */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullet(val marker: String, val text: String, val indent: Int) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    data object Rule : MdBlock
}

object MarkdownParser {
    private val ordered = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
    private val unordered = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val heading = Regex("^(#{1,6})\\s+(.*)$")

    fun parse(md: String): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        val para = StringBuilder()
        fun flush() { if (para.isNotBlank()) out += MdBlock.Paragraph(para.toString().trim()); para.clear() }
        val lines = md.replace("\r\n", "\n").split("\n")
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trimStart()
            when {
                t.startsWith("```") -> {
                    flush()
                    val lang = t.removePrefix("```").trim()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) { code.appendLine(lines[i]); i++ }
                    out += MdBlock.Code(lang, code.toString().trimEnd())
                }
                heading.matches(t) -> { flush(); val m = heading.find(t)!!; out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2]) }
                t.matches(Regex("^(-{3,}|\\*{3,}|_{3,})$")) -> { flush(); out += MdBlock.Rule }
                unordered.matches(line) -> { flush(); val m = unordered.find(line)!!; out += MdBlock.Bullet("•", m.groupValues[2], m.groupValues[1].length / 2) }
                ordered.matches(line) -> { flush(); val m = ordered.find(line)!!; out += MdBlock.Bullet(m.groupValues[2] + ".", m.groupValues[3], m.groupValues[1].length / 2) }
                t.startsWith(">") -> { flush(); out += MdBlock.Quote(t.removePrefix(">").trim()) }
                t.isEmpty() -> flush()
                else -> { if (para.isNotEmpty()) para.append(' '); para.append(t) }
            }
            i++
        }
        flush()
        return out
    }

    /** Inline: **bold**, *italic*, `code`, [text](url). */
    fun inline(text: String): AnnotatedString = buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) && text.indexOf("**", i + 2) > i -> {
                    val end = text.indexOf("**", i + 2)
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                }
                text[i] == '`' && text.indexOf('`', i + 1) > i -> {
                    val end = text.indexOf('`', i + 1)
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Tokens.SurfaceVariant, color = Tokens.OnPrimaryContainer)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                }
                (text[i] == '*' || text[i] == '_') && i + 1 < text.length && text[i + 1] != ' ' && text.indexOf(text[i], i + 1) > i + 1 -> {
                    val end = text.indexOf(text[i], i + 1)
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                }
                text[i] == '[' && Regex("^\\[([^\\]]+)]\\(([^)]+)\\)").find(text.substring(i)) != null -> {
                    val m = Regex("^\\[([^\\]]+)]\\(([^)]+)\\)").find(text.substring(i))!!
                    withStyle(SpanStyle(color = Tokens.Primary)) { append(m.groupValues[1]) }
                    i += m.value.length
                }
                else -> { append(text[i]); i++ }
            }
        }
    }
}

@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (b in blocks) when (b) {
            is MdBlock.Heading -> Text(MarkdownParser.inline(b.text), style = MaterialTheme.typography.titleMedium.copy(
                fontSize = when (b.level) { 1 -> 20.sp; 2 -> 18.sp; else -> 16.sp }))
            is MdBlock.Paragraph -> Text(MarkdownParser.inline(b.text), style = MaterialTheme.typography.bodyMedium)
            is MdBlock.Bullet -> Row(Modifier.padding(start = (12 * b.indent).dp)) {
                Text(b.marker, Modifier.width(22.dp), color = Tokens.Primary)
                Text(MarkdownParser.inline(b.text), style = MaterialTheme.typography.bodyMedium)
            }
            is MdBlock.Quote -> Text(MarkdownParser.inline(b.text), color = Tokens.OnSurfaceMuted, fontStyle = FontStyle.Italic,
                modifier = Modifier.padding(start = 8.dp))
            is MdBlock.Code -> Text(
                b.code, style = MonoStyle, color = Tokens.OnSurface,
                modifier = Modifier.fillMaxWidth().background(Tokens.Background, RoundedCornerShape(6.dp))
                    .horizontalScroll(rememberScrollState()).padding(10.dp),
                softWrap = false,
            )
            MdBlock.Rule -> Text("―――", color = Tokens.Outline)
        }
    }
}
