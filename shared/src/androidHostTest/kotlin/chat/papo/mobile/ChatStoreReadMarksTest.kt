package chat.papo.mobile

import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.chat.ChatStore
import chat.papo.mobile.chat.MessagePosition
import chat.papo.mobile.chat.ReadMark
import chat.papo.mobile.data.api.ApiException
import chat.papo.mobile.data.api.ChatApi
import chat.papo.mobile.data.api.MemoryCookieStore
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.data.db.account.AccountDb
import chat.papo.mobile.data.ws.WsClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** O que o `ChatStore` avisa depois de ler um canal, e onde ele diz que as mensagens guardadas estão. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatStoreReadMarksTest {
    private class Fixture(scope: TestScope, val server: PagedChannel, engine: HttpClientEngine = server.engine) {
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        private val client = PapoClient(engine, ServerUrl("https://papo.test"), MemoryCookieStore("jwt"))
        val db = AccountDb(TestResources.driver())
        val store = ChatStore(
            myUserId = "u1",
            db = db,
            api = ChatApi(client),
            ws = WsClient(client, scope.backgroundScope, sessionStillValid = { true }),
            scope = scope.backgroundScope,
            io = dispatcher,
        )
        val marks = mutableListOf<ReadMark>()

        init {
            scope.backgroundScope.launch { store.readMarks.collect { marks += it } }
            scope.testScheduler.runCurrent()
        }

        fun reads() = server.requests.filter { it.startsWith("PUT /channels/c1/read") }
    }

    @Test
    fun lerOCanalAvisaDepoisDoPutComOInstanteDaMensagemGuardada() = runTest {
        val f = Fixture(this, PagedChannel(100))
        f.store.refreshLatest("c1")

        f.store.markRead("c1", "m100")
        runCurrent()

        assertEquals(1, f.reads().size)
        assertEquals(listOf(ReadMark("c1", "m100", f.server.ts(100))), f.marks)
    }

    @Test
    fun mensagemQueOAparelhoNaoTemVaiSemInstante() = runTest {
        val f = Fixture(this, PagedChannel(100))

        f.store.markRead("c1", "m7")
        runCurrent()

        assertEquals(listOf(ReadMark("c1", "m7", null)), f.marks)
    }

    @Test
    fun canalJaLidoAteAMensagemNaoFazPedidoNemAvisa() = runTest {
        val f = Fixture(this, PagedChannel(100))
        f.db.chatQueries.upsertChannel("c1", "geral", "text", 0, null, null, "m100", null, null, null, "m100", null)

        f.store.markRead("c1", "m100")
        runCurrent()

        assertEquals(0, f.reads().size)
        assertEquals(emptyList(), f.marks)
    }

    @Test
    fun pedidoRecusadoSobeSemAvisar() = runTest {
        val engine = MockEngine {
            respond(
                """{"type":"t","title":"Proibido","status":403}""", HttpStatusCode.Forbidden,
                headersOf(HttpHeaders.ContentType, "application/problem+json"),
            )
        }
        val f = Fixture(this, PagedChannel(10), engine)

        assertFailsWith<ApiException> { f.store.markRead("c1", "m10") }
        runCurrent()

        assertEquals(emptyList(), f.marks)
        assertEquals(1, engine.requestHistory.size)
    }

    @Test
    fun aPosicaoDasMensagensGuardadasEOInstanteEOId() = runTest {
        val f = Fixture(this, PagedChannel(100))
        f.store.refreshLatest("c1") // m100..m61

        val positions = f.store.storedPositions(listOf("m100", "m61", "m60", "nenhuma"))

        assertEquals(setOf("m100", "m61"), positions.keys)
        assertEquals(MessagePosition(1_767_225_600_000_000L + 100 * 1_000_000L, "m100"), positions["m100"])
        assertTrue(positions.getValue("m61") < positions.getValue("m100"))
    }

    @Test
    fun semIdsNaoHaConsulta() = runTest {
        val f = Fixture(this, PagedChannel(10))
        assertEquals(emptyMap(), f.store.storedPositions(emptyList()))
    }
}
