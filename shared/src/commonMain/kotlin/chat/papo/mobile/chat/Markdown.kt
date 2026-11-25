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

/** Bloco de uma mensagem: texto corrido, item de lista ou régua horizontal. */
sealed interface MarkdownBlock {
    /** Linhas de texto separadas por `\n`, como [Markdown.parse] as devolve; sem spans é uma linha em branco. */
    data class Paragraph(val spans: List<TextSpan>) : MarkdownBlock

    /**
     * Item de lista. [level] vai de 0 a [Markdown.MAX_LIST_LEVEL] (níveis mais fundos ficam no último) e
     * [marker] é o que aparece antes do texto: `•`, `3.`, `☐`. Linhas de continuação vêm em [spans], separadas por `\n`.
     */
    data class ListItem(val level: Int, val marker: String, val spans: List<TextSpan>) : MarkdownBlock

    /** Régua horizontal (`---`, `***`, `___`, `- - -`). */
    data object Rule : MarkdownBlock
}

/**
 * Subconjunto do Markdown que o cliente web do Papo mostra (marked + GFM):
 * negrito, itálico, riscado, código em linha e em bloco, títulos, citações,
 * links, menções, listas (com tarefas), réguas e títulos com sublinhado.
 * Tabelas ficam como texto. O que não fecha vira texto literal, como no Markdown.
 */
object Markdown {
    /** Níveis de lista mostrados com recuo (0, 1 e 2). */
    const val MAX_LIST_LEVEL = 2

    private val bullets = listOf("•", "◦", "▪")
    private val mention = Regex("""^@mention\(<@([0-9a-fA-F-]{36})>\)""")
    private val url = Regex("""^https?://[^\s<>"]+""")
    private val namedLink = Regex("""^\[([^\]\n]+)]\((https?://[^\s)]+)\)""")
    private val heading = Regex("""^#{1,6}\s+""")
    private val fence = Regex("""```[^\n`]*\n?([\s\S]*?)```""")
    private val itemStart = Regex("""^([ \t]*)([*+-]|\d{1,9}[.)])([ \t]+)(\S[\s\S]*)$""")
    private val taskStart = Regex("""^\[([ xX])\][ \t]+(\S[\s\S]*)$""")
    private val rule = Regex("""^ {0,3}(?:(?:-[ \t]*){3,}|(?:_[ \t]*){3,}|(?:\*[ \t]*){3,})$""")
    private val setextUnderline = Regex("""^ {0,3}(?:=+|-+)[ \t]*$""")

    /** O texto sem a estrutura de blocos: listas e réguas ficam como o autor digitou. */
    fun parse(content: String): List<TextSpan> =
        (Scanner(structured = false).scan(logicalLines(content)).firstOrNull() as? MarkdownBlock.Paragraph)?.spans.orEmpty()

    /** A mensagem em blocos, na ordem em que aparecem. */
    fun blocks(content: String): List<MarkdownBlock> =
        if (content.isEmpty()) emptyList() else Scanner(structured = true).scan(logicalLines(content))

    /** Parte de uma linha: texto ou um bloco de código entre crases triplas. */
    private sealed interface Piece {
        class Text(val text: String) : Piece
        class Code(val text: String) : Piece
    }

    /**
     * Linhas do texto como o autor as escreveu. Um bloco de código cercado cabe numa linha só, mesmo
     * com várias linhas dentro, e a quebra logo depois da cerca de fechamento encerra a linha dele.
     */
    private fun logicalLines(content: String): List<List<Piece>> {
        val lines = ArrayList<List<Piece>>()
        var current = ArrayList<Piece>()
        fun text(segment: String) {
            segment.split('\n').forEachIndexed { index, part ->
                if (index > 0) {
                    lines += current
                    current = ArrayList()
                }
                if (part.isNotEmpty()) current += Piece.Text(part)
            }
        }
        var cursor = 0
        for (block in fence.findAll(content)) {
            text(content.substring(cursor, block.range.first))
            current += Piece.Code(block.groupValues[1].trimEnd('\n'))
            cursor = block.range.last + 1
            if (content.getOrNull(cursor) == '\n') {
                lines += current
                current = ArrayList()
                cursor++
            }
        }
        text(content.substring(cursor))
        lines += current
        return lines
    }

