package chat.papo.mobile.chat

/**
 * Quantas mensagens de outras pessoas chegaram depois de [previousNewestId], a mais nova que a tela já conhecia.
 *
 * [messages] vem da mais nova para a mais antiga, como na lista da conversa. As minhas ([me]) não contam:
 * quem escreve já sabe o que mandou. Devolve 0 quando [previousNewestId] não está na janela (ela foi refeita por
 * um salto, ou a mensagem foi apagada): sem esse marco não dá para saber o que veio depois.
 */
fun newArrivals(previousNewestId: String?, messages: List<Message>, me: String): Int {
    if (previousNewestId == null) return 0
    val boundary = messages.indexOfFirst { it.id == previousNewestId }
    if (boundary <= 0) return 0
    return messages.subList(0, boundary).count { it.authorId != me }
}
