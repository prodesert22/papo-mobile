package chat.papo.mobile

import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.chat.ChatStore
import chat.papo.mobile.chat.Located
import chat.papo.mobile.data.api.ApiException
import chat.papo.mobile.data.api.ChatApi
import chat.papo.mobile.data.api.MemoryCookieStore
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.data.db.account.AccountDb
import chat.papo.mobile.data.ws.WsClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Abrir uma mensagem pelo horário de uma notificação, que sai um pouco depois da mensagem: o instante exato dela
 * não se sabe, e a página decrescente a partir do horário da notificação a traz.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatStoreLocateApproxTest {
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

        suspend fun ids() = store.messages("c1", 5000).first().map { it.id }

        /** Só as leituras de página feitas depois de [from] requisições. */
        fun pages(from: Int) = server.requests.drop(from).filter { "/channels/c1/messages?" in it }

        /** O horário de uma notificação da mensagem [n]: 3 ms depois dela. */
        fun notifiedAt(n: Int) = server.ts(n).replace("Z", ".003Z")
    }

    @Test
    fun notificacaoTresMilissegundosDepoisAchaAMensagemEOQueVemDepois() = runTest {
        val f = Fixture(this, PagedChannel(200))

        val located = f.store.locate("c1", "m50", f.notifiedAt(50), approximate = true)

        // Antes dela, 40 (m50..m11); depois, as 100 seguintes (m51..m150), que ainda não chegam ao fim do canal.
        assertEquals(Located.Found(100), located)
        assertEquals((150 downTo 11).map { "m$it" }, f.ids())
        assertTrue(f.store.isDetached("c1"))
        val pages = f.pages(0)
        assertEquals(2, pages.size)
        // A primeira desce a partir do horário da notificação, com o id da mensagem; a segunda sobe do horário exato dela.
        assertTrue("order=desc" in pages[0] && "limit=40" in pages[0] && "last_id=m50" in pages[0], pages[0])
        assertTrue(f.notifiedAt(50).replace(":", "%3A") in pages[0], pages[0])
        assertTrue("order=asc" in pages[1] && "limit=100" in pages[1] && "last_id=m50" in pages[1], pages[1])
        assertTrue(f.server.ts(50).replace(":", "%3A") in pages[1], pages[1])
    }

    @Test
    fun mensagemMaisNovaDoCanalVemNaPaginaDescendenteESemOutraLeituraAMais() = runTest {
        val f = Fixture(this, PagedChannel(50))

        val located = f.store.locate("c1", "m50", f.notifiedAt(50), approximate = true)

        assertEquals(Located.Found(0), located)
        assertEquals((50 downTo 11).map { "m$it" }, f.ids())
        assertFalse(f.store.isDetached("c1")) // a vizinhança chega ao fim do histórico
        assertEquals(2, f.pages(0).size) // a descendente e a crescente, que vem vazia
    }

    @Test
    fun mensagemApagadaDevolveMissingSemGuardarNada() = runTest {
        val f = Fixture(this, PagedChannel(100).apply { deleted += 50 })

        assertEquals(Located.Missing, f.store.locate("c1", "m50", f.notifiedAt(50), approximate = true))

        assertEquals(1, f.pages(0).size) // só a descendente, e ela não a traz
        assertEquals(emptyList(), f.ids())
    }

    @Test
    fun mensagemJaGuardadaNaoFazRequisicao() = runTest {
        val f = Fixture(this, PagedChannel(100))
        f.store.refreshLatest("c1") // m100..m61
        val before = f.server.requests.size

        assertEquals(Located.Found(10), f.store.locate("c1", "m90", f.notifiedAt(90), approximate = true))

        assertEquals(before, f.server.requests.size)
    }

    @Test
    fun vizinhancaQueEncostaNoTrechoGuardadoViraUmSoSemMarcarDistancia() = runTest {
        val f = Fixture(this, PagedChannel(200))
        f.store.refreshLatest("c1") // m200..m161

        val located = f.store.locate("c1", "m150", f.notifiedAt(150), approximate = true)

        // Depois dela vêm m151..m200 (50, sem mais), que se juntam ao que já estava: nenhum buraco.
        assertEquals(Located.Found(50), located)
        assertEquals((200 downTo 111).map { "m$it" }, f.ids())
        assertFalse(f.store.isDetached("c1"))
    }

    @Test
    fun trechoGuardadoLongeDaVizinhancaDaLugarAEla() = runTest {
        val f = Fixture(this, PagedChannel(400))
        f.store.refreshLatest("c1") // m400..m361

        assertEquals(Located.Found(100), f.store.locate("c1", "m50", f.notifiedAt(50), approximate = true))

        assertEquals((150 downTo 11).map { "m$it" }, f.ids()) // o trecho de cima saiu
        assertTrue(f.store.isDetached("c1"))
    }

    @Test
    fun mensagemNaQuadragesimaPosicaoDaPaginaDescendenteAindaEAchada() = runTest {
        val f = Fixture(this, PagedChannel(100))

        // Entre a mensagem m20 e a notificação entraram outras 39: a página de 40 acaba na m20.
        assertEquals(Located.Found(80), f.store.locate("c1", "m20", f.notifiedAt(59), approximate = true))

        assertEquals((100 downTo 20).map { "m$it" }, f.ids())
    }

    @Test
    fun comMaisDe39MensagensEntreAMensagemEANotificacaoAPaginaNaoAlcancaESaiMissing() = runTest {
        val f = Fixture(this, PagedChannel(100))

        // O limite conhecido: com 40 mensagens depois dela e antes do horário da notificação, a página termina em m21.
        assertEquals(Located.Missing, f.store.locate("c1", "m20", f.notifiedAt(60), approximate = true))

        assertEquals(emptyList(), f.ids())
    }

    @Test
    fun falhaDoServidorSobeEDeixaOBancoComoEstava() = runTest {
        val server = PagedChannel(100).apply { failPages = true }
        val f = Fixture(this, server)

        assertFailsWith<ApiException> { f.store.locate("c1", "m50", f.notifiedAt(50), approximate = true) }

        assertEquals(emptyList(), f.ids())
    }

    @Test
    fun semOParametroOCaminhoExatoNaoMudou() = runTest {
        val f = Fixture(this, PagedChannel(200))

        // Com o horário exato, a primeira leitura é a crescente (o que veio depois dela), como antes.
        assertEquals(Located.Found(100), f.store.locate("c1", "m50", f.server.ts(50)))

        assertTrue("order=asc" in f.pages(0).first())
    }
}
