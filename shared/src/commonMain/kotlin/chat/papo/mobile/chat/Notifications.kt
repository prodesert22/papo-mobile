package chat.papo.mobile.chat

import chat.papo.mobile.core.parseInstant
import chat.papo.mobile.data.api.ExternalAuthorDto
import chat.papo.mobile.data.api.MAX_NOTIFICATION_IDS
import chat.papo.mobile.data.api.NotificationDto

/**
 * Uma linha da caixa de notificações. [content] vem truncado a 512 caracteres pelo servidor, e [createdAt] é o
 * instante da notificação (ISO 8601, como o servidor mandou), um pouco depois do da mensagem.
 */
data class NotificationItem(
    val id: String,
    val messageId: String,
    /** Canal de texto, ou o id da conversa direta: o servidor usa o mesmo id para as duas coisas. */
    val channelId: String,
    val authorId: String?,
    val webhookId: String?,
    val externalAuthor: ExternalAuthorDto?,
    val content: String,
    val read: Boolean,
    val createdAt: String,
) {
    /** [createdAt] em microssegundos desde a época, para ordenar sem depender do formato do texto; null se ilegível. */
    val createdAtUs: Long? = isoMicros(createdAt)
}

internal fun NotificationDto.toItem() = NotificationItem(
    id = id,
    messageId = messageId,
    channelId = channelId,
    authorId = authorId,
    webhookId = webhookId,
    externalAuthor = externalAuthor,
    content = messageContent,
    read = read,
    createdAt = createdAt,
)

internal fun isoMicros(iso: String): Long? {
    val instant = parseInstant(iso) ?: return null
    return instant.epochSeconds * 1_000_000 + instant.nanosecondsOfSecond / 1_000
}

/** Por que a linha existe. O servidor não manda o motivo: ele se deduz do conteúdo ([classify]). */
enum class TriggerKind { Mention, Reply, Everyone, Dm }

/** A menção direta como o servidor a casa (`@mention(<@id>)`, sem diferenciar maiúsculas); `<@id>` sozinho não vale. */
private val directMention = Regex(
    """@mention\(<@([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})>\)""",
    RegexOption.IGNORE_CASE,
)

/**
 * Deduz por que [item] notificou [myUserId], na ordem em que o servidor decide: toda mensagem de conversa direta
 * ([isDm]) gera linha; depois a menção direta a [myUserId]; depois `@everyone` (sem diferenciar maiúsculas, e como o
 * `\b` do servidor, sem letra, dígito ou `_` ASCII logo depois); o que sobra é uma resposta a uma mensagem minha.
 *
 * O conteúdo vem truncado a 512 caracteres: uma menção depois do corte não é vista, e a linha cai em
 * [TriggerKind.Everyone] (se há `@everyone` antes do corte) ou em [TriggerKind.Reply]. Aceitamos o erro: a
 * alternativa seria ler a mensagem inteira, que pode nem estar guardada. A comparação do id é exata, como a do
 * servidor, que casa o texto capturado com o id do usuário.
 */
fun classify(item: NotificationItem, myUserId: String, isDm: Boolean): TriggerKind = when {
    isDm -> TriggerKind.Dm
    directMention.findAll(item.content).any { it.groupValues[1] == myUserId } -> TriggerKind.Mention
    containsEveryone(item.content) -> TriggerKind.Everyone
    else -> TriggerKind.Reply
}

private fun containsEveryone(text: String): Boolean {
    var at = text.indexOf('@')
    while (at >= 0) {
        if (everyoneAt(text, at)) return true
        at = text.indexOf('@', at + 1)
    }
    return false
}

/** Mais nova primeiro: `(createdAt, id)` decrescente, a mesma ordem da listagem do servidor. */
private val newestFirst: Comparator<NotificationItem> =
    compareByDescending<NotificationItem> { it.createdAtUs ?: Long.MIN_VALUE }.thenByDescending { it.id }

/**
 * Junta [page] ao que já se tem, sem repetir id e na ordem `(createdAt, id)` decrescente. Para o mesmo id vale a
 * linha nova (o conteúdo pode ter sido editado), mas uma linha lida nunca volta a não lida: o servidor não
 * desfaz a leitura, então uma cópia atrasada que ainda a mostra não lida está velha.
 */
fun mergePage(old: List<NotificationItem>, page: List<NotificationItem>): List<NotificationItem> {
    val byId = LinkedHashMap<String, NotificationItem>(old.size + page.size)
    for (item in old) byId[item.id] = item
    for (fresh in page) {
        val wasRead = byId[fresh.id]?.read == true
        byId[fresh.id] = if (wasRead && !fresh.read) fresh.copy(read = true) else fresh
    }
    return byId.values.sortedWith(newestFirst)
}

