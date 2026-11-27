package chat.papo.mobile

import chat.papo.mobile.core.BuildInfo
import app.cash.sqldelight.db.SqlDriver
import chat.papo.mobile.account.AccountManager
import chat.papo.mobile.account.AccountResources
import chat.papo.mobile.account.AccountStore
import chat.papo.mobile.chat.NotificationOpenRequests
import chat.papo.mobile.chat.NotificationPermission
import chat.papo.mobile.chat.NotificationPermissionPrompt
import chat.papo.mobile.chat.NotificationTexts
import chat.papo.mobile.chat.SystemNotifications
import chat.papo.mobile.chat.SystemNotifier
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
import chat.papo.mobile.media.VoiceClipPlayerFactory
import chat.papo.mobile.media.VoiceRecorder
import chat.papo.mobile.media.VoiceRecording
import chat.papo.mobile.push.PushTokens
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.notif_system_attachment
import chat.papo.mobile.resources.notif_system_empty
import chat.papo.mobile.resources.notif_system_unknown_user
import chat.papo.mobile.security.AppLock
import chat.papo.mobile.security.AppPrefs
import chat.papo.mobile.security.LockState
import chat.papo.mobile.security.MasterKeyStore
import chat.papo.mobile.security.NotificationPrivacy
import chat.papo.mobile.security.UnlockedSession
import chat.papo.mobile.security.WebPlayerData
import chat.papo.mobile.ui.theme.AppThemeStore
import chat.papo.mobile.voice.CallManager
import chat.papo.mobile.voice.VoiceEngineFactory
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import org.jetbrains.compose.resources.getString

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
    /** A compilação instalada: versão mostrada ao usuário, número da versão e se é de desenvolvimento. */
    val build: BuildInfo = BuildInfo(),
    /** Apaga o que o player web do YouTube deixou em claro; null onde a plataforma não tem um. Roda quando a sessão fecha. */
    val webPlayerData: WebPlayerData? = null,
    /** Motor do player de áudio; null onde a plataforma ainda não tem um, e então os áudios aparecem como arquivos. */
    val audio: AudioEngineFactory? = null,
    /** Mostra as notificações do sistema; null onde a plataforma ainda não tem, e então nada notifica fora do app. */
    val notifier: SystemNotifier? = null,
    /** A permissão de notificar, para a interface pedi-la; null junto com [notifier]. */
    val notificationPermission: NotificationPermission? = null,
    /** O token de push do aparelho; null onde a plataforma não tem push, e então nenhuma conta registra o aparelho. */
    val pushTokens: PushTokens? = null,
    /** O conteúdo que as notificações montadas pelo app mostram; o [notifier] lê o mesmo objeto que a tela de segurança muda. */
    val notificationPrivacy: NotificationPrivacy = NotificationPrivacy(prefs),
    /**
     * O JSON das bibliotecas de terceiros e das suas licenças (o que o plugin do AboutLibraries gerou na compilação); null
     * onde a plataforma não o tem ou não o conseguiu ler. Lê do recurso a cada chamada: bloqueia, por isso o código comum
     * só o chama por [AppGraph.readLibraryDefinitions].
     */
    val libraryDefinitions: () -> String? = { null },
    /** O gravador de mensagens de voz; null onde a plataforma não tem um, e então o clipe não oferece gravar. Vale junto com [voiceClipPlayers]. */
    val voiceRecorder: VoiceRecorder? = null,
    /** O player da revisão de uma gravação; null junto com [voiceRecorder]. */
    val voiceClipPlayers: VoiceClipPlayerFactory? = null,
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
    /** A gravação de mensagens de voz (uma por vez no app); null sem gravador na plataforma. */
    val voiceRecording: VoiceRecording? = null,
) : UnlockedSession {
    /** Em chamada o app não bloqueia sozinho nem desliga o tempo real. */
    override val busy: StateFlow<Boolean> get() = calls.active

    override suspend fun close() {
        calls.leave()
        // A gravação que ficou na memória não sobrevive à sessão (nem ao bloqueio, que a fecha).
        voiceRecording?.release()
        audio?.release()
        // Um rótulo ou cor escolhido há instantes ainda pode esperar o escritor; falhar aqui não pode deixar o registro aberto.
        try {
            accounts.flushAppearance()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // perde só a última escolha
        }
        accounts.close()
        scope.cancel()
        registry.close()
    }
}

