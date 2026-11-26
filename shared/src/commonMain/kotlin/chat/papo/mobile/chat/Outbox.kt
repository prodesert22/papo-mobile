package chat.papo.mobile.chat

import chat.papo.mobile.data.api.ApiException
import chat.papo.mobile.data.api.FileUnavailableException
import chat.papo.mobile.data.api.OutgoingFile
import io.ktor.client.network.sockets.ConnectTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Por que uma mensagem não saiu: o servidor recusou ou ela nem chegou até ele. */
enum class SendFailure {
    /** O servidor limitou a taxa de envio (429). */
    RateLimited,

    /** O servidor entendeu o pedido e o recusou: permissão, tamanho, tipo de arquivo, canal que não existe mais. */
    Rejected,

    /** Não chegou ao servidor: sem conexão, ou o envio parou antes de o corpo inteiro sair. */
    NotSent,

    /** A sessão não vale mais, nem depois de renovada uma vez. */
    Unauthorized,

    /** O corpo passou do limite do servidor (413): são arquivos demais para uma mensagem só. */
    TooLarge,

    /** Um arquivo anexado não abriu ou deixou de ser lido: o provedor o perdeu ou revogou o acesso. */
    FileUnavailable,
}

/** Em que ponto está uma mensagem que o usuário mandou e o servidor ainda não confirmou. */
sealed interface PendingStatus {
    /** Espera a vez: há outra mensagem do mesmo canal saindo. */
    data object Queued : PendingStatus

    data object Sending : PendingStatus

    /**
     * Sabidamente não criada no servidor. Reenviar não duplica. [detail] é o texto do servidor em
     * [SendFailure.Rejected] e o nome do arquivo em [SendFailure.FileUnavailable]; nos outros motivos é null.
     */
    data class Failed(val reason: SendFailure, val detail: String? = null) : PendingStatus

    /** O servidor pode ter criado a mensagem: não houve resposta que diga o contrário. Reenviar pode duplicar. */
    data object Uncertain : PendingStatus
}

/**
 * Uma mensagem em saída. [content] já vem aparado e com as menções no formato do servidor.
 * [progress] vai de 0 a 1 enquanto ela sai com anexos; null quando não há o que medir (sem anexos,
 * ou com algum de tamanho desconhecido: a tela mostra uma barra indeterminada).
 */
data class PendingMessage(
    val localId: String,
    val channelId: String,
    val content: String,
    val replyToId: String?,
    val files: List<OutgoingFile>,
    val enqueuedAt: Instant,
    val status: PendingStatus = PendingStatus.Queued,
    val progress: Float? = null,
) {
    /** Pode já existir no servidor sem o app saber: é a única que uma mensagem recebida pode confirmar. */
    internal val mayExist: Boolean get() = status == PendingStatus.Sending || status == PendingStatus.Uncertain
}

/**
 * Decide o que uma falha de envio significa. [bodyDelivered] diz se o corpo inteiro já tinha
 * sido entregue ao motor HTTP: sem isso o servidor não pode ter criado a mensagem, então uma
 * falha de rede vira [SendFailure.NotSent]; com isso, ou sem saber, ela fica [PendingStatus.Uncertain].
 * Relança o cancelamento.
 */
fun classifySendFailure(error: Throwable, bodyDelivered: Boolean = true): PendingStatus = when {
    error is CancellationException -> throw error
    // O corpo parou no meio porque o arquivo falhou: o servidor não recebeu a mensagem inteira, então não a criou.
    error is FileUnavailableException -> PendingStatus.Failed(SendFailure.FileUnavailable, error.fileName)
    error is ApiException -> when (error.status) {
        401 -> PendingStatus.Failed(SendFailure.Unauthorized)
        413 -> PendingStatus.Failed(SendFailure.TooLarge)
        429 -> PendingStatus.Failed(SendFailure.RateLimited)
        in 400..499 -> PendingStatus.Failed(SendFailure.Rejected, error.detail ?: error.title)
        // 5xx: o servidor pode ter criado a mensagem antes de falhar. O que não é erro HTTP de verdade também.
        else -> PendingStatus.Uncertain
    }
    error is ConnectTimeoutException -> PendingStatus.Failed(SendFailure.NotSent)
    bodyDelivered -> PendingStatus.Uncertain
    else -> PendingStatus.Failed(SendFailure.NotSent)
}