/** Divide [ids] (sem repetir) em blocos que o servidor aceita num `PUT read_notification`. */
fun readBatches(ids: Collection<String>): List<List<String>> = ids.distinct().chunked(MAX_NOTIFICATION_IDS)

/**
 * Leitura de um canal ou conversa até uma mensagem (`PUT /channels/{id}/read` bem-sucedido). [createdAt] é o instante
 * da mensagem, se estava guardada.
 */
data class ReadMark(val channelId: String, val messageId: String, val createdAt: String?)

/** Onde uma mensagem guardada está na lista do canal: a ordem é `(createdUs, id)`, a mesma do servidor. */
data class MessagePosition(val createdUs: Long, val id: String) : Comparable<MessagePosition> {
    override fun compareTo(other: MessagePosition): Int =
        compareValuesBy(this, other, MessagePosition::createdUs, MessagePosition::id)
}

/**
 * Espelha no aparelho o que o servidor faz em `PUT /channels/{id}/read`: as linhas não lidas do canal cuja mensagem
 * está na posição da marca ou antes dela passam a lidas, e o servidor não manda evento. [positionOf] diz onde uma
 * mensagem guardada está (null se não está).
 *
 * Com as duas mensagens guardadas vale a posição, como no servidor. Sem isso, a linha da própria mensagem da marca
 * conta, e as outras comparam o instante da notificação com o da mensagem da marca, como o web: a notificação sai um
 * pouco depois da mensagem, então o erro é sempre deixar uma linha não lida (que a próxima leitura da caixa
 * corrige), nunca marcar uma que o servidor não marcou. Sem o instante da marca, só vale a própria mensagem dela.
 */
fun markReadUpTo(
    items: List<NotificationItem>,
    mark: ReadMark,
    positionOf: (messageId: String) -> MessagePosition?,
): List<NotificationItem> {
    val markPosition = positionOf(mark.messageId)
    val markUs = mark.createdAt?.let(::isoMicros)
    return items.map { item ->
        if (item.read || item.channelId != mark.channelId) return@map item
        val itemPosition = positionOf(item.messageId)
        val covered = when {
            markPosition != null && itemPosition != null -> itemPosition <= markPosition
            item.messageId == mark.messageId -> true
            else -> markUs != null && item.createdAtUs != null && item.createdAtUs <= markUs
        }
        if (covered) item.copy(read = true) else item
    }
}

/**
 * O que as menções somam. [perChannel] tem só canais com algo a contar; [perDm] vem de `unread_count` das conversas
 * diretas; [total] é a soma dos dois. [capped] diz que a caixa não foi lida inteira: pode haver mais além do contado.
 */
data class MentionCounts(
    val perChannel: Map<String, Int>,
    val perDm: Map<String, Int>,
    val total: Int,
    val capped: Boolean,
)

/**
 * Conta as menções não lidas. Cada linha não lida de [items] que não é de uma conversa direta conta para o canal
 * dela se [counts] aceita o par (canal, motivo): é o predicado quem aplica "suprimir `@everyone`" e "suprimir
 * menções", e quem recusa canais que a tela não mostra (a linha de uma conversa direta fechada, que não está em
 * [dms], chega aqui como um canal desconhecido). As conversas diretas contam o `unread_count` do servidor, exato e
 * sem depender das linhas. [capped] é o da caixa e segue em frente.
 */
fun mentionCounts(
    items: List<NotificationItem>,
    dms: List<Dm>,
    myUserId: String,
    capped: Boolean,
    counts: (channelId: String, kind: TriggerKind) -> Boolean,
): MentionCounts {
    val dmIds = dms.mapTo(HashSet()) { it.id }
    val perChannel = HashMap<String, Int>()
    for (item in items) {
        if (item.read || item.channelId in dmIds) continue
        if (counts(item.channelId, classify(item, myUserId, isDm = false))) perChannel[item.channelId] = (perChannel[item.channelId] ?: 0) + 1
    }
    val perDm = dms.filter { it.unreadCount > 0 }.associate { it.id to it.unreadCount }
    return MentionCounts(perChannel, perDm, perChannel.values.sum() + perDm.values.sum(), capped)
}

/** O teto que o selo mostra antes do `+`. */
private const val BADGE_LIMIT = 99

/**
 * O texto do selo para [n] menções: null sem nada a mostrar, `99+` acima de 99 e `n+` quando a caixa foi
 * lida só em parte ([capped]), porque há mais além do contado.
 */
fun badgeText(n: Int, capped: Boolean): String? = when {
    n <= 0 -> null
    n > BADGE_LIMIT -> "$BADGE_LIMIT+"
    capped -> "$n+"
    else -> n.toString()
}
