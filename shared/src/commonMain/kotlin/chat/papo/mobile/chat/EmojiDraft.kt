package chat.papo.mobile.chat

/**
 * O texto do compositor e a seleção dele, em unidades UTF-16. [selectionStart] e [selectionEnd] são iguais quando
 * só há o cursor; [selectionStart] fica depois de [selectionEnd] numa seleção feita de trás para frente.
 */
data class DraftField(val text: String, val selectionStart: Int, val selectionEnd: Int = selectionStart) {
    val collapsed: Boolean get() = selectionStart == selectionEnd
}

/**
 * Os emojis do servidor no rascunho. O texto continua `:nome:`, o que se guarda, se envia, se copia e o web e a TUI entendem:
 * o compositor só os desenha como imagem. Cada um conta como um caractere para o cursor, o apagar e a seleção.
 * O texto mostrado tem um caractere no lugar de cada `:nome:`, e as funções daqui convertem as posições de um texto no outro.
 * Sem tela: só regras sobre o texto.
 */
object EmojiDraft {
    /**
     * Os `:nome:` de [text] que a mensagem vai mostrar como imagem: os mesmos da regra de [EmojiText.matches], menos os
     * que ficam dentro de código ou de link, onde o parser da mensagem deixa o texto como foi escrito.
     */
    fun find(text: String, idByName: Map<String, String>): List<EmojiMatch> =
        if (idByName.isEmpty() || ':' !in text) emptyList() else EmojiText.matches(text, idByName, Markdown.literalRanges(text))

    /** A posição [offset] do rascunho no texto mostrado. Dentro de um emoji vale o começo dele. */
    fun toShown(emojis: List<EmojiMatch>, offset: Int): Int {
        var hidden = 0
        for (emoji in emojis) {
            if (emoji.end <= offset) {
                hidden += emoji.length - 1
            } else {
                return (if (emoji.start < offset) emoji.start else offset) - hidden
            }
        }
        return offset - hidden
    }

    /** A posição [shown] do texto mostrado no rascunho: o começo do emoji antes do caractere dele e o fim dele depois. */
    fun toDraft(emojis: List<EmojiMatch>, shown: Int): Int {
        var hidden = 0
        for (emoji in emojis) {
            val at = emoji.start - hidden
            if (shown <= at) break
            if (shown == at + 1) return emoji.end
            hidden += emoji.length - 1
        }
        return shown + hidden
    }

    /**
     * O que o campo entrega depois de uma tecla, de um colar ou de um toque ([current]) acertado para o emoji ser um
     * caractere só: uma edição que pega parte de um `:nome:` do [previous] pega o emoji todo (o apagar logo depois dele
     * leva o `:nome:` inteiro), um texto que entra no meio de um emoji entra depois dele, e o cursor ou a seleção
     * nunca ficam no meio de um. [previousEmojis] são os de `previous.text`, e [idByName] os da conta.
     */
    fun settle(previous: DraftField, previousEmojis: List<EmojiMatch>, current: DraftField, idByName: Map<String, String>): DraftField {
        val edited = if (current.text == previous.text) current else keepWhole(previous.text, previousEmojis, current)
        val emojis = if (edited.text == previous.text) previousEmojis else find(edited.text, idByName)
        return snapToBoundaries(edited, emojis, from = previous.takeIf { edited.text == previous.text })
    }

    /** [old] teve o trecho [start, end) trocado por um texto de [insertedLength] caracteres, que começa em [start] no texto novo. */
    private class Edit(val start: Int, val end: Int, val insertedLength: Int)

    private fun diff(old: String, current: DraftField): Edit {
        val text = current.text
        val limit = minOf(old.length, text.length)
        var prefix = 0
        while (prefix < limit && old[prefix] == text[prefix]) prefix++
        // O cursor de depois da edição marca onde ela terminou (digitar, colar e apagar deixam o cursor no fim do que entrou).
        // Sem ele, `:a::b:` apagado logo depois do primeiro emoji se confundiria com o apagar do segundo.
        if (current.collapsed && old.endsWith(text.substring(current.selectionEnd))) {
            val end = old.length - (text.length - current.selectionEnd)
            val start = minOf(prefix, current.selectionEnd, end)
            return Edit(start, end, current.selectionEnd - start)
        }
        var suffix = 0
        while (suffix < limit - prefix && old[old.length - 1 - suffix] == text[text.length - 1 - suffix]) suffix++
        return Edit(prefix, old.length - suffix, text.length - suffix - prefix)
    }

    private fun keepWhole(old: String, oldEmojis: List<EmojiMatch>, current: DraftField): DraftField {
        if (oldEmojis.isEmpty()) return current
        val edit = diff(old, current)
        var start = edit.start
        var end = edit.end
        for (emoji in oldEmojis) {
            if (start == end) {
                if (emoji.start < start && start < emoji.end) {
                    start = emoji.end
                    end = emoji.end
                }
            } else if (start < emoji.end && end > emoji.start && (start > emoji.start || end < emoji.end)) {
                start = minOf(start, emoji.start)
                end = maxOf(end, emoji.end)
            }
        }
        if (start == edit.start && end == edit.end) return current
        val inserted = current.text.substring(edit.start, edit.start + edit.insertedLength)
        return DraftField(old.substring(0, start) + inserted + old.substring(end), start + inserted.length)
    }

    /**
     * Tira o cursor ou a ponta da seleção do meio de um emoji. O cursor segue o sentido em que andou desde
     * [from] (a seleção de antes, se o texto não mudou); sem isso vai para a borda mais perto, e no empate para o fim.
     * Uma seleção cresce até pegar o emoji inteiro.
     */
    private fun snapToBoundaries(field: DraftField, emojis: List<EmojiMatch>, from: DraftField?): DraftField {
        if (emojis.isEmpty()) return field
        val low = minOf(field.selectionStart, field.selectionEnd)
        val high = maxOf(field.selectionStart, field.selectionEnd)
        val lowEmoji = emojis.firstOrNull { it.start < low && low < it.end }
        val highEmoji = emojis.firstOrNull { it.start < high && high < it.end }
        if (field.collapsed) {
            val emoji = lowEmoji ?: return field
            val forward = if (from != null && from.collapsed) low > from.selectionStart else low - emoji.start >= emoji.end - low
            val caret = if (forward) emoji.end else emoji.start
            return DraftField(field.text, caret)
        }
        val newLow = lowEmoji?.start ?: low
        val newHigh = highEmoji?.end ?: high
        return if (field.selectionStart <= field.selectionEnd) {
            DraftField(field.text, newLow, newHigh)
        } else {
            DraftField(field.text, newHigh, newLow)
        }
    }
}
