package chat.papo.mobile.account

import app.cash.sqldelight.db.SqlDriver
import chat.papo.mobile.chat.AccessStore
import chat.papo.mobile.chat.AuthorAvatars
import chat.papo.mobile.chat.BlockList
import chat.papo.mobile.chat.Channel
import chat.papo.mobile.chat.ChannelNotificationLevels
import chat.papo.mobile.chat.ChatStore
import chat.papo.mobile.chat.ConversationDirectory
import chat.papo.mobile.chat.Dm
import chat.papo.mobile.chat.Drafts
import chat.papo.mobile.chat.EmojiCatalog
import chat.papo.mobile.chat.MentionBadges
import chat.papo.mobile.chat.MessageRetention
import chat.papo.mobile.chat.readableChannels
import chat.papo.mobile.chat.NotificationDispatcher
import chat.papo.mobile.chat.NotificationInbox
import chat.papo.mobile.chat.NotificationPrefs
import chat.papo.mobile.chat.ProfileCache
import chat.papo.mobile.chat.RecentEmojis
import chat.papo.mobile.chat.RecentSearchFilters
import chat.papo.mobile.chat.SystemNotifications
import chat.papo.mobile.chat.WebhookAvatars
import chat.papo.mobile.chat.avatarMediaName
import chat.papo.mobile.chat.profileStamp
import chat.papo.mobile.data.api.ApiException
import chat.papo.mobile.data.api.AuthApi
import chat.papo.mobile.data.api.ChatApi
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.data.api.PushApi
import chat.papo.mobile.data.db.account.AccountDb
import chat.papo.mobile.data.ws.WsClient
import chat.papo.mobile.media.MediaCache
import chat.papo.mobile.media.VideoPosterExtractor
import chat.papo.mobile.media.VideoPosters
import chat.papo.mobile.push.PushRegistration
import chat.papo.mobile.push.PushTokens
import chat.papo.mobile.voice.StoredVoiceVolumes
import chat.papo.mobile.voice.VoiceVolumes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Banco e cache de mídia de uma conta, já abertos com as chaves dela. */
class AccountResources(val driver: SqlDriver, val media: MediaCache)

