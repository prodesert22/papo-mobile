package chat.papo.mobile.chat

/** O `:prefixo` que o usuário está digitando: onde começa (o `:`) e o texto depois dele. */
data class EmojiQuery(val start: Int, val text: String)

/** Resultado de trocar um trecho do rascunho: o texto novo e onde fica o cursor. */
data class EmojiEdit(val text: String, val cursor: Int)

/**
 * Autocompletar de emoji no rascunho: `:pol` sugere emojis e `:thumbsup:` vira 👍 ao
 * digitar o segundo dois-pontos. Só regras sobre texto, sem tela.
 */
object EmojiCompletion {
    /** Menos que isso é cedo demais para sugerir (`:)` e `:D` são carinhas, não emoji). */
    const val MIN_QUERY = 2
    private const val MAX_QUERY = 32

    private fun isNameChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '+' || c == '-'

    /** O `:prefixo` imediatamente antes de [cursor], ou null se ali não se está digitando um. */
    fun queryBefore(text: String, cursor: Int): EmojiQuery? {
        if (cursor < 1 || cursor > text.length) return null
        var i = cursor
        while (i > 0 && isNameChar(text[i - 1])) i--
        if (i < 1 || text[i - 1] != ':') return null
        val colon = i - 1
        // O `:` precisa abrir uma palavra; em `10:30` ou `https://x` não é emoji.
        if (colon > 0 && !(text[colon - 1].isWhitespace() || text[colon - 1] in "(\"'")) return null
        val name = text.substring(i, cursor)
        return if (name.length in MIN_QUERY..MAX_QUERY) EmojiQuery(colon, name) else null
    }

    /** Troca o [query] pelo [replacement] no rascunho. */
    fun replace(text: String, cursor: Int, query: EmojiQuery, replacement: String): EmojiEdit {
        val updated = text.substring(0, query.start) + replacement + text.substring(cursor)
        return EmojiEdit(updated, query.start + replacement.length)
    }

    /**
     * Se o usuário acabou de digitar o `:` que fecha um atalho Unicode completo
     * (`:+1:`, `:joy:`), devolve o texto com o emoji no lugar. [previous] é o rascunho
     * antes da tecla e [current] o de agora; só vale para uma tecla `:` no cursor.
     * Um nome que também existe como emoji do servidor fica como texto, para virar a imagem
     * do servidor na mensagem.
     */
    fun closeShortcode(
        previous: String,
        current: String,
        cursor: Int,
        catalog: UnicodeEmojiCatalog,
        serverNames: Set<String>,
    ): EmojiEdit? {
        if (current.length != previous.length + 1 || cursor < 1 || cursor > current.length) return null
        if (current[cursor - 1] != ':' || current.removeRange(cursor - 1, cursor) != previous) return null
        val query = queryBefore(current, cursor - 1) ?: return null
        if (query.text in serverNames) return null
        // O atalho tem que estar escrito como é (minúsculas e sublinhados), sem aproximação.
        val emoji = catalog.forShortcode(query.text)?.takeIf { query.text == query.text.lowercase() } ?: return null
        return replace(current, cursor, query, emoji.char)
    }

    /**
     * [closeShortcode] para o que o campo acabou de mudar, que só vale para uma tecla digitada: não no meio de uma composição
     * do teclado ([composing]), não com uma seleção (o cursor tem de estar parado em [selectionStart]) e não enquanto o
     * [catalog] ainda carrega (null).
     */
    fun closeTypedShortcode(
        previous: String,
        current: String,
        selectionStart: Int,
        selectionEnd: Int,
        composing: Boolean,
        catalog: UnicodeEmojiCatalog?,
        serverNames: Set<String>,
    ): EmojiEdit? {
        if (catalog == null || composing || selectionStart != selectionEnd) return null
        return closeShortcode(previous, current, selectionStart, catalog, serverNames)
    }
}
