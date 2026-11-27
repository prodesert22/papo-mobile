package chat.papo.mobile.ui.home

import chat.papo.mobile.ui.chat.MessageTarget

/** Uma conversa do painel: o canal e, quando ela abriu numa mensagem (uma busca, um áudio), qual. */
data class ParkedChat(val channelId: String, val target: MessageTarget? = null)

/**
 * A última conversa aberta em cada conta. A chave é o id da conta, o par (servidor, usuário): a conversa de uma conta
 * nunca aparece em outra, nem em duas contas do mesmo servidor. O valor é imutável; quem muda recebe outro.
 */
class ParkedChats private constructor(private val byAccount: Map<String, ParkedChat>) {
    operator fun get(accountId: String): ParkedChat? = byAccount[accountId]

    val entries: Map<String, ParkedChat> get() = byAccount

    /** [chat] passa a ser a última conversa de [accountId]; a que ela tinha sai. */
    fun opened(accountId: String, chat: ParkedChat): ParkedChats =
        if (byAccount[accountId] == chat) this else ParkedChats(byAccount + (accountId to chat))

    /** Só as contas de [accountIds] ficam: a conta removida leva a conversa dela. */
    fun retaining(accountIds: Set<String>): ParkedChats {
        val kept = byAccount.filterKeys { it in accountIds }
        return if (kept.size == byAccount.size) this else ParkedChats(kept)
    }

    /** Os campos de cada conversa, em fila, para o estado salvo: conta, canal, id da mensagem, instante dela (vazios sem mensagem) e `~` se o instante é aproximado. */
    fun encode(): List<String> = byAccount.flatMap { (accountId, chat) ->
        listOf(
            accountId, chat.channelId, chat.target?.id.orEmpty(), chat.target?.createdAtIso.orEmpty(),
            if (chat.target?.approximate == true) APPROXIMATE else "",
        )
    }

    override fun equals(other: Any?): Boolean = other is ParkedChats && byAccount == other.byAccount

    override fun hashCode(): Int = byAccount.hashCode()

    override fun toString(): String = "ParkedChats(${byAccount.size})"

    companion object {
        val Empty = ParkedChats(emptyMap())

        private const val FIELDS = 5
        private const val APPROXIMATE = "~"

        /** O inverso de [encode]. O que vier incompleto ou sem conta e canal é descartado: um estado salvo estragado vira lista vazia. */
        fun decode(values: List<String>): ParkedChats {
            val byAccount = LinkedHashMap<String, ParkedChat>()
            for (fields in values.chunked(FIELDS)) {
                if (fields.size < FIELDS || fields[0].isEmpty() || fields[1].isEmpty()) continue
                // A mensagem pedida só vale com o id e o instante juntos.
                val target = if (fields[2].isNotEmpty() && fields[3].isNotEmpty()) MessageTarget(fields[2], fields[3], fields[4] == APPROXIMATE) else null
                byAccount[fields[0]] = ParkedChat(fields[1], target)
            }
            return if (byAccount.isEmpty()) Empty else ParkedChats(byAccount)
        }
    }
}
