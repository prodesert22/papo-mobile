package chat.papo.mobile

import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.chat.BlockList
import chat.papo.mobile.core.MutationResult
import chat.papo.mobile.data.api.ApiException
import chat.papo.mobile.data.api.ChatApi
import chat.papo.mobile.data.api.MemoryCookieStore
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.data.db.account.AccountDb
import chat.papo.mobile.data.ws.WsState
import chat.papo.mobile.ui.components.UiError
import chat.papo.mobile.ui.components.toUiError
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Um servidor com a lista de bloqueados e os códigos que cada rota devolve. */
internal class BlockServer(initial: List<String> = emptyList()) {
    val requests = CopyOnWriteArrayList<String>()
    @Volatile var blocked: List<String> = initial
    @Volatile var blockStatus = HttpStatusCode.NoContent
    @Volatile var listStatus = HttpStatusCode.OK
    @Volatile var dmStatus = HttpStatusCode.OK
    @Volatile var dmType = "https://papo.test/problems/dm-blocked"
    /** Segura as respostas de bloquear e desbloquear até ser liberado. */
    @Volatile var gate: CompletableDeferred<Unit>? = null

    val engine = MockEngine { request ->
        val path = request.url.encodedPath
        requests += "${request.method.value} $path"
        if (path.endsWith("/block")) gate?.await()
        fun problem(status: HttpStatusCode, type: String = "https://papo.test/problems/other") = respond(
            """{"type":"$type","title":"Erro","status":${status.value},"detail":"falhou ${status.value}"}""",
            status,
            headersOf(HttpHeaders.ContentType, "application/problem+json"),
        )
        when {
            path == "/users/blocks" ->
                if (listStatus != HttpStatusCode.OK) {
                    problem(listStatus)
                } else {
                    val users = blocked.joinToString(",") { """{"id":"$it","username":"user-$it","nickname":"Apelido $it"}""" }
                    respond("""{"users":[$users]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            path.endsWith("/block") && request.method == HttpMethod.Post -> {
                val id = path.removePrefix("/users/").removeSuffix("/block")
                if (blockStatus.value in 200..299) {
                    blocked = blocked.filterNot { it == id } + id
                    respond("", blockStatus)
                } else {
                    problem(blockStatus)
                }
            }
            path.endsWith("/block") && request.method == HttpMethod.Delete -> {
                val id = path.removePrefix("/users/").removeSuffix("/block")
                if (blockStatus.value in 200..299) {
                    blocked = blocked - id
                    respond("", blockStatus)
                } else {
                    problem(blockStatus)
                }
            }
            path == "/dms" && request.method == HttpMethod.Post ->
                if (dmStatus == HttpStatusCode.OK) {
                    respond(
                        """{"id":"d9","user":{"id":"u2","username":"bia"},"created_at":"2026-01-01T00:00:00Z"}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } else {
                    problem(dmStatus, dmType)
                }
            else -> problem(HttpStatusCode.NotFound)
        }
    }

    fun count(call: String) = requests.count { it == call }
}

/**
 * Quem a conta bloqueou. O motor HTTP falso responde em outra thread: o que depende de um pedido espera uma
 * pós-condição observável (`waitUntil`), não o relógio virtual.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BlocksTest {
    private class Fixture(scope: TestScope, val server: BlockServer) {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val driver = TestResources.driver()
        val db = AccountDb(driver)
        val connection = MutableStateFlow(WsState.Disconnected)
        val client = PapoClient(server.engine, ServerUrl("https://papo.test"), MemoryCookieStore("jwt"))
        val api = ChatApi(client)
        val blocks = BlockList(api, db.chatQueries, connection, scope.backgroundScope, dispatcher)

        fun addDm(id: String, userId: String) =
            db.chatQueries.upsertDm(id, userId, "2026-01-01T00:00:00Z", null, null, null, null, null, 0)

        fun dmIds() = db.chatQueries.selectDms().executeAsList().map { it.id }
    }

    @Test
    fun leAListaUmaVezNaPrimeiraConexaoENaNasReconexoes() = runTest {
        val f = Fixture(this, BlockServer(initial = listOf("u2")))
        f.blocks.start()
        f.blocks.start() // chamar de novo não agenda outra leitura
        runCurrent()
        assertEquals(0, f.server.requests.size) // sem conexão, sem pedido

        f.connection.value = WsState.Connecting
        runCurrent()
        assertEquals(0, f.server.requests.size)

        f.connection.value = WsState.Connected
        TestResources.waitUntil { f.blocks.blocked.value.isNotEmpty() }
        assertEquals(setOf("u2"), f.blocks.blocked.value)

        f.connection.value = WsState.Disconnected
        runCurrent()
        f.connection.value = WsState.Connected
        runCurrent()
        assertEquals(1, f.server.count("GET /users/blocks"))
    }

    @Test
    fun falhaNaPrimeiraLeituraNaoDerrubaNemRepete() = runTest {
        val server = BlockServer(initial = listOf("u2")).also { it.listStatus = HttpStatusCode.InternalServerError }
        val f = Fixture(this, server)
        f.blocks.start()
        f.connection.value = WsState.Connected
        TestResources.waitUntil { server.count("GET /users/blocks") == 1 }
        runCurrent()

        assertEquals(emptySet(), f.blocks.blocked.value)
        assertEquals(1, server.count("GET /users/blocks"))
    }

    @Test
    fun abrirATelaLeDeNovoEMostraNomesDoServidor() = runTest {
        val server = BlockServer(initial = listOf("u2", "u3"))
        val f = Fixture(this, server)

        f.blocks.refresh()

        assertEquals(setOf("u2", "u3"), f.blocks.blocked.value)
        assertEquals(listOf("Apelido u2", "Apelido u3"), f.blocks.users.value.map { it.displayName })
    }

    @Test
    fun leituraComErroFalhaEMantemOQueTinha() = runTest {
        val server = BlockServer(initial = listOf("u2"))
        val f = Fixture(this, server)
        f.blocks.refresh()
        server.listStatus = HttpStatusCode.InternalServerError

        assertFailsWith<ApiException> { f.blocks.refresh() }

        assertEquals(setOf("u2"), f.blocks.blocked.value)
    }

    @Test
    fun bloquearEntraNaListaERemoveAsConversasDiretasComAPessoa() = runTest {
        val server = BlockServer()
        val f = Fixture(this, server)
        f.addDm("d1", "u2")
        f.addDm("d2", "u3")

        val result = f.blocks.block("u2")

        assertEquals(MutationResult.Done, result)
        assertEquals(setOf("u2"), f.blocks.blocked.value)
        assertEquals(listOf("d2"), f.dmIds())
        // Um POST e depois a leitura da lista: a mudança confere com o servidor.
        assertEquals(listOf("POST /users/u2/block", "GET /users/blocks"), server.requests.toList())
    }

    @Test
    fun bloquearQuemJaEstavaBloqueadoNaoDuplica() = runTest {
        val f = Fixture(this, BlockServer(initial = listOf("u2")))
        f.blocks.refresh()

        assertEquals(MutationResult.Done, f.blocks.block("u2"))

        assertEquals(listOf("u2"), f.blocks.users.value.map { it.id })
    }

    @Test
    fun bloquearComErroMantemAListaEAConversa() = runTest {
        val server = BlockServer().also { it.blockStatus = HttpStatusCode.InternalServerError }
        val f = Fixture(this, server)
        f.addDm("d1", "u2")

        val result = f.blocks.block("u2")

        assertEquals(MutationResult.Uncertain, result)
        assertEquals(emptySet(), f.blocks.blocked.value)
        assertEquals(listOf("d1"), f.dmIds())
        assertEquals(1, server.count("POST /users/u2/block")) // nada repetido sozinho
    }

    @Test
    fun bloquearComIncertezaConfereNoServidorSemMandarDeNovo() = runTest {
        // O servidor aplicou o bloqueio e a resposta se perdeu: a leitura mostra que ele vale.
        val server = BlockServer(initial = listOf("u2")).also { it.blockStatus = HttpStatusCode.BadGateway }
        val f = Fixture(this, server)
        f.addDm("d1", "u2")

        val result = f.blocks.block("u2")

        assertEquals(MutationResult.Uncertain, result)
        assertEquals(setOf("u2"), f.blocks.blocked.value)
        assertEquals(1, server.count("POST /users/u2/block"))
    }

    @Test
    fun bloquearComLimiteDeTaxaPedeEsperarComUmSoPedido() = runTest {
        val server = BlockServer().also { it.blockStatus = HttpStatusCode.TooManyRequests }
        val f = Fixture(this, server)

        assertEquals(MutationResult.RateLimited, f.blocks.block("u2"))

        assertEquals(listOf("POST /users/u2/block"), server.requests.toList())
        assertEquals(emptySet(), f.blocks.blocked.value)
    }

    @Test
    fun bloquearRecusadoDevolveODetalheEMantemAConversa() = runTest {
        val server = BlockServer().also { it.blockStatus = HttpStatusCode.BadRequest }
        val f = Fixture(this, server)
        f.addDm("d1", "u2")

        val result = f.blocks.block("u2")

        assertEquals("falhou 400", assertIs<MutationResult.Rejected>(result).detail)
        assertEquals(listOf("d1"), f.dmIds())
        assertEquals(emptySet(), f.blocks.blocked.value)
    }

    @Test
    fun aLeituraDepoisDeBloquearQueFalhaNaoDesfazOBloqueio() = runTest {
        val server = BlockServer().also { it.listStatus = HttpStatusCode.InternalServerError }
        val f = Fixture(this, server)
        f.addDm("d1", "u2")

        assertEquals(MutationResult.Done, f.blocks.block("u2"))

        assertEquals(setOf("u2"), f.blocks.blocked.value)
        assertEquals(emptyList(), f.dmIds())
    }

    @Test
    fun desbloquearSaiDaListaESemReabrirAConversa() = runTest {
        val server = BlockServer(initial = listOf("u2", "u3"))
        val f = Fixture(this, server)
        f.blocks.refresh()

        val result = f.blocks.unblock("u2")

        assertEquals(MutationResult.Done, result)
        assertEquals(setOf("u3"), f.blocks.blocked.value)
        assertEquals(emptyList(), f.dmIds())
        assertEquals(1, server.count("DELETE /users/u2/block"))
        assertEquals(0, server.count("POST /dms"))
    }

    @Test
    fun desbloquearComErroMantemAPessoaBloqueada() = runTest {
        val server = BlockServer(initial = listOf("u2"))
        val f = Fixture(this, server)
        f.blocks.refresh()
        server.blockStatus = HttpStatusCode.TooManyRequests

        assertEquals(MutationResult.RateLimited, f.blocks.unblock("u2"))

        assertEquals(setOf("u2"), f.blocks.blocked.value)
    }

    @Test
    fun desbloquearComIncertezaConfereNoServidor() = runTest {
        val server = BlockServer(initial = listOf("u2")).also { it.blockStatus = HttpStatusCode.InternalServerError }
        val f = Fixture(this, server)
        f.blocks.refresh()

        assertEquals(MutationResult.Uncertain, f.blocks.unblock("u2"))

        assertEquals(setOf("u2"), f.blocks.blocked.value)
        assertEquals(1, server.count("DELETE /users/u2/block"))
    }

    @Test
    fun duasContasNoMesmoServidorTemListasSeparadas() = runTest {
        val a = Fixture(this, BlockServer())
        val b = Fixture(this, BlockServer())
        a.addDm("d1", "u2")
        b.addDm("d1", "u2")

        a.blocks.block("u2")

        assertEquals(setOf("u2"), a.blocks.blocked.value)
        assertEquals(emptySet(), b.blocks.blocked.value)
        assertEquals(emptyList(), a.dmIds())
        assertEquals(listOf("d1"), b.dmIds())
    }

    @Test
    fun recusaDeConversaDiretaPorBloqueioViraDmBlocked() = runTest {
        val server = BlockServer().also { it.dmStatus = HttpStatusCode.Forbidden }
        val f = Fixture(this, server)

        val error = assertFailsWith<ApiException> { f.api.openDm("u2") }

        assertEquals(UiError.DmBlocked, error.toUiError())
    }

    @Test
    fun recusaDeConversaDiretaPorOutroMotivoContinuaSendoOTextoDoServidor() = runTest {
        val server = BlockServer().also {
            it.dmStatus = HttpStatusCode.Forbidden
            it.dmType = "https://papo.test/problems/forbidden"
        }
        val f = Fixture(this, server)

        val error = assertFailsWith<ApiException> { f.api.openDm("u2") }

        assertEquals(UiError.Server("falhou 403"), error.toUiError())
        assertTrue(error.isForbidden)
    }
}
