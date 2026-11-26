package chat.papo.mobile

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import chat.papo.mobile.account.AccountManager
import chat.papo.mobile.account.AccountStore
import chat.papo.mobile.account.AddAccountResult
import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.chat.PasswordCheck
import chat.papo.mobile.core.MutationResult
import chat.papo.mobile.data.api.ApiException
import chat.papo.mobile.data.api.AuthApi
import chat.papo.mobile.data.api.MemoryCookieStore
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.core.AppDispatchers
import chat.papo.mobile.data.db.registry.RegistryDb
import chat.papo.mobile.security.AppPrefs
import chat.papo.mobile.ui.addserver.AddServerStep
import chat.papo.mobile.ui.addserver.AddServerViewModel
import chat.papo.mobile.ui.addserver.CredentialsMode
import chat.papo.mobile.ui.addserver.ResetOutcome
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
import kotlin.test.assertFailsWith
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

    // Redefinir a senha com um link

    private val newPassword = "Nova@Senha1"

    private fun link(token: String, host: String = "papo.test") = "https://$host/passwordchange/$token"

    /** Abre a etapa de redefinição já no servidor `papo.test`, com o usuário digitado. */
    private suspend fun TestScope.resetStep(backend: FakeBackend = FakeBackend()): AddServerViewModel {
        val vm = AddServerViewModel(manager(backend), null, null)
        vm.setAddress("papo.test")
        vm.findServer()
        vm.settle()
        vm.setUsername("ana")
        vm.openReset()
        assertEquals(AddServerStep.ResetPassword, vm.state.value.step)
        return vm
    }

    private fun AddServerViewModel.fillReset(text: String, password: String = newPassword, repeat: String = password) {
        setResetLink(text)
        setNewPassword(password)
        setNewPasswordRepeat(repeat)
    }

    @Test
    fun redefinirComOLinkSegueParaEntrarComOUsuarioEASenhaNova() = runTest {
        val backend = FakeBackend()
        val vm = resetStep(backend)

        vm.fillReset(link("tok-ana"))
        vm.submitReset()
        vm.settle()

        val state = vm.state.value
        assertEquals(AddServerStep.Credentials, state.step)
        assertTrue(state.passwordWasReset)
        assertEquals("ana", state.username)
        assertEquals(CredentialsMode.Login, state.mode)
        assertEquals(listOf("", "", "", ""), listOf(state.resetLink, state.newPassword, state.newPasswordRepeat, state.password))
        assertNull(state.resetOutcome)
        assertEquals(listOf("tok-ana"), backend.resets.toList())

        vm.setPassword(newPassword)
        vm.submitCredentials()
        vm.settle()
        assertIs<AddAccountResult.Added>(vm.state.value.done)
        assertFalse(vm.state.value.passwordWasReset)
    }

    @Test
    fun tokenCruTambemServe() = runTest {
        val backend = FakeBackend()
        val vm = resetStep(backend)

        vm.fillReset("  tok-ana ")
        vm.submitReset()
        vm.settle()

        assertEquals(AddServerStep.Credentials, vm.state.value.step)
        assertEquals(listOf("tok-ana"), backend.resets.toList())
    }

    @Test
    fun linkDeOutroEnderecoMostraOsDoisESoEnviaNoSegundoToque() = runTest {
        val backend = FakeBackend()
        val vm = resetStep(backend)

        vm.fillReset(link("tok-ana", host = "site.exemplo.org"))
        vm.submitReset()
        vm.settle()

        assertEquals(ResetOutcome.HostDiffers("site.exemplo.org", "papo.test"), vm.state.value.resetOutcome)
        assertEquals(emptyList(), backend.resets.toList())
        assertEquals(AddServerStep.ResetPassword, vm.state.value.step)

        vm.submitReset()
        vm.settle()

        assertEquals(AddServerStep.Credentials, vm.state.value.step)
        // O token foi ao servidor da conta, não ao endereço do link.
        assertEquals(listOf("tok-ana"), backend.resets.toList())
        assertEquals(setOf("papo.test"), backend.engine.requestHistory.map { it.url.host }.toSet())
    }

    @Test
    fun trocarOLinkDepoisDoAvisoPedeOAvisoDeNovoSeOEnderecoMuda() = runTest {
        val backend = FakeBackend()
        val vm = resetStep(backend)
        vm.fillReset(link("tok-ana", host = "site.exemplo.org"))
        vm.submitReset()

        vm.setResetLink(link("tok-ana", host = "outro.exemplo.org"))
        vm.submitReset()
        vm.settle()

        assertEquals(ResetOutcome.HostDiffers("outro.exemplo.org", "papo.test"), vm.state.value.resetOutcome)
        assertEquals(emptyList(), backend.resets.toList())
    }

    @Test
    fun linkIlegivelOuSenhaRuimNaoVaoAoServidor() = runTest {
        val backend = FakeBackend()
        val vm = resetStep(backend)

        vm.fillReset("isso não é um link")
        vm.submitReset()
        assertEquals(ResetOutcome.InvalidLink, vm.state.value.resetOutcome)

        vm.fillReset(link("tok-ana"), password = "fraca", repeat = "fraca")
        vm.submitReset()
        assertIs<ResetOutcome.Check>(vm.state.value.resetOutcome)

        vm.fillReset(link("tok-ana"), password = newPassword, repeat = "$newPassword!")
        vm.submitReset()
        assertEquals(ResetOutcome.Check(PasswordCheck.Mismatch), vm.state.value.resetOutcome)

        assertEquals(emptyList(), backend.resets.toList())
        assertEquals(AddServerStep.ResetPassword, vm.state.value.step)
    }

    @Test
    fun linkInvalidoOuUsado_410_mostraOAvisoELimpaTudoComUmSoPost() = runTest {
        val backend = FakeBackend()
        val vm = resetStep(backend)

        vm.fillReset(link("tok-gone"))
        vm.submitReset()
        vm.settle()

        val state = vm.state.value
        assertEquals(ResetOutcome.Gone, state.resetOutcome)
        assertEquals(AddServerStep.ResetPassword, state.step)
        assertEquals(listOf("", "", ""), listOf(state.resetLink, state.newPassword, state.newPasswordRepeat))
        assertEquals(listOf("tok-gone"), backend.resets.toList())
    }

    @Test
    fun limiteDeTaxaPedeEsperarMantemOLinkApagaAsSenhasEFazUmSoPost() = runTest {
        val backend = FakeBackend()
        val vm = resetStep(backend)

        vm.fillReset(link("tok-slow"))
        vm.submitReset()
        vm.settle()

        val state = vm.state.value
        assertEquals(ResetOutcome.Failed(MutationResult.RateLimited), state.resetOutcome)
        assertEquals(link("tok-slow"), state.resetLink)
        assertEquals(listOf("", ""), listOf(state.newPassword, state.newPasswordRepeat))
        assertEquals(listOf("tok-slow"), backend.resets.toList())
    }

    @Test
    fun respostaIncertaPedeEntrarComASenhaNovaEApagaOLink() = runTest {
        val backend = FakeBackend()
        val vm = resetStep(backend)

        vm.fillReset(link("tok-boom"))
        vm.submitReset()
        vm.settle()

        val state = vm.state.value
        assertEquals(ResetOutcome.Failed(MutationResult.Uncertain), state.resetOutcome)
        assertEquals("", state.resetLink)
        assertEquals(AddServerStep.ResetPassword, state.step)
        assertEquals(listOf("tok-boom"), backend.resets.toList())
    }

    @Test
    fun politicaRecusadaMostraODetalheSemOTokenNemASenha() = runTest {
        val backend = FakeBackend()
        val vm = resetStep(backend)

        vm.fillReset(link("tok-weak"))
        vm.submitReset()
        vm.settle()
        assertEquals("senha fraca", assertIs<MutationResult.Rejected>(assertIs<ResetOutcome.Failed>(vm.state.value.resetOutcome).result).detail)
        assertEquals(link("tok-weak"), vm.state.value.resetLink) // o link ainda vale: só a senha foi recusada

        vm.fillReset(link("tok-echo"))
        vm.submitReset()
        vm.settle()
        val echoed = assertIs<MutationResult.Rejected>(assertIs<ResetOutcome.Failed>(vm.state.value.resetOutcome).result).detail.orEmpty()
        assertEquals("o token … e a senha … não servem", echoed)
    }

    @Test
    fun doisToquesSeguidosMandamUmSoPedido() = runTest {
        val backend = FakeBackend()
        val vm = resetStep(backend)

        vm.fillReset(link("tok-ana"))
        vm.submitReset()
        vm.submitReset()
        vm.settle()

        assertEquals(listOf("tok-ana"), backend.resets.toList())
    }

    @Test
    fun voltarDaRedefinicaoLimpaOsCamposEVoltaAEntrarSemPerderOServidor() = runTest {
        val vm = resetStep()
        vm.fillReset(link("tok-ana"))

        assertTrue(vm.back())

        val state = vm.state.value
        assertEquals(AddServerStep.Credentials, state.step)
        assertEquals(listOf("", "", ""), listOf(state.resetLink, state.newPassword, state.newPasswordRepeat))
        assertEquals(ServerUrl("https://papo.test"), state.probe?.url)

        assertTrue(vm.back())
        assertEquals(AddServerStep.Address, vm.state.value.step)
    }

    @Test
    fun redefinirSoSeAbrePelaEtapaDeEntrar() = runTest {
        val vm = AddServerViewModel(manager(FakeBackend()), null, null)
        vm.openReset()
        assertEquals(AddServerStep.Address, vm.state.value.step)

        vm.submitReset() // fora da etapa: sem efeito, sem erro
        assertNull(vm.state.value.resetOutcome)
    }

    @Test
    fun oTokenNaoApareceNaMensagemDoErroDaApi() = runTest {
        val backend = FakeBackend()
        val client = PapoClient(backend.engine, ServerUrl("https://papo.test"), MemoryCookieStore())
        val error = assertFailsWith<ApiException> { AuthApi(client).consumePasswordReset("tok-gone", newPassword) }

        assertEquals(410, error.status)
        assertFalse("tok-gone" in error.message.orEmpty())
        assertFalse(newPassword in error.message.orEmpty())
        client.close()
    }
}
