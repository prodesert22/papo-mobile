package chat.papo.mobile.chat

import chat.papo.mobile.core.MutationResult
import chat.papo.mobile.core.classifyMutation
import chat.papo.mobile.data.api.ApiException
import chat.papo.mobile.data.api.ChatApi
import chat.papo.mobile.data.ws.WsEvent
import chat.papo.mobile.data.ws.WsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds

/**
 * O que a caixa mostra. [items] vem da mais nova à mais antiga. [hasMore] diz que o servidor tem linhas mais antigas
 * que as carregadas. [capped] diz que a leitura parou no limite de páginas ainda achando linhas não lidas: pode haver
 * mais não lidas além das contadas. [error] é a última falha, e some na próxima operação que der certo.
 */
data class InboxState(
    val items: List<NotificationItem> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val hasMore: Boolean = false,
    val capped: Boolean = false,
    val error: Throwable? = null,
)

/**
 * O que quem decide notificar precisa da caixa: as linhas e o andamento das leituras, sem poder pedir nenhuma.
 * [readsStarted] e [readsFinished] contam as leituras da caixa (a que começou e a que terminou, falha incluída): uma
 * leitura que começa depois de um evento já traz as linhas que ele criou, então basta esperar a seguinte terminar.
 */
interface InboxView {
    val state: StateFlow<InboxState>
    val readsStarted: StateFlow<Long>
    val readsFinished: StateFlow<Long>
}

/**
 * A caixa de notificações de uma conta: uma cópia, só em memória, das linhas que o servidor guarda. O servidor é a
 * fonte e a sessão a recarrega, então nada vai para o disco. É da sessão: duas contas, mesmo no mesmo servidor, têm
 * caixas separadas.
 *
 * O evento `new_notification` não vira linha: ele não traz o canal nem o instante, e com o canal em "todas as
 * mensagens" o id é efêmero, sem linha no servidor. Ele só pede uma leitura das linhas mais novas, que é quem decide o
 * que existe. Nada é periódico, e nenhuma falha é repetida sozinha (regra 15): o erro fica em [InboxState.error] e
 * [refresh], [loadMore] e [markRead] são ações de quem usa.
 *
 * [positionsOf] diz onde estão as mensagens guardadas, para espelhar uma leitura de canal ([ReadMark]).
 */
