package chat.papo.mobile.chat

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import chat.papo.mobile.core.parseInstant
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
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
) {
    private val q = db.chatQueries
    private val syncMutex = Mutex()

    val channels: StateFlow<List<Channel>> =
        q.selectChannels().asFlow().mapToList(io).map { rows -> rows.map { it.toModel() } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val dms: StateFlow<List<Dm>> =
        q.selectDms().asFlow().mapToList(io).map { rows -> rows.map { it.toModel() } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val users: StateFlow<Map<String, User>> =
        q.selectUsers().asFlow().mapToList(io).map { rows -> rows.associate { it.id to it.toModel() } }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

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

    val connection: StateFlow<WsState> get() = ws.state

    private val hasOlder = HashMap<String, Boolean>()
    private var viewing: String? = null
    private var lastTypingSent: Pair<String, TimeMark>? = null
    private var lastActivitySent: TimeMark? = null
    private val typingMarks = HashMap<Pair<String, String>, TimeMark>()

    fun start() {
        scope.launch { ws.events.collect { runCatching { handle(it) } } }
        scope.launch {
            // Cada reconexão pode ter perdido eventos: recarrega as listas e o canal aberto.
            ws.state.collect { state ->
                if (state == WsState.Connected) {
                    notifyActivity(force = true)
                    runSync()
                    viewing?.let { runCatching { refreshLatest(it) } }
                }
            }
        }
        scope.launch {
            while (true) {
                delay(3.seconds)
                expireTyping()
            }
        }
        ws.start()
    }

    /** Canal que está na tela agora; mensagens dele não contam como não lidas. */
    fun setViewing(channelId: String?) {
        viewing = channelId
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

    /** Recarrega canais, conversas diretas e usuários. */
    suspend fun sync() = syncMutex.withLock {
        _syncing.value = true
        try {
            val channelList = api.channels()
            val dmList = api.dms()
            withContext(io) {
                q.transaction {
                    channelList.forEach(::upsert)
                    if (channelList.isEmpty()) q.deleteAllChannels() else q.deleteChannelsNotIn(channelList.map { it.id })
                    dmList.forEach(::upsert)
                    if (dmList.isEmpty()) q.deleteAllDms() else q.deleteDmsNotIn(dmList.map { it.id })
                }
            }
            syncUsers()
        } finally {
            _syncing.value = false
        }
    }

    private suspend fun syncUsers() {
        var since: String? = null
        var lastId: String? = null
        repeat(MAX_USER_PAGES) {
            val page = api.users(since, lastId)
            withContext(io) { q.transaction { page.users.forEach(::upsert) } }
            val last = page.users.lastOrNull()
            if (!page.hasMore || last?.createdAt == null) return
            since = last.createdAt
            lastId = last.id
        }
    }

    // Mensagens

    fun messages(channelId: String, limit: Long): Flow<List<Message>> =
        q.selectMessages(channelId, limit).asFlow().mapToList(io).map { rows -> rows.map { it.toModel() } }

    fun hasOlder(channelId: String): Boolean = hasOlder[channelId] ?: true

    /**
     * Busca a página mais recente do canal. Se ela não encosta no que já está
     * guardado, o que é mais antigo é descartado: assim nunca fica um buraco
     * invisível no meio do histórico.
     */
    suspend fun refreshLatest(channelId: String) {
        val page = api.messages(channelId, limit = PAGE_SIZE)
        withContext(io) {
            q.transaction {
                val fetched = page.messages
                if (fetched.isNotEmpty()) {
                    val overlap = q.countMessagesIn(fetched.map { it.id }).executeAsOne()
                    val stored = q.newestMessage(channelId).executeAsOneOrNull()
                    if (stored != null && overlap == 0L && page.hasMore) {
                        q.deleteMessagesOlderThan(channelId, fetched.minOf { it.createdUs() })
                    }
                } else if (!page.hasMore) {
                    q.deleteChannelMessages(channelId)
                }
                fetched.forEach(::upsert)
            }
        }
        if (!page.hasMore && (hasOlder[channelId] != false)) {
            val stored = withContext(io) { q.selectMessages(channelId, PAGE_SIZE + 1L).executeAsList().size }
            if (stored <= page.messages.size) hasOlder[channelId] = false
        }
    }

    /** Carrega a página anterior à mensagem mais antiga guardada. Devolve false quando o histórico acabou. */
    suspend fun loadOlder(channelId: String): Boolean {
        if (hasOlder[channelId] == false) return false
        val oldest = withContext(io) { q.oldestMessage(channelId).executeAsOneOrNull() } ?: return false
        val page = api.messages(channelId, since = oldest.created_at, lastId = oldest.id, limit = PAGE_SIZE)
        withContext(io) { q.transaction { page.messages.forEach(::upsert) } }
        hasOlder[channelId] = page.hasMore && page.messages.isNotEmpty()
        return hasOlder[channelId] == true
    }

    suspend fun send(channelId: String, content: String, replyTo: String?, files: List<OutgoingFile>) {
        val sent = api.sendMessage(channelId, content.trim(), replyTo, files)
        withContext(io) {
            q.transaction {
                upsert(sent)
                noteLastMessage(sent.channelId, sent.id, sent.createdAt, preview(sent.content, sent.attachments), sent.authorId)
            }
        }
    }

    suspend fun edit(messageId: String, content: String) {
        val edited = api.editMessage(messageId, content.trim())
        withContext(io) { upsert(edited) }
    }

    suspend fun delete(messageId: String) {
        api.deleteMessage(messageId)
        withContext(io) { q.deleteMessage(messageId) }
    }

    suspend fun toggleReaction(message: Message, emojiId: String?, unicode: String?) {
        val request = ReactionRequest(emojiId, unicode)
        val mine = message.userReactions.any { it.emojiId == emojiId && it.unicode == unicode }
        if (mine) api.unreact(message.channelId, message.id, request) else api.react(message.channelId, message.id, request)
        withContext(io) {
            val row = q.selectMessage(message.id).executeAsOneOrNull() ?: return@withContext
            val own = decodeList(UserReactionDto.serializer(), row.user_reactions)
            val updated = if (mine) {
                own.filterNot { it.emojiId == emojiId && it.unicode == unicode }
            } else {
                own + UserReactionDto(emojiId = emojiId, unicode = unicode)
            }
            // A contagem chega pelo evento react_update; aqui só muda o "eu reagi".
            q.setMessageReactions(row.reactions, encodeList(UserReactionDto.serializer(), updated), message.id)
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
        val list = api.pinned(channelId)
        _pinned.update { it + (channelId to list.map { m -> m.id }.toSet()) }
        return list.map { it.toModel() }
    }

    suspend fun setPinned(channelId: String, messageId: String, pinned: Boolean) {
        if (pinned) api.pin(channelId, messageId) else api.unpin(channelId, messageId)
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

    // Eventos do WebSocket

    internal suspend fun handle(event: WsEvent) {
        when (event) {
            is WsEvent.MessageCreated -> withContext(io) {
                q.transaction {
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
                    noteLastMessage(event.channelId, event.id, event.createdAt, preview(event.content, event.attachments), event.authorId)
                }
                event.authorId?.let { typingMarks.remove(event.channelId to it) }
                publishTyping()
            }
            is WsEvent.MessageEdited -> withContext(io) { q.editMessage(event.content, event.editedAt, event.id) }
            is WsEvent.MessageDeleted -> withContext(io) { q.deleteMessage(event.id) }
            is WsEvent.ReactionUpdated -> withContext(io) {
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
            is WsEvent.EmbedsUpdated -> withContext(io) {
                q.setMessageEmbeds(encodeList(EmbedDto.serializer(), event.embeds), event.messageId)
            }
            is WsEvent.AttachmentModeration -> withContext(io) {
                val row = q.selectMessage(event.messageId).executeAsOneOrNull() ?: return@withContext
                val updated = decodeList(AttachmentDto.serializer(), row.attachments)
                    .map { if (it.id == event.attachmentId) it.copy(moderationStatus = event.status) else it }
                q.setMessageAttachments(encodeList(AttachmentDto.serializer(), updated), event.messageId)
            }
            is WsEvent.MessagePinned -> {
                val channelId = withContext(io) { q.selectMessage(event.messageId).executeAsOneOrNull()?.channel_id }
                if (channelId != null) {
                    applyPin(channelId, event.messageId, event.isPinned)
                } else if (!event.isPinned) {
                    _pinned.update { all -> all.mapValues { it.value - event.messageId } }
                }
            }
            is WsEvent.ChannelCreated -> withContext(io) {
                q.insertChannelIfAbsent(event.channelId, event.name, event.channelType, event.position.toLong(), event.topic, event.parentId)
            }
            is WsEvent.ChannelUpdated -> withContext(io) {
                q.updateChannelInfo(event.name, event.position.toLong(), event.topic, event.parentId, event.channelId)
            }
            is WsEvent.ChannelDeleted -> withContext(io) {
                q.transaction {
                    q.deleteChannel(event.channelId)
                    q.deleteChannelMessages(event.channelId)
                }
            }
            is WsEvent.Typing -> {
                if (event.userId.isEmpty() || event.userId == myUserId) return
                val key = event.channelId to event.userId
                if (event.isTyping) typingMarks[key] = TimeSource.Monotonic.markNow() else typingMarks.remove(key)
                publishTyping()
            }
            is WsEvent.AvatarUpdated -> withContext(io) { q.bumpAvatar(event.userId) }
            is WsEvent.UserJoined, is WsEvent.RoleAdded, is WsEvent.RoleRemoved -> scope.launch { runCatching { syncUsers() } }
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
        const val MAX_USER_PAGES = 50
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