    /** Estilo da linha (título ou citação) mais o conteúdo em linha. */
    private fun lineSpans(raw: String, base: TextSpan = TextSpan("")): List<TextSpan> {
        var line = raw
        var style = base
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
        val out = ArrayList<TextSpan>()
        inline(line, style, out)
        return out
    }

    private fun isTitleOrQuote(line: String) = heading.containsMatchIn(line) || line.startsWith("> ") || line == ">"

    /** Colunas ocupadas por espaços e tabulações (tabulação vai até o próximo múltiplo de 4). */
    private fun columns(whitespace: String): Int {
        var column = 0
        for (c in whitespace) column += if (c == '\t') 4 - column % 4 else 1
        return column
    }

    /**
     * Lê as linhas e monta os blocos. Com [structured] falso só há um parágrafo: nada de lista,
     * régua nem título com sublinhado.
     *
     * Regras iguais às do marked no cliente web: uma linha em branco encerra a lista; o aninhamento
     * segue a coluna em que o texto do item começa; só lista que começa em 1 interrompe um parágrafo;
     * linha comum logo depois de um item continua o item.
     */
    private class Scanner(private val structured: Boolean) {
        /** Linha do texto corrido; [raw] só existe para linha comum, a única que um sublinhado transforma em título. */
        private class Line(val spans: List<TextSpan>, val raw: String? = null)

        /** Lista aberta: coluna onde o texto do último item dela começa, o tipo e o próximo número. */
        private class Frame(var offset: Int, val ordered: Boolean, val delimiter: Char, var next: Int)

        private class PendingItem(val level: Int, val marker: String, val spans: MutableList<TextSpan>)

        private val blocks = ArrayList<MarkdownBlock>()
        private val region = ArrayList<Line>()
        private val frames = ArrayList<Frame>()
        private var item: PendingItem? = null

        fun scan(lines: List<List<Piece>>): List<MarkdownBlock> {
            lines.forEach { line(it) }
            endList()
            // O fim da última linha não é uma linha em branco a mais.
            if (blocks.isNotEmpty() && region.lastOrNull()?.let { it.raw == null && it.spans.isEmpty() } == true) {
                region.removeAt(region.lastIndex)
            }
            flushRegion()
            return blocks
        }

        private fun line(pieces: List<Piece>) {
            val only = pieces.singleOrNull()
            val raw = when {
                pieces.isEmpty() -> ""
                only is Piece.Text -> only.text
                else -> null
            }
            when {
                raw == null -> {
                    endList()
                    region += Line(pieces.flatMap { codeLineSpans(it) })
                }
                raw.isBlank() -> {
                    endList()
                    region += Line(if (raw.isEmpty()) emptyList() else lineSpans(raw))
                }
                else -> text(raw)
            }
        }

        private fun codeLineSpans(piece: Piece): List<TextSpan> = when (piece) {
            is Piece.Text -> lineSpans(piece.text)
            is Piece.Code -> listOf(TextSpan(piece.text, code = true))
        }

        private fun text(raw: String) {
            if (structured && (setext(raw) || startRule(raw) || startItem(raw))) return
            if (isTitleOrQuote(raw)) {
                endList()
                region += Line(lineSpans(raw))
                return
            }
            val open = item
            if (open != null) {
                open.spans += TextSpan("\n")
                inline(raw.trim(), TextSpan(""), open.spans)
            } else {
                region += Line(lineSpans(raw), raw)
            }
        }

        /** `texto` seguido de `---` ou `===`: as linhas comuns de cima viram título (negrito). */
        private fun setext(raw: String): Boolean {
            if (frames.isNotEmpty() || !setextUnderline.matches(raw)) return false
            val paragraph = region.takeLastWhile { it.raw != null }
            // Como no marked: um parágrafo com uma linha de aparência de item não vira título.
            if (paragraph.isEmpty() || paragraph.any { itemStart.matches(it.raw.orEmpty()) }) return false
            repeat(paragraph.size) { region.removeAt(region.lastIndex) }
            for (line in paragraph) region += Line(lineSpans(line.raw.orEmpty(), TextSpan("", bold = true)))
            return true
        }

