package chat.papo.mobile.chat

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import chat.papo.mobile.core.parseInstant
import chat.papo.mobile.data.api.ApiException
import chat.papo.mobile.data.api.AttachmentDto
import chat.papo.mobile.data.api.ChannelDto
import chat.papo.mobile.data.api.ChatApi
import chat.papo.mobile.data.api.DirectConversationDto
import chat.papo.mobile.data.api.EmbedDto
import chat.papo.mobile.data.api.ExternalAuthorDto
import chat.papo.mobile.data.api.MessageDto
import chat.papo.mobile.data.api.OutgoingFile
import chat.papo.mobile.data.api.PapoJson
import chat.papo.mobile.data.api.ReactionRequest
import chat.papo.mobile.data.api.ReactionSummaryDto
import chat.papo.mobile.data.api.UserReactionDto
import chat.papo.mobile.data.api.UserSummaryDto
import chat.papo.mobile.data.db.account.AccountDb
import chat.papo.mobile.data.ws.WsClient
import chat.papo.mobile.data.ws.WsCommand
import chat.papo.mobile.data.ws.WsEvent
import chat.papo.mobile.data.ws.WsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import chat.papo.mobile.data.db.account.Channel as ChannelRow
import chat.papo.mobile.data.db.account.Dm as DmRow
import chat.papo.mobile.data.db.account.Message as MessageRow
import chat.papo.mobile.data.db.account.User as UserRow
import kotlinx.coroutines.channels.Channel as RequestChannel

/**
 * Conversas de uma conta. O banco local é a fonte do que a tela mostra: as
 * respostas REST e os eventos do WebSocket são gravados nele, e a tela observa.
 */
