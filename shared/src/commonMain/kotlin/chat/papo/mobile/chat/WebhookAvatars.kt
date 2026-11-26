package chat.papo.mobile.chat

import chat.papo.mobile.data.api.ChatApi
import chat.papo.mobile.media.MediaCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Avatares dos webhooks de uma conta. O servidor não dá versão ao avatar de um webhook nem avisa quando ele muda
 * (o caminho é sempre o mesmo, `/webhooks/<id>/avatar`), então o cache de mídia sozinho nunca o renovaria. Por isso
 * cada avatar é pedido ao servidor uma vez por sessão e, dali em diante, vem do cache de mídia cifrado.
 *
 * Várias mensagens do mesmo webhook pedem o avatar juntas: um único pedido atende todas. O pedido pertence ao
 * [scope] da sessão, não a quem chamou primeiro, então uma tela que sai de cena no meio dele não deixa os outros
 * sem resposta. Cada conta tem o seu [WebhookAvatars]: nada é compartilhado entre contas.
 */
class WebhookAvatars(
    private val api: ChatApi,
    private val media: MediaCache,
    private val scope: CoroutineScope,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    /** Depois de uma falha, o intervalo mínimo até o servidor ser perguntado de novo pelo mesmo webhook. */
    private val retryAfter: Duration = RETRY_AFTER,
) {
    private sealed interface State {
        /** O servidor respondeu nesta sessão: o webhook tem avatar ([exists], e ele está no cache) ou não tem. */
        data class Answered(val exists: Boolean) : State

        /** A última tentativa, em [at], falhou: vale o que o cache tiver. */
        data class Failed(val at: TimeMark) : State

        class Loading(val result: Deferred<ByteArray?>) : State
    }

    private val mutex = Mutex()
    private val states = HashMap<String, State>()

    /**
     * O que o cache já tem do avatar, sem falar com o servidor: mostra algo enquanto [get] busca. Pode ser a
     * imagem de uma sessão anterior.
     */
    suspend fun cached(webhookId: String): ByteArray? = media.peek(mediaName(webhookId))

    /**
     * O avatar atual do webhook, ou null se ele não tem um. Na primeira chamada de cada sessão pergunta ao servidor:
     * a imagem nova substitui a guardada, e a resposta 404 apaga a guardada. Se o servidor não responde (rede, 429,
     * 5xx), vale a imagem guardada, que é melhor que nenhuma, e uma nova pergunta só sai depois de [retryAfter].
     */
    suspend fun get(webhookId: String): ByteArray? {
        val state = mutex.withLock {
            when (val current = states[webhookId]) {
                null -> startLoading(webhookId)
                is State.Failed -> if (current.at.elapsedNow() >= retryAfter) startLoading(webhookId) else current
                else -> current
            }
        }
        return when (state) {
            is State.Answered -> if (state.exists) cached(webhookId) else null
            is State.Failed -> cached(webhookId)
            is State.Loading -> state.result.await()
        }
    }

    /** Chamado com [mutex] preso: assim ninguém abre um segundo pedido para o mesmo webhook. */
    private fun startLoading(webhookId: String): State.Loading =
        State.Loading(scope.async { fetch(webhookId) }).also { states[webhookId] = it }

    private suspend fun fetch(webhookId: String): ByteArray? {
        val name = mediaName(webhookId)
        val answer = try {
            api.webhookAvatar(webhookId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val stale = media.peek(name)
            settle(webhookId, State.Failed(timeSource.markNow()))
            return stale
        }
        if (answer != null) media.put(name, answer) else media.evict(name)
        settle(webhookId, State.Answered(exists = answer != null))
        return answer
    }

    private suspend fun settle(webhookId: String, state: State) = mutex.withLock { states[webhookId] = state }

    companion object {
        /** Nome do avatar do webhook no cache de mídia. */
        fun mediaName(webhookId: String) = "webhook-avatar/$webhookId"

        val RETRY_AFTER = 30.seconds
    }
}