        private fun startRule(raw: String): Boolean {
            if (!rule.matches(raw)) return false
            endList()
            flushRegion()
            blocks += MarkdownBlock.Rule
            return true
        }

        private fun startItem(raw: String): Boolean {
            val match = itemStart.matchEntire(raw) ?: return false
            val (space, marker, gap, content) = match.destructured
            val indent = columns(space)
            val ordered = marker.length > 1
            val number = if (ordered) marker.dropLast(1).toInt() else 0
            if (frames.isEmpty() && !canStartList(indent, ordered, number)) return false

            flushItem()
            flushRegion()
            val frame = enter(indent, textColumn(space, marker, gap), ordered, marker.last(), number)
            val level = minOf(frames.lastIndex, MAX_LIST_LEVEL)
            val task = taskStart.matchEntire(content)
            val label = markerLabel(task?.groupValues?.get(1), ordered, frame.next, level)
            frame.next++
            val spans = ArrayList<TextSpan>()
            inline((task?.groupValues?.get(2) ?: content).trimEnd(), TextSpan(""), spans)
            item = PendingItem(level, label, spans)
            return true
        }

        /** Coluna onde o texto do item começa; com 5 espaços ou mais depois do marcador, só o primeiro conta. */
        private fun textColumn(space: String, marker: String, gap: String): Int {
            val before = columns(space + marker)
            val width = columns(space + marker + gap) - before
            return before + if (width in 1..4) width else 1
        }

        /** O que aparece no lugar do marcador: caixa de tarefa (`checkbox` é o ` `, `x` ou `X` entre colchetes), número ou bolinha do nível. */
        private fun markerLabel(checkbox: String?, ordered: Boolean, number: Int, level: Int): String = when {
            checkbox != null -> if (checkbox == " ") "☐" else "☑"
            ordered -> "$number."
            else -> bullets[level]
        }

        /** Primeiro item de uma lista: até 3 espaços de recuo e, se o parágrafo de cima está aberto, só o número 1. */
        private fun canStartList(indent: Int, ordered: Boolean, number: Int): Boolean {
            if (indent > 3) return false
            val afterParagraph = region.lastOrNull()?.raw != null
            return !(afterParagraph && ordered && number != 1)
        }

        /**
         * A lista do item novo. O item fica dentro do item aberto de um nível enquanto o recuo alcançar a coluna
         * onde o texto dele começa; senão é irmão do item daquele nível, se o tipo da lista é o mesmo, ou abre
         * uma lista nova nesse nível (trocar `-` por `*`, ou `.` por `)`, começa outra lista, e a numeração volta).
         */
        private fun enter(indent: Int, offset: Int, ordered: Boolean, delimiter: Char, number: Int): Frame {
            var depth = 0
            while (depth < frames.size && indent >= frames[depth].offset) depth++
            while (frames.size > depth + 1) frames.removeAt(frames.lastIndex)
            val same = frames.getOrNull(depth)
            if (same != null && same.ordered == ordered && same.delimiter == delimiter) {
                same.offset = offset
                return same
            }
            val fresh = Frame(offset, ordered, delimiter, number)
            if (same != null) frames[depth] = fresh else frames += fresh
            return fresh
        }

        private fun flushItem() {
            item?.let { blocks += MarkdownBlock.ListItem(it.level, it.marker, merge(it.spans)) }
            item = null
        }

        /** Uma linha em branco, um título, uma citação, um código ou uma régua fecham a lista. */
        private fun endList() {
            flushItem()
            frames.clear()
        }

        private fun flushRegion() {
            if (region.isEmpty()) return
            val spans = ArrayList<TextSpan>()
            region.forEachIndexed { index, line ->
                if (index > 0) spans += TextSpan("\n")
                spans += line.spans
            }
            blocks += MarkdownBlock.Paragraph(merge(spans))
            region.clear()
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
