package chat.papo.mobile

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import chat.papo.mobile.account.AccountManager
import chat.papo.mobile.account.AccountStore
import chat.papo.mobile.account.AddAccountResult
import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.core.AppDispatchers
import chat.papo.mobile.data.db.registry.RegistryDb
import chat.papo.mobile.security.AppPrefs
import chat.papo.mobile.ui.addserver.AddServerStep
import chat.papo.mobile.ui.addserver.AddServerViewModel
import chat.papo.mobile.ui.addserver.CredentialsMode
import chat.papo.mobile.ui.components.UiError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AddServerViewModelTest {
    private class Prefs : AppPrefs {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String) = map[key]
        override fun putString(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    private fun TestScope.manager(backend: FakeBackend): AccountManager {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { RegistryDb.Schema.create(it) }
        return AccountManager(
            store = AccountStore(RegistryDb(driver), dispatcher),
            engine = backend.engine,
            prefs = Prefs(),
            dispatchers = AppDispatchers(dispatcher, dispatcher, dispatcher),
            scope = backgroundScope,
            openResources = { TestResources.inMemory(dispatcher) },
            deleteLocalData = {},
        ).also { it.setForeground(false) }
    }

    /** O motor HTTP falso responde em outra thread: espera a ação em curso terminar de verdade. */
    private suspend fun AddServerViewModel.settle() {
        state.first { !it.busy }
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun servidorPublicoEmHttpsVaiDiretoParaOLogin() = runTest {
        val vm = AddServerViewModel(manager(FakeBackend()), null, null)
        vm.setAddress("  Papo.Test/ ")
        vm.findServer()
        vm.settle()

        val state = vm.state.value
        assertEquals(AddServerStep.Credentials, state.step)
        assertEquals("papo.test", state.address) // endereço normalizado volta para o campo
        assertEquals(ServerUrl("https://papo.test"), state.probe?.url)
        assertFalse(state.busy)
        assertNull(state.error)
    }

    @Test
    fun httpPedeConfirmacaoAntesDeSeguir() = runTest {
        val vm = AddServerViewModel(manager(FakeBackend()), null, null)
        vm.setAddress("http://192.168.0.10:8080")
        vm.findServer()
        vm.settle()
        assertEquals(AddServerStep.InsecureWarning, vm.state.value.step)

        vm.acceptInsecure()
        assertEquals(AddServerStep.Credentials, vm.state.value.step)
    }

    @Test
    fun servidorFechadoPassaPelaSenhaDoServidor() = runTest {
        val vm = AddServerViewModel(manager(FakeBackend(serverPassword = "portaria")), null, null)
        vm.setAddress("papo.test")
        vm.findServer()
        vm.settle()
        assertEquals(AddServerStep.ServerPassword, vm.state.value.step)

        vm.setServerPassword("errada")
        vm.submitServerPassword()
        vm.settle()
        assertEquals(AddServerStep.ServerPassword, vm.state.value.step)
        assertEquals(UiError.Server("Senha do servidor incorreta"), vm.state.value.error)

        vm.setServerPassword("portaria")
        assertNull(vm.state.value.error) // digitar limpa o erro
        vm.submitServerPassword()
        vm.settle()
        assertEquals(AddServerStep.Credentials, vm.state.value.step)
        assertEquals("", vm.state.value.serverPassword) // a senha não fica guardada no estado

        vm.setUsername("ana")
        vm.setPassword("Senha@123")
        vm.submitCredentials()
        vm.settle()
        assertIs<AddAccountResult.Added>(vm.state.value.done)
        assertEquals("", vm.state.value.password)
    }

    @Test
    fun credenciaisErradasMostramOMotivoDoServidorEDeixamTentarDeNovo() = runTest {
        val vm = AddServerViewModel(manager(FakeBackend()), null, null)
        vm.setAddress("papo.test")
        vm.findServer()
        vm.settle()

        vm.setUsername("ana")
        vm.setPassword("errada")
        vm.submitCredentials()
        vm.settle()
        assertEquals(UiError.Server("Credenciais inválidas"), vm.state.value.error)
        assertNull(vm.state.value.done)
        assertFalse(vm.state.value.busy)

        vm.setPassword("Senha@123")
        vm.submitCredentials()
        vm.settle()
        assertEquals("ana", vm.state.value.done?.account?.username)
    }

    @Test
    fun criarContaRegistraEJaEntra() = runTest {
        val backend = FakeBackend()
        val manager = manager(backend)
        val vm = AddServerViewModel(manager, null, null)
        vm.setAddress("papo.test")
        vm.findServer()
        vm.settle()

        vm.setMode(CredentialsMode.Register)
        vm.setUsername("carla")
        vm.setPassword("Senha@789")
        vm.submitCredentials()
        vm.settle()
        // O registro não devolve sessão: a conta só existe porque o login veio logo depois.
        assertEquals("carla", assertIs<AddAccountResult.Added>(vm.state.value.done).account.username)
        assertEquals(listOf("/server", "/auth/register", "/auth/login", "/auth/whoami"), backend.engine.requestHistory.map { it.url.encodedPath })
    }

    @Test
    fun nomeDeUsuarioJaExistenteNaoViraConta() = runTest {
        val manager = manager(FakeBackend())
        val vm = AddServerViewModel(manager, null, null)
        vm.setAddress("papo.test")
        vm.findServer()
        vm.settle()

        vm.setMode(CredentialsMode.Register)
        vm.setUsername("ana")
        vm.setPassword("Outra@123")
        vm.submitCredentials()
        vm.settle()
        assertEquals(UiError.Server("Username já existe"), vm.state.value.error)
        assertNull(vm.state.value.done)
        assertTrue(manager.accounts.value.isEmpty())
    }

    @Test
    fun enderecoInvalidoFicaNoPrimeiroPassoComOErroCerto() = runTest {
        val backend = FakeBackend()
        val vm = AddServerViewModel(manager(backend), null, null)
        vm.setAddress("ftp://papo.test")
        vm.findServer()
        vm.settle()
        assertEquals(AddServerStep.Address, vm.state.value.step)
        assertEquals(UiError.Url(ServerUrl.Problem.Scheme), vm.state.value.error)
        assertEquals(0, backend.engine.requestHistory.size)
    }

    @Test
    fun voltarRetornaAoEnderecoEDepoisSaiDaTela() = runTest {
        val vm = AddServerViewModel(manager(FakeBackend()), null, null)
        vm.setAddress("papo.test")
        vm.findServer()
        vm.settle()
        vm.setPassword("algo")

        assertTrue(vm.back())
        assertEquals(AddServerStep.Address, vm.state.value.step)
        assertNull(vm.state.value.probe)
        assertEquals("", vm.state.value.password)
        assertFalse(vm.back()) // no primeiro passo quem decide é a tela
    }

    @Test
    fun reentrarEmContaExistenteJaComecaProcurandoOServidor() = runTest {
        val vm = AddServerViewModel(manager(FakeBackend()), "https://papo.test", "ana")
        vm.settle()
        assertEquals(AddServerStep.Credentials, vm.state.value.step)
        assertEquals("ana", vm.state.value.username)
    }
}
