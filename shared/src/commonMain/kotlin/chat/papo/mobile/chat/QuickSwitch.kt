package chat.papo.mobile.chat

import chat.papo.mobile.core.parseInstant
import kotlin.time.Instant

/**
 * O que uma conta já tem carregado para a troca rápida. [accountId] é a identidade da conta (servidor e
 * usuário): cada fonte vem de uma única sessão, e tudo o que sai dela carrega esse id de volta.
 */
data class SwitchSource(
    val accountId: String,
    /** Nome da conta como o usuário a vê (apelido ou nome do servidor). */
    val label: String,
    val myUserId: String,
    val channels: List<Channel>,
    val dms: List<Dm>,
    val users: Map<String, User>,
)

enum class SwitchKind { Text, Voice, Dm }

/** Uma conversa que dá para abrir, com a conta a que pertence. */
data class SwitchEntry(
    val accountId: String,
    val accountLabel: String,
    /** Id que abre a conversa: o do canal, ou o da conversa direta. */
    val channelId: String,
    val kind: SwitchKind,
    val name: String,
    /** `@usuário` de uma conversa direta, para achá-la também por ele; nulo nos canais. */
    val username: String?,
    val unread: Boolean,
    /** Ver [switchEntries]: só dados já carregados. */
    val mention: Boolean,
    /** Mensagens não lidas de uma conversa direta; 0 nos canais, que só dizem se há ou não. */
    val unreadCount: Int,
    val lastActivity: Instant?,
) {
    /** Chave única na lista: o mesmo id de canal em dois servidores são duas entradas. */
    val key: String get() = "$accountId/$channelId"
}

/** A conversa aberta agora; ela não aparece na troca. */
data class OpenConversation(val accountId: String, val channelId: String)

/**
 * Conversas de todas as contas, na ordem da troca rápida: menções, depois não lidas, depois as de atividade mais
 * recente (comparando os instantes, não os textos) e por fim o nome sem acento. Categorias e a conversa [open] ficam
 * de fora; canais de voz entram, porque têm chat. Canal silenciado (`off`) não conta como não lido.
 *
 * "Menção" usa só o que está carregado, porque ainda não há contagem de menções não lidas por conversa: é uma
 * conversa direta com mensagens não lidas, ou um canal não lido cuja última mensagem menciona o próprio usuário.
 * Se chegou depois uma mensagem sem menção, a menção deixa de contar.
 */
fun switchEntries(sources: List<SwitchSource>, open: OpenConversation? = null): List<SwitchEntry> =
    sources
        .flatMap { it.channelEntries() + it.dmEntries() }
        .filterNot { open != null && it.accountId == open.accountId && it.channelId == open.channelId }
        .map { RankedEntry(it, foldForSearch(it.name)) }
        .sortedWith(rankOrder)
        .map { it.entry }

/**
 * Entradas cujo nome (ou `@usuário`) casa com [query], sem diferenciar acento nem maiúsculas, e depois as que só casam
 * com o nome da conta. Dentro de cada grupo vale a ordem de [entries], a de [switchEntries]. Consulta vazia devolve tudo.
 */
fun filterSwitchEntries(entries: List<SwitchEntry>, query: String): List<SwitchEntry> {
    val byName = searchByName(query, entries) { listOfNotNull(it.name, it.username) }
    if (byName.size == entries.size) return byName
    val matched = byName.toHashSet()
    return byName + searchByName(query, entries.filter { it !in matched }) { listOf(it.accountLabel) }
}

private class RankedEntry(val entry: SwitchEntry, val nameKey: String)

private val rankOrder: Comparator<RankedEntry> = compareBy<RankedEntry> { tier(it.entry) }
    .thenComparator { a, b -> newestFirst(a.entry.lastActivity, b.entry.lastActivity) }
    .thenBy { it.nameKey }
    .thenBy { it.entry.name }

private fun tier(entry: SwitchEntry) = if (entry.mention) 0 else if (entry.unread) 1 else 2

/** Mais recente primeiro; quem não tem atividade conhecida vem depois de todos. */
private fun newestFirst(a: Instant?, b: Instant?): Int = when {
    a == null && b == null -> 0
    a == null -> 1
    b == null -> -1
    else -> b.compareTo(a)
}

private fun SwitchSource.channelEntries(): List<SwitchEntry> = channels
    .filter { it.type != ChannelType.Category }
    .map { channel ->
        val unread = channel.unread && channel.notification != NOTIFICATIONS_OFF
        SwitchEntry(
            accountId = accountId,
            accountLabel = label,
            channelId = channel.id,
            kind = if (channel.type == ChannelType.Voice) SwitchKind.Voice else SwitchKind.Text,
            name = channel.name,
            username = null,
            unread = unread,
            mention = unread && mentionsUser(channel.lastMessageText, myUserId),
            unreadCount = 0,
            lastActivity = parseInstant(channel.lastMessageAt),
        )
    }

private fun SwitchSource.dmEntries(): List<SwitchEntry> = dms.map { dm ->
    val user = users[dm.userId]
    val unread = dm.unreadCount > 0
    SwitchEntry(
        accountId = accountId,
        accountLabel = label,
        channelId = dm.id,
        kind = SwitchKind.Dm,
        name = user?.displayName ?: UNKNOWN_USER,
        username = user?.username,
        unread = unread,
        mention = unread,
        unreadCount = dm.unreadCount,
        lastActivity = parseInstant(dm.lastMessageAt),
    )
}

private fun mentionsUser(text: String?, userId: String): Boolean =
    text != null && mentionedIds(text).any { it.equals(userId, ignoreCase = true) }

/** Mesmo valor que a lista de conversas mostra quando o usuário da conversa direta não está carregado. */
private const val UNKNOWN_USER = "?"

private const val NOTIFICATIONS_OFF = "off"
