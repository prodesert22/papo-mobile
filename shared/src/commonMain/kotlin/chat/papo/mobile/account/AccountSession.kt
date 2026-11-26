package chat.papo.mobile.account

import app.cash.sqldelight.db.SqlDriver
import chat.papo.mobile.chat.AccessStore
import chat.papo.mobile.chat.ChatStore
import chat.papo.mobile.chat.Drafts
import chat.papo.mobile.chat.EmojiCatalog
import chat.papo.mobile.chat.ProfileCache
import chat.papo.mobile.chat.RecentEmojis
import chat.papo.mobile.chat.RecentSearchFilters
import chat.papo.mobile.chat.WebhookAvatars
import chat.papo.mobile.chat.profileStamp
import chat.papo.mobile.data.api.ApiException
import chat.papo.mobile.data.api.AuthApi
import chat.papo.mobile.data.api.ChatApi
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.data.db.account.AccountDb
import chat.papo.mobile.data.ws.WsClient
import chat.papo.mobile.media.MediaCache
import chat.papo.mobile.media.VideoPosterExtractor
import chat.papo.mobile.media.VideoPosters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
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
    val recentSearches = RecentSearchFilters()

    val ws = WsClient(client, scope, sessionStillValid = ::sessionStillValid)
    /** Dono do servidor, cargos e entradas por canal desta conta; o `ChatStore` o lê e o atualiza. */
    val permissions = AccessStore(db.metaQueries, api, scope, io, server = auth::server)
    val chat = ChatStore(userId, db, api, ws, scope, io, permissions)
    /** Avatares dos webhooks: um pedido ao servidor por sessão, depois o cache de mídia cifrado da conta. */
    val webhookAvatars = WebhookAvatars(api, media, scope)
    /** Perfis lidos nesta sessão; a leitura vale enquanto a presença, o apelido e o avatar do usuário não mudam. */
    val profiles = ProfileCache(api, media, scope, background, stampOf = { id -> profileStamp(chat.users.value[id], chat.presence.value[id]) })

    private var started = false

    /** Liga a sincronização e o tempo real. Sem efeito se a sessão já expirou. */
    fun start() {
        if (client.cookies.get() == null) return
        if (!started) {
            started = true
            chat.start()
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
