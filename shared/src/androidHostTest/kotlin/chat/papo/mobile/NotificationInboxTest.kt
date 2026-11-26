package chat.papo.mobile

import chat.papo.mobile.chat.InboxState
import chat.papo.mobile.chat.MessagePosition
import chat.papo.mobile.chat.NotificationInbox
import chat.papo.mobile.chat.ReadMark
import chat.papo.mobile.core.MutationResult
import chat.papo.mobile.data.api.ApiException
import chat.papo.mobile.data.api.ChatApi
import chat.papo.mobile.data.api.MemoryCookieStore
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.data.ws.WsEvent
import chat.papo.mobile.data.ws.WsState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException

/**
 * A caixa de notificações sobre o motor HTTP falso. O motor responde numa thread própria, então cada espera é por uma
 * pós-condição do estado, e o relógio virtual só avança para o que o próprio teste mede (o debounce de 1 s).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationInboxTest {
    private class Fixture(scope: TestScope, val server: NotificationServer, userId: String = "u1") {
        val events = MutableSharedFlow<WsEvent>(extraBufferCapacity = 64)
        val connection = MutableStateFlow(WsState.Disconnected)
        val marks = MutableSharedFlow<ReadMark>(extraBufferCapacity = 64)
        val positions = HashMap<String, MessagePosition>()
        var positionLookups = 0
        var positionsFail = false
        val inbox = NotificationInbox(
            userId = userId,
            api = ChatApi(PapoClient(server.engine, ServerUrl("https://papo.test"), MemoryCookieStore("jwt"))),
            events = events,
            connection = connection,
            readMarks = marks,
            positionsOf = { ids ->
                positionLookups++
                if (positionsFail) throw java.io.IOException("banco indisponível")
                ids.mapNotNull { id -> positions[id]?.let { id to it } }.toMap()
            },
            scope = scope.backgroundScope,
        )

        suspend fun state(condition: (InboxState) -> Boolean) = inbox.state.first(condition)

        suspend fun ids() = inbox.state.value.items.map { it.id }
    }

    private fun TestScope.fixture(server: NotificationServer = NotificationServer()) = Fixture(this, server)

    /** Liga a caixa e a conexão, e espera a primeira leitura. */
    private suspend fun TestScope.connected(f: Fixture) {
        f.inbox.start()
        runCurrent()
        f.connection.value = WsState.Connected
        f.state { it.loaded && !it.loading }
    }

    private fun newNotification(id: String = "efemero") = WsEvent.NewNotification(id = id, authorId = "u2", messageId = "m-x", content = "oi")

    // Leitura

    @Test
    fun conectarLeAPrimeiraPaginaEMostraAsLinhasDaMaisNovaParaAMaisAntiga() = runTest {
        val server = NotificationServer().apply { add("a", 1); add("b", 3); add("c", 2, read = true) }
        val f = fixture(server)

        connected(f)

        assertEquals(listOf("b", "c", "a"), f.ids())
        assertEquals(listOf(false, true, false), f.inbox.state.value.items.map { it.read })
        val state = f.inbox.state.value
        assertFalse(state.hasMore || state.capped || state.loading)
        assertNull(state.error)
        assertEquals(1, server.listRequests.size)
        assertEquals("GET /users/u1/notifications?order=desc", server.listRequests.single())
    }

    @Test
    fun cadaConexaoNovaLeDeNovoDesdeAPrimeiraPagina() = runTest {
        val server = NotificationServer().apply { add("a", 1) }
        val f = fixture(server)
        connected(f)

        server.add("b", 2)
        f.connection.value = WsState.Disconnected
        runCurrent()
        f.connection.value = WsState.Connected
        f.state { it.items.size == 2 && !it.loading }

        assertEquals(listOf("b", "a"), f.ids())
        assertEquals(2, server.listRequests.size)
        assertTrue("since" !in server.listRequests.last())
    }

    @Test
    fun comecarDuasVezesNaoLeDuasVezes() = runTest {
        val server = NotificationServer().apply { add("a", 1) }
        val f = fixture(server)
        f.inbox.start()
        f.inbox.start()
        runCurrent()
        f.connection.value = WsState.Connected
        f.state { it.loaded && !it.loading }

        assertEquals(1, server.listRequests.size)
    }

    @Test
    fun continuaAteUmaPaginaSemNaoLidas() = runTest {
        // Páginas de 2: [b5 b4] não lidas, [b3 b2] uma não lida, [b1 b0] lidas: a terceira para a leitura.
        val server = NotificationServer(pageSize = 2).apply {
            add("n6", 6); add("n5", 5); add("n4", 4); add("n3", 3, read = true); add("n2", 2, read = true); add("n1", 1, read = true)
            add("n0", 0, read = true)
        }
        val f = fixture(server)

        connected(f)

        assertEquals(3, server.listRequests.size)
        assertTrue("last_id=n5" in server.listRequests[1] && "since=${server.ts(5)}" in server.listRequests[1], server.listRequests[1])
        assertEquals(listOf("n6", "n5", "n4", "n3", "n2", "n1"), f.ids())
        val state = f.inbox.state.value
        assertTrue(state.hasMore) // ainda há n0 no servidor
        assertFalse(state.capped)
    }

    @Test
    fun paraQuandoOServidorNaoTemMais() = runTest {
        val server = NotificationServer(pageSize = 2).apply { add("a", 3); add("b", 2); add("c", 1) }
        val f = fixture(server)

        connected(f)

        assertEquals(2, server.listRequests.size)
        assertEquals(listOf("a", "b", "c"), f.ids())
        assertFalse(f.inbox.state.value.hasMore || f.inbox.state.value.capped)
    }

    @Test
    fun paraNaPrimeiraPaginaQuandoNaoHaNenhumaLinha() = runTest {
        val f = fixture()

        connected(f)

        assertEquals(emptyList(), f.ids())
        assertEquals(1, f.server.listRequests.size)
        assertTrue(f.inbox.state.value.loaded)
    }

    @Test
    fun paraEmCincoPaginasComNaoLidasEMarcaComoCortada() = runTest {
        val server = NotificationServer(pageSize = 2).apply { repeat(12) { add("n%02d".format(it), it) } }
        val f = fixture(server)

        connected(f)

        assertEquals(5, server.listRequests.size)
        assertEquals(10, f.inbox.state.value.items.size)
        assertTrue(f.inbox.state.value.capped)
        assertTrue(f.inbox.state.value.hasMore)
    }

    @Test
    fun quintaPaginaSemMaisLinhasNaoEhCorte() = runTest {
        val server = NotificationServer(pageSize = 2).apply { repeat(10) { add("n%02d".format(it), it) } }
        val f = fixture(server)

        connected(f)

        assertEquals(5, server.listRequests.size)
        assertFalse(f.inbox.state.value.capped)
        assertFalse(f.inbox.state.value.hasMore)
    }

    @Test
    fun carregarMaisLeAPaginaSeguinteAPartirDaMaisAntiga() = runTest {
        val server = NotificationServer(pageSize = 2).apply {
            add("n4", 4, read = true); add("n3", 3, read = true); add("n2", 2, read = true); add("n1", 1, read = true); add("n0", 0)
        }
        val f = fixture(server)
        connected(f)
        assertEquals(listOf("n4", "n3"), f.ids())
        assertTrue(f.inbox.state.value.hasMore)

        f.inbox.loadMore()

        assertEquals(listOf("n4", "n3", "n2", "n1"), f.ids())
        assertTrue("last_id=n3" in server.listRequests.last())
        assertTrue(f.inbox.state.value.hasMore)
        assertFalse(f.inbox.state.value.capped) // a página nova não tem não lidas

        f.inbox.loadMore()

        assertEquals(listOf("n4", "n3", "n2", "n1", "n0"), f.ids())
        assertFalse(f.inbox.state.value.hasMore)
    }

    @Test
    fun carregarMaisComNaoLidasNaPaginaMantemOCorte() = runTest {
        val server = NotificationServer(pageSize = 2).apply { repeat(14) { add("n%02d".format(it), it) } }
        val f = fixture(server)
        connected(f) // 5 páginas, cortada

        f.inbox.loadMore()

        assertEquals(12, f.inbox.state.value.items.size)
        assertTrue(f.inbox.state.value.capped)
    }

    @Test
    fun carregarMaisSemNadaAMaisNaoFazRequisicao() = runTest {
        val server = NotificationServer().apply { add("a", 1) }
        val f = fixture(server)
        connected(f)

        f.inbox.loadMore()

        assertEquals(1, server.listRequests.size)
    }

    @Test
    fun carregarMaisAntesDeLerNadaNaoFazRequisicao() = runTest {
        val f = fixture()
        f.inbox.loadMore()
        assertEquals(0, f.server.requests.size)
    }

    @Test
    fun falhaNaLeituraFicaNoEstadoSemApagarOQueHaviaENadaERepetidoSozinho() = runTest {
        val server = NotificationServer().apply { add("a", 1) }
        val f = fixture(server)
        connected(f)
        server.listStatus = 429

        f.inbox.refresh()

        val failed = f.inbox.state.value
        assertEquals(429, assertIs<ApiException>(failed.error).status)
        assertEquals(listOf("a"), f.ids())
        assertFalse(failed.loading)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, server.listRequests.size)

        server.listStatus = null
        f.inbox.refresh()
        assertNull(f.inbox.state.value.error)
    }

    @Test
    fun falhaNaPrimeiraLeituraDeixaACaixaNaoCarregada() = runTest {
        val server = NotificationServer().apply { listStatus = 500 }
        val f = fixture(server)
        f.inbox.start()
        runCurrent()
        f.connection.value = WsState.Connected
        f.state { it.error != null && !it.loading }

        assertFalse(f.inbox.state.value.loaded)
        assertEquals(emptyList(), f.ids())
    }

    @Test
    fun falhaNoCarregarMaisMantemAsLinhasEMostraOErro() = runTest {
        val server = NotificationServer(pageSize = 1).apply { add("a", 2, read = true); add("b", 1, read = true) }
        val f = fixture(server)
        connected(f)
        server.listStatus = 500

        f.inbox.loadMore()

        assertEquals(listOf("a"), f.ids())
        assertTrue(f.inbox.state.value.error != null)
        assertTrue(f.inbox.state.value.hasMore)
    }

    @Test
    fun cancelarALeituraRelancaOCancelamentoESoltaOCarregando() = runTest {
        val server = NotificationServer().apply { add("a", 1); holdList = CompletableDeferred() }
        val f = fixture(server)

        val refresh = async { f.inbox.refresh() }
        f.state { it.loading }
        refresh.cancel()

        assertFailsWith<CancellationException> { refresh.await() }
        assertFalse(f.inbox.state.value.loading)
        assertNull(f.inbox.state.value.error)
        assertEquals(emptyList(), f.ids())
    }

    // new_notification

    @Test
    fun oEventoSozinhoNaoCriaLinhaNemAntesNemDepoisDaLeitura() = runTest {
        val f = fixture()
        connected(f)

        f.events.emit(newNotification("id-do-evento"))
        runCurrent()

        assertEquals(emptyList(), f.ids()) // antes de o debounce vencer, não há linha nem pedido
        assertEquals(1, f.server.listRequests.size)

        advanceTimeBy(1_001)
        runCurrent()
        f.state { it.loaded && !it.loading && f.server.listRequests.size == 2 }

        assertEquals(emptyList(), f.ids()) // o servidor não tem linha para um evento sem linha
    }

    @Test
    fun idEfemeroAusenteDaListaNuncaAparece() = runTest {
        val server = NotificationServer().apply { add("real", 1) }
        val f = fixture(server)
        connected(f)

        f.events.emit(newNotification("efemero-sem-linha"))
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        f.state { server.listRequests.size == 2 && !it.loading }

        assertEquals(listOf("real"), f.ids())
    }

    @Test
    fun tresEventosEmUmSegundoFazemUmaLeituraDasMaisNovasSemLastId() = runTest {
        val server = NotificationServer().apply { add("a", 1) }
        val f = fixture(server)
        connected(f)
        server.add("b", 5)

        f.events.emit(newNotification("e1"))
        runCurrent()
        advanceTimeBy(300)
        f.events.emit(newNotification("e2"))
        runCurrent()
        advanceTimeBy(300)
        f.events.emit(newNotification("e3"))
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        f.state { "b" in f.inbox.state.value.items.map { it.id } && !it.loading }

        assertEquals(listOf("b", "a"), f.ids())
        assertEquals(2, server.listRequests.size) // a primeira página da conexão e uma das novas
        val newer = server.listRequests.last()
        assertTrue("since=${server.ts(1)}" in newer, newer)
        assertTrue("last_id" !in newer, newer)

        // Nada mais é pedido sozinho depois disso.
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, server.listRequests.size)
    }

    @Test
    fun eventosDeDepoisDaLeituraPedemOutra() = runTest {
        val server = NotificationServer().apply { add("a", 1) }
        val f = fixture(server)
        connected(f)

        f.events.emit(newNotification("e1"))
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        f.state { server.listRequests.size == 2 && !it.loading }
        server.add("b", 9)
        f.events.emit(newNotification("e2"))
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        f.state { server.listRequests.size == 3 && !it.loading }

        assertEquals(listOf("b", "a"), f.ids())
    }

    @Test
    fun semLinhaNenhumaOEventoPedeAPrimeiraPaginaInteira() = runTest {
        val f = fixture()
        connected(f)
        f.server.add("a", 1)

        f.events.emit(newNotification())
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        f.state { "a" in f.inbox.state.value.items.map { it.id } && !it.loading }

        assertTrue("since" !in f.server.listRequests.last())
    }

    @Test
    fun maisDeUmaPaginaDeNovasRecarregaAPrimeiraPagina() = runTest {
        val server = NotificationServer(pageSize = 2).apply { add("a", 1, read = true) }
        val f = fixture(server)
        connected(f)
        listOf("b" to 2, "c" to 3, "d" to 4).forEach { (id, n) -> server.add(id, n) }

        f.events.emit(newNotification())
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        f.state { f.inbox.state.value.items.size >= 4 && !it.loading }

        // Primeira leitura da conexão, as novas (has_more) e a recarga desde o começo, que segue até a página sem não lidas.
        assertEquals(listOf("d", "c", "b", "a"), f.ids())
        assertTrue(server.listRequests.drop(1).first().contains("since=${server.ts(1)}"))
        assertTrue(server.listRequests.drop(2).first().let { "since" !in it })
    }

    @Test
    fun copiaAtrasadaDaListaNaoDesfazALeituraLocal() = runTest {
        val server = NotificationServer().apply { applyReads = false; add("a", 1); add("b", 2) }
        val f = fixture(server)
        connected(f)
        assertEquals(MutationResult.Done, f.inbox.markRead(listOf("a")))
        assertEquals(listOf(false, true), f.inbox.state.value.items.map { it.read })

        // O servidor ainda mostra "a" como não lida (a leitura saiu antes de o PUT valer).
        f.inbox.refresh()

        assertEquals(listOf(false, true), f.inbox.state.value.items.map { it.read })
    }

    // Marcar como lida

    @Test
    fun marcarComoLidaMandaOPutEMarcaAquiComOs200() = runTest {
        val server = NotificationServer().apply { add("a", 1); add("b", 2); add("c", 3) }
        val f = fixture(server)
        connected(f)

        val result = f.inbox.markRead(listOf("a", "c"))

        assertEquals(MutationResult.Done, result)
        assertEquals(listOf(listOf("a", "c")), server.reads)
        assertEquals(listOf("c" to true, "b" to false, "a" to true), f.inbox.state.value.items.map { it.id to it.read })
        assertNull(f.inbox.state.value.error)
    }

    @Test
    fun marcarComo404EJaLidaEMarcaAqui() = runTest {
        val server = NotificationServer().apply { add("a", 1); readStatus = 404 }
        val f = fixture(server)
        connected(f)

        val result = f.inbox.markRead(listOf("a"))

        assertEquals(MutationResult.Done, result)
        assertTrue(f.inbox.state.value.items.single().read)
        assertNull(f.inbox.state.value.error)
        assertEquals(1, server.reads.size)
    }

    @Test
    fun marcarComo429FazUmPutNaoMudaNadaAquiENaoRepete() = runTest {
        val server = NotificationServer().apply { add("a", 1); readStatus = 429 }
        val f = fixture(server)
        connected(f)

        val result = f.inbox.markRead(listOf("a"))
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(MutationResult.RateLimited, result)
        assertEquals(1, server.reads.size)
        assertFalse(f.inbox.state.value.items.single().read)
        assertEquals(429, assertIs<ApiException>(f.inbox.state.value.error).status)
    }

    @Test
    fun marcarComoOutroErroDeServidorNaoMarcaEClassificaOResultado() = runTest {
        val server = NotificationServer().apply { add("a", 1); readStatus = 500 }
        val f = fixture(server)
        connected(f)

        assertEquals(MutationResult.Uncertain, f.inbox.markRead(listOf("a")))
        server.readStatus = 400
        assertIs<MutationResult.Rejected>(f.inbox.markRead(listOf("a")))
        assertFalse(f.inbox.state.value.items.single().read)
    }

    @Test
    fun marcarComAConexaoQueCaiuNaoMarcaEDizQueFicouIncerto() = runTest {
        val server = NotificationServer().apply { add("a", 1) }
        val f = fixture(server)
        connected(f)
        server.readDropped = true

        assertEquals(MutationResult.Uncertain, f.inbox.markRead(listOf("a")))

        assertFalse(f.inbox.state.value.items.single().read)
        assertIs<java.io.IOException>(f.inbox.state.value.error)
        assertEquals(1, server.reads.size)
    }

    @Test
    fun umPutQueDeuCertoLimpaOErroAnterior() = runTest {
        val server = NotificationServer().apply { add("a", 1); readStatus = 429 }
        val f = fixture(server)
        connected(f)
        f.inbox.markRead(listOf("a"))
        assertTrue(f.inbox.state.value.error != null)

        server.readStatus = 200
        f.inbox.markRead(listOf("a"))

        assertNull(f.inbox.state.value.error)
        assertTrue(f.inbox.state.value.items.single().read)
    }

    @Test
    fun marcarMilEUmaVaiEmDoisBlocosEUmaFalhaParaOsSeguintes() = runTest {
        val server = NotificationServer(pageSize = 5).apply { add("a", 1) }
        val f = fixture(server)
        connected(f)
        val ids = List(1001) { "x$it" }

        f.inbox.markRead(ids)
        assertEquals(listOf(1000, 1), server.reads.map { it.size })

        server.reads.clear()
        server.readStatus = 429
        assertEquals(MutationResult.RateLimited, f.inbox.markRead(ids))
        assertEquals(listOf(1000), server.reads.map { it.size }) // o segundo bloco nem sai
    }

    @Test
    fun marcarSemIdsNaoFazRequisicao() = runTest {
        val f = fixture()
        assertEquals(MutationResult.Done, f.inbox.markRead(emptyList()))
        assertEquals(0, f.server.requests.size)
    }

    @Test
    fun cancelarOMarcarRelancaOCancelamentoESemMarcarNemMostrarErro() = runTest {
        val server = NotificationServer().apply { add("a", 1); holdRead = CompletableDeferred() }
        val f = fixture(server)
        connected(f)

        val job = async { f.inbox.markRead(listOf("a")) }
        TestResources.waitUntil { server.reads.size == 1 }
        job.cancel()

        assertFailsWith<CancellationException> { job.await() }
        assertFalse(f.inbox.state.value.items.single().read)
        assertNull(f.inbox.state.value.error)
    }

    // Leitura do canal

    @Test
    fun lerOCanalAteUmaMensagemMarcaAsLinhasDeleAteALaESoElas() = runTest {
        val server = NotificationServer().apply {
            add("n1", 1, channel = "c1", messageId = "m1"); add("n2", 2, channel = "c1", messageId = "m2"); add("n3", 3, channel = "c1", messageId = "m3")
            add("o1", 4, channel = "c2", messageId = "m1")
        }
        val f = fixture(server)
        f.positions += mapOf("m1" to MessagePosition(10, "m1"), "m2" to MessagePosition(20, "m2"), "m3" to MessagePosition(30, "m3"))
        connected(f)

        f.marks.emit(ReadMark("c1", "m2", server.ts(2)))
        f.state { s -> s.items.any { it.read } }

        assertEquals(listOf("o1" to false, "n3" to false, "n2" to true, "n1" to true), f.inbox.state.value.items.map { it.id to it.read })
        assertEquals(1, server.listRequests.size) // espelho local: nenhum pedido
        assertEquals(0, server.reads.size)
    }

    @Test
    fun lerUmCanalSemLinhasNaoLidasNemConsultaAsPosicoes() = runTest {
        val server = NotificationServer().apply { add("n1", 1, channel = "c1", read = true); add("n2", 2, channel = "c2") }
        val f = fixture(server)
        connected(f)

        f.marks.emit(ReadMark("c1", "m1", null))
        f.marks.emit(ReadMark("c9", "m1", null))
        runCurrent()

        assertEquals(0, f.positionLookups)
        assertEquals(listOf(false, true), f.inbox.state.value.items.map { it.read })
    }

    @Test
    fun seAsPosicoesFalhamAMarcaNaoMudaNadaEAsProximasContinuam() = runTest {
        val server = NotificationServer().apply { add("n1", 1, messageId = "m1") }
        val f = fixture(server)
        f.positions["m1"] = MessagePosition(10, "m1")
        connected(f)
        f.positionsFail = true

        f.marks.emit(ReadMark("c1", "m1", server.ts(1)))
        runCurrent()
        assertFalse(f.inbox.state.value.items.single().read)

        f.positionsFail = false
        f.marks.emit(ReadMark("c1", "m1", server.ts(1)))
        f.state { s -> s.items.any { it.read } }
        assertTrue(f.inbox.state.value.items.single().read)
    }

    // Mensagem apagada

    @Test
    fun mensagemApagadaTiraAsLinhasDela() = runTest {
        val server = NotificationServer().apply { add("a", 1, messageId = "m1"); add("b", 2, messageId = "m2"); add("c", 3, messageId = "m1") }
        val f = fixture(server)
        connected(f)

        f.events.emit(WsEvent.MessageDeleted("m1", "c1"))
        f.state { it.items.size == 1 }

        assertEquals(listOf("b"), f.ids())
        assertEquals(1, server.listRequests.size) // nada foi pedido
    }

    @Test
    fun apagarMensagemSemLinhaNaoMudaOEstado() = runTest {
        val server = NotificationServer().apply { add("a", 1) }
        val f = fixture(server)
        connected(f)
        val before = f.inbox.state.value

        f.events.emit(WsEvent.MessageDeleted("outra", "c1"))
        f.events.emit(WsEvent.AvatarUpdated("u2")) // evento que a caixa não usa
        runCurrent()

        assertEquals(before, f.inbox.state.value)
    }

    // Contas

    @Test
    fun duasContasNoMesmoServidorTemCaixasSeparadas() = runTest {
        val first = NotificationServer("u1").apply { add("a1", 1, content = "da primeira") }
        val second = NotificationServer("u2").apply { add("b1", 1, content = "da segunda"); add("b2", 2) }
        val s1 = TestResources.session(first.engine, this, userId = "u1")
        val s2 = TestResources.session(second.engine, this, userId = "u2")

        s1.inbox.refresh()
        s2.inbox.refresh()

        assertEquals(listOf("a1"), s1.inbox.state.value.items.map { it.id })
        assertEquals(listOf("b2", "b1"), s2.inbox.state.value.items.map { it.id })
        s2.inbox.markRead(listOf("b1"))
        assertFalse(s1.inbox.state.value.items.single().read)
        assertEquals(0, first.reads.size)
        assertEquals(listOf(listOf("b1")), second.reads)
        assertTrue(first.requests.all { "/users/u1/" in it } && second.requests.all { "/users/u2/" in it })
    }
}
