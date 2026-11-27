package chat.papo.mobile.security

import chat.papo.mobile.core.crypto.Crypto
import chat.papo.mobile.core.crypto.KeyRing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Tudo o que só existe com o app desbloqueado (bancos, sessões, conexões). */
interface UnlockedSession {
    /** Indica atividade que não pode ser interrompida pelo bloqueio automático (chamada em curso). */
    val busy: StateFlow<Boolean>

    suspend fun close()
}

sealed interface LockState {
    data object Starting : LockState

    data class Locked(val authenticating: Boolean = false, val error: String? = null) : LockState

    class Unlocked(val session: UnlockedSession) : LockState
}

enum class LockTimeout(val id: String, val duration: Duration) {
    Immediately("immediately", Duration.ZERO),
    OneMinute("1m", 1.minutes),
    FiveMinutes("5m", 5.minutes),
    FifteenMinutes("15m", 15.minutes),
    OneHour("1h", 1.hours);

    companion object {
        fun fromId(id: String?): LockTimeout = entries.firstOrNull { it.id == id } ?: Immediately
    }
}

/**
 * Se a janela do app esconde o conteúdo (capturas, gravação de tela e lista de apps recentes). [pref] é a escolha da
 * pessoa, ou nulo se ela nunca escolheu: então vale o padrão de sempre, esconder com o bloqueio ligado e liberar sem ele.
 * Com escolha, vale a escolha, com ou sem o bloqueio.
 */
fun secureWindow(lockEnabled: Boolean, pref: Boolean?): Boolean = pref ?: lockEnabled

enum class LockChange { Done, Cancelled, Unavailable, Failed }

/**
 * Controla o ciclo bloqueado/desbloqueado do app. Desbloquear é recuperar a
 * chave mestra do [MasterKeyStore]; bloquear fecha a sessão e zera a chave.
 *
 * [onSessionClosed] roda sempre que uma sessão fecha (o app trava ou os dados são apagados), antes de ela fechar: é onde
 * se apaga o que ficou fora da sessão e fora da cifra, como os dados do WebView do player do YouTube ([WebPlayerData]).
 * Uma falha dele não impede de bloquear.
 */
