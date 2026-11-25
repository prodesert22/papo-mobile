package chat.papo.mobile.chat

/**
 * A [message] é dirigida a [userId], quem usa a conta: menciona essa pessoa (`@mention(<@id>)` no
 * conteúdo) ou responde a uma mensagem dela. [repliedTo] é a mensagem respondida, se já está carregada;
 * sem ela não há como saber de quem é, e a resposta não conta.
 *
 * Mensagem da própria pessoa nunca é dirigida a ela, mesmo se mencionar ou responder a si mesma.
 * `@everyone` não entra aqui: depende do cargo do autor, e a regra do destaque dourado, que o inclui, é
 * [isHighlightedFor].
 */
fun isAddressedTo(userId: String, message: Message, repliedTo: Message?): Boolean {
    if (message.authorId == userId) return false
    if (userId in mentionedIds(message.content)) return true
    return repliedTo?.authorId == userId
}

/**
 * A [message] ganha o destaque dourado para [userId]: é dirigida a ele ([isAddressedTo]) ou é um `@everyone`
 * de um autor que pode usá-lo. [authorCanEveryone] é o resultado de `authorCanEveryone(...)` para o autor da
 * mensagem (webhook nunca pode). Como no web, a mensagem da própria pessoa com `@everyone` também fica
 * dourada: é a única exceção a "mensagem minha não destaca", e vale só para o `@everyone`, nunca para
 * menção ou resposta.
 */
fun isHighlightedFor(userId: String, message: Message, repliedTo: Message?, authorCanEveryone: Boolean): Boolean =
    isAddressedTo(userId, message, repliedTo) || (authorCanEveryone && isEveryoneMessage(message.content))

/**
 * O texto cru de [content] contém `@everyone` como o web o reconhece: sem diferenciar maiúsculas, no início
 * ou depois de um espaço, e sem letra, dígito ou `_` ASCII logo depois (o `\b` do servidor e do web, que não
 * trata `é` como letra). Trechos de código, em linha ou em bloco, não contam, porque o Markdown os mostra
 * como estão. Só diz que o texto tem o trecho; se ele vale (o autor pode usá-lo) é de [authorCanEveryone].
 *
 * O servidor casa `@everyone` em qualquer lugar do texto, até em código: pode haver aviso sem destaque
 * (`x@everyone`, `` `@everyone` ``). O destaque segue o web e não promete mais que isso.
 */
fun isEveryoneMessage(content: String): Boolean {
    var at = content.indexOf(EVERYONE_MENTION, ignoreCase = true)
    var code: List<IntRange>? = null
    while (at >= 0) {
        if ((at == 0 || content[at - 1].isWhitespace()) && everyoneAt(content, at)) {
            val ranges = code ?: codeRanges(content).also { code = it }
            if (ranges.none { at in it }) return true
        }
        at = content.indexOf(EVERYONE_MENTION, at + 1, ignoreCase = true)
    }
    return false
}
