package chat.papo.mobile

import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.ChannelEditResult
import chat.papo.mobile.core.MutationResult
import chat.papo.mobile.data.ws.WsEvent
import chat.papo.mobile.ui.channel.ChannelSettingsViewModel
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** A tela de edição do canal sobre o motor HTTP falso: o que ela mostra, o que deixa salvar e o que acontece com cada resposta. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelSettingsViewModelTest {
    private val put = "PUT /channels/c1"

    private fun TestScope.session(server: ChannelEditServer): AccountSession {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return TestResources.session(server.engine, this, userId = "me")
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(session: AccountSession, channelId: String = "c1") =
        ChannelSettingsViewModel(session.chat, session.userId, channelId)

    @Test
    fun osCamposComecamComOQueEstaGuardadoEODonoPodeEditar() = runTest {
        val session = session(ChannelEditServer())
        session.chat.sync()
        runCurrent()

        val state = viewModel(session).state.value

        assertEquals("papo", state.name)
        assertEquals("Conversa fiada", state.topic)
        assertTrue(state.canManage)
        assertTrue(state.hasTopic)
        assertFalse(state.canSave) // nada mudou
    }

    @Test
    fun canalAindaNaoLidoEsperaEDepoisPreencheSemApagarODigitado() = runTest {
        val session = session(ChannelEditServer())
        val vm = viewModel(session)
        runCurrent()
        assertNull(vm.state.value.channel)
        assertFalse(vm.state.value.canSave)

        session.chat.sync()
        runCurrent()
        assertEquals("papo", vm.state.value.name)

        vm.setName("digitado")
        session.chat.handle(WsEvent.ChannelUpdated("c1", "de outro", 2, "Conversa fiada", "cat"))
        runCurrent()
        assertEquals("digitado", vm.state.value.name)
        assertEquals("de outro", vm.state.value.channel?.name)
    }

    @Test
    fun salvarMandaSoOQueMudouEFechaATela() = runTest {
        val server = ChannelEditServer()
        val session = session(server)
        session.chat.sync()
        runCurrent()
        val vm = viewModel(session)

        vm.setName("  novo  ")
        vm.save()
        TestResources.waitUntil { vm.state.value.saved }

        assertEquals("""{"name":"novo"}""", server.bodies.single())
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.failure)
        assertEquals("novo", session.chat.channels.value.single { it.id == "c1" }.name)
    }

    @Test
    fun apagarADescricaoMandaStringVazia() = runTest {
        val server = ChannelEditServer()
        val session = session(server)
        session.chat.sync()
        runCurrent()
        val vm = viewModel(session)

        vm.setTopic("   ")
        vm.save()
        TestResources.waitUntil { vm.state.value.saved }

        assertEquals("""{"name":"papo","topic":""}""", server.bodies.single())
    }

    @Test
    fun categoriaNaoMostraDescricaoENaoAMandaMesmoComTexto() = runTest {
        val server = ChannelEditServer()
        val session = session(server)
        session.chat.sync()
        runCurrent()
        val vm = viewModel(session, "cat")

        vm.setName("Outras")
        vm.setTopic("não vai")
        vm.save()
        TestResources.waitUntil { vm.state.value.saved }

        assertFalse(vm.state.value.hasTopic)
        assertEquals("""{"name":"Outras"}""", server.bodies.single())
    }

    @Test
    fun semMudancaOuComCampoInvalidoNadaVaiAoServidor() = runTest {
        val server = ChannelEditServer()
        val session = session(server)
        session.chat.sync()
        runCurrent()
        val vm = viewModel(session)

        vm.save() // nada mudou
        vm.setName("   ")
        vm.save()
        vm.setName("a".repeat(33))
        vm.save()
        vm.setName("papo")
        vm.setTopic("a".repeat(513))
        vm.save()
        advanceTimeBy(2.seconds)
        runCurrent()

        assertEquals(0, server.count(put))
    }

    @Test
    fun semPermissaoNadaVaiAoServidorENaoHaComoSalvar() = runTest {
        val server = ChannelEditServer().also { it.ownerId = "boss" }
        val session = session(server)
        session.chat.sync()
        runCurrent()
        val vm = viewModel(session)

        vm.setName("novo")
        vm.save()
        advanceTimeBy(2.seconds)
        runCurrent()

        assertFalse(vm.state.value.canManage)
        assertFalse(vm.state.value.editable)
        assertFalse(vm.state.value.canSave)
        assertEquals(0, server.count(put))
    }

    @Test
    fun antesDeSaberOQueAContaPodeNaoHaOQueSalvar() = runTest {
        val server = ChannelEditServer()
        val session = session(server)
        // O canal está no banco, mas o dono do servidor ainda não foi lido: o desconhecido esconde, não oferece.
        session.chat.handle(WsEvent.ChannelCreated("c1", "papo", "text", 2, null, null))
        runCurrent()
        val vm = viewModel(session)
        vm.setName("novo")
        runCurrent()

        assertFalse(vm.state.value.canManage)
        assertFalse(vm.state.value.canSave)
    }

    @Test
    fun nomeJaExistenteMostraAFalhaELiberaOFormulario() = runTest {
        val server = ChannelEditServer().also { it.putStatus = HttpStatusCode.Conflict }
        val session = session(server)
        session.chat.sync()
        runCurrent()
        val vm = viewModel(session)

        vm.setName("geral")
        vm.save()
        TestResources.waitUntil { vm.state.value.failure != null }

        val state = vm.state.value
        assertEquals(ChannelEditResult.NameTaken, state.failure)
        assertFalse(state.busy)
        assertFalse(state.saved)
        assertEquals("geral", state.name)
        assertTrue(state.canSave) // dá para trocar o nome e tentar de novo

        vm.setName("outro")
        runCurrent()
        assertNull(vm.state.value.failure)
    }

    @Test
    fun perderAPermissaoComATelaAbertaRecusaOMostraOAvisoEBloqueiaOSalvar() = runTest {
        val server = ChannelEditServer().also { it.putStatus = HttpStatusCode.Forbidden }
        val session = session(server)
        session.chat.sync()
        runCurrent()
        val vm = viewModel(session)
        assertTrue(vm.state.value.canManage)

        server.ownerId = "boss"
        vm.setName("novo")
        vm.save()
        TestResources.waitUntil { !vm.state.value.canManage }
        runCurrent()

        val state = vm.state.value
        assertEquals(ChannelEditResult.Forbidden, state.failure)
        assertFalse(state.canSave)
        vm.save()
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(1, server.count(put)) // nada foi reenviado
    }

    @Test
    fun canalQueNaoExisteMaisAvisaEBloqueiaSemFecharSozinho() = runTest {
        val server = ChannelEditServer().also { it.putStatus = HttpStatusCode.NotFound }
        val session = session(server)
        session.chat.sync()
        runCurrent()
        val vm = viewModel(session)

        vm.setName("novo")
        vm.save()
        TestResources.waitUntil { vm.state.value.failure != null }
        runCurrent()

        val state = vm.state.value
        assertEquals(ChannelEditResult.NotFound, state.failure)
        assertTrue(state.gone)
        assertFalse(state.saved)
        assertFalse(state.canSave)
    }

    @Test
    fun canalApagadoPorOutroComATelaAbertaMarcaQueAcabou() = runTest {
        val server = ChannelEditServer()
        val session = session(server)
        session.chat.sync()
        runCurrent()
        val vm = viewModel(session)

        session.chat.handle(WsEvent.ChannelDeleted("c1"))
        runCurrent()

        assertTrue(vm.state.value.gone)
        assertFalse(vm.state.value.canSave)
    }

    @Test
    fun limiteDeRequisicoesAvisaEVoltaAPoderSalvarSemRepetirSozinho() = runTest {
        val server = ChannelEditServer().also { it.putStatus = HttpStatusCode.TooManyRequests }
        val session = session(server)
        session.chat.sync()
        runCurrent()
        val vm = viewModel(session)

        vm.setName("novo")
        vm.save()
        TestResources.waitUntil { vm.state.value.failure != null }
        advanceTimeBy(10.seconds)
        runCurrent()

        assertEquals(ChannelEditResult.Failed(MutationResult.RateLimited), vm.state.value.failure)
        assertTrue(vm.state.value.canSave)
        assertEquals(1, server.count(put))
    }

    @Test
    fun umSegundoToqueEmSalvarEOCampoDuranteOPedidoSaoIgnorados() = runTest {
        val gate = CompletableDeferred<Unit>()
        val server = ChannelEditServer().also { it.gate = gate }
        val session = session(server)
        session.chat.sync()
        runCurrent()
        val vm = viewModel(session)

        vm.setName("novo")
        vm.save()
        runCurrent()
        assertTrue(vm.state.value.busy)
        vm.save()
        vm.setName("mudou no meio")
        TestResources.waitUntil { server.count(put) == 1 }
        assertEquals("novo", vm.state.value.name)
        assertFalse(vm.state.value.editable)

        gate.complete(Unit)
        TestResources.waitUntil { vm.state.value.saved }

        assertEquals(1, server.count(put))
    }
}
