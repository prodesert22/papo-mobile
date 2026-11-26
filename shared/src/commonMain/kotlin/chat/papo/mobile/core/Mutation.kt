package chat.papo.mobile.core

import chat.papo.mobile.chat.PendingStatus
import chat.papo.mobile.chat.SendFailure
import chat.papo.mobile.chat.classifySendFailure

/**
 * O resultado de uma mudança no servidor (perfil, status, bloqueio, senha). Nenhuma delas é repetida sozinha:
 * [Rejected] e [RateLimited] pedem uma ação do usuário e [Uncertain] pede conferir o estado antes de tentar de novo.
 */
sealed interface MutationResult {
    data object Done : MutationResult

    /** O servidor entendeu e recusou (4xx). [detail] é o texto dele, [type] o `type` do problema, se veio. */
    data class Rejected(val detail: String?, val type: String?) : MutationResult

    /** Limite de taxa (429): esperar e tentar de novo à mão. */
    data object RateLimited : MutationResult

    /** Sabidamente não chegou ao servidor (sem conexão); repetir é seguro. */
    data object NotSent : MutationResult

    /** A sessão não vale mais. */
    data object Unauthorized : MutationResult

    /** O servidor pode ter aplicado a mudança: conferir (recarregar) antes de repetir. */
    data object Uncertain : MutationResult
}

/** Roda [block] e traduz o resultado. Relança o cancelamento (regra 7). */
suspend fun runMutation(block: suspend () -> Unit): MutationResult =
    try {
        block()
        MutationResult.Done
    } catch (e: Throwable) {
        classifyMutation(e)
    }

/** Decide o que uma falha de mudança significa, com as mesmas regras do envio de mensagem. */
fun classifyMutation(error: Throwable): MutationResult {
    val type = (error as? chat.papo.mobile.data.api.ApiException)?.type
    return when (val status = classifySendFailure(error, bodyDelivered = true)) {
        is PendingStatus.Failed -> when (status.reason) {
            SendFailure.RateLimited -> MutationResult.RateLimited
            SendFailure.Unauthorized -> MutationResult.Unauthorized
            SendFailure.NotSent -> MutationResult.NotSent
            SendFailure.Rejected, SendFailure.TooLarge, SendFailure.FileUnavailable -> MutationResult.Rejected(status.detail, type)
        }
        else -> MutationResult.Uncertain
    }
}
