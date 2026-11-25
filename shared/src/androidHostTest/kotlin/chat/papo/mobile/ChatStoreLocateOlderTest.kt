package chat.papo.mobile

import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.chat.ChatStore
import chat.papo.mobile.chat.Located
import chat.papo.mobile.data.api.ChatApi
import chat.papo.mobile.data.api.MemoryCookieStore
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.data.db.account.AccountDb
import chat.papo.mobile.data.ws.WsClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A procura da mensagem respondida: só o id é conhecido, então o histórico é percorrido para trás. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatStoreLocateOlderTest {
    private class Fixture(scope: TestScope, val server: PagedChannel) {
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        private val client = PapoClient(server.engine, ServerUrl("https://papo.test"), MemoryCookieStore("jwt"))
        val store = ChatStore(
            myUserId = "u1",
            db = AccountDb(TestResources.driver()),
            api = ChatApi(client),
            ws = WsClient(client, scope.backgroundScope, sessionStillValid = { true }),
            scope = scope.backgroundScope,
            io = dispatcher,
        )

        suspend fun ids(limit: Long = 5000) = store.messages("c1", limit).first().map { it.id }

        /** Só as leituras de página feitas depois de [from] requisições. */
        fun pages(from: Int) = server.requests.drop(from).filter { "/channels/c1/messages?" in it }
    }

    @Test
    fun mensagemJaGuardadaNaoFazRequisicao() = runTest {
        val f = Fixture(this, PagedChannel(100))
        f.store.refreshLatest("c1") // m100..m61
        val before = f.server.requests.size

        assertEquals(Located.Found(10), f.store.locateOlder("c1", "m90"))
        assertEquals(Located.Found(0), f.store.locateOlder("c1", "m100"))
        assertEquals(before, f.server.requests.size)
    }

    @Test
    fun achaDepoisDeAlgumasPaginasPeloCursorSinceELastId() = runTest {
        val f = Fixture(this, PagedChannel(600))
        f.store.refreshLatest("c1") // m600..m561
        val before = f.server.requests.size

        // m300 está na terceira página para trás (m360..m261); antes dela são 300 mensagens mais novas.
        assertEquals(Located.Found(300), f.store.locateOlder("c1", "m300"))

        val pages = f.pages(before)
        assertEquals(3, pages.size)
        // Cada página parte da mais antiga guardada, para trás, com o tamanho máximo do servidor.
        listOf("m561", "m461", "m361").forEachIndexed { index, last ->
            val page = pages[index]
            assertTrue(page.startsWith("GET /channels/c1/messages?"), page)
            assertTrue("order=desc" in page && "limit=100" in page && "last_id=$last" in page && "since=" in page, page)
        }
        assertEquals((600 downTo 261).map { "m$it" }, f.ids())
    }

    @Test
    fun achaNaUltimaPaginaPermitidaSemDizerQueNaoChegou() = runTest {
        val f = Fixture(this, PagedChannel(1000))
        f.store.refreshLatest("c1") // m1000..m961
        val before = f.server.requests.size

        // O limite são cinco páginas (m960..m461): m470 está na quinta e é achada.
        assertEquals(Located.Found(530), f.store.locateOlder("c1", "m470"))
        assertEquals(5, f.pages(before).size)
    }

    @Test
    fun chegarAoComecoSemAcharDizQueFoiApagada() = runTest {
        val f = Fixture(this, PagedChannel(250).apply { deleted += 123 })
        f.store.refreshLatest("c1") // m250..m211
        val before = f.server.requests.size

        assertEquals(Located.Missing, f.store.locateOlder("c1", "m123"))
        assertEquals(3, f.pages(before).size) // as duas primeiras trazem 100 cada; a terceira (m9..m1) é a última
        assertEquals(249, f.ids().size)

        // Já se sabe que o histórico acabou: nova procura não vai ao servidor.
        val after = f.server.requests.size
        assertEquals(Located.Missing, f.store.locateOlder("c1", "m123"))
        assertEquals(after, f.server.requests.size)
        assertFalse(f.store.hasOlder("c1"))
    }

    @Test
    fun paraNoLimiteDePaginasECadaNovaProcuraContinuaDaMaisAntigaCarregada() = runTest {
        val f = Fixture(this, PagedChannel(1000))
        f.store.refreshLatest("c1") // m1000..m961
        val before = f.server.requests.size

        assertEquals(Located.NotReached, f.store.locateOlder("c1", "apagada"))
        val first = f.pages(before)
        assertEquals(5, first.size)
        assertTrue("last_id=m961" in first.first() && "last_id=m561" in first.last(), first.toString())
        assertEquals(540, f.ids().size) // o que foi lido continua guardado
        assertTrue(f.store.hasOlder("c1"))

        // Continuar parte da mais antiga carregada (m461), sem reler o que já veio.
        val mid = f.server.requests.size
        assertEquals(Located.Missing, f.store.locateOlder("c1", "apagada")) // m460..m1 acaba o histórico
        val second = f.pages(mid)
        assertTrue("last_id=m461" in second.first(), second.toString())
        assertEquals(5, second.size) // 100 + 100 + 100 + 100 + 60: a quinta página já é a última
        assertEquals(1000, f.ids().size)
    }

    @Test
    fun orcamentoDePaginasPodeSerMenorEUltimaPaginaGanhaDoLimite() = runTest {
        val f = Fixture(this, PagedChannel(300))
        f.store.refreshLatest("c1") // m300..m261

        assertEquals(Located.NotReached, f.store.locateOlder("c1", "apagada", maxPages = 1)) // m260..m161
        assertEquals(Located.NotReached, f.store.locateOlder("c1", "apagada", maxPages = 1)) // m160..m61
        // A terceira página (m60..m1) é a última do canal: ali termina o histórico, e não só o orçamento.
        assertEquals(Located.Missing, f.store.locateOlder("c1", "apagada", maxPages = 1))
    }

    @Test
    fun falhaDeRedeNoMeioDaProcuraSobeSemMarcarComoApagada() = runTest {
        val server = PagedChannel(500).apply { deleted += 100 }
        val f = Fixture(this, server)
        f.store.refreshLatest("c1")
        server.failPages = true

        assertFailsWith<Exception> { f.store.locateOlder("c1", "m100") }
        assertTrue(f.store.hasOlder("c1"))

        // O servidor voltou: a procura recomeça de onde estava.
        server.failPages = false
        assertEquals(Located.Missing, f.store.locateOlder("c1", "m100"))
    }

    @Test
    fun cancelarAProcuraDevolveOCancelamentoEDeixaOHistoricoComoEstava() = runTest {
        val server = PagedChannel(500).apply { deleted += 100 }
        val f = Fixture(this, server)
        f.store.refreshLatest("c1")
        val stored = f.ids().size
        server.holdPages = CompletableDeferred() // a primeira página fica presa no servidor

        val search = async { f.store.locateOlder("c1", "m100") }
        server.pageArrived.receive()
        search.cancel()

        assertFailsWith<CancellationException> { search.await() }
        assertEquals(stored, f.ids().size)
        assertTrue(f.store.hasOlder("c1"))
    }

    @Test
    fun orcamentoSemPaginasNaoFazSentido() = runTest {
        val f = Fixture(this, PagedChannel(100))
        f.store.refreshLatest("c1")
        val before = f.server.requests.size

        assertFailsWith<IllegalArgumentException> { f.store.locateOlder("c1", "apagada", maxPages = 0) }
        assertEquals(before, f.server.requests.size)
    }

    @Test
    fun semNadaGuardadoNaoHaCursorEPaginarParaTrasNaoFazRequisicao() = runTest {
        val f = Fixture(this, PagedChannel(100))

        assertFalse(f.store.loadOlder("c1"))
        assertEquals(0, f.server.requests.size)
    }

    @Test
    fun loadOlderAceitaOTamanhoDaPagina() = runTest {
        val f = Fixture(this, PagedChannel(100))
        f.store.refreshLatest("c1")
        assertTrue(f.store.loadOlder("c1", limit = 10))
        assertTrue("limit=10" in f.server.requests.last())
        assertEquals(50, f.ids().size)
    }
}