class AppLock(
    private val keyStore: MasterKeyStore,
    private val crypto: Crypto,
    private val prefs: AppPrefs,
    private val wiper: LocalDataWiper,
    private val scope: CoroutineScope,
    private val openSession: suspend (KeyRing) -> UnlockedSession,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val onSessionClosed: () -> Unit = {},
) {
    private val mutex = Mutex()
    private var keyRing: KeyRing? = null
    private var masterKey: ByteArray? = null
    private var backgroundedAt: TimeMark? = null
    private var backgroundJob: Job? = null

    private val _state = MutableStateFlow<LockState>(LockState.Starting)
    val state: StateFlow<LockState> = _state.asStateFlow()

    private val _lockEnabled = MutableStateFlow(keyStore.protection() == KeyProtection.UserAuth)
    val lockEnabled: StateFlow<Boolean> = _lockEnabled.asStateFlow()

    private val _timeout = MutableStateFlow(LockTimeout.fromId(prefs.getString(PREF_TIMEOUT)))
    val timeout: StateFlow<LockTimeout> = _timeout.asStateFlow()

    // Só "true" e "false" são escolhas; sem valor (ou com lixo) a pessoa não escolheu e vale o padrão de [secureWindow].
    private val _secureScreen = MutableStateFlow(prefs.getString(PREF_SECURE_SCREEN)?.toBooleanStrictOrNull())
    /** A escolha de esconder o conteúdo em capturas e na lista de apps recentes; nula enquanto não houve escolha. */
    val secureScreen: StateFlow<Boolean?> = _secureScreen.asStateFlow()

    private val _dataWasWiped = MutableStateFlow(false)
    /** Os dados locais foram apagados porque a chave do aparelho deixou de valer. */
    val dataWasWiped: StateFlow<Boolean> = _dataWasWiped.asStateFlow()

    fun userAuthAvailability(): UserAuthAvailability = keyStore.userAuthAvailability()

    /** Chamado uma vez na abertura do app. */
    suspend fun start() = mutex.withLock {
        if (_state.value != LockState.Starting) return@withLock
        when (keyStore.protection()) {
            KeyProtection.None -> createFresh()
            KeyProtection.Device -> handle(keyStore.unlock(SILENT_PROMPT))
            KeyProtection.UserAuth -> _state.value = LockState.Locked()
        }
    }

    suspend fun unlock(prompt: AuthPrompt) = mutex.withLock {
        val current = _state.value
        if (current !is LockState.Locked || current.authenticating) return@withLock
        _state.value = LockState.Locked(authenticating = true)
        handle(keyStore.unlock(prompt))
    }

    suspend fun lockNow() = mutex.withLock { lockLocked() }

    fun onBackground() {
        if (!_lockEnabled.value) return
        backgroundedAt = timeSource.markNow()
        backgroundJob?.cancel()
        backgroundJob = scope.launch {
            delay(_timeout.value.duration)
            lockIfIdle()
        }
    }

    fun onForeground() {
        backgroundJob?.cancel()
        backgroundJob = null
        val since = backgroundedAt ?: return
        backgroundedAt = null
        if (_lockEnabled.value && since.elapsedNow() >= _timeout.value.duration) {
            scope.launch { lockIfIdle() }
        }
    }

    suspend fun setLockEnabled(enabled: Boolean, prompt: AuthPrompt): LockChange = mutex.withLock {
        val master = masterKey ?: return@withLock LockChange.Failed
        if (enabled == _lockEnabled.value) return@withLock LockChange.Done
        if (enabled && keyStore.userAuthAvailability() != UserAuthAvailability.Available) {
            return@withLock LockChange.Unavailable
        }
        when (keyStore.protect(master.copyOf(), userAuth = enabled, prompt = prompt)) {
            is KeyResult.Success -> {
                _lockEnabled.value = enabled
                LockChange.Done
            }
            KeyResult.Cancelled -> LockChange.Cancelled
            else -> LockChange.Failed
        }
    }

    fun setTimeout(value: LockTimeout) {
        prefs.putString(PREF_TIMEOUT, value.id)
        _timeout.value = value
    }

    fun setSecureScreen(enabled: Boolean) {
        prefs.putString(PREF_SECURE_SCREEN, enabled.toString())
        _secureScreen.value = enabled
    }

    /** Apaga a chave e todos os dados locais e recomeça do zero. Nada muda nos servidores. */
    suspend fun resetLocalData() = mutex.withLock {
        (_state.value as? LockState.Unlocked)?.let { closeSession(it.session) }
        _state.value = LockState.Starting
        keyStore.wipe()
        wiper.wipeAll()
        _lockEnabled.value = false
        createFresh()
    }

    fun dismissWipedNotice() {
        _dataWasWiped.value = false
    }

    private suspend fun lockIfIdle() = mutex.withLock {
        val current = _state.value
        if (current is LockState.Unlocked && current.session.busy.value) return@withLock
        lockLocked()
    }

    private suspend fun lockLocked() {
        val current = _state.value
        if (current !is LockState.Unlocked || !_lockEnabled.value) return
        _state.value = LockState.Locked()
        closeSession(current.session)
    }

    private suspend fun closeSession(session: UnlockedSession) {
        try {
            onSessionClosed()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Limpar o que ficou fora da sessão não pode impedir o bloqueio: a sessão e as chaves fecham do mesmo jeito.
        }
        runCatching { session.close() }
        keyRing?.destroy()
        keyRing = null
        masterKey?.fill(0)
        masterKey = null
    }

    private suspend fun createFresh() {
        handle(keyStore.create())
    }

    private suspend fun handle(result: KeyResult) {
        when (result) {
            is KeyResult.Success -> {
                val ring = KeyRing(result.masterKey.copyOf(), crypto)
                val session = try {
                    openSession(ring)
                } catch (e: Exception) {
                    ring.destroy()
                    result.masterKey.fill(0)
                    _state.value = LockState.Locked(error = e.message ?: "Falha ao abrir os dados locais")
                    return
                }
                masterKey = result.masterKey
                keyRing = ring
                _lockEnabled.value = keyStore.protection() == KeyProtection.UserAuth
                _state.value = LockState.Unlocked(session)
            }
            KeyResult.Cancelled -> _state.value = LockState.Locked()
            is KeyResult.Failed -> _state.value = LockState.Locked(error = result.message)
            KeyResult.Invalidated -> {
                // Sem a chave do hardware nada do que está em disco pode ser lido.
                keyStore.wipe()
                wiper.wipeAll()
                _lockEnabled.value = false
                _dataWasWiped.value = true
                createFresh()
            }
        }
    }

    private companion object {
        const val PREF_TIMEOUT = "lock.timeout"
        const val PREF_SECURE_SCREEN = "lock.secure_screen"
        val SILENT_PROMPT = AuthPrompt("", "")
    }
}