/** Tudo o que pertence a uma conta enquanto o app está desbloqueado. Nada é compartilhado entre contas. */
class AccountSession(
    val accountId: String,
    val userId: String,
    val client: PapoClient,
    val scope: CoroutineScope,
    resources: AccountResources,
    io: CoroutineDispatcher,
    background: CoroutineDispatcher = io,
    posterExtractor: VideoPosterExtractor? = null,
    /** Onde as notificações do sistema aparecem; nulo onde a plataforma não tem (e então a conta não notifica fora do app). */
    notifications: SystemNotifications? = null,
    /** O nome da conta nas notificações (servidor e `@usuário`), lido a cada notificação porque o rótulo pode mudar. */
    accountLabel: () -> String = { accountId },
    /** O token de push do aparelho; nulo onde a plataforma não tem push, e então a conta não registra o aparelho. */
    pushTokens: PushTokens? = null,
) {
    private val driver = resources.driver
    val media: MediaCache = resources.media
    val auth = AuthApi(client)
    val api = ChatApi(client)
    /** Fila das capas de vídeo desta conta: uma extração por vez, pelo cliente dela. */
    val videoPosters = VideoPosters(client, posterExtractor)
    private val db = AccountDb(driver)
    val emojis = EmojiCatalog(api, media, db.metaQueries, scope, io, background)
    val recentEmojis = RecentEmojis(db.metaQueries, scope, io)
    val drafts = Drafts(db.metaQueries, scope, io)
    /** O volume de cada pessoa nas chamadas desta conta; no banco cifrado dela, sem ir ao servidor. */
    val voiceVolumes: VoiceVolumes = StoredVoiceVolumes(db.metaQueries, scope, io)
    val recentSearches = RecentSearchFilters()
    /** O tema só deste servidor, se a conta tiver um; no banco cifrado dela. */
    val theme = ServerThemeStore(db.metaQueries, scope, io)

    val ws = WsClient(client, scope, sessionStillValid = ::sessionStillValid)
    /** Dono do servidor, cargos e entradas por canal desta conta; o `ChatStore` o lê e o atualiza. */
    val permissions = AccessStore(db.metaQueries, api, scope, io, server = auth::server)
    val chat = ChatStore(userId, db, api, ws, scope, io, permissions, retention = MessageRetention(db, io, media))
    /** O nível de notificação que o servidor guarda por canal; é o que vale nos canais sincronizados. */
    private val channelLevels = ChannelNotificationLevels(api, db.chatQueries, userId, chat.channels, chat::reloadChannels, scope, io, chat::requestRefresh)
    /** Silêncio, supressão e nível das notificações desta conta; ficam no banco cifrado dela (o nível sincronizado, no servidor). */
    val notificationPrefs = NotificationPrefs(db.metaQueries, scope, io, channelLevels)
    /** Caixa de notificações desta conta, só em memória; as contagens de menção saem dela. */
    val inbox = NotificationInbox(userId, api, ws.events, ws.state, chat.readMarks, chat::storedPositions, scope)
    /** O número de menções por canal, por conversa direta e no total, já com a supressão aplicada. */
    val mentions = MentionBadges(
        userId, inbox.state, chat.dms, combine(chat.channels, chat.access) { list, access -> readableChannels(list, access, userId) },
        notificationPrefs, scope,
    )
    /** Avatares dos webhooks: um pedido ao servidor por sessão, depois o cache de mídia cifrado da conta. */
    val webhookAvatars = WebhookAvatars(api, media, scope)
    /** Decide quais mensagens do tempo real viram notificação do sistema; só existe se a plataforma notifica. */
    private val dispatcher = notifications?.let { system ->
        NotificationDispatcher(
            accountId = accountId,
            userId = userId,
            accountLabel = accountLabel,
            events = ws.events,
            readMarks = chat.readMarks,
            inbox = inbox,
            prefs = notificationPrefs,
            directory = object : ConversationDirectory {
                override fun channel(id: String): Channel? = readableChannels(chat.channels.value, chat.access.value, userId).firstOrNull { it.id == id }
                override fun dm(id: String): Dm? = chat.dms.value.firstOrNull { it.id == id }
                override fun userName(id: String): String? = chat.users.value[id]?.displayName
            },
            avatars = object : AuthorAvatars {
                // O mesmo nome e o mesmo pedido da imagem do usuário na conversa: o cache de mídia da conta a guarda cifrada.
                override suspend fun ofUser(userId: String): ByteArray? {
                    val user = chat.users.value[userId] ?: return null
                    return media.get(avatarMediaName(userId, user.avatarVersion), holdFailures = true) { api.avatar(userId) }
                }

                override suspend fun ofWebhook(webhookId: String): ByteArray? = webhookAvatars.get(webhookId)
            },
            viewing = chat.viewingChannel,
            mentionCount = { id -> mentions.mentions.value.let { it.perChannel[id] ?: it.perDm[id] ?: 0 } },
            notifier = system.notifier,
            texts = system.texts,
            scope = scope,
        )
    }
    /** O registro do aparelho para push no servidor desta conta; só existe se a plataforma tem push. */
    val push: PushRegistration? = pushTokens?.let { tokens ->
        PushRegistration(PushApi(client), tokens, db.metaQueries, scope, io, hasSession = { client.cookies.get() != null })
    }
    /** Quem esta conta bloqueou; a lista é desta sessão e não vale para outra conta. */
    val blocks = BlockList(api, db.chatQueries, ws.state, scope, io)
    /** Perfis lidos nesta sessão; a leitura vale enquanto a presença, o apelido e o avatar do usuário não mudam. */
    val profiles = ProfileCache(api, media, scope, background, stampOf = { id -> profileStamp(chat.users.value[id], chat.presence.value[id]) })

    private var started = false

    /** Liga a sincronização e o tempo real. Sem efeito se a sessão já expirou. */
    fun start() {
        if (client.cookies.get() == null) return
        if (!started) {
            started = true
            // Antes do WebSocket: o primeiro evento não pode passar sem quem o escute.
            dispatcher?.start()
            chat.start()
            blocks.start()
            inbox.start()
            // Uma vez por sessão: com o app indo e voltando da frente só o tempo real religa (regra 15).
            push?.start()
        } else {
            ws.start()
        }
    }

    /** Desliga o tempo real (app em segundo plano). O que está no banco continua disponível. */
    fun pause() {
        scope.launch { ws.stop() }
    }

    fun close() {
        scope.cancel()
        client.close()
        runCatching { driver.close() }
    }

    private suspend fun sessionStillValid(): Boolean = try {
        auth.whoami()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        !e.isUnauthorized
    } catch (e: Exception) {
        true // sem rede: continua tentando
    }
}
