package chat.papo.mobile.chat

/** Trecho de texto de uma mensagem já interpretado. */
data class TextSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val quote: Boolean = false,
    val link: String? = null,
    val mentionUserId: String? = null,
)

/**
 * Subconjunto do Markdown que o cliente web do Papo mostra (marked + GFM):
 * negrito, itálico, riscado, código em linha e em bloco, títulos, citações,
 * links e menções. Listas e tabelas ficam como texto. O que não fecha vira
 * texto literal, como no Markdown.
 */
object Markdown {
    private val mention = Regex("""^@mention\(<@([0-9a-fA-F-]{36})>\)""")
    private val url = Regex("""^https?://[^\s<>"]+""")
    private val namedLink = Regex("""^\[([^\]\n]+)]\((https?://[^\s)]+)\)""")
    private val heading = Regex("""^#{1,6}\s+""")
    private val fence = Regex("""```[^\n`]*\n?([\s\S]*?)```""")

    fun parse(content: String): List<TextSpan> {
        val out = ArrayList<TextSpan>()
        var cursor = 0
        for (block in fence.findAll(content)) {
            lines(content.substring(cursor, block.range.first), out)
            out += TextSpan(block.groupValues[1].trimEnd('\n'), code = true)
            cursor = block.range.last + 1
            // O bloco de código ocupa a linha inteira; a quebra seguinte é dele.
            if (content.getOrNull(cursor) == '\n') {
                out += TextSpan("\n")
                cursor++
            }
        }
        lines(content.substring(cursor), out)
        return merge(out)
    }

    private fun lines(text: String, out: MutableList<TextSpan>) {
        if (text.isEmpty()) return
        val parts = text.split('\n')
        parts.forEachIndexed { index, raw ->
            var line = raw
            var style = TextSpan("")
            val title = heading.find(line)
            when {
                title != null -> {
                    line = line.substring(title.range.last + 1)
                    style = style.copy(bold = true)
                }
                line.startsWith("> ") || line == ">" -> {
                    line = line.removePrefix(">").removePrefix(" ")
                    style = style.copy(quote = true, italic = true)
                }
            }
            inline(line, style, out)
            if (index < parts.lastIndex) out += TextSpan("\n")
        }
    }

    private val delimiters = listOf("***", "___", "**", "__", "~~", "*", "_")

    private fun inline(text: String, style: TextSpan, out: MutableList<TextSpan>) {
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) {
                out += style.copy(text = plain.toString())
                plain.clear()
            }
        }
        var i = 0
        while (i < text.length) {
            val rest = text.substring(i)
            val c = text[i]

            if (c == '\\' && i + 1 < text.length && text[i + 1] in "\\`*_~[]()>#@") {
                plain.append(text[i + 1])
                i += 2
                continue
            }
            if (c == '`') {
                val close = text.indexOf('`', i + 1)
                if (close > i + 1) {
                    flush()
                    out += style.copy(text = text.substring(i + 1, close), code = true)
                    i = close + 1
                    continue
                }
            }
            if (c == '@') {
                val m = mention.find(rest)
                if (m != null) {
                    flush()
                    out += style.copy(text = m.value, mentionUserId = m.groupValues[1])
                    i += m.value.length
                    continue
                }
            }
            if (c == '[') {
                val m = namedLink.find(rest)
                if (m != null) {
                    flush()
                    out += style.copy(text = m.groupValues[1], link = m.groupValues[2])
                    i += m.value.length
                    continue
                }
            }
            if (c == 'h') {
                val m = url.find(rest)
                if (m != null && (i == 0 || !text[i - 1].isLetterOrDigit())) {
                    val address = m.value.trimEnd('.', ',', ')', '!', '?', ';', ':', '*', '_', '~')
                    flush()
                    out += style.copy(text = address, link = address)
                    i += address.length
                    continue
                }
            }
            val delimiter = delimiters.firstOrNull { rest.startsWith(it) }
            if (delimiter != null) {
                val close = closing(text, i + delimiter.length, delimiter)
                // `_` no meio de uma palavra (snake_case) não é ênfase.
                val wordBound = delimiter[0] != '_' || i == 0 || !text[i - 1].isLetterOrDigit()
                if (close > i + delimiter.length && wordBound && !text[i + delimiter.length].isWhitespace()) {
                    flush()
                    val inner = when (delimiter) {
                        "***", "___" -> style.copy(bold = true, italic = true)
                        "**", "__" -> style.copy(bold = true)
                        "~~" -> style.copy(strike = true)
                        else -> style.copy(italic = true)
                    }
                    inline(text.substring(i + delimiter.length, close), inner, out)
                    i = close + delimiter.length
                    continue
                }
                plain.append(delimiter)
                i += delimiter.length
                continue
            }
            plain.append(c)
            i++
        }
        flush()
    }

    /** Posição do fechamento de [delimiter] a partir de [from], ou -1. */
    private fun closing(text: String, from: Int, delimiter: String): Int {
        var i = from
        while (i < text.length) {
            val at = text.indexOf(delimiter, i)
            if (at < 0) return -1
            val escaped = at > 0 && text[at - 1] == '\\'
            val afterSpace = at > 0 && text[at - 1].isWhitespace()
            // Para `*` e `_` simples, o fechamento não pode ser parte de um delimitador maior.
            val partOfLonger = delimiter.length == 1 && text.getOrNull(at + 1) == delimiter[0]
            val midWord = delimiter[0] == '_' && text.getOrNull(at + delimiter.length)?.isLetterOrDigit() == true
            if (!escaped && !afterSpace && !partOfLonger && !midWord) return at
            i = at + if (partOfLonger) 2 else 1
        }
        return -1
    }

    private fun merge(spans: List<TextSpan>): List<TextSpan> {
        val out = ArrayList<TextSpan>()
        for (span in spans) {
            if (span.text.isEmpty()) continue
            val last = out.lastOrNull()
            if (last != null && last.link == null && last.mentionUserId == null && last.copy(text = "") == span.copy(text = "")) {
                out[out.lastIndex] = last.copy(text = last.text + span.text)
            } else {
                out += span
            }
        }
        return out
    }
}
