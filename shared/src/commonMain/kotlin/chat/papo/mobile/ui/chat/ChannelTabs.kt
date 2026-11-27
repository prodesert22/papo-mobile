package chat.papo.mobile.ui.chat

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import chat.papo.mobile.chat.MentionCounts
import chat.papo.mobile.chat.badgeText

/** As duas abas da lista da conta: os canais e as conversas diretas. */
enum class ListTab { Channels, Direct }

/** O selo de menções de cada aba; nulo onde não há nada a mostrar. */
data class TabBadges(val channels: String?, val direct: String?)

/**
 * O que cada aba soma, com as mesmas contagens que as linhas mostram: [MentionCounts.perChannel] já traz só os canais
 * que a lista exibe, e [MentionCounts.perDm] o `unread_count` do servidor, exato. Só a dos canais pode ser parcial
 * ([MentionCounts.capped] é da caixa de notificações, que não tem linhas de conversa direta).
 */
fun tabBadges(counts: MentionCounts): TabBadges = TabBadges(
    channels = badgeText(counts.perChannel.values.sum(), counts.capped),
    direct = badgeText(counts.perDm.values.sum(), capped = false),
)

/**
 * A aba que cada conta deixou escolhida. Fica só na memória (e no estado salvo ao girar a tela): sem escolha, ou depois
 * de o app reiniciar, a conta abre nos canais. O valor é imutável; quem muda recebe outro.
 */
class ListTabs private constructor(private val byAccount: Map<String, ListTab>) {
    operator fun get(accountId: String): ListTab = byAccount[accountId] ?: ListTab.Channels

    /** [tab] passa a ser a aba de [accountId]. Os canais são o padrão e não ocupam entrada. */
    fun selected(accountId: String, tab: ListTab): ListTabs = when {
        this[accountId] == tab -> this
        tab == ListTab.Channels -> ListTabs(byAccount - accountId)
        else -> ListTabs(byAccount + (accountId to tab))
    }

    /** Conta e nome da aba, em fila, para o estado salvo. */
    fun encode(): List<String> = byAccount.flatMap { (accountId, tab) -> listOf(accountId, tab.name) }

    override fun equals(other: Any?): Boolean = other is ListTabs && byAccount == other.byAccount

    override fun hashCode(): Int = byAccount.hashCode()

    companion object {
        val Empty = ListTabs(emptyMap())

        /** O inverso de [encode]. Um par incompleto, sem conta ou com aba desconhecida é descartado. */
        fun decode(values: List<String>): ListTabs {
            val byAccount = LinkedHashMap<String, ListTab>()
            for (pair in values.chunked(2)) {
                val tab = ListTab.entries.firstOrNull { it.name == pair.getOrNull(1) } ?: continue
                if (pair[0].isNotEmpty() && tab != ListTab.Channels) byAccount[pair[0]] = tab
            }
            return if (byAccount.isEmpty()) Empty else ListTabs(byAccount)
        }

        val Saver: Saver<ListTabs, Any> = listSaver(save = { it.encode() }, restore = { decode(it) })
    }
}