/** Tolerância entre o relógio do aparelho e o do servidor ao comparar quando uma mensagem foi criada. */
private val CLOCK_SKEW = 5.minutes

/**
 * A entrada em saída mais antiga que uma mensagem recebida do servidor representa: mesmo canal, mesmo
 * conteúdo aparado, mesma resposta e mesmo número de anexos. Só conta quem pode já existir no servidor
 * (enviando ou incerta). [createdAt], quando dado, descarta mensagens criadas antes do envio: serve a
 * páginas lidas do histórico, onde há mensagens antigas iguais; no evento de tempo real fica null.
 * [pending] está na ordem em que as mensagens foram enfileiradas.
 */
fun matchPending(
    pending: List<PendingMessage>,
    channelId: String,
    content: String?,
    replyTo: String?,
    attachmentCount: Int,
    createdAt: Instant? = null,
): PendingMessage? {
    val trimmed = content.orEmpty().trim()
    return pending.firstOrNull {
        it.mayExist && it.channelId == channelId && it.content == trimmed && it.replyToId == replyTo &&
            it.files.size == attachmentCount && (createdAt == null || createdAt >= it.enqueuedAt - CLOCK_SKEW)
    }
}

/**
 * Fila de envio de uma sessão. A tela mostra as mensagens daqui no mesmo instante em que o usuário as
 * manda e o envio segue no escopo da sessão, então sair da conversa não o cancela.
 *
 * Regras:
 *  - uma mensagem por vez em cada canal, na ordem em que foram mandadas;
 *  - qualquer falha, ou resposta que não se sabe se criou a mensagem, pausa a fila daquele canal;
 *  - nada é reenviado sozinho (o POST não tem chave de idempotência, e um 429 pede calma): só [retry].
 *
 * Use da thread da sessão, como o resto do [ChatStore]; só o progresso do envio chega de outra.
 */
