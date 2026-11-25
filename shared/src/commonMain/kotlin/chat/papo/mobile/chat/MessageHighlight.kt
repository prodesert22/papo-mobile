package chat.papo.mobile.chat

/**
 * A [message] é dirigida a [userId], quem usa a conta: menciona essa pessoa (`@mention(<@id>)` no
 * conteúdo) ou responde a uma mensagem dela. [repliedTo] é a mensagem respondida, se já está carregada;
 * sem ela não há como saber de quem é, e a resposta não conta.
 *
 * Mensagem da própria pessoa nunca é dirigida a ela, mesmo se mencionar ou responder a si mesma.
 * `@everyone` não entra aqui: depende das permissões do cargo, que o app ainda não lê.
 */
fun isAddressedTo(userId: String, message: Message, repliedTo: Message?): Boolean {
    if (message.authorId == userId) return false
    if (userId in mentionedIds(message.content)) return true
    return repliedTo?.authorId == userId
}
