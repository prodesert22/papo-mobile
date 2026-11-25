package chat.papo.mobile.chat

import chat.papo.mobile.core.dayKey
import kotlinx.datetime.TimeZone
import kotlin.time.Duration.Companion.minutes

/** Janela de agrupamento, a mesma do cliente web. */
private val GROUP_WINDOW = 10.minutes

/**
 * A mensagem [newer], logo abaixo de [older] na conversa, continua o grupo dela (sem avatar nem nome)?
 *
 * Como no cliente web: o mesmo autor, ou o mesmo webhook, dentro de 10 minutos, e a mais nova não é resposta.
 * Também quebra o grupo uma mensagem sem horário, uma que veio antes da outra e a virada do dia (o
 * separador de data aparece ali). Duas mensagens sem autor conhecido nunca se agrupam: não dá para saber
 * se são da mesma pessoa. Mensagens de webhook que trazem nomes de exibição diferentes também ficam separadas.
 */
fun continuesGroup(older: Message, newer: Message, zone: TimeZone = TimeZone.currentSystemDefault()): Boolean {
    if (newer.replyTo != null) return false
    val before = older.createdAt ?: return false
    val after = newer.createdAt ?: return false
    val gap = after - before
    if (gap.isNegative() || gap > GROUP_WINDOW) return false
    return before.dayKey(zone) == after.dayKey(zone) && sameSender(older, newer)
}

private fun sameSender(a: Message, b: Message): Boolean = when {
    a.webhookId != null || b.webhookId != null ->
        a.webhookId == b.webhookId && a.externalAuthor?.name == b.externalAuthor?.name
    else -> a.authorId != null && a.authorId == b.authorId
}