/** Raiz de dependências do app, criada uma vez por processo. */
class AppGraph(private val platform: PlatformDeps) {
    val scope = CoroutineScope(SupervisorJob() + platform.dispatchers.main)

    val build: BuildInfo get() = platform.build

    val appVersion: String get() = platform.build.versionName

    /** O JSON das bibliotecas de terceiros, lido sob demanda fora da thread principal; o chamador não o guarda depois de usar. */
    suspend fun readLibraryDefinitions(): String? = withContext(platform.dispatchers.io) { platform.libraryDefinitions() }

    /** A permissão de notificar do sistema; null onde a plataforma não tem notificações. */
    val notificationPermission: NotificationPermission? get() = platform.notificationPermission

    /** O que as notificações do app mostram de uma mensagem; a tela de segurança muda, o notificador lê. */
    val notificationPrivacy: NotificationPrivacy get() = platform.notificationPrivacy

    /** A explicação única que antecede o pedido da permissão; null onde não há permissão a pedir. */
    val notificationPrompt: NotificationPermissionPrompt? =
        platform.notificationPermission?.let { NotificationPermissionPrompt(it, platform.prefs) }

    /** O que tocar numa notificação pede à tela: abrir uma conversa de uma conta. */
    val notificationOpens = NotificationOpenRequests()

    /** O tema global; nas preferências, para a tela de bloqueio já usá-lo. */
    val theme = AppThemeStore(platform.prefs)

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
        // A pessoa pode ter mudado a permissão nas configurações do Android enquanto o app estava fora da tela.
        platform.notificationPermission?.refresh()
        lock.onForeground()
        currentSession()?.accounts?.setForeground(true)
    }

    /** App saiu da tela: agenda o bloqueio e desliga o tempo real. */
    fun onBackground() {
        // Tocar em segundo plano exigiria um serviço de mídia, que o app não tem: o áudio pausa, mesmo com uma chamada aberta.
        currentSession()?.audio?.pause()
        // O Android não deixa capturar o microfone em segundo plano sem um serviço, que o app não tem: a gravação para e fica para a revisão.
        currentSession()?.voiceRecording?.onBackground()
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
        val notifications = platform.notifier?.let {
            SystemNotifications(
                it,
                NotificationTexts(
                    attachment = getString(Res.string.notif_system_attachment),
                    unknownUser = getString(Res.string.notif_system_unknown_user),
                    emptyMessage = getString(Res.string.notif_system_empty),
                ),
            )
        }
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
            notifications = notifications,
            pushTokens = platform.pushTokens?.takeIf { it.available },
        )
        accounts.load()
        val audio = platform.audio?.let { engines ->
            // O áudio é da conta selecionada e da sessão que o abriu: o player confere os dois a cada mudança.
            val live = combine(accounts.activeId, accounts.sessionMap) { active, sessions ->
                AudioAccounts(active, sessions.values.map { it.client })
            }
            AudioPlayback(engines, sessionScope, live)
        }
        val calls = CallManager(platform.voice, sessionScope)
        val recorder = platform.voiceRecorder
        val clipPlayers = platform.voiceClipPlayers
        val voiceRecording = if (recorder != null && clipPlayers != null) {
            VoiceRecording(
                recorder = recorder,
                players = clipPlayers,
                scope = sessionScope,
                io = platform.dispatchers.io,
                callActive = calls.active,
                pauseOtherAudio = { audio?.pause() },
                wallClockMs = { Clock.System.now().toEpochMilliseconds() },
            )
        } else {
            null
        }
        AppSession(accounts, keys, calls, driver, sessionScope, audio, voiceRecording)
    }
}
