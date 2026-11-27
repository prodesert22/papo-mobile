package chat.papo.mobile.chat

import chat.papo.mobile.core.parseInstant
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

/** Uma conversa é um canal ou uma conversa direta; muda o canal do Android em que a notificação sai e o título dela. */
enum class ConversationKind { Channel, Dm }

/** Uma mensagem na notificação: quem a enviou, o texto simples (sem marcação) e quando. */
data class NotificationLine(
    val author: String,
    val text: String,
    /** O instante da mensagem; sem ele (data ilegível) quem mostra usa a hora de agora. */
    val at: Instant?,
)

/**
 * O que abrir quando se toca na notificação: a mensagem mais nova da conversa. [createdAt] é um instante ISO 8601.
 * Com [approximate], ele é o da linha da caixa, um pouco depois da mensagem (ver `ChatStore.locate`); sem ele, é o da
 * própria mensagem.
 */
data class NotificationTarget(val messageId: String, val createdAt: String, val approximate: Boolean)

/**
 * A notificação de uma conversa de uma conta, inteira: quem a recebe a mostra no lugar da anterior da mesma conversa.
 * Nada aqui vale para outra conta: o par ([accountId], [conversationId]) a identifica, e o mesmo id de conversa em outra
 * conta é outra notificação.
 */
data class ConversationNotification(
    val accountId: String,
    /** O nome da conta para quem lê (servidor e `@usuário`); é o título do grupo de notificações dela. */
    val accountLabel: String,
    val conversationId: String,
    val kind: ConversationKind,
    /** Nome do canal (sem o `#`) ou da pessoa da conversa direta. */
    val title: String,
    /** As últimas mensagens, da mais antiga à mais nova; nunca vazia. */
    val messages: List<NotificationLine>,
    /** Menções não lidas da conversa (em conversa direta, as mensagens não lidas). Com 1 ou mais, a notificação conta no selo. */
    val mentionCount: Int,
    val target: NotificationTarget,
)

/**
 * Notificações do sistema (a área de notificações do aparelho). A implementação mora na plataforma, e quem decide se
 * uma mensagem notifica é o [NotificationDispatcher]; aqui só se mostra e se tira. Todas as operações têm como chave a
 * conta, nunca só a conversa.
 */
interface SystemNotifier {
    /** O sistema deixa o app notificar? Falso sem a permissão ou com as notificações do app desligadas no aparelho. */
    fun isPermissionGranted(): Boolean

    /** Mostra a notificação da conversa, ou troca a que já estava. Sem permissão, não faz nada. */
    suspend fun post(notification: ConversationNotification)

    /** Tira a notificação da conversa [conversationId] da conta [accountId], se existe. */
    fun cancelConversation(accountId: String, conversationId: String)

    /** Tira todas as notificações da conta [accountId]; os canais dela continuam. */
    fun cancelAccount(accountId: String)

    /** A conta saiu do app: tira as notificações dela e apaga o grupo e os canais que ela tinha no sistema. */
    fun removeAccount(accountId: String)
}

/**
 * A permissão de notificar, para a interface pedi-la. No Android 13 ou mais ela se pede em tempo de execução; antes
 * disso só o desligar nas configurações do aparelho tira o direito de notificar.
 */
interface NotificationPermission {
    /** O app pode notificar agora. Muda quando a pessoa responde ao pedido e [refresh] relê. */
    val granted: StateFlow<Boolean>

    /** A plataforma tem um pedido de permissão a mostrar (no Android, a partir da versão 13) e o app ainda não a tem. */
    val canRequest: Boolean

    /** Mostra o pedido do sistema e devolve se o app pode notificar depois dele. Sem tela para o pedido, devolve o que já era. */
    suspend fun request(): Boolean

    /** Abre as configurações de notificação do app no sistema. */
    fun openSettings()

    /** Relê o estado (a pessoa pode ter mudado nas configurações do sistema). */
    fun refresh()
}

/** O que uma conta precisa para notificar o sistema: quem mostra e os textos que a decisão escreve. */
class SystemNotifications(val notifier: SystemNotifier, val texts: NotificationTexts)

/** Os textos que a decisão de notificar escreve sozinha; vêm do arquivo de strings (regra 10), não do código. */
data class NotificationTexts(
    /** No lugar do texto de uma mensagem que só traz anexo. */
    val attachment: String,
    /** Quem enviou ou foi mencionado e o app não conhece. */
    val unknownUser: String,
    /** Mensagem sem texto nem anexo (só um embed, por exemplo). */
    val emptyMessage: String,
)

/** Um pedido para abrir uma conversa, vindo de tocar numa notificação. Os campos já passaram por [NotificationOpen.parse]. */
data class NotificationOpen(
    val accountId: String,
    val conversationId: String,
    val target: NotificationTarget?,
) {
    companion object {
        private const val MAX_ID = 64
        private const val MAX_INSTANT = 40
        private val safeId = Regex("[0-9A-Za-z_-]+")

        /**
         * Lê o que a notificação levou no pedido. O que vem de fora do app não é confiável: sem conta ou conversa, ou com
         * id fora do formato dos ids do servidor, o pedido não vale (null); mensagem sem instante legível (ou o
         * contrário) abre só a conversa. Não confere se a conta existe: isso é de quem a abre.
         */
        fun parse(
            accountId: String?,
            conversationId: String?,
            messageId: String?,
            createdAt: String?,
            approximate: Boolean,
        ): NotificationOpen? {
            if (accountId == null || conversationId == null || !isSafeId(accountId) || !isSafeId(conversationId)) return null
            val instant = createdAt?.takeIf { it.length <= MAX_INSTANT && parseInstant(it) != null }
            val target = if (messageId != null && isSafeId(messageId) && instant != null) {
                NotificationTarget(messageId, instant, approximate)
            } else {
                null
            }
            return NotificationOpen(accountId, conversationId, target)
        }

        private fun isSafeId(value: String?): Boolean = value != null && value.length <= MAX_ID && safeId.matches(value)
    }
}
