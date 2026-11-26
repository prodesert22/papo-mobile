package chat.papo.mobile

import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.NotificationItem
import chat.papo.mobile.core.MutationResult
import chat.papo.mobile.data.api.PapoJson
import chat.papo.mobile.data.ws.WsEvent
import chat.papo.mobile.ui.chat.MessageTarget
import chat.papo.mobile.ui.components.UiError
import chat.papo.mobile.ui.notifications.InboxViewModel
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * As ações da tela da caixa sobre o motor HTTP falso. O motor responde numa thread própria: cada espera é por uma
 * pós-condição (um pedido que chegou, uma navegação, o estado), nunca por tempo virtual.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InboxViewModelTest {
    private class Fixture(scope: TestScope, val server: NotificationServer) {
        val session: AccountSession = TestResources.session(server.engine, scope, userId = "u1")
        val vm = InboxViewModel(session)
        val opened = CopyOnWriteArrayList<Pair<String, MessageTarget>>()

        suspend fun load() {
            session.inbox.refresh()
        }

        fun row(id: String): NotificationItem = session.inbox.state.value.items.first { it.id == id }

        fun open(id: String) = vm.open(row(id)) { channel, target -> opened += channel to target }
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.fixture(server: NotificationServer = NotificationServer(pageSize = 2000)): Fixture {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return Fixture(this, server)
    }

    /** Põe a conversa no banco da sessão, como se já tivesse chegado da lista de canais. */
    private suspend fun Fixture.know(channelId: String) {
        session.chat.handle(WsEvent.ChannelCreated(channelId, name = "geral"))
        session.chat.channels.first { list -> list.any { it.id == channelId } }
    }

    @Test
    fun abrirUmaLinhaDeUmCanalConhecidoMarcaUmaVezENavegaComOInstanteAproximado() = runTest {
        val server = NotificationServer(pageSize = 2000).apply { add("n1", 1, channel = "c1", messageId = "m1"); add("n2", 2, channel = "c1") }
        val f = fixture(server)
        f.load()
        f.know("c1")

        f.open("n1")
        TestResources.waitUntil { f.opened.isNotEmpty() && server.reads.isNotEmpty() }
        runCurrent()

        assertEquals(listOf("c1" to MessageTarget("m1", server.ts(1), approximate = true)), f.opened.toList())
        assertEquals(listOf(listOf("n1")), server.reads.toList())
        assertEquals(0, server.count("GET /dms/")) // o canal já estava no aparelho
        TestResources.waitUntil { f.row("n1").read }
        assertFalse(f.row("n2").read)
    }

    @Test
    fun linhaJaLidaNaoFazPedidoDeLeitura() = runTest {
        val server = NotificationServer(pageSize = 2000).apply { add("n1", 1, channel = "c1", read = true) }
        val f = fixture(server)
        f.load()
        f.know("c1")

        f.open("n1")
        TestResources.waitUntil { f.opened.isNotEmpty() }
        runCurrent()

        assertEquals(0, server.reads.size)
    }

    @Test
    fun conversaDiretaFechadaFazUmGetPorIdGravaEAbre() = runTest {
        val server = NotificationServer(pageSize = 2000).apply {
            add("n1", 1, channel = "d9", messageId = "m1")
            dmBodies["d9"] = dmJson("d9")
        }
        val f = fixture(server)
        f.load()
        assertTrue(f.session.chat.dms.value.isEmpty())

        f.open("n1")
        TestResources.waitUntil { f.opened.isNotEmpty() }
        runCurrent()

        assertEquals(1, server.count("GET /dms/d9"))
        assertEquals(listOf("d9"), f.session.chat.dms.first { it.isNotEmpty() }.map { it.id })
        assertEquals("d9", f.opened.single().first)
        assertTrue(f.opened.single().second.approximate)
    }

    @Test
    fun conversaDiretaJaGuardadaNaoPedeOServidor() = runTest {
        val server = NotificationServer(pageSize = 2000).apply { add("n1", 1, channel = "d1") }
        val f = fixture(server)
        f.load()
        f.session.chat.handle(WsEvent.DmUpdated(PapoJson.decodeFromString(chat.papo.mobile.data.api.DirectConversationDto.serializer(), server.dmJson("d1"))))
        f.session.chat.dms.first { it.isNotEmpty() }

        f.open("n1")
        TestResources.waitUntil { f.opened.isNotEmpty() }

        assertEquals(0, server.count("GET /dms/"))
    }

    @Test
    fun conversaQueOServidorNaoTemMostraIndisponivelSemNavegarEMarcaALinha() = runTest {
        val server = NotificationServer(pageSize = 2000).apply { add("n1", 1, channel = "sumiu") }
        val f = fixture(server)
        f.load()

        f.open("n1")
        val state = f.vm.state.first { it.unavailable }
        TestResources.waitUntil { server.reads.isNotEmpty() }

        assertNull(state.openError)
        assertEquals(emptyList(), f.opened.toList())
        assertEquals(1, server.count("GET /dms/sumiu"))
        f.vm.dismissUnavailable()
        assertFalse(f.vm.state.value.unavailable)
    }

    @Test
    fun bloqueioEntreAsPessoasTambemEConversaIndisponivel() = runTest {
        val server = NotificationServer(pageSize = 2000).apply { add("n1", 1, channel = "d9"); dmStatus = 403 }
        val f = fixture(server)
        f.load()

        f.open("n1")

        assertTrue(f.vm.state.first { it.unavailable }.unavailable)
        assertEquals(emptyList(), f.opened.toList())
    }

    @Test
    fun falhaDoServidorNaoMarcaNemNavegaEDeixaTocarDeNovo() = runTest {
        val server = NotificationServer(pageSize = 2000).apply { add("n1", 1, channel = "d9"); dmStatus = 500 }
        val f = fixture(server)
        f.load()

        f.open("n1")
        val failed = f.vm.state.first { it.openError != null }

        assertIs<UiError.Server>(failed.openError)
        assertFalse(failed.unavailable)
        assertNull(failed.opening)
        assertEquals(0, server.reads.size)
        assertEquals(emptyList(), f.opened.toList())

        server.dmStatus = null
        server.dmBodies["d9"] = server.dmJson("d9")
        f.vm.dismissOpenError()
        f.open("n1")
        TestResources.waitUntil { f.opened.isNotEmpty() }
        assertEquals(2, server.count("GET /dms/d9"))
    }

    @Test
    fun enquantoUmaLinhaAbreOutroToqueNaoFazOutroPedido() = runTest {
        val server = NotificationServer(pageSize = 2000).apply {
            add("n1", 1, channel = "d9"); add("n2", 2, channel = "d8")
            dmBodies["d9"] = dmJson("d9")
            holdDm = CompletableDeferred()
        }
        val f = fixture(server)
        f.load()

        f.open("n1")
        f.vm.state.first { it.opening == "n1" }
        TestResources.waitUntil { server.count("GET /dms/") == 1 }
        f.open("n2")
        runCurrent()
        server.holdDm?.complete(Unit)
        TestResources.waitUntil { f.opened.isNotEmpty() }

        assertEquals(1, server.count("GET /dms/"))
        assertEquals(listOf("d9"), f.opened.map { it.first })
    }

    @Test
    fun marcarTodasUsaBlocosDeMil() = runTest {
        val server = NotificationServer(pageSize = 2000).apply { repeat(1001) { add("n%04d".format(it), it) } }
        val f = fixture(server)
        f.load()
        assertEquals(1001, f.session.inbox.state.value.items.size)

        f.vm.markAllRead()
        f.vm.state.first { !it.markingAll && server.reads.size == 2 }

        assertEquals(listOf(1000, 1), server.reads.map { it.size })
        assertTrue(f.session.inbox.state.value.items.all { it.read })
        assertNull(f.vm.state.value.markFailure)
    }

    @Test
    fun marcarTodasSoMandaAsNaoLidasCarregadas() = runTest {
        val server = NotificationServer(pageSize = 2000).apply { add("a", 1); add("b", 2, read = true); add("c", 3) }
        val f = fixture(server)
        f.load()

        f.vm.markAllRead()
        f.vm.state.first { !it.markingAll && server.reads.isNotEmpty() }

        assertEquals(setOf("a", "c"), server.reads.single().toSet())
    }

    @Test
    fun marcarTodasSemNadaNaoLidoNaoFazPedido() = runTest {
        val server = NotificationServer(pageSize = 2000).apply { add("a", 1, read = true) }
        val f = fixture(server)
        f.load()

        f.vm.markAllRead()
        runCurrent()

        assertEquals(0, server.reads.size)
        assertFalse(f.vm.state.value.markingAll)
    }

    @Test
    fun marcarTodasComLimiteDeTaxaMostraAFalhaENaoRepete() = runTest {
        val server = NotificationServer(pageSize = 2000).apply { add("a", 1); readStatus = 429 }
        val f = fixture(server)
        f.load()

        f.vm.markAllRead()
        val state = f.vm.state.first { !it.markingAll && it.markFailure != null }

        assertEquals(MutationResult.RateLimited, state.markFailure)
        assertEquals(1, server.reads.size)
        assertFalse(f.row("a").read)
    }

    @Test
    fun tentarDeNovoFazUmaLeituraSoEOErroSome() = runTest {
        val server = NotificationServer(pageSize = 2000).apply { add("a", 1); listStatus = 500 }
        val f = fixture(server)
        f.load()
        assertTrue(f.session.inbox.state.value.error != null)
        assertEquals(1, server.listRequests.size)

        server.listStatus = null
        f.vm.retry()
        f.session.inbox.state.first { it.loaded && it.error == null }

        assertEquals(2, server.listRequests.size)
        assertEquals(listOf("a"), f.session.inbox.state.value.items.map { it.id })
    }

    @Test
    fun carregarMaisFazUmaLeituraDaProximaPagina() = runTest {
        val server = NotificationServer(pageSize = 1).apply { add("a", 2, read = true); add("b", 1, read = true) }
        val f = fixture(server)
        f.load()

        f.vm.loadMore()
        f.session.inbox.state.first { it.items.size == 2 }

        assertEquals(2, server.listRequests.size)
    }
}