class ChatStore(
    private val myUserId: String,
    db: AccountDb,
    private val api: ChatApi,
    private val ws: WsClient,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    /** Fonte das permissões da conta. O padrão não conhece o dono do servidor e, sem ele, nada é negado. */
    private val permissions: AccessStore = AccessStore(db.metaQueries, api, scope, io),
) {
    private val q = db.chatQueries
    private val meta = db.metaQueries
    private val syncMutex = Mutex()

    /**
     * A resposta do POST/DELETE de uma reação e o evento `react_update` chegam por caminhos que não têm ordem
     * entre si e gravam a mesma linha. Quem lê e grava sem esta trava pode escrever de volta o que leu antes da
     * gravação do outro e apagá-la: a contagem velha volta, ou o "eu reagi" some.
     */
    private val reactionLock = Mutex()

    val channels: StateFlow<List<Channel>> =
        q.selectChannels().asFlow().mapToList(io).map { rows -> rows.map { it.toModel() } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** O que se sabe das permissões da conta; a regra que o lê está em `Permissions.kt`. */
    val access: StateFlow<PermissionSnapshot> = permissions.snapshot

    /** Os canais que a conta pode ver: sem os que a leitura nega e sem a categoria que o filtro esvaziou. */
    val visibleChannels: StateFlow<List<Channel>> =
        combine(channels, access) { list, snapshot -> visibleChannels(list, snapshot, myUserId) }
            .stateIn(scope, SharingStarted.Eagerly, visibleChannels(channels.value, access.value, myUserId))

    val dms: StateFlow<List<Dm>> =
        q.selectDms().asFlow().mapToList(io).map { rows -> rows.map { it.toModel() } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val users: StateFlow<Map<String, User>> =
        q.selectUsers().asFlow().mapToList(io).map { rows -> rows.associate { it.id to it.toModel() } }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /**
     * Canais cujo trecho guardado não chega à mensagem mais nova: o usuário abriu
     * uma mensagem antiga e está lendo em volta dela. A marca fica no banco, junto
     * das mensagens que descreve, e some quando o trecho volta a alcançar o fim.
     */
    val detached: StateFlow<Set<String>> =
        meta.keysWithPrefix(DETACHED).asFlow().mapToList(io).map { keys -> keys.mapTo(HashSet()) { it.removePrefix(DETACHED) } }
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

    private val _presence = MutableStateFlow<Map<String, Presence>>(emptyMap())
    val presence: StateFlow<Map<String, Presence>> = _presence.asStateFlow()

    private val _typing = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    /** Quem está digitando, por canal (sem o próprio usuário). */
    val typing: StateFlow<Map<String, Set<String>>> = _typing.asStateFlow()

    private val _voiceMembers = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    /** Quem está em cada canal de voz. */
    val voiceMembers: StateFlow<Map<String, Set<String>>> = _voiceMembers.asStateFlow()

    private val _pinned = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    /** Ids das mensagens fixadas, por canal (carregado sob demanda). */
    val pinned: StateFlow<Map<String, Set<String>>> = _pinned.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _syncError = MutableStateFlow<Throwable?>(null)
    val syncError: StateFlow<Throwable?> = _syncError.asStateFlow()

    private val _synced = MutableStateFlow(false)

    /**
     * Os canais, as permissões e os usuários já chegaram do servidor ao menos uma vez desde que a sessão abriu. Antes
     * disso a lista é a do banco, de uma sessão anterior, e o que a conta pode ler ainda é desconhecido.
     */
    val synced: StateFlow<Boolean> = _synced.asStateFlow()

    val connection: StateFlow<WsState> get() = ws.state

    private val hasOlder = HashMap<String, Boolean>()
    private var viewing: String? = null
    private var lastTypingSent: Pair<String, TimeMark>? = null
    private var lastActivitySent: TimeMark? = null
    private val typingMarks = HashMap<Pair<String, String>, TimeMark>()

    fun start() {
        scope.launch { ws.events.collect { runCatching { handle(it) } } }
        scope.launch {
            ws.state.collect { state -> if (state == WsState.Connected) onConnected() }
        }
        scope.launch {
            while (true) {
                delay(3.seconds)
                expireTyping()
            }
        }
        ws.start()
    }

    /** Cada reconexão pode ter perdido eventos: recarrega as listas, as permissões e o canal aberto. */
    internal suspend fun onConnected() {
        notifyActivity(force = true)
        runSync()
        // Quem lê um trecho antigo fica onde está; o fim do histórico vem quando voltar para ele. Canal que
        // a leitura nega nem é pedido: seria um 403 certo.
        viewing?.let { if (!isDetached(it) && !readDenied(it)) runCatching { refreshLatest(it) } }
    }

    /** Canal que está na tela agora; mensagens dele não contam como não lidas. */
    fun setViewing(channelId: String?) {
        viewing = channelId
    }

    /** [channelId] deixou de estar na tela. Só vale se ele ainda é o que está: outro, já marcado depois, fica. */
    fun clearViewing(channelId: String) {
        if (viewing == channelId) viewing = null
    }

    suspend fun runSync() {
        try {
            sync()
            _syncError.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _syncError.value = e
        }
    }

    /** Recarrega o dono e os cargos do servidor, os canais com as permissões, as conversas diretas e os usuários. */
    suspend fun sync() = syncMutex.withLock {
        _syncing.value = true
        try {
            permissions.refreshMeta()
            val channelList = api.channels()
            val dmList = api.dms()
            permissions.applyChannels(channelList)
            withContext(io) {
                q.transaction {
                    storeChannels(channelList)
                    if (channelList.isEmpty()) q.deleteAllChannels() else q.deleteChannelsNotIn(channelList.map { it.id })
                    dmList.forEach(::upsert)
                    if (dmList.isEmpty()) q.deleteAllDms() else q.deleteDmsNotIn(dmList.map { it.id })
                }
            }
            syncUsers()
            _synced.value = true
        } finally {
            _syncing.value = false
        }
    }

    /**
     * Grava os usuários e guarda os cargos de cada um nas permissões. Os cargos só entram no fim, com todas as
     * páginas: `usersComplete` vale quando a última delas chegou e o próprio usuário estava nelas.
     */
    private suspend fun syncUsers() {
        val since = permissions.beginUsersSync()
        val roles = HashMap<String, Set<String>>()
        var cursor: String? = null
        var lastId: String? = null
        var complete = false
        for (i in 0 until MAX_USER_PAGES) {
            val page = api.users(cursor, lastId)
            withContext(io) { q.transaction { page.users.forEach(::upsert) } }
            page.users.forEach { user -> roles[user.id] = user.roles.mapTo(HashSet()) { it.id } }
            val last = page.users.lastOrNull()
            if (!page.hasMore) {
                complete = true
                break
            }
            if (last?.createdAt == null) break
            cursor = last.createdAt
            lastId = last.id
        }
        // Sem o próprio usuário nas páginas, "ausente = sem cargos" negaria tudo por um dado que faltou.
        permissions.applyUsers(roles, complete && myUserId in roles, since)
    }

    /**
     * Grava os canais de uma resposta de `GET /channels`. De canal que a leitura nega não se guarda a prévia da
     * última mensagem: o servidor a manda mesmo assim, e o conteúdo não é do usuário. Só dentro de [io].
     */
    private fun storeChannels(channels: List<ChannelDto>) {
        val snapshot = permissions.snapshot.value
        channels.forEach {
            val denied = snapshot.channel(myUserId, it.id, ChannelFlag.Read) == Decision.Denied
            upsert(if (denied) it.copy(lastMessage = null) else it)
        }
    }

    /**
     * Relê os canais e as entradas de permissão deles sem tocar nas conversas diretas nem nos usuários.
     * Também restaura o `parent_id` que o servidor não manda no `channel_update` de uma mudança de permissão.
     */
    private suspend fun refreshChannels() {
        val channelList = api.channels()
        permissions.applyChannels(channelList)
        withContext(io) { q.transaction { storeChannels(channelList) } }
    }

    /**
     * Pede a releitura das permissões depois de um 403: o que o app sabia ficou velho (um `PUT /roles` não
     * avisa ninguém). No máximo uma a cada poucos segundos; nada é reenviado (regra 15).
     */
    fun requestRefresh() {
        if (!permissions.allowForbiddenRefresh()) return
        scope.launch {
            quietly {
                permissions.refreshMeta()
                refreshChannels()
            }
        }
    }

    /** Faz [block] e, se o servidor recusar com 403, pede a releitura das permissões antes de repassar o erro. */
    private suspend fun <T> reportingForbidden(block: suspend () -> T): T = try {
        block()
    } catch (e: ApiException) {
        if (e.isForbidden) requestRefresh()
        throw e
    }

    /** Trabalho de segundo plano que pode falhar sem que ninguém precise saber; o cancelamento segue adiante. */
    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private fun readDenied(channelId: String): Boolean =
        permissions.snapshot.value.channel(myUserId, channelId, ChannelFlag.Read) == Decision.Denied

    /**
     * Apaga do aparelho o que se guardou de um canal que o usuário não lê mais: as mensagens, a prévia da
     * última e a marca de trecho antigo. O rascunho (`draft:`) fica: é do usuário, e o canal volta com o acesso.
     */
    suspend fun forgetHistory(channelId: String) {
        withContext(io) {
            q.transaction {
                q.deleteChannelMessages(channelId)
                q.setChannelLastMessage(null, null, null, null, channelId)
                meta.remove(DETACHED + channelId)
            }
        }
        hasOlder.remove(channelId)
        _pinned.update { it - channelId }
    }

    /** Esquece o histórico de cada canal que passa a ter a leitura negada, não importa o que mudou as permissões. */
    private suspend fun forgetUnreadableChannels() {
        var forgotten = emptySet<String>()
        combine(channels, access) { list, snapshot ->
            list.filter { it.type != ChannelType.Category && snapshot.channel(myUserId, it.id, ChannelFlag.Read) == Decision.Denied }
                .mapTo(HashSet()) { it.id }
        }.collect { denied ->
            (denied - forgotten).forEach { quietly { forgetHistory(it) } }
            forgotten = denied
        }
    }

    // Mensagens

    fun messages(channelId: String, limit: Long): Flow<List<Message>> =
        q.selectMessages(channelId, limit).asFlow().mapToList(io).map { rows -> rows.map { it.toModel() } }

    fun hasOlder(channelId: String): Boolean = hasOlder[channelId] ?: true

    /**
     * Busca a página mais recente do canal. Se ela não encosta no que já está
     * guardado, o que é mais antigo é descartado: assim nunca fica um buraco
     * invisível no meio do histórico. Depois dela o trecho guardado volta a
     * terminar na mensagem mais nova, mesmo que antes estivesse longe do fim.
     */
    suspend fun refreshLatest(channelId: String) {
        val page = reportingForbidden { api.messages(channelId, limit = PAGE_SIZE) }
        var discarded = false
        var arrived = emptyList<MessageDto>()
        withContext(io) {
            q.transaction {
                val fetched = page.messages
                arrived = newlyMine(fetched)
                if (fetched.isNotEmpty()) {
                    val overlap = q.countMessagesIn(fetched.map { it.id }).executeAsOne()
                    val stored = q.newestMessage(channelId).executeAsOneOrNull()
                    if (stored != null && overlap == 0L && page.hasMore) {
                        q.deleteMessagesOlderThan(channelId, fetched.minOf { it.createdUs() })
                        discarded = true
                    }
                } else if (!page.hasMore) {
                    q.deleteChannelMessages(channelId)
                }
                fetched.forEach(::upsert)
                meta.remove(DETACHED + channelId)
            }
        }
        confirmArrived(arrived)
        // O que valia para o trecho descartado não vale para o novo: há histórico antes desta página.
        if (discarded) hasOlder.remove(channelId)
        if (!page.hasMore && (hasOlder[channelId] != false)) {
            val stored = withContext(io) { q.selectMessages(channelId, PAGE_SIZE + 1L).executeAsList().size }
            if (stored <= page.messages.size) hasOlder[channelId] = false
        }
    }

    /**
     * Carrega a página anterior à mensagem mais antiga guardada, de até [limit] mensagens (o servidor
     * entrega no máximo 100). Devolve false quando o histórico acabou.
     */
    suspend fun loadOlder(channelId: String, limit: Int = PAGE_SIZE): Boolean {
        if (hasOlder[channelId] == false) return false
        val oldest = withContext(io) { q.oldestMessage(channelId).executeAsOneOrNull() } ?: return false
        val page = reportingForbidden { api.messages(channelId, since = oldest.created_at, lastId = oldest.id, limit = limit) }
        withContext(io) { q.transaction { page.messages.forEach(::upsert) } }
        hasOlder[channelId] = page.hasMore && page.messages.isNotEmpty()
        return hasOlder[channelId] == true
    }

    /**
     * Traz uma mensagem para o trecho guardado, para a tela poder rolar até ela.
     * [createdAtIso] é o instante dela como o servidor informou: com ele o cursor
     * `since` + `last_id` vai direto à vizinhança, sem percorrer o histórico. Uma
     * leitura traz o que veio depois dela e outra, a partir da mensagem seguinte,
     * a página que termina nela.
     *
     * Se a vizinhança encosta no trecho guardado, os dois viram um só. Senão há
     * um buraco entre eles, e o trecho guardado dá lugar à vizinhança: o canal
     * fica marcado em [detached] até [loadNewer] ou [refreshLatest] o levarem de
     * volta ao fim do histórico.
     */
    suspend fun locate(channelId: String, messageId: String, createdAtIso: String): Located {
        position(channelId, messageId)?.let { return Located.Found(it) }
        val after = reportingForbidden {
            api.messages(channelId, since = createdAtIso, lastId = messageId, order = "asc", limit = AROUND_NEWER)
        }
        // Nada depois dela: seria a mais nova do canal, e a página mais recente já foi lida.
        val next = after.messages.firstOrNull() ?: return Located.Missing
        val before = reportingForbidden { api.messages(channelId, since = next.createdAt, lastId = next.id, limit = PAGE_SIZE) }
        if (before.messages.none { it.id == messageId }) return Located.Missing
        val around = before.messages + after.messages
        withContext(io) {
            q.transaction {
                val overlaps = q.countMessagesIn(around.map { it.id }).executeAsOne() > 0
                if (!overlaps) q.deleteChannelMessages(channelId)
                around.forEach(::upsert)
                when {
                    !after.hasMore -> meta.remove(DETACHED + channelId) // a vizinhança chega à mensagem mais nova
                    !overlaps -> meta.put(DETACHED + channelId, "1")
                }
            }
            q.oldestMessage(channelId).executeAsOneOrNull()?.id
        }.let { oldest ->
            // Se a página anterior passou a ser o começo do trecho, é ela que diz se há mais para trás.
            if (oldest == before.messages.last().id) hasOlder[channelId] = before.hasMore
        }
        return position(channelId, messageId)?.let { Located.Found(it) } ?: Located.Missing
    }

    /**
     * Procura uma mensagem que está antes do trecho guardado, sem saber o instante dela: é o caso da
     * mensagem respondida, porque `reply_to` traz só o id e o servidor não lê uma mensagem avulsa.
     * Sem instante não há cursor que leve até ela, então a única saída é paginar para trás a partir
     * da mais antiga guardada, conferindo cada página. A resposta sempre aponta para uma mensagem do
     * mesmo canal e mais antiga, e o trecho guardado não tem buracos: se ela existe, está atrás dele.
     *
     * Chegar ao começo do canal sem achá-la é [Located.Missing] (foi apagada). Cada página traz
     * [ORIGINAL_PAGE_SIZE] mensagens e a procura desiste depois de [maxPages] delas com
     * [Located.NotReached]: o que foi lido continua guardado, e chamar de novo segue dali.
     */
    suspend fun locateOlder(channelId: String, messageId: String, maxPages: Int = ORIGINAL_MAX_PAGES): Located {
        require(maxPages > 0) { "maxPages precisa ser positivo: $maxPages" }
        position(channelId, messageId)?.let { return Located.Found(it) }
        repeat(maxPages) {
            val more = loadOlder(channelId, ORIGINAL_PAGE_SIZE)
            position(channelId, messageId)?.let { return Located.Found(it) }
            if (!more) return Located.Missing
        }
        return Located.NotReached
    }

    /** Quantas mensagens guardadas do canal são mais novas que esta; null se ela não está guardada nele. */
    private suspend fun position(channelId: String, messageId: String): Int? = withContext(io) {
        val row = q.selectMessage(messageId).executeAsOneOrNull()?.takeIf { it.channel_id == channelId }
        row?.let { q.countMessagesNewerThan(channelId, it.created_us, it.id).executeAsOne().toInt() }
    }

    /** Das mensagens de uma página, as minhas que o banco ainda não tinha. Só dentro de [io], antes de gravar a página. */
    private fun newlyMine(page: List<MessageDto>): List<MessageDto> =
        page.filter { it.authorId == myUserId && q.selectMessage(it.id).executeAsOneOrNull() == null }

    /** Uma mensagem minha que veio numa página é a que ficou incerta na fila de envio, se for igual a ela e tão nova quanto. */
    private fun confirmArrived(arrived: List<MessageDto>) {
        arrived.sortedBy { it.createdUs() }.forEach {
            queue.confirm(it.channelId, it.content, it.replyTo, it.attachments.size, parseInstant(it.createdAt))
        }
    }

    suspend fun isDetached(channelId: String): Boolean = withContext(io) { detachedNow(channelId) }

    /** Só dentro de [io]: lê a marca direto do banco, na mesma transação de quem pergunta. */
    private fun detachedNow(channelId: String): Boolean = meta.get(DETACHED + channelId).executeAsOneOrNull() != null

    /**
     * Com o trecho guardado longe do fim do histórico, carrega a página que vem
     * depois da mensagem mais nova dele. Devolve quantas mensagens entraram. Ao
     * alcançar o fim, o canal volta a receber as mensagens novas.
     */
    suspend fun loadNewer(channelId: String): Int {
        val newest = withContext(io) { q.newestMessage(channelId).executeAsOneOrNull()?.takeIf { detachedNow(channelId) } } ?: return 0
        val page = reportingForbidden {
            api.messages(channelId, since = newest.created_at, lastId = newest.id, order = "asc", limit = PAGE_SIZE)
        }
        var arrived = emptyList<MessageDto>()
        withContext(io) {
            q.transaction {
                arrived = newlyMine(page.messages)
                page.messages.forEach(::upsert)
                if (!page.hasMore) meta.remove(DETACHED + channelId)
            }
        }
        confirmArrived(arrived)
        // O que foi enviado enquanto a última página vinha entra pela leitura do fim do histórico.
        if (!page.hasMore) refreshLatest(channelId)
        return page.messages.size
    }

    // Fila de envio

    private val queue = Outbox(scope) { message, onProgress -> deliver(message, onProgress) }

    /** Pedidos de releitura dos canais; o `debounce` junta os que chegam em rajada (um `channel_update` por permissão mudada). */
    private val channelRefreshRequests = RequestChannel<Unit>(RequestChannel.CONFLATED)

    // Depois de todos os campos: o escopo pode usá-los já na primeira instrução.
    init {
        scope.launch { refreshChannelsOnRequest() }
        scope.launch { forgetUnreadableChannels() }
    }

    @OptIn(FlowPreview::class)
    private suspend fun refreshChannelsOnRequest() {
        channelRefreshRequests.receiveAsFlow().debounce(CHANNELS_REFRESH_DEBOUNCE).collect { quietly { refreshChannels() } }
    }

    /** O que o usuário mandou e o servidor ainda não confirmou, de todos os canais, na ordem do envio. */
    val outbox: StateFlow<List<PendingMessage>> get() = queue.pending

    /** Põe a mensagem na fila de envio do canal, que segue mesmo se a tela sair. Devolve o id local dela. */
    fun enqueue(channelId: String, content: String, replyTo: String?, files: List<OutgoingFile>): String =
        queue.enqueue(channelId, content, replyTo, files)

    /** Manda de novo o que falhou. Uma mensagem incerta só vai com [confirmedDuplicate]: ela pode sair duplicada. */
    fun retry(localId: String, confirmedDuplicate: Boolean = false): Boolean = queue.retry(localId, confirmedDuplicate)

    /** Tira da fila; se estava saindo, cancela o envio. */
    fun withdraw(localId: String) = queue.withdraw(localId)

    private suspend fun deliver(message: PendingMessage, onProgress: (sent: Long, total: Long?) -> Unit) {
        val sent = reportingForbidden {
            api.sendMessage(message.channelId, message.content, message.replyToId, message.files, onProgress)
        }
        val away = withContext(io) {
            q.transactionWithResult {
                val away = detachedNow(sent.channelId)
                if (!away) upsert(sent)
                noteLastMessage(sent.channelId, sent.id, sent.createdAt, preview(sent.content, sent.attachments), sent.authorId)
                away
            }
        }
        if (away) {
            // Quem escreve lendo um trecho antigo volta para o fim da conversa, onde a mensagem entrou.
            // Se essa leitura falhar, a mensagem já foi enviada: não é uma falha de envio, e a tela
            // segue no trecho antigo, de onde o usuário pode pedir de novo as mensagens recentes.
            try {
                refreshLatest(sent.channelId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /** A mensagem como está guardada, sem ir ao servidor; null se não está no banco ou é de outro canal. */
    suspend fun storedMessage(channelId: String, id: String): Message? = withContext(io) {
        q.selectMessage(id).executeAsOneOrNull()?.takeIf { it.channel_id == channelId }?.toModel()
    }

    suspend fun edit(messageId: String, content: String) {
        val edited = reportingForbidden { api.editMessage(messageId, content.trim()) }
        withContext(io) { upsert(edited) }
    }

    suspend fun delete(messageId: String) {
        reportingForbidden { api.deleteMessage(messageId) }
        withContext(io) { q.deleteMessage(messageId) }
    }

    suspend fun toggleReaction(message: Message, emojiId: String?, unicode: String?) {
        val request = ReactionRequest(emojiId, unicode)
        val mine = message.userReactions.any { it.emojiId == emojiId && it.unicode == unicode }
        reportingForbidden {
            if (mine) api.unreact(message.channelId, message.id, request) else api.react(message.channelId, message.id, request)
        }
        withContext(io) {
            reactionLock.withLock {
                // A linha é lida aqui, já com a trava: o evento pode ter chegado antes ou chegar depois desta resposta.
                val row = q.selectMessage(message.id).executeAsOneOrNull() ?: return@withContext
                val own = decodeList(UserReactionDto.serializer(), row.user_reactions).filterNot { it.emojiId == emojiId && it.unicode == unicode }
                val updated = if (mine) own else own + UserReactionDto(emojiId = emojiId, unicode = unicode)
                // A contagem chega só pelo evento react_update; aqui muda apenas o "eu reagi".
                q.setMessageReactions(row.reactions, encodeList(UserReactionDto.serializer(), updated), message.id)
            }
        }
    }

    suspend fun markRead(channelId: String, messageId: String) {
        val alreadyRead = withContext(io) {
            val channel = q.selectChannel(channelId).executeAsOneOrNull()
            val dm = q.selectDm(channelId).executeAsOneOrNull()
            val read = (channel?.last_read_message ?: dm?.last_read_message) == messageId && (dm?.unread_count ?: 0L) == 0L
            q.transaction {
                q.setChannelRead(messageId, channelId)
                q.setDmRead(messageId, channelId)
            }
            read
        }
        if (!alreadyRead) api.markRead(channelId, messageId)
    }

    // Pins

    suspend fun loadPinned(channelId: String): List<Message> {
        val list = reportingForbidden { api.pinned(channelId) }
        _pinned.update { it + (channelId to list.map { m -> m.id }.toSet()) }
        return list.map { it.toModel() }
    }

    suspend fun setPinned(channelId: String, messageId: String, pinned: Boolean) {
        reportingForbidden { if (pinned) api.pin(channelId, messageId) else api.unpin(channelId, messageId) }
        applyPin(channelId, messageId, pinned)
    }

    private fun applyPin(channelId: String, messageId: String, pinned: Boolean) = _pinned.update { all ->
        val current = all[channelId] ?: return@update all
        all + (channelId to if (pinned) current + messageId else current - messageId)
    }

    // Conversas diretas

    suspend fun openDm(userId: String): String {
        val dm = api.openDm(userId)
        withContext(io) { q.transaction { upsert(dm) } }
        return dm.id
    }

    suspend fun closeDm(dmId: String) {
        api.closeDm(dmId)
        withContext(io) { q.deleteDm(dmId) }
    }

    // Digitação e presença

    /** Avisa que o usuário está digitando; limitado a um aviso a cada poucos segundos por canal. */
    suspend fun notifyTyping(channelId: String) {
        val last = lastTypingSent
        if (last != null && last.first == channelId && last.second.elapsedNow() < TYPING_THROTTLE) return
        lastTypingSent = channelId to TimeSource.Monotonic.markNow()
        ws.send(WsCommand.Typing(channelId))
    }

    /** Atividade real do usuário (toque, digitação); alimenta o "ausente" automático do servidor. */
    suspend fun notifyActivity(force: Boolean = false) {
        val last = lastActivitySent
        if (!force && last != null && last.elapsedNow() < ACTIVITY_THROTTLE) return
        lastActivitySent = TimeSource.Monotonic.markNow()
        ws.send(WsCommand.PresenceActivity)
    }

    private fun expireTyping() {
        val expired = typingMarks.filterValues { it.elapsedNow() > TYPING_TTL }.keys
        if (expired.isEmpty()) return
        expired.forEach { typingMarks.remove(it) }
        publishTyping()
    }

    private fun publishTyping() {
        _typing.value = typingMarks.keys.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
    }

    /**
     * O servidor respondeu 410 a um pedido do anexo: a moderação o bloqueou (o `message_delete` costuma vir junto, mas
     * não há ordem entre os dois). Grava `blocked` no anexo guardado, sem pedir nada ao servidor, para a tela trocá-lo
     * pelo aviso. Mensagem ou anexo desconhecido, ou já bloqueado, não muda nada.
     */
    suspend fun markBlocked(messageId: String, attachmentId: String) =
        withContext(io) { setAttachmentStatus(messageId, attachmentId, BLOCKED) }

    /** Troca o `moderation_status` do anexo da mensagem guardada; só grava se algo mudou. */
    private fun setAttachmentStatus(messageId: String, attachmentId: String, status: String) = q.transaction {
        val row = q.selectMessage(messageId).executeAsOneOrNull() ?: return@transaction
        val attachments = decodeList(AttachmentDto.serializer(), row.attachments)
        if (attachments.none { it.id == attachmentId && it.moderationStatus != status }) return@transaction
        val updated = attachments.map { if (it.id == attachmentId) it.copy(moderationStatus = status) else it }
        q.setMessageAttachments(encodeList(AttachmentDto.serializer(), updated), messageId)
    }

    // Eventos do WebSocket

    internal suspend fun handle(event: WsEvent) {
        when (event) {
            is WsEvent.MessageCreated -> {
                val fresh = withContext(io) {
                    val fresh = q.transactionWithResult {
                        // Num trecho antigo a mensagem nova não entra: ficaria um buraco entre ela e o que está guardado.
                        val insert = !detachedNow(event.channelId)
                        val fresh = insert && q.selectMessage(event.id).executeAsOneOrNull() == null
                        if (insert) {
                            q.insertMessageIfAbsent(
                                id = event.id,
                                channel_id = event.channelId,
                                author_id = event.authorId,
                                content = event.content,
                                created_at = event.createdAt,
                                created_us = microsOf(event.createdAt),
                                reply_to = event.replyTo,
                                webhook_id = event.webhookId,
                                external_author = event.externalAuthor?.let { PapoJson.encodeToString(ExternalAuthorDto.serializer(), it) },
                                attachments = encodeList(AttachmentDto.serializer(), event.attachments),
                            )
                        }
                        noteLastMessage(event.channelId, event.id, event.createdAt, preview(event.content, event.attachments), event.authorId)
                        fresh
                    }
                    event.authorId?.let { typingMarks.remove(event.channelId to it) }
                    publishTyping()
                    fresh
                }
                // Mensagem minha que chegou antes da resposta do POST: sai da fila de envio, senão apareceria duas vezes.
                // Se ela já estava guardada (a resposta chegou antes), a entrada não existe mais, e uma igual que
                // ainda esteja saindo é outra mensagem: não se mexe nela.
                if (fresh && event.authorId == myUserId) queue.confirm(event.channelId, event.content, event.replyTo, event.attachments.size)
            }
            is WsEvent.MessageEdited -> withContext(io) { q.editMessage(event.content, event.editedAt, event.id) }
            is WsEvent.MessageDeleted -> withContext(io) { q.deleteMessage(event.id) }
            is WsEvent.ReactionUpdated -> withContext(io) {
                reactionLock.withLock {
                    val row = q.selectMessage(event.messageId).executeAsOneOrNull() ?: return@withContext
                    val others = decodeList(ReactionSummaryDto.serializer(), row.reactions)
                        .filterNot { it.emojiId == event.emojiId && it.unicode == event.unicode }
                    val updated = if (event.count > 0) others + ReactionSummaryDto(event.emojiId, event.unicode, event.count) else others
                    var own = row.user_reactions
                    if (event.count == 0) {
                        own = encodeList(
                            UserReactionDto.serializer(),
                            decodeList(UserReactionDto.serializer(), own).filterNot { it.emojiId == event.emojiId && it.unicode == event.unicode },
                        )
                    }
                    q.setMessageReactions(encodeList(ReactionSummaryDto.serializer(), updated), own, event.messageId)
                }
            }
            is WsEvent.EmbedsUpdated -> withContext(io) {
                q.setMessageEmbeds(encodeList(EmbedDto.serializer(), event.embeds), event.messageId)
            }
            is WsEvent.AttachmentModeration -> withContext(io) { setAttachmentStatus(event.messageId, event.attachmentId, event.status) }
            is WsEvent.MessagePinned -> {
                val channelId = withContext(io) { q.selectMessage(event.messageId).executeAsOneOrNull()?.channel_id }
                if (channelId != null) {
                    applyPin(channelId, event.messageId, event.isPinned)
                } else if (!event.isPinned) {
                    _pinned.update { all -> all.mapValues { it.value - event.messageId } }
                }
            }
            is WsEvent.ChannelCreated -> {
                withContext(io) {
                    q.insertChannelIfAbsent(event.channelId, event.name, event.channelType, event.position.toLong(), event.topic, event.parentId)
                }
                // O evento não traz as permissões do canal novo; a leitura delas vem logo depois.
                channelRefreshRequests.trySend(Unit)
            }
            is WsEvent.ChannelUpdated -> {
                withContext(io) {
                    q.updateChannelInfo(event.name, event.position.toLong(), event.topic, event.parentId, event.channelId)
                }
                // Mudar a permissão de um cargo no canal também chega aqui, sem as permissões e sem `parent_id`
                // (o canal sairia da categoria): a releitura traz as entradas e devolve a categoria.
                channelRefreshRequests.trySend(Unit)
            }
            is WsEvent.ChannelDeleted -> withContext(io) {
                q.transaction {
                    q.deleteChannel(event.channelId)
                    q.deleteChannelMessages(event.channelId)
                    meta.remove(DETACHED + event.channelId)
                }
            }
            is WsEvent.Typing -> {
                if (event.userId.isEmpty() || event.userId == myUserId) return
                val key = event.channelId to event.userId
                if (event.isTyping) typingMarks[key] = TimeSource.Monotonic.markNow() else typingMarks.remove(key)
                publishTyping()
            }
            is WsEvent.AvatarUpdated -> withContext(io) { q.bumpAvatar(event.userId) }
            is WsEvent.UserJoined -> launchUsersSync()
            is WsEvent.RoleAdded -> {
                permissions.roleAdded(event.userId, event.roleId)
                launchUsersSync()
            }
            is WsEvent.RoleRemoved -> {
                permissions.roleRemoved(event.userId, event.roleId)
                launchUsersSync()
            }
            is WsEvent.DmUpdated -> withContext(io) { q.transaction { upsert(event.dm) } }
            is WsEvent.PresenceUpdated -> {
                _presence.update { it + (event.userId to Presence(event.status, event.statusMessage)) }
                withContext(io) { q.updatePresenceFields(event.statusMessage, event.typing, event.nickname, event.userId) }
            }
            is WsEvent.PresenceSync -> {
                _presence.value = event.members.associate { it.userId to Presence(it.status, it.statusMessage) }
                val voice = HashMap<String, MutableSet<String>>()
                event.members.forEach { member -> member.voiceChannels.forEach { voice.getOrPut(it) { mutableSetOf() }.add(member.userId) } }
                _voiceMembers.value = voice
            }
            is WsEvent.VoiceStateUpdated -> _voiceMembers.update { all ->
                all + (event.channelId to (all[event.channelId].orEmpty() + event.userId))
            }
            is WsEvent.VoiceLeft -> _voiceMembers.update { all ->
                all + (event.channelId to (all[event.channelId].orEmpty() - event.userId))
            }
            is WsEvent.VoiceJoined -> _voiceMembers.update { all ->
                all + (event.channelId to (event.members.map { it.userId }.toSet() + myUserId))
            }
            else -> Unit
        }
    }

    private fun launchUsersSync() {
        scope.launch { quietly { syncUsers() } }
    }

    // Gravação

    private fun noteLastMessage(channelId: String, messageId: String, createdAt: String, text: String, authorId: String?) {
        val author = authorId?.let { q.selectUser(it).executeAsOneOrNull()?.username }
        q.setChannelLastMessage(messageId, createdAt, text, author, channelId)
        val mine = authorId == myUserId
        val unseen = if (mine || viewing == channelId) 0L else 1L
        q.setDmLastMessage(messageId, createdAt, text, author, unseen, channelId)
        if (mine) {
            q.setChannelRead(messageId, channelId)
            q.setDmRead(messageId, channelId)
        }
    }

    private fun upsert(user: UserSummaryDto) = q.upsertUser(
        id = user.id,
        username = user.username,
        nickname = user.nickname,
        status = user.status,
        status_message = user.statusMessage,
        typing_phrase = user.typing,
        banned = user.banned,
        role_color = user.roles.firstNotNullOfOrNull { it.color },
    )

    private fun upsert(channel: ChannelDto) = q.upsertChannel(
        id = channel.id,
        name = channel.name,
        type = channel.type,
        position = channel.position.toLong(),
        topic = channel.topic,
        parent_id = channel.parentId,
        last_message_id = channel.lastMessage?.id,
        last_message_at = channel.lastMessage?.createdAt,
        last_message_text = channel.lastMessage?.content,
        last_message_author = channel.lastMessage?.authorUsername,
        last_read_message = channel.lastReadMessage,
        notification_settings = channel.notificationSettings,
    )

    private fun upsert(dm: DirectConversationDto) {
        upsert(dm.user)
        q.upsertDm(
            id = dm.id,
            user_id = dm.user.id,
            created_at = dm.createdAt,
            last_message_id = dm.lastMessage?.id,
            last_message_at = dm.lastMessage?.createdAt,
            last_message_text = dm.lastMessage?.content,
            last_message_author = dm.lastMessage?.authorUsername,
            last_read_message = dm.lastReadMessage,
            unread_count = dm.unreadCount.toLong(),
        )
    }

    private fun upsert(message: MessageDto) = q.upsertMessage(
        id = message.id,
        channel_id = message.channelId,
        author_id = message.authorId,
        content = message.content,
        created_at = message.createdAt,
        created_us = message.createdUs(),
        edited_at = message.editedAt,
        reply_to = message.replyTo,
        webhook_id = message.webhookId,
        external_author = message.externalAuthor?.let { PapoJson.encodeToString(ExternalAuthorDto.serializer(), it) },
        attachments = encodeList(AttachmentDto.serializer(), message.attachments),
        embeds = encodeList(EmbedDto.serializer(), message.embeds),
        reactions = encodeList(ReactionSummaryDto.serializer(), message.reactions),
        user_reactions = encodeList(UserReactionDto.serializer(), message.userReactions),
    )

    private fun MessageDto.createdUs() = microsOf(createdAt)

    private fun MessageDto.toModel() = Message(
        id = id,
        channelId = channelId,
        authorId = authorId,
        content = content.orEmpty(),
        createdAt = parseInstant(createdAt),
        createdAtIso = createdAt,
        edited = editedAt != null,
        replyTo = replyTo,
        webhookId = webhookId,
        externalAuthor = externalAuthor,
        attachments = attachments,
        embeds = embeds,
        reactions = reactions,
        userReactions = userReactions,
    )

    private fun MessageRow.toModel() = Message(
        id = id,
        channelId = channel_id,
        authorId = author_id,
        content = content.orEmpty(),
        createdAt = parseInstant(created_at),
        createdAtIso = created_at,
        edited = edited_at != null,
        replyTo = reply_to,
        webhookId = webhook_id,
        externalAuthor = external_author?.let { runCatching { PapoJson.decodeFromString(ExternalAuthorDto.serializer(), it) }.getOrNull() },
        attachments = decodeList(AttachmentDto.serializer(), attachments),
        embeds = decodeList(EmbedDto.serializer(), embeds),
        reactions = decodeList(ReactionSummaryDto.serializer(), reactions),
        userReactions = decodeList(UserReactionDto.serializer(), user_reactions),
    )

    private fun ChannelRow.toModel() = Channel(
        id = id,
        name = name,
        type = ChannelType.from(type),
        position = position.toInt(),
        topic = topic,
        parentId = parent_id,
        lastMessageId = last_message_id,
        lastMessageAt = last_message_at,
        lastMessageText = last_message_text,
        lastMessageAuthor = last_message_author,
        lastReadMessage = last_read_message,
        notification = notification_settings,
    )

    private fun DmRow.toModel() = Dm(
        id = id,
        userId = user_id,
        lastMessageId = last_message_id,
        lastMessageText = last_message_text,
        lastMessageAt = last_message_at,
        unreadCount = unread_count.toInt(),
    )

    private fun UserRow.toModel() = User(
        id = id,
        username = username,
        nickname = nickname,
        statusMessage = status_message,
        typingPhrase = typing_phrase,
        banned = banned,
        roleColor = role_color,
        avatarVersion = avatar_version,
    )

    private companion object {
        const val PAGE_SIZE = 40
        // Ao abrir uma mensagem antiga: o máximo que o servidor entrega do que veio depois dela.
        // Quanto mais longe essa página chega, maior a chance de encostar no trecho já guardado.
        const val AROUND_NEWER = 100
        // Procura da mensagem respondida: páginas do tamanho máximo do servidor, e quantas ler antes de desistir.
        const val ORIGINAL_PAGE_SIZE = 100
        const val ORIGINAL_MAX_PAGES = 5
        const val DETACHED = "detached:"
        const val BLOCKED = "blocked"
        const val MAX_USER_PAGES = 50
        // Mudar permissões de canal manda um channel_update por cargo; uma releitura basta para a rajada.
        val CHANNELS_REFRESH_DEBOUNCE = 1.seconds
        // O servidor nunca avisa que alguém parou de digitar: o indicador expira aqui.
        val TYPING_THROTTLE = 2.seconds
        val TYPING_TTL = 6.seconds
        val ACTIVITY_THROTTLE = 15.seconds

        fun preview(content: String?, attachments: List<AttachmentDto>): String =
            content?.takeIf { it.isNotBlank() } ?: if (attachments.isNotEmpty()) "📎 ${attachments.first().fileName}" else ""

        fun microsOf(iso: String): Long {
            val instant = parseInstant(iso) ?: return 0
            return instant.epochSeconds * 1_000_000 + instant.nanosecondsOfSecond / 1_000
        }

        fun <T> encodeList(serializer: kotlinx.serialization.KSerializer<T>, list: List<T>): String =
            PapoJson.encodeToString(ListSerializer(serializer), list)

        fun <T> decodeList(serializer: kotlinx.serialization.KSerializer<T>, text: String): List<T> =
            runCatching { PapoJson.decodeFromString(ListSerializer(serializer), text) }.getOrDefault(emptyList())
    }
}