class NotificationInbox(
    private val userId: String,
    private val api: ChatApi,
    private val events: Flow<WsEvent>,
    private val connection: StateFlow<WsState>,
    private val readMarks: Flow<ReadMark>,
    private val positionsOf: suspend (Collection<String>) -> Map<String, MessagePosition>,
    private val scope: CoroutineScope,
) : InboxView {
    private val _state = MutableStateFlow(InboxState())
    override val state: StateFlow<InboxState> = _state.asStateFlow()

    private val _readsStarted = MutableStateFlow(0L)
    override val readsStarted: StateFlow<Long> = _readsStarted.asStateFlow()

    private val _readsFinished = MutableStateFlow(0L)
    override val readsFinished: StateFlow<Long> = _readsFinished.asStateFlow()

    /** Uma leitura por vez: a resposta de uma nunca passa por cima do que outra acabou de gravar. */
    private val loadLock = Mutex()

    /** Pedidos de leitura das mais novas; vários que chegam juntos viram um. */
    private val newerRequests = Channel<Unit>(Channel.CONFLATED)

    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            connection.collect { if (it == WsState.Connected) refresh() }
        }
        scope.launch { events.collect(::onEvent) }
        scope.launch { readNewerOnRequest() }
        scope.launch { readMarks.collect { applyReadMark(it) } }
    }

    /**
     * Relê desde a primeira página e continua até uma página sem linhas não lidas, até acabar ou até [MAX_PAGES]
     * (nesse caso [InboxState.capped]). Uma falha vai para [InboxState.error] e deixa o que havia.
     */
    suspend fun refresh() = loadLock.withLock { guarded { reload() } }

    /** A página seguinte, mais antiga que a última carregada. Sem efeito se não há mais. */
    suspend fun loadMore() = loadLock.withLock {
        val last = _state.value.takeIf { it.hasMore }?.items?.lastOrNull() ?: return@withLock
        guarded {
            val page = api.notifications(userId, since = last.createdAt, lastId = last.id)
            val fresh = page.notifications.map { it.toItem() }
            _state.update {
                it.copy(
                    items = mergePage(it.items, fresh),
                    hasMore = page.hasMore,
                    capped = page.hasMore && fresh.any { item -> !item.read },
                    error = null,
                )
            }
        }
    }

    /**
     * Marca as linhas [ids] como lidas no servidor, em blocos de 1000, e depois aqui. Um 404 quer dizer que nenhuma
     * linha do bloco casou (já estavam lidas, ou sumiram com a mensagem) e vale como lido. Qualquer outra falha, o
     * 429 incluído, para no bloco que falhou, não muda nada aqui e vai para [InboxState.error]; nada é repetido.
     */
    suspend fun markRead(ids: Collection<String>): MutationResult {
        for (batch in readBatches(ids)) {
            try {
                api.markNotificationsRead(userId, batch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (!e.isNotFound) return fail(e)
            } catch (e: Exception) {
                return fail(e)
            }
            val done = batch.toHashSet()
            _state.update { it.copy(items = it.items.map { item -> if (item.id in done) item.copy(read = true) else item }) }
        }
        _state.update { it.copy(error = null) }
        return MutationResult.Done
    }

    private fun fail(error: Exception): MutationResult {
        _state.update { it.copy(error = error) }
        return classifyMutation(error)
    }

    private fun onEvent(event: WsEvent) {
        when (event) {
            is WsEvent.NewNotification -> newerRequests.trySend(Unit)
            is WsEvent.MessageDeleted -> _state.update { current ->
                // O servidor tira a linha da lista quando a mensagem some.
                if (current.items.none { it.messageId == event.id }) current
                else current.copy(items = current.items.filterNot { it.messageId == event.id })
            }
            else -> Unit
        }
    }

    /**
     * Espera [NEWER_DELAY] depois do primeiro pedido e faz uma leitura só: os eventos que chegam nesse tempo estão
     * cobertos por ela. Os que chegam durante a leitura pedem a seguinte.
     */
    private suspend fun readNewerOnRequest() {
        while (true) {
            newerRequests.receive()
            delay(NEWER_DELAY)
            newerRequests.tryReceive()
            loadLock.withLock { guarded { readNewer() } }
        }
    }

    /**
     * As linhas criadas depois da mais nova que se tem, sem `last_id`. Sem nenhuma linha ainda, ou com mais de uma
     * página de novidades, o cursor não serve e vale a leitura desde a primeira página.
     */
    private suspend fun readNewer() {
        val newest = _state.value.items.firstOrNull() ?: return reload()
        val page = api.notifications(userId, since = newest.createdAt)
        if (page.hasMore) return reload()
        val fresh = page.notifications.map { it.toItem() }
        _state.update { it.copy(items = mergePage(it.items, fresh), error = null) }
    }

    private suspend fun reload() {
        val collected = ArrayList<NotificationItem>()
        var since: String? = null
        var lastId: String? = null
        var hasMore: Boolean
        var capped = false
        var pages = 0
        while (true) {
            val page = api.notifications(userId, since, lastId)
            val fresh = page.notifications.map { it.toItem() }
            collected += fresh
            hasMore = page.hasMore
            pages++
            val last = fresh.lastOrNull()
            if (!hasMore || last == null || fresh.none { !it.read }) break
            if (pages == MAX_PAGES) {
                capped = true
                break
            }
            since = last.createdAt
            lastId = last.id
        }
        // A leitura é a lista nova; do que havia só vale o que o servidor não desfaz: linha lida continua lida.
        _state.update { it.copy(items = mergePage(emptyList(), collected).keepRead(it.items), hasMore = hasMore, capped = capped, loaded = true, error = null) }
    }

    /** Roda [block] marcando [InboxState.loading]; uma falha vai para [InboxState.error]. O cancelamento segue adiante. */
    private suspend fun guarded(block: suspend () -> Unit) {
        _state.update { it.copy(loading = true) }
        _readsStarted.update { it + 1 }
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e) }
        } finally {
            _state.update { it.copy(loading = false) }
            _readsFinished.update { it + 1 }
        }
    }

    private suspend fun applyReadMark(mark: ReadMark) {
        val pending = _state.value.items.filter { !it.read && it.channelId == mark.channelId }
        if (pending.isEmpty()) return
        val positions = try {
            positionsOf(pending.map { it.messageId } + mark.messageId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return // o espelho é uma conveniência: a próxima leitura da caixa traz o que o servidor marcou
        }
        _state.update { it.copy(items = markReadUpTo(it.items, mark, positions::get)) }
    }

    private fun List<NotificationItem>.keepRead(previous: List<NotificationItem>): List<NotificationItem> {
        val read = previous.filter { it.read }.mapTo(HashSet()) { it.id }
        return map { if (!it.read && it.id in read) it.copy(read = true) else it }
    }

    private companion object {
        /** Páginas de 100 linhas que uma leitura percorre atrás de não lidas: 500 linhas. */
        const val MAX_PAGES = 5

        /** Um pico de `new_notification` (o servidor manda um por mensagem) vira uma leitura por segundo, no máximo. */
        val NEWER_DELAY = 1.seconds
    }
}
