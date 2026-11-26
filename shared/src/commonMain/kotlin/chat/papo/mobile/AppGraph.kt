package chat.papo.mobile

import app.cash.sqldelight.db.SqlDriver
import chat.papo.mobile.account.AccountManager
import chat.papo.mobile.account.AccountResources
import chat.papo.mobile.account.AccountStore
import chat.papo.mobile.core.AppDispatchers
import chat.papo.mobile.core.crypto.Crypto
import chat.papo.mobile.core.crypto.KeyRing
import chat.papo.mobile.data.db.DatabaseFactory
import chat.papo.mobile.data.db.account.AccountDb
import chat.papo.mobile.data.db.registry.RegistryDb
import chat.papo.mobile.media.AudioAccounts
import chat.papo.mobile.media.AudioEngineFactory
import chat.papo.mobile.media.AudioPlayback
import chat.papo.mobile.media.MediaCache
import chat.papo.mobile.media.VideoPosterExtractor
import chat.papo.mobile.security.AppLock
import chat.papo.mobile.security.AppPrefs
import chat.papo.mobile.security.LockState
import chat.papo.mobile.security.MasterKeyStore
import chat.papo.mobile.security.UnlockedSession
import chat.papo.mobile.security.WebPlayerData
import chat.papo.mobile.voice.CallManager
import chat.papo.mobile.voice.VoiceEngineFactory
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

/** O que cada plataforma precisa fornecer ao código comum. */
class PlatformDeps(
    val crypto: Crypto,
    val keyStore: MasterKeyStore,
    val prefs: AppPrefs,
    val databases: DatabaseFactory,
    val httpEngine: HttpClientEngine,
    val dispatchers: AppDispatchers,
    /** Pasta do cache de mídia (apagável pelo sistema); cada conta usa uma subpasta. */
    val mediaDirectory: String,
    /** Motor de chamadas; null onde a plataforma ainda não tem um. */
    val voice: VoiceEngineFactory? = null,
    /** Tira o quadro da capa dos vídeos; null onde a plataforma ainda não sabe, e então os cartões ficam sem capa. */
    val videoPosters: VideoPosterExtractor? = null,
    /** Versão instalada do app, como mostrada ao usuário. */
    val appVersion: String = "",
    /** Apaga o que o player web do YouTube deixou em claro; null onde a plataforma não tem um. Roda quando a sessão fecha. */
    val webPlayerData: WebPlayerData? = null,
    /** Motor do player de áudio; null onde a plataforma ainda não tem um, e então os áudios aparecem como arquivos. */
    val audio: AudioEngineFactory? = null,
)

/** Estado do app desbloqueado: registro de contas aberto e uma sessão por conta. */
class AppSession internal constructor(
    val accounts: AccountManager,
    val keys: KeyRing,
    val calls: CallManager,
    private val registry: SqlDriver,
    private val scope: CoroutineScope,
    /** O player de áudio de todas as contas (um áudio por vez); null sem motor na plataforma. */
    val audio: AudioPlayback? = null,
) : UnlockedSession {
    /** Em chamada o app não bloqueia sozinho nem desliga o tempo real. */
    override val busy: StateFlow<Boolean> get() = calls.active

    override suspend fun close() {
        calls.leave()
        audio?.release()
        accounts.close()
        scope.cancel()
        registry.close()
    }
}

/** Raiz de dependências do app, criada uma vez por processo. */
class AppGraph(private val platform: PlatformDeps) {
    val scope = CoroutineScope(SupervisorJob() + platform.dispatchers.main)

    val appVersion: String get() = platform.appVersion

    val lock = AppLock(
        keyStore = platform.keyStore,
        crypto = platform.crypto,
        prefs = platform.prefs,
        wiper = {
            platform.databases.deleteAll()
            MediaCache.deleteDirectory(platform.mediaDirectory)
        },
        scope = scope,
        openSession = ::openSession,
        onSessionClosed = { platform.webPlayerData?.wipe() },
    )

    private fun mediaDir(accountId: String) = "${platform.mediaDirectory}/$accountId"

    /** App voltou para a tela: confere o bloqueio e religa o tempo real das contas. */
    fun onForeground() {
        lock.onForeground()
        currentSession()?.accounts?.setForeground(true)
    }

    /** App saiu da tela: agenda o bloqueio e desliga o tempo real. */
    fun onBackground() {
        // Tocar em segundo plano exigiria um serviço de mídia, que o app não tem: o áudio pausa, mesmo com uma chamada aberta.
        currentSession()?.audio?.pause()
        currentSession()?.takeIf { !it.busy.value }?.accounts?.setForeground(false)
        lock.onBackground()
    }

    /** Sessão atual, se o app está desbloqueado. */
    fun currentSession(): AppSession? = (lock.state.value as? LockState.Unlocked)?.session as? AppSession

    private suspend fun openSession(keys: KeyRing): UnlockedSession = withContext(platform.dispatchers.io) {
        val key = keys.registryDbKey()
        val driver = try {
            platform.databases.open(DatabaseFactory.REGISTRY, key, RegistryDb.Schema)
        } finally {
            key.fill(0)
        }
        val sessionScope = CoroutineScope(SupervisorJob() + platform.dispatchers.main)
        val accounts = AccountManager(
            store = AccountStore(RegistryDb(driver), platform.dispatchers.io),
            engine = platform.httpEngine,
            prefs = platform.prefs,
            dispatchers = platform.dispatchers,
            scope = sessionScope,
            openResources = { id ->
                val dbKey = keys.accountDbKey(id)
                try {
                    AccountResources(
                        driver = platform.databases.open(DatabaseFactory.accountDbName(id), dbKey, AccountDb.Schema),
                        media = MediaCache(mediaDir(id), keys.mediaKey(id), platform.crypto, platform.dispatchers.io),
                    )
                } finally {
                    dbKey.fill(0)
                }
            },
            deleteLocalData = { id ->
                platform.databases.delete(DatabaseFactory.accountDbName(id))
                MediaCache.deleteDirectory(mediaDir(id))
            },
            posterExtractor = platform.videoPosters,
        )
        accounts.load()
        val audio = platform.audio?.let { engines ->
            // O áudio é da conta selecionada e da sessão que o abriu: o player confere os dois a cada mudança.
            val live = combine(accounts.activeId, accounts.sessionMap) { active, sessions ->
                AudioAccounts(active, sessions.values.map { it.client })
            }
            AudioPlayback(engines, sessionScope, live)
        }
        AppSession(accounts, keys, CallManager(platform.voice, sessionScope), driver, sessionScope, audio)
    }
}
