package chat.papo.mobile.chat

/** Pedaço de um texto: letras comuns ou um emoji do servidor escrito como `:nome:`. */
sealed interface EmojiPiece {
    data class Plain(val text: String) : EmojiPiece
    data class Custom(val name: String, val id: String) : EmojiPiece
}

/** Um `:nome:` de um texto que é emoji do servidor. [start] é inclusivo e [end] exclusivo, em unidades UTF-16. */
data class EmojiMatch(val start: Int, val end: Int, val name: String, val id: String) {
    val length: Int get() = end - start
}

/** `:nome:` e mensagens só de emoji. Sem tela nem rede: só regras sobre o texto. */
object EmojiText {
    // Mesma regra do cliente web: letras, números e sublinhado entre dois-pontos.
    private val shortcode = Regex(""":([A-Za-z0-9_]+):""")

    /**
     * Os `:nome:` de [text] que o servidor tem, em ordem. Um nome que o servidor não conhece não entra
     * (`10:30:45` não vira nada). O que cai em [literal] (trechos de código ou de link, ver [Markdown.literalRanges]) fica como texto.
     */
    fun matches(text: String, idByName: Map<String, String>, literal: List<IntRange> = emptyList()): List<EmojiMatch> {
        if (idByName.isEmpty() || ':' !in text) return emptyList()
        val out = ArrayList<EmojiMatch>()
        for (match in shortcode.findAll(text)) {
            val name = match.groupValues[1]
            val id = idByName[name] ?: continue
            if (literal.any { it.first <= match.range.last && match.range.first <= it.last }) continue
            out += EmojiMatch(match.range.first, match.range.last + 1, name, id)
        }
        return out
    }

    /**
     * Separa [text] em trechos comuns e emojis do servidor. Um `:nome:` que o servidor
     * não tem fica como texto, do jeito que foi escrito (`10:30:45` não vira nada).
     */
    fun split(text: String, idByName: Map<String, String>): List<EmojiPiece> {
        val out = ArrayList<EmojiPiece>()
        var last = 0
        for (match in matches(text, idByName)) {
            if (match.start > last) out += EmojiPiece.Plain(text.substring(last, match.start))
            out += EmojiPiece.Custom(match.name, match.id)
            last = match.end
        }
        if (last < text.length || out.isEmpty()) out += EmojiPiece.Plain(text.substring(last))
        return out
    }

    /**
     * A mensagem tem só emojis (do servidor ou Unicode) e espaços? Então aparece maior,
     * como nos outros clientes. Passa de [max] emojis, volta ao tamanho normal.
     */
    fun isEmojiOnly(
        content: String,
        idByName: Map<String, String>,
        unicode: UnicodeEmojiCatalog?,
        max: Int = MAX_JUMBO,
    ): Boolean {
        var count = 0
        var i = 0
        while (i < content.length) {
            val c = content[i]
            if (c.isWhitespace()) {
                i++
                continue
            }
            val custom = if (c == ':') shortcode.matchAt(content, i) else null
            val length = when {
                custom != null && custom.groupValues[1] in idByName -> custom.value.length
                unicode != null -> unicode.emojiLengthAt(content, i)
                else -> 0
            }
            if (length == 0) return false
            count++
            if (count > max) return false
            i += length
        }
        return count > 0
    }

    const val MAX_JUMBO = 27
}
