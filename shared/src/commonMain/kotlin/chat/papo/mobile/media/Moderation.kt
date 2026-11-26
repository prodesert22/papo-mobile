package chat.papo.mobile.media

import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.data.api.ApiException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Como a interface trata um anexo, pelo estado de moderação que o servidor informa. */
enum class ModerationView {
    /** Aparece como qualquer anexo. */
    Normal,

    /** Fica escondido até o usuário tocar nele. */
    Sensitive,

    /** O servidor o removeu: o aviso ocupa o lugar do anexo e nada é pedido. */
    Blocked,
}

/**
 * O tratamento de um anexo com [status] de `moderation_status`. Só `blocked` e `sensitive` mudam a tela; o resto
 * (`clean`, `processing`, `failed`, um valor que o app não conhece e a falta dele) aparece normal. `pending` não pede
 * aviso de "verificando": é também o estado fixo de um servidor sem moderação, e esconder o anexo por causa dele
 * esconderia todas as imagens desses servidores.
 */
fun moderationView(status: String?): ModerationView = when (status) {
    "blocked" -> ModerationView.Blocked
    "sensitive" -> ModerationView.Sensitive
    else -> ModerationView.Normal
}

/**
 * Roda [fetch], um pedido do anexo [attachmentId] da mensagem [messageId] (miniatura, original). Se o servidor
 * responde 410, a moderação o bloqueou: o anexo é gravado como `blocked` na conversa, sem nenhum pedido novo, [onGone]
 * avisa a tela que o pediu e o erro segue para quem pediu. A gravação não é cancelada junto com o pedido: quem sai da
 * tela no meio dela não a perde.
 */
internal suspend fun <T> AccountSession.guardGone(
    messageId: String,
    attachmentId: String,
    onGone: () -> Unit = {},
    fetch: suspend () -> T,
): T =
    try {
        fetch()
    } catch (e: ApiException) {
        if (e.status == STATUS_GONE) {
            withContext(NonCancellable) { chat.markBlocked(messageId, attachmentId) }
            onGone()
        }
        throw e
    }

private const val STATUS_GONE = 410