class Outbox(
    private val scope: CoroutineScope,
    private val now: () -> Instant = { Clock.System.now() },
    /** Envia a mensagem e guarda o resultado. Chama o segundo argumento a cada avanço do envio dos bytes. */
    private val deliver: suspend (PendingMessage, (sent: Long, total: Long?) -> Unit) -> Unit,
) {
    private val _pending = MutableStateFlow<List<PendingMessage>>(emptyList())

    /** Em ordem de envio, de todos os canais. */
    val pending: StateFlow<List<PendingMessage>> = _pending.asStateFlow()

    private var sequence = 0
    private val pumping = HashSet<String>()
    private val flights = HashMap<String, Flight>()

    /** Põe a mensagem no fim da fila do canal; ela sai assim que chegar a vez. Devolve o id local dela. */
    fun enqueue(channelId: String, content: String, replyToId: String?, files: List<OutgoingFile>): String {
        val entry = PendingMessage("local-${++sequence}", channelId, content.trim(), replyToId, files, now())
        _pending.update { it + entry }
        pump(channelId)
        return entry.localId
    }

    /**
     * Manda de novo uma mensagem que falhou. Uma [PendingStatus.Uncertain] só vai se [confirmed]: ela
     * pode já estar no servidor e sair duplicada. Devolve false se nada mudou.
     */
    fun retry(localId: String, confirmed: Boolean = false): Boolean {
        val entry = find(localId) ?: return false
        val allowed = entry.status is PendingStatus.Failed || (entry.status == PendingStatus.Uncertain && confirmed)
        if (!allowed) return false
        update(localId) { it.copy(status = PendingStatus.Queued, progress = null) }
        pump(entry.channelId)
        return true
    }

    /**
     * Tira a mensagem da fila. Se ela está saindo, o envio é cancelado: antes de o corpo inteiro sair
     * o servidor não pode tê-la criado e ela some; depois, fica [PendingStatus.Uncertain].
     */
    fun withdraw(localId: String) {
        val entry = find(localId) ?: return
        val flight = flights[localId]
        if (flight != null) {
            flight.cancelledByUser = true
            flight.job.cancel()
            return // quem enviava resolve o destino dela
        }
        remove(localId)
        pump(entry.channelId) // a fila que esperava por ela segue
    }

    /**
     * Uma mensagem minha acabou de chegar do servidor por outro caminho que não a resposta do POST (o
     * evento de tempo real chega antes dela, ou uma página do histórico traz a que ficou incerta): tira
     * a entrada correspondente para a mensagem não aparecer duas vezes. Devolve se havia uma.
     */
    fun confirm(channelId: String, content: String?, replyTo: String?, attachmentCount: Int, createdAt: Instant? = null): Boolean {
        val match = matchPending(_pending.value, channelId, content, replyTo, attachmentCount, createdAt) ?: return false
        remove(match.localId)
        pump(channelId) // se ela era a incerta que segurava a fila, a fila segue
        return true
    }

    private fun find(localId: String) = _pending.value.firstOrNull { it.localId == localId }

    private fun update(localId: String, change: (PendingMessage) -> PendingMessage) =
        _pending.update { list -> list.map { if (it.localId == localId) change(it) else it } }

    private fun remove(localId: String) = _pending.update { list -> list.filterNot { it.localId == localId } }

    /** Garante que há quem envie a fila do canal. A fila só anda enquanto a primeira do canal espera a vez. */
    private fun pump(channelId: String) {
        if (!pumping.add(channelId)) return
        scope.launch {
            try {
                while (true) {
                    val next = _pending.value.firstOrNull { it.channelId == channelId }
                    if (next == null || next.status != PendingStatus.Queued) break
                    send(next)
                }
            } finally {
                pumping.remove(channelId)
            }
        }
    }

    private suspend fun send(entry: PendingMessage) {
        val id = entry.localId
        val upload = UploadProgress()
        // Com todos os tamanhos conhecidos o corpo tem total e a barra começa em zero; senão ela fica indeterminada (null).
        update(id) { it.copy(status = PendingStatus.Sending, progress = if (it.files.isEmpty() || it.files.any { f -> f.size == null }) null else 0f) }
        try {
            supervisorScope {
                // O envio roda à parte para ser cancelado sozinho, sem levar a fila junto.
                val job = async(start = CoroutineStart.LAZY) {
                    deliver(entry) { sent, total ->
                        upload.record(sent, total)
                        if (entry.files.isNotEmpty()) publishProgress(id, upload)
                    }
                }
                flights[id] = Flight(job)
                job.await()
            }
            remove(id)
        } catch (e: CancellationException) {
            // Cancelada a fila inteira (sessão encerrada): propaga. Só o envio: decide o destino da entrada.
            currentCoroutineContext().ensureActive()
            val byUser = flights[id]?.cancelledByUser == true
            when {
                byUser && !upload.bodyDelivered -> remove(id)
                upload.bodyDelivered -> update(id) { it.copy(status = PendingStatus.Uncertain, progress = null) }
                else -> update(id) { it.copy(status = PendingStatus.Failed(SendFailure.NotSent), progress = null) }
            }
        } catch (e: Throwable) {
            val status = classifySendFailure(e, upload.bodyDelivered)
            update(id) { it.copy(status = status, progress = null) }
        } finally {
            flights.remove(id)
        }
    }

    /** O progresso só vira estado a cada 1%, para um arquivo grande não refazer a tela milhares de vezes. */
    private fun publishProgress(localId: String, upload: UploadProgress) {
        val fraction = upload.fraction ?: return
        update(localId) {
            val shown = it.progress ?: 0f
            val advance = fraction - shown >= PROGRESS_STEP || (fraction >= 1f && shown < 1f)
            if (it.status == PendingStatus.Sending && advance) it.copy(progress = fraction) else it
        }
    }

    private class Flight(val job: Deferred<Unit>) {
        var cancelledByUser = false
    }

    /** Quanto do corpo já foi entregue ao motor HTTP. Escrito pela thread do envio, lido pela da sessão. */
    private class UploadProgress {
        @Volatile private var sent = 0L

        @Volatile private var total: Long? = null

        fun record(sent: Long, total: Long?) {
            this.sent = sent
            this.total = total
        }

        val fraction: Float? get() = total?.takeIf { it > 0 }?.let { (sent.toFloat() / it).coerceIn(0f, 1f) }

        /** Sem o total, qualquer byte entregue já conta: o servidor pode ter recebido tudo. */
        val bodyDelivered: Boolean get() = total?.let { sent >= it } ?: (sent > 0)
    }

    private companion object {
        const val PROGRESS_STEP = 0.01f
    }
}
