package chat.papo.mobile.chat

/** O mais que o texto de uma mensagem leva para a notificação; o resto o sistema cortaria de qualquer jeito. */
private const val MAX_TEXT = 300

/** O mais que um nome leva para a notificação. */
private const val MAX_NAME = 64

private val whitespace = Regex("""\s+""")

/**
 * O texto de uma mensagem para a notificação: simples, numa linha, sem marcação (regra 6: o conteúdo remoto é só texto).
 * O Markdown sai (fica o que ele mostra), `@mention(<@id>)` vira `@nome` pelo [nameOf] (ou `@` e o nome desconhecido),
 * e o que passa de [MAX_TEXT] é cortado com reticências. Uma mensagem sem texto mas com anexo vira só "Anexo": o nome do
 * arquivo e o conteúdo dele nunca entram, e por isso um anexo que o servidor marcou como sensível também não aparece.
 */
fun notificationText(content: String?, hasAttachments: Boolean, nameOf: (String) -> String?, texts: NotificationTexts): String {
    val plain = Markdown.parse(content.orEmpty()).joinToString("") { span ->
        val mentioned = span.mentionUserId
        if (mentioned == null) span.text else "@" + (nameOf(mentioned)?.let(::cleanName)?.takeIf { it.isNotEmpty() } ?: texts.unknownUser)
    }
    val text = clean(plain)
    return when {
        text.isNotEmpty() -> ellipsize(text, MAX_TEXT)
        hasAttachments -> texts.attachment
        else -> texts.emptyMessage
    }
}

/** O nome de quem escreveu, limpo como o texto, ou [fallback] se não sobra nada. */
fun notificationName(name: String?, fallback: String): String =
    name?.let(::cleanName)?.takeIf { it.isNotEmpty() } ?: fallback

private fun cleanName(name: String): String = ellipsize(clean(name), MAX_NAME)

/** Sem controle, sem as marcas que invertem o sentido da leitura, e com cada trecho de espaço virando um espaço. */
private fun clean(text: String): String =
    text.filterNot { it.isISOControl() && !it.isWhitespace() || it in BIDI_CONTROLS }.replace(whitespace, " ").trim()

private val BIDI_CONTROLS = ('‪'..'‮') + ('⁦'..'⁩') + '‎' + '‏' + '؜'

private fun ellipsize(text: String, max: Int): String {
    if (text.length <= max) return text
    // Não corta no meio de um par substituto: sobraria meio emoji.
    val end = if (text[max - 1].isHighSurrogate()) max - 1 else max
    return text.substring(0, end).trimEnd() + "…"
}
