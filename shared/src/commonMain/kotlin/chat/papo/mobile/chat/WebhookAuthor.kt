package chat.papo.mobile.chat

/** O que um id de webhook pode ter para entrar num caminho sem mudá-lo: nada de barra, `?`, `#` nem `%`. */
private val WEBHOOK_ID = Regex("[0-9A-Za-z-]+")

/**
 * O id do webhook cujo avatar o app busca no servidor da conta, ou null para ficar com as iniciais.
 *
 * O servidor preenche `external_author.avatar_url` com `/webhooks/<id>/avatar` quando o webhook tem um avatar
 * persistente; quem envia pode pôr outra coisa, como uma URL absoluta. Só vale o caminho exato do próprio webhook
 * da mensagem: qualquer outro valor não é pedido, porque a busca entregaria o endereço de quem lê a um terceiro e
 * passaria por fora do cliente da conta.
 */
fun webhookAvatarId(webhookId: String?, avatarUrl: String?): String? {
    if (webhookId == null || avatarUrl == null) return null
    if (!WEBHOOK_ID.matches(webhookId)) return null
    return webhookId.takeIf { avatarUrl == "/webhooks/$webhookId/avatar" }
}
