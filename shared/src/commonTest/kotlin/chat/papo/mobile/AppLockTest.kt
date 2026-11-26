package chat.papo.mobile

import chat.papo.mobile.core.crypto.Crypto
import chat.papo.mobile.core.crypto.KeyRing
import chat.papo.mobile.security.AppLock
import chat.papo.mobile.security.AppPrefs
import chat.papo.mobile.security.AuthPrompt
import chat.papo.mobile.security.KeyProtection
import chat.papo.mobile.security.KeyResult
import chat.papo.mobile.security.LockChange
import chat.papo.mobile.security.LockState
import chat.papo.mobile.security.LockTimeout
import chat.papo.mobile.security.MasterKeyStore
import chat.papo.mobile.security.UnlockedSession
import chat.papo.mobile.security.UserAuthAvailability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class AppLockTest {
    private class FakeKeyStore : MasterKeyStore {
        var protection = KeyProtection.None
        var availability = UserAuthAvailability.Available
        var nextUnlock: KeyResult? = null
        var wiped = 0
        var prompts = 0
        private var master: ByteArray? = null

        override fun protection() = protection
        override fun userAuthAvailability() = availability

        override suspend fun create(): KeyResult {
            master = ByteArray(Crypto.KEY_SIZE) { 7 }
            protection = KeyProtection.Device
            return KeyResult.Success(master!!.copyOf())
        }

        override suspend fun unlock(prompt: AuthPrompt): KeyResult {
            if (protection == KeyProtection.UserAuth) prompts++
            return nextUnlock?.also { nextUnlock = null } ?: KeyResult.Success(master!!.copyOf())
        }

        override suspend fun protect(masterKey: ByteArray, userAuth: Boolean, prompt: AuthPrompt): KeyResult {
            protection = if (userAuth) KeyProtection.UserAuth else KeyProtection.Device
            master = masterKey.copyOf()
            return KeyResult.Success(masterKey)
        }

        override fun wipe() {
            wiped++
            master = null
            protection = KeyProtection.None
        }
    }

    private class FakePrefs : AppPrefs {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String) = map[key]
        override fun putString(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    private object NoCrypto : Crypto {
        override fun randomBytes(size: Int) = ByteArray(size)
        override fun hmacSha256(key: ByteArray, data: ByteArray) = ByteArray(32)
        override fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray) = plaintext
        override fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray) = sealed
    }

    private class FakeSession : UnlockedSession {
        override val busy = MutableStateFlow(false)
        var closed = false
        override suspend fun close() {
            closed = true
        }
    }

    private class Fixture(scope: TestScope) {
        val keyStore = FakeKeyStore()
        val prefs = FakePrefs()
        val sessions = mutableListOf<FakeSession>()
        var wipes = 0
        var closes = 0
        var onClose: () -> Unit = {}
        val lock = AppLock(
            keyStore = keyStore,
            crypto = NoCrypto,
            prefs = prefs,
            wiper = { wipes++ },
            scope = scope.backgroundScope,
            openSession = { _: KeyRing -> FakeSession().also { sessions += it } },
            timeSource = scope.testScheduler.timeSource,
            onSessionClosed = { closes++; onClose() },
        )
        val prompt = AuthPrompt("t", "s")
    }

    @Test
    fun primeiraExecucaoCriaChaveEAbreSemPedirNada() = runTest {
        val f = Fixture(this)
        f.lock.start()
        assertIs<LockState.Unlocked>(f.lock.state.value)
        assertEquals(KeyProtection.Device, f.keyStore.protection)
        assertEquals(0, f.keyStore.prompts)
        assertEquals(false, f.lock.lockEnabled.value)
    }

    @Test
    fun comBloqueioLigadoComecaBloqueadoESoAbreAoAutenticar() = runTest {
        val f = Fixture(this)
        f.lock.start()
        assertEquals(LockChange.Done, f.lock.setLockEnabled(true, f.prompt))

        // Novo processo com a mesma chave guardada.
        val restarted = AppLock(f.keyStore, NoCrypto, f.prefs, { }, backgroundScope, { FakeSession() }, testScheduler.timeSource)
        restarted.start()
        assertIs<LockState.Locked>(restarted.state.value)

        f.keyStore.nextUnlock = KeyResult.Cancelled
        restarted.unlock(f.prompt)
        assertIs<LockState.Locked>(restarted.state.value)

        restarted.unlock(f.prompt)
        assertIs<LockState.Unlocked>(restarted.state.value)
        assertEquals(2, f.keyStore.prompts)
    }

    @Test
    fun bloqueiaAoVoltarDoSegundoPlanoDepoisDoTempo() = runTest {
        val f = Fixture(this)
        f.lock.start()
        f.lock.setLockEnabled(true, f.prompt)
        f.lock.setTimeout(LockTimeout.OneMinute)

        f.lock.onBackground()
        advanceTimeBy(30.seconds)
        f.lock.onForeground()
        runCurrent()
        assertIs<LockState.Unlocked>(f.lock.state.value)

        f.lock.onBackground()
        advanceTimeBy(61.seconds)
        runCurrent()
        assertIs<LockState.Locked>(f.lock.state.value)
        assertTrue(f.sessions.single().closed)
    }

    @Test
    fun bloqueioImediatoFechaASessaoAoSairDaTela() = runTest {
        val f = Fixture(this)
        f.lock.start()
        f.lock.setLockEnabled(true, f.prompt)
        f.lock.onBackground()
        runCurrent()
        assertIs<LockState.Locked>(f.lock.state.value)
        assertTrue(f.sessions.single().closed)
    }

    @Test
    fun semBloqueioLigadoNaoBloqueiaEmSegundoPlano() = runTest {
        val f = Fixture(this)
        f.lock.start()
        f.lock.onBackground()
        advanceTimeBy(600.seconds)
        f.lock.onForeground()
        runCurrent()
        assertIs<LockState.Unlocked>(f.lock.state.value)
    }

    @Test
    fun chamadaEmCursoAdiaOBloqueioAutomatico() = runTest {
        val f = Fixture(this)
        f.lock.start()
        f.lock.setLockEnabled(true, f.prompt)
        f.sessions.single().busy.value = true
        f.lock.onBackground()
        runCurrent()
        assertIs<LockState.Unlocked>(f.lock.state.value)
    }

    @Test
    fun naoLigaBloqueioSemBiometriaCadastrada() = runTest {
        val f = Fixture(this)
        f.lock.start()
        f.keyStore.availability = UserAuthAvailability.NotEnrolled
        assertEquals(LockChange.Unavailable, f.lock.setLockEnabled(true, f.prompt))
        assertEquals(false, f.lock.lockEnabled.value)
    }

    @Test
    fun chaveInvalidadaApagaOsDadosLocaisERecomeca() = runTest {
        val f = Fixture(this)
        f.lock.start()
        f.lock.setLockEnabled(true, f.prompt)
        f.lock.lockNow()
        assertIs<LockState.Locked>(f.lock.state.value)

        f.keyStore.nextUnlock = KeyResult.Invalidated
        f.lock.unlock(f.prompt)

        assertIs<LockState.Unlocked>(f.lock.state.value)
        assertEquals(1, f.wipes)
        assertEquals(1, f.keyStore.wiped)
        assertEquals(true, f.lock.dataWasWiped.value)
        assertEquals(false, f.lock.lockEnabled.value)
    }

    @Test
    fun travarApagaOQueFicouForaDaSessaoAntesDeFecharla() = runTest {
        val f = Fixture(this)
        f.lock.start()
        f.lock.setLockEnabled(true, f.prompt)
        assertEquals(0, f.closes)
        var sessionClosedAtHook = true
        f.onClose = { sessionClosedAtHook = f.sessions.single().closed }

        f.lock.lockNow()

        assertEquals(1, f.closes)
        assertEquals(false, sessionClosedAtHook)
        assertTrue(f.sessions.single().closed)
    }

    @Test
    fun abrirEDesbloquearNaoApagamNada() = runTest {
        val f = Fixture(this)
        f.lock.start()
        f.lock.setLockEnabled(true, f.prompt)
        f.lock.lockNow()
        f.lock.unlock(f.prompt)

        assertIs<LockState.Unlocked>(f.lock.state.value)
        assertEquals(1, f.closes)
    }

    @Test
    fun semBloqueioLigadoOHookNaoRoda() = runTest {
        val f = Fixture(this)
        f.lock.start()

        f.lock.lockNow()

        assertEquals(0, f.closes)
        assertIs<LockState.Unlocked>(f.lock.state.value)
    }

    @Test
    fun apagarOsDadosLocaisTambemApagaOQueFicouForaDaSessao() = runTest {
        val f = Fixture(this)
        f.lock.start()

        f.lock.resetLocalData()

        assertEquals(1, f.closes)
        assertEquals(1, f.wipes)
    }

    @Test
    fun umHookQueFalhaNaoImpedeOBloqueio() = runTest {
        val f = Fixture(this)
        f.lock.start()
        f.lock.setLockEnabled(true, f.prompt)
        f.onClose = { error("o WebView já não existia") }

        f.lock.lockNow()

        assertIs<LockState.Locked>(f.lock.state.value)
        assertTrue(f.sessions.single().closed)
    }

    @Test
    fun umHookQueSinalizaCancelamentoNaoEEngolido() = runTest {
        val f = Fixture(this)
        f.lock.start()
        f.lock.setLockEnabled(true, f.prompt)
        f.onClose = { throw CancellationException("cancelado") }

        assertFailsWith<CancellationException> { f.lock.lockNow() }
    }
}
