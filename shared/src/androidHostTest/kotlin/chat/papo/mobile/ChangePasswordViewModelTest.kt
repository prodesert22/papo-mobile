package chat.papo.mobile

import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.PasswordCheck
import chat.papo.mobile.chat.PasswordProblem
import chat.papo.mobile.core.MutationResult
import chat.papo.mobile.ui.settings.ChangePasswordOutcome
import chat.papo.mobile.ui.settings.ChangePasswordState
import chat.papo.mobile.ui.settings.ChangePasswordViewModel
import chat.papo.mobile.ui.settings.PasswordStep
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A troca da senha da própria conta sobre o motor HTTP falso: a ordem dos pedidos, cada falha e o fim da sessão. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChangePasswordViewModelTest {
    private class Server {
        val requests = CopyOnWriteArrayList<String>()
        val bodies = CopyOnWriteArrayList<String>()
        @Volatile var resetStatus = HttpStatusCode.OK
        @Volatile var passwordStatus = HttpStatusCode.OK
        @Volatile var passwordDetail = "senha fraca"
        @Volatile var gate: CompletableDeferred<Unit>? = null

        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            requests += "${request.method.value} $path"
            if (request.method == HttpMethod.Put) bodies += request.body.toByteArray().decodeToString()
            fun problem(status: HttpStatusCode, detail: String = "falhou ${status.value}") = respond(
                """{"type":"t","title":"Erro","status":${status.value},"detail":"$detail"}""",
                status,
                headersOf(HttpHeaders.ContentType, "application/problem+json"),
            )
            gate?.await()
            when {
                path == "/users/u1/reset" && request.method == HttpMethod.Post ->
                    if (resetStatus == HttpStatusCode.OK) respond("""{"ok":true}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) else problem(resetStatus)
                path == "/users/u1/password" && request.method == HttpMethod.Put ->
                    if (passwordStatus == HttpStatusCode.OK) respond("""{"ok":true}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) else problem(passwordStatus, passwordDetail)
                else -> problem(HttpStatusCode.NotFound)
            }
        }
    }

    private val ended = CopyOnWriteArrayList<String>()
    private val good = "Nova@Senha1"

    private fun TestScope.session(server: Server): AccountSession {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return TestResources.session(server.engine, this)
    }

    private fun TestScope.viewModel(server: Server, endSession: suspend () -> Unit = { ended += "u1" }) =
        ChangePasswordViewModel(session(server), endSession)

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        ended.clear()
    }

    private suspend fun ChangePasswordViewModel.settled(): ChangePasswordState = state.first { !it.busy && it.outcome != null }

    @Test
    fun peloReset_depoisPelaSenha_eComSucessoEncerraASessaoSemSairNoServidor() = runTest {
        val server = Server()
        val vm = viewModel(server)

        vm.submit(good, good)
        val done = vm.settled()

        assertEquals(ChangePasswordOutcome.Changed, done.outcome)
        assertEquals(listOf("POST /users/u1/reset", "PUT /users/u1/password"), server.requests.toList())
        assertEquals(listOf("""{"password":"$good"}"""), server.bodies.toList())
        assertEquals(listOf("u1"), ended.toList())
        assertTrue(server.requests.none { it.contains("/auth/") })
    }

    @Test
    fun resetRecusadoNaoMandaASenhaENaoEncerraASessao() = runTest {
        val server = Server().also { it.resetStatus = HttpStatusCode.Conflict }
        val vm = viewModel(server)

        vm.submit(good, good)
        val failed = assertIs<ChangePasswordOutcome.Failed>(vm.settled().outcome)

        assertEquals(PasswordStep.Prepare, failed.step)
        assertEquals("falhou 409", assertIs<MutationResult.Rejected>(failed.result).detail)
        assertEquals(listOf("POST /users/u1/reset"), server.requests.toList())
        assertEquals(emptyList(), ended.toList())
    }

    @Test
    fun resetComLimiteDeTaxaPedeEsperarComUmSoPedido() = runTest {
        val server = Server().also { it.resetStatus = HttpStatusCode.TooManyRequests }
        val vm = viewModel(server)

        vm.submit(good, good)
        val failed = assertIs<ChangePasswordOutcome.Failed>(vm.settled().outcome)

        assertEquals(MutationResult.RateLimited, failed.result)
        assertEquals(listOf("POST /users/u1/reset"), server.requests.toList())
    }

    @Test
    fun resetIncertoNaoMandaASenhaEDizQueNadaMudou() = runTest {
        val server = Server().also { it.resetStatus = HttpStatusCode.InternalServerError }
        val vm = viewModel(server)

        vm.submit(good, good)
        val failed = assertIs<ChangePasswordOutcome.Failed>(vm.settled().outcome)

        assertEquals(PasswordStep.Prepare, failed.step)
        assertEquals(MutationResult.Uncertain, failed.result)
        assertEquals(listOf("POST /users/u1/reset"), server.requests.toList())
        assertEquals(emptyList(), ended.toList())
    }

    @Test
    fun senhaRecusadaPeloServidorMostraODetalheESessaoContinua() = runTest {
        val server = Server().also { it.passwordStatus = HttpStatusCode.BadRequest }
        val vm = viewModel(server)

        vm.submit(good, good)
        val failed = assertIs<ChangePasswordOutcome.Failed>(vm.settled().outcome)

        assertEquals(PasswordStep.Change, failed.step)
        assertEquals("senha fraca", assertIs<MutationResult.Rejected>(failed.result).detail)
        assertEquals(listOf("POST /users/u1/reset", "PUT /users/u1/password"), server.requests.toList())
        assertEquals(emptyList(), ended.toList())
    }

    @Test
    fun senhaComLimiteDeTaxaTemUmSoPutENaoEncerra() = runTest {
        val server = Server().also { it.passwordStatus = HttpStatusCode.TooManyRequests }
        val vm = viewModel(server)

        vm.submit(good, good)
        val failed = assertIs<ChangePasswordOutcome.Failed>(vm.settled().outcome)

        assertEquals(MutationResult.RateLimited, failed.result)
        assertEquals(1, server.requests.count { it.startsWith("PUT") })
        assertEquals(emptyList(), ended.toList())
    }

    @Test
    fun senhaIncertaPedeConferirAntesDeRepetirESemEncerrarNemRepetir() = runTest {
        val server = Server().also { it.passwordStatus = HttpStatusCode.BadGateway }
        val vm = viewModel(server)

        vm.submit(good, good)
        val failed = assertIs<ChangePasswordOutcome.Failed>(vm.settled().outcome)

        assertEquals(PasswordStep.Change, failed.step)
        assertEquals(MutationResult.Uncertain, failed.result)
        assertEquals(1, server.requests.count { it.startsWith("PUT") })
        assertEquals(emptyList(), ended.toList())
    }

    @Test
    fun detalheQueRepeteASenhaChegaSemEla() = runTest {
        val server = Server().also {
            it.passwordStatus = HttpStatusCode.BadRequest
            it.passwordDetail = "a senha $good e inválida"
        }
        val vm = viewModel(server)

        vm.submit(good, good)
        val failed = assertIs<ChangePasswordOutcome.Failed>(vm.settled().outcome)

        val detail = assertIs<MutationResult.Rejected>(failed.result).detail.orEmpty()
        assertEquals("a senha … e inválida", detail)
    }

    @Test
    fun senhaFracaOuDiferenteNaoVaiAoServidor() = runTest {
        val server = Server()
        val vm = viewModel(server)

        vm.submit("abc", "abc")
        val weak = assertIs<ChangePasswordOutcome.Invalid>(vm.state.value.outcome)
        assertEquals(
            PasswordCheck.Weak(listOf(PasswordProblem.TooShort, PasswordProblem.NoUppercase, PasswordProblem.NoSymbol)),
            weak.check,
        )

        vm.submit(good, "$good!")
        assertEquals(ChangePasswordOutcome.Invalid(PasswordCheck.Mismatch), vm.state.value.outcome)
        runCurrent()

        assertEquals(emptyList(), server.requests.toList())
    }

    @Test
    fun outroEnvioEnquantoEsperaEIgnorado() = runTest {
        val gate = CompletableDeferred<Unit>()
        val server = Server().also { it.gate = gate }
        val vm = viewModel(server)

        vm.submit(good, good)
        assertTrue(vm.state.value.busy)
        vm.submit(good, good)
        TestResources.waitUntil { server.requests.isNotEmpty() }
        gate.complete(Unit)
        vm.settled()

        assertEquals(1, server.requests.count { it == "POST /users/u1/reset" })
        assertEquals(1, server.requests.count { it == "PUT /users/u1/password" })
    }

    @Test
    fun falhaAoEncerrarASessaoNaoDesfazOResultado() = runTest {
        val server = Server()
        val vm = viewModel(server) { error("registro fechado") }

        vm.submit(good, good)

        assertEquals(ChangePasswordOutcome.Changed, vm.settled().outcome)
    }

    @Test
    fun semMensagensNaFilaNaoHaAviso() = runTest {
        assertEquals(0, viewModel(Server()).queued)
    }
}
