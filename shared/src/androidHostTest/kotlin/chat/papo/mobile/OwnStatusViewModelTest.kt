package chat.papo.mobile

import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.Presence
import chat.papo.mobile.chat.UserProfile
import chat.papo.mobile.core.MutationResult
import chat.papo.mobile.data.ws.WsEvent
import chat.papo.mobile.ui.profile.OwnStatus
import chat.papo.mobile.ui.profile.OwnStatusViewModel
import chat.papo.mobile.ui.profile.ownStatusOf
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.isSuccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A escolha de Disponível, Ausente ou Ocupado: de onde vem o estado atual, o "salvando" e a volta à escolha anterior se falha. */
@OptIn(ExperimentalCoroutinesApi::class)
class OwnStatusViewModelTest {
    private class Server {
        val requests = CopyOnWriteArrayList<String>()
        val bodies = CopyOnWriteArrayList<String>()
        @Volatile var statusResponse = HttpStatusCode.OK
        @Volatile var stored: String? = null
        @Volatile var profileResponse = HttpStatusCode.OK
        @Volatile var gate: CompletableDeferred<Unit>? = null

        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            requests += "${request.method.value} $path"
            fun json(text: String, status: HttpStatusCode = HttpStatusCode.OK) =
                respond(text, status, headersOf(HttpHeaders.ContentType, if (status.isSuccess()) "application/json" else "application/problem+json"))
            fun problem(status: HttpStatusCode) = json("""{"type":"t","title":"Erro","status":${status.value},"detail":"falhou ${status.value}"}""", status)
            when {
                request.method == HttpMethod.Get && path == "/users/u1/profile" ->
                    if (profileResponse.isSuccess()) json("""{"id":"u1","username":"ana","status":${stored?.let { "\"$it\"" }},"roles":[]}""") else problem(profileResponse)
                request.method == HttpMethod.Put && path == "/users/u1/status" -> {
                    bodies += request.body.toByteArray().decodeToString()
                    gate?.await()
                    if (statusResponse.isSuccess()) json("""{"message":"ok"}""") else problem(statusResponse)
                }
                else -> respond("", HttpStatusCode.NoContent)
            }
        }
    }

    private fun TestScope.session(server: Server): AccountSession {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return TestResources.session(server.engine, this, "u1")
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun AccountSession.presenceOf(status: String) {
        chat.handle(WsEvent.PresenceUpdated("u1", status))
    }

    /** Escolhe e deixa o estado alcançar o pedido que saiu: o estado é derivado e só acompanha quando o relógio virtual anda. */
    private fun TestScope.choose(vm: OwnStatusViewModel, status: OwnStatus) {
        vm.choose(status)
        runCurrent()
    }

    private fun profile(status: String?) = UserProfile("u1", "ana", null, null, null, null, null, emptyList(), status = status)

    // De onde vem o estado

    @Test
    fun aPresencaAoVivoDizOEstadoSemPedirOPerfil() = runTest {
        val server = Server()
        val session = session(server)
        session.presenceOf("busy")
        val vm = OwnStatusViewModel(session)

        assertEquals(OwnStatus.Busy, vm.state.first { it.selected != null }.selected)
        runCurrent()

        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun semPresencaOStatusGuardadoNoPerfilDizOEstado() = runTest {
        val server = Server().also { it.stored = "away" }
        val vm = OwnStatusViewModel(session(server))

        assertEquals(OwnStatus.Away, vm.state.first { it.selected != null }.selected)
        assertEquals(listOf("GET /users/u1/profile"), server.requests.toList())
    }

    @Test
    fun perfilSemStatusGuardadoEDisponivel() = runTest {
        val server = Server()
        val vm = OwnStatusViewModel(session(server))

        assertEquals(OwnStatus.Available, vm.state.first { it.selected != null }.selected)
    }

    @Test
    fun semPresencaEComPerfilQueFalhaNadaFicaMarcadoMasEscolherContinuaPossivel() = runTest {
        val server = Server().also { it.profileResponse = HttpStatusCode.InternalServerError }
        val vm = OwnStatusViewModel(session(server))
        TestResources.waitUntil { server.requests.isNotEmpty() }
        runCurrent()

        assertNull(vm.state.value.selected)
        choose(vm, OwnStatus.Busy)
        assertEquals(OwnStatus.Busy, vm.state.value.selected)
        assertTrue(vm.state.value.saving)
        vm.state.first { !it.saving }
        assertEquals(1, server.bodies.size)
    }

    @Test
    fun regrasDeOndeVemOEstado() {
        assertEquals(OwnStatus.Available, ownStatusOf(Presence("online", null), profile("busy")))
        assertEquals(OwnStatus.Away, ownStatusOf(Presence("away", null), null))
        assertEquals(OwnStatus.Busy, ownStatusOf(Presence("busy", null), null))
        assertEquals(OwnStatus.Busy, ownStatusOf(Presence("offline", null), profile("busy")))
        assertEquals(OwnStatus.Available, ownStatusOf(null, profile(null)))
        assertNull(ownStatusOf(Presence("offline", null), null))
        assertNull(ownStatusOf(null, null))
    }

    // Escolher

    @Test
    fun escolherAusenteMandaOPutEFicaSalvandoAteARespostaVoltarComAEscolha() = runTest {
        val server = Server().also { it.gate = CompletableDeferred() }
        val session = session(server)
        session.presenceOf("online")
        val vm = OwnStatusViewModel(session)
        vm.state.first { it.selected == OwnStatus.Available }

        choose(vm, OwnStatus.Away)
        assertEquals(OwnStatus.Away, vm.state.value.selected)
        assertTrue(vm.state.value.saving)
        TestResources.waitUntil { server.bodies.isNotEmpty() }
        server.gate!!.complete(Unit)
        val done = vm.state.first { !it.saving }

        assertEquals("""{"status":"away"}""", server.bodies.single())
        assertEquals(OwnStatus.Away, done.selected)
        assertNull(done.problem)
    }

    @Test
    fun disponivelMandaStatusNuloQueSaiComoCorpoSemOCampo() = runTest {
        val server = Server()
        val session = session(server)
        session.presenceOf("busy")
        val vm = OwnStatusViewModel(session)
        vm.state.first { it.selected == OwnStatus.Busy }

        choose(vm, OwnStatus.Available)
        val done = vm.state.first { !it.saving }

        assertEquals("{}", server.bodies.single())
        assertEquals(OwnStatus.Available, done.selected)
    }

    @Test
    fun oEventoDePresencaEncerraOSalvandoAntesDaResposta() = runTest {
        val server = Server().also { it.gate = CompletableDeferred() }
        val session = session(server)
        session.presenceOf("online")
        val vm = OwnStatusViewModel(session)
        vm.state.first { it.selected == OwnStatus.Available }

        choose(vm, OwnStatus.Busy)
        TestResources.waitUntil { server.bodies.isNotEmpty() }
        session.presenceOf("busy")
        val state = vm.state.first { !it.saving }

        assertEquals(OwnStatus.Busy, state.selected)
        server.gate!!.complete(Unit)
        runCurrent()
        assertEquals(OwnStatus.Busy, vm.state.value.selected)
        assertNull(vm.state.value.problem)
    }

    @Test
    fun falhaVoltaAEscolhaAnteriorEDizPorqueSemRepetir() = runTest {
        val cases = mapOf(
            HttpStatusCode.InternalServerError to MutationResult.Uncertain,
            HttpStatusCode.TooManyRequests to MutationResult.RateLimited,
            HttpStatusCode.BadRequest to MutationResult.Rejected("falhou 400", "t"),
        )
        for ((status, expected) in cases) {
            val server = Server().also { it.statusResponse = status }
            val session = session(server)
            session.presenceOf("online")
            val vm = OwnStatusViewModel(session)
            vm.state.first { it.selected == OwnStatus.Available }

            choose(vm, OwnStatus.Busy)
            val failed = vm.state.first { !it.saving && it.problem != null }
            runCurrent()

            assertEquals(OwnStatus.Available, failed.selected, "$status")
            assertEquals(expected, failed.problem, "$status")
            assertEquals(1, server.bodies.size, "$status")
        }
    }

    @Test
    fun novaEscolhaLimpaOProblemaDaAnterior() = runTest {
        val server = Server().also { it.statusResponse = HttpStatusCode.TooManyRequests }
        val session = session(server)
        session.presenceOf("online")
        val vm = OwnStatusViewModel(session)
        vm.state.first { it.selected == OwnStatus.Available }
        choose(vm, OwnStatus.Away)
        vm.state.first { !it.saving && it.problem != null }

        server.statusResponse = HttpStatusCode.OK
        choose(vm, OwnStatus.Away)
        assertNull(vm.state.value.problem)
        val done = vm.state.first { !it.saving }

        assertEquals(OwnStatus.Away, done.selected)
        assertEquals(2, server.bodies.size)
    }

    @Test
    fun escolherOQueJaEstaMarcadoOuEnquantoSalvaNaoMandaNada() = runTest {
        val server = Server().also { it.gate = CompletableDeferred() }
        val session = session(server)
        session.presenceOf("online")
        val vm = OwnStatusViewModel(session)
        vm.state.first { it.selected == OwnStatus.Available }

        choose(vm, OwnStatus.Available)
        assertTrue(server.bodies.isEmpty())
        assertFalse(vm.state.value.saving)

        choose(vm, OwnStatus.Busy)
        choose(vm, OwnStatus.Away)
        TestResources.waitUntil { server.bodies.isNotEmpty() }
        server.gate!!.complete(Unit)
        vm.state.first { !it.saving }

        assertEquals(listOf("""{"status":"busy"}"""), server.bodies.toList())
    }

    @Test
    fun umaMudancaDeOutroAparelhoDepoisDeConfirmarPassaAValer() = runTest {
        val server = Server()
        val session = session(server)
        session.presenceOf("online")
        val vm = OwnStatusViewModel(session)
        vm.state.first { it.selected == OwnStatus.Available }
        choose(vm, OwnStatus.Busy)
        TestResources.waitUntil { server.bodies.isNotEmpty() }
        vm.state.first { !it.saving && it.selected == OwnStatus.Busy }

        session.presenceOf("away")

        assertEquals(OwnStatus.Away, vm.state.first { it.selected == OwnStatus.Away }.selected)
    }
}
