package chat.papo.mobile

import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.data.api.MemoryCookieStore
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.media.ByteBudgetExceeded
import chat.papo.mobile.media.POSTER_MAX_PX
import chat.papo.mobile.media.POSTER_PARALLEL
import chat.papo.mobile.media.POSTER_SETTLE_MS
import chat.papo.mobile.media.RangeReader
import chat.papo.mobile.media.RequestPacer
import chat.papo.mobile.media.VideoPosterExtractor
import chat.papo.mobile.media.VideoPosters
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A fila das capas de uma conta: poucas extrações por vez, sem repetir o que falhou, esperando o visualizador, sempre pelo
 * cliente da própria conta e com o ritmo de pedidos dividido entre elas. O extrator é um falso; o decodificador de verdade
 * só roda no aparelho.
 */
class VideoPostersTest {
    private val server = ServerUrl("https://papo.test")
    private val jpeg = byteArrayOf(1, 2, 3)
    private val settle = 400L
    private val clients = mutableListOf<PapoClient>()

    private fun client(cookie: String = "jwt-ana", seen: MutableList<HttpRequestData> = mutableListOf()) = PapoClient(
        MockEngine { request ->
            seen += request
            respond(ByteArray(100), HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentRange, "bytes 0-99/1000"))
        },
        server,
        MemoryCookieStore(cookie),
    ).also(clients::add)

    private fun posters(
        extractor: VideoPosterExtractor?,
        client: PapoClient = client(),
        parallel: Int = 1,
        pacer: RequestPacer = RequestPacer(0),
    ) = VideoPosters(client, extractor, settleMs = settle, blockBytes = 100, budgetBytes = 300, parallel = parallel, pacer = pacer)

    /** Um extrator falso: [body] roda a cada extração e devolve o que o decodificador devolveria. */
    private class FakeExtractor(private val body: suspend FakeExtractor.(RangeReader) -> ByteArray?) : VideoPosterExtractor {
        val readers = mutableListOf<RangeReader>()
        val maxPx = mutableListOf<Int>()
        var active = 0
        var maxActive = 0
        val calls get() = readers.size

        override suspend fun extract(reader: RangeReader, maxPx: Int): ByteArray? {
            readers += reader
            this.maxPx += maxPx
            active++
            maxActive = maxOf(maxActive, active)
            try {
                return body(reader)
            } finally {
                active--
            }
        }
    }

    private suspend fun VideoPosters.estaSendoFeita(id: String = "att-1") = making(id).first()

    /** A capa do anexo [id] com [size] bytes, como o cartão a pede: pelo endereço do anexo no servidor. */
    private suspend fun VideoPosters.capa(id: String = "att-1", size: Long = 1_000) =
        poster(id, server.endpoint("/attachments/$id"), size)

    // Caminho feliz e cliente da conta

    @Test
    fun semExtratorNaoHaCapaNemPedido() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val posters = posters(null, client(seen = seen))

        assertNull(posters.capa())

        assertEquals(0, seen.size)
        assertEquals(0L, testScheduler.currentTime) // nem esperou o tempo de assentar
    }

    @Test
    fun devolveOQuadroDoExtratorDepoisDeAssentar() = runTest {
        val extractor = FakeExtractor { jpeg }
        val posters = posters(extractor)

        val result = async { posters.capa() }
        advanceTimeBy(settle - 1)
        runCurrent()
        assertEquals(0, extractor.calls)

        advanceUntilIdle()

        assertContentEquals(jpeg, result.await())
        assertEquals(listOf(POSTER_MAX_PX), extractor.maxPx)
    }

    @Test
    fun oLeitorEntregueAoExtratorTemOTamanhoDoAnexoEPedeTrechosPeloClienteDaConta() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val extractor = FakeExtractor { reader ->
            assertEquals(1_000L, reader.size)
            assertEquals(10, reader.readAt(250, ByteArray(10), 0, 10))
            jpeg
        }

        assertContentEquals(jpeg, posters(extractor, client("jwt-ana", seen)).capa("att-1", 1_000))

        val request = seen.single()
        assertEquals("https://papo.test/attachments/att-1", request.url.toString())
        assertEquals("bytes=200-299", request.headers[HttpHeaders.Range])
        assertEquals("Auth=jwt-ana", request.headers[HttpHeaders.Cookie])
    }

    @Test
    fun oOrcamentoDoLeitorValeParaCadaExtracaoELogoOVideoFicaSemCapa() = runTest {
        val extractor = FakeExtractor { reader ->
            // Orçamento de 300 bytes em blocos de 100: o quarto bloco estoura.
            for (block in 0..3) reader.readAt(block * 100L, ByteArray(10), 0, 10)
            jpeg
        }
        val posters = posters(extractor)

        assertNull(posters.capa())
        assertNull(posters.capa()) // e não tenta de novo

        assertEquals(1, extractor.calls)
        assertEquals(300L, extractor.readers.single().fetchedBytes)
    }

    // Falhas e tentativa única por sessão

    @Test
    fun quemFalhouNaoEhTentadoDeNovoNaSessaoMasOutroAnexoEh() = runTest {
        val extractor = FakeExtractor { null }
        val posters = posters(extractor)

        assertNull(posters.capa("att-1"))
        assertNull(posters.capa("att-1"))
        assertNull(posters.capa("att-1"))
        assertEquals(1, extractor.calls)

        assertNull(posters.capa("att-2"))
        assertEquals(2, extractor.calls)
    }

    @Test
    fun falhaDoExtratorViraSemCapaEContaComoFalha() = runTest {
        val extractor = FakeExtractor { error("codec quebrou") }
        val posters = posters(extractor)

        assertNull(posters.capa())
        assertNull(posters.capa())

        assertEquals(1, extractor.calls)
    }

    @Test
    fun falhaNaoPrendeAFilaDosOutros() = runTest {
        val extractor = FakeExtractor { reader -> if (reader.size == 1L) error("quebrou") else jpeg }
        val posters = posters(extractor)

        assertNull(posters.capa("att-1", size = 1))
        assertContentEquals(jpeg, posters.capa("att-2", size = 1_000))
    }

    @Test
    fun cancelarNaoViraFalhaEAProximaVezTentaDeNovo() = runTest {
        val gate = CompletableDeferred<Unit>()
        val extractor = FakeExtractor { gate.await(); jpeg }
        val posters = posters(extractor)

        val first = async { posters.capa() }
        advanceTimeBy(settle + 1)
        runCurrent()
        assertEquals(1, extractor.calls)
        first.cancel() // o cartão saiu da tela no meio da extração
        runCurrent()
        assertEquals(0, extractor.active)

        gate.complete(Unit)
        val second = async { posters.capa() }
        advanceUntilIdle()

        assertContentEquals(jpeg, second.await())
        assertEquals(2, extractor.calls)
    }

    // Quando começa

    @Test
    fun cartaoQueSaiAntesDeAssentarNaoGastaPedidoNenhum() = runTest {
        val extractor = FakeExtractor { jpeg }
        val posters = posters(extractor)

        val passing = async { posters.capa() }
        advanceTimeBy(settle - 1)
        passing.cancel()
        advanceUntilIdle()

        assertEquals(0, extractor.calls)
        // Sair não é falhar: voltando, o cartão pede a capa.
        assertContentEquals(jpeg, posters.capa())
        assertEquals(1, extractor.calls)
    }

    // Uma por vez

    @Test
    fun umaExtracaoPorVezEmOrdemDeChegada() = runTest {
        val order = mutableListOf<Long>()
        val extractor = FakeExtractor { reader ->
            order += reader.size
            delay(1_000)
            jpeg
        }
        val posters = posters(extractor)

        val results = listOf(1L, 2L, 3L).map { size -> async { posters.capa("att-$size", size = size) } }
        advanceUntilIdle()

        results.forEach { assertContentEquals(jpeg, it.await()) }
        assertEquals(1, extractor.maxActive)
        assertEquals(listOf(1L, 2L, 3L), order)
        assertEquals(settle + 3 * 1_000, testScheduler.currentTime)
    }

    @Test
    fun quemSaiDaFilaNaoExtrai() = runTest {
        val gate = CompletableDeferred<Unit>()
        val extractor = FakeExtractor { reader -> if (reader.size == 1L) gate.await(); jpeg }
        val posters = posters(extractor)

        val running = async { posters.capa("att-1", size = 1) }
        val waiting = async { posters.capa("att-2", size = 2) }
        val last = async { posters.capa("att-3", size = 3) }
        advanceTimeBy(settle + 1)
        runCurrent()
        assertEquals(1, extractor.calls)

        waiting.cancel() // o cartão rolou para fora enquanto esperava a vez
        gate.complete(Unit)
        advanceUntilIdle()

        assertContentEquals(jpeg, running.await())
        assertContentEquals(jpeg, last.await())
        assertEquals(listOf(1L, 3L), extractor.readers.map { it.size })
    }

    // Em paralelo

    @Test
    fun comDuasVagasDuasExtracoesRodamJuntasEATerceiraEsperaUmaVaga() = runTest {
        val gates = mapOf(1L to CompletableDeferred<Unit>(), 2L to CompletableDeferred<Unit>())
        val order = mutableListOf<Long>()
        val extractor = FakeExtractor { reader ->
            order += reader.size
            gates[reader.size]?.await()
            jpeg
        }
        val posters = posters(extractor, parallel = 2)

        val results = listOf(1L, 2L, 3L).map { size -> async { posters.capa("att-$size", size = size) } }
        advanceTimeBy(settle + 1)
        runCurrent()
        assertEquals(listOf(1L, 2L), order) // a terceira espera uma vaga
        assertEquals(2, extractor.active)

        gates.getValue(2L).complete(Unit)
        runCurrent()
        assertEquals(listOf(1L, 2L, 3L), order)
        gates.getValue(1L).complete(Unit)
        advanceUntilIdle()

        results.forEach { assertContentEquals(jpeg, it.await()) }
        assertEquals(2, extractor.maxActive)
    }

    @Test
    fun falharNumaExtracaoParalelaNaoAtrapalhaAOutra() = runTest {
        val extractor = FakeExtractor { reader -> if (reader.size == 1L) error("quebrou") else jpeg }
        val posters = posters(extractor, parallel = 2)

        val broken = async { posters.capa("att-1", size = 1) }
        val fine = async { posters.capa("att-2", size = 2) }
        advanceUntilIdle()

        assertNull(broken.await())
        assertContentEquals(jpeg, fine.await())
    }

    @Test
    fun asExtracoesEmParaleloDividemOMesmoRitmoDePedidos() = runTest {
        // Cada extração lê dois blocos: quatro pedidos, que com 100 ms de intervalo só terminam 300 ms depois do primeiro.
        val extractor = FakeExtractor { reader ->
            reader.readAt(0, ByteArray(1), 0, 1)
            reader.readAt(100, ByteArray(1), 0, 1)
            jpeg
        }
        val posters = posters(extractor, parallel = 2, pacer = RequestPacer(100) { testScheduler.currentTime })

        val results = listOf(async { posters.capa("att-1") }, async { posters.capa("att-2") })
        advanceUntilIdle()

        results.forEach { assertContentEquals(jpeg, it.await()) }
        assertEquals(2, extractor.maxActive)
        assertTrue(testScheduler.currentTime - settle >= 300, "levou ${testScheduler.currentTime - settle} ms")
    }

    @Test
    fun osPadroesDeProducaoSaoDuasExtracoesPorVezDepoisDoTempoDeAssentar() = runTest {
        val gate = CompletableDeferred<Unit>()
        val extractor = FakeExtractor { gate.await(); jpeg }
        val posters = VideoPosters(client(), extractor)

        val results = (1..3).map { n -> async { posters.capa("att-$n") } }
        advanceTimeBy(POSTER_SETTLE_MS - 1)
        runCurrent()
        assertEquals(0, extractor.calls)
        advanceTimeBy(1)
        runCurrent()

        assertEquals(POSTER_PARALLEL, extractor.calls)
        assertEquals(2, POSTER_PARALLEL)
        gate.complete(Unit)
        advanceUntilIdle()
        results.forEach { assertContentEquals(jpeg, it.await()) }
        assertEquals(POSTER_PARALLEL, extractor.maxActive)
    }

    @Test
    fun semVagasNaoHaExtracaoQueNaoRodeNenhuma() {
        assertFailsWith<IllegalArgumentException> { VideoPosters(client(), null, parallel = 0) }
    }

    // O que está sendo feito (o progresso do cartão)

    @Test
    fun aCapaEstaSendoFeitaDesdeOPedidoAteSair() = runTest {
        val gate = CompletableDeferred<Unit>()
        val extractor = FakeExtractor { gate.await(); jpeg }
        val posters = posters(extractor)
        assertFalse(posters.estaSendoFeita())

        val result = async { posters.capa() }
        runCurrent()
        assertTrue(posters.estaSendoFeita()) // assentando
        advanceTimeBy(settle + 1)
        runCurrent()
        assertEquals(1, extractor.calls)
        assertTrue(posters.estaSendoFeita()) // extraindo

        gate.complete(Unit)
        advanceUntilIdle()

        assertContentEquals(jpeg, result.await())
        assertFalse(posters.estaSendoFeita())
    }

    @Test
    fun quemEsperaAVezNaFilaTambemEstaSendoFeito() = runTest {
        val gate = CompletableDeferred<Unit>()
        val extractor = FakeExtractor { gate.await(); jpeg }
        val posters = posters(extractor)

        val first = async { posters.capa("att-1") }
        val second = async { posters.capa("att-2") }
        advanceTimeBy(settle + 1)
        runCurrent()

        assertEquals(1, extractor.calls)
        assertTrue(posters.estaSendoFeita("att-1"))
        assertTrue(posters.estaSendoFeita("att-2")) // na fila
        assertFalse(posters.estaSendoFeita("att-3")) // nunca pedido

        gate.complete(Unit)
        advanceUntilIdle()
        first.await()
        second.await()
        assertFalse(posters.estaSendoFeita("att-2"))
    }

    @Test
    fun comOVisualizadorAbertoACapaContinuaSendoFeitaEsperando() = runTest {
        val posters = posters(FakeExtractor { jpeg })
        posters.playerOpened()

        val result = async { posters.capa() }
        advanceUntilIdle()
        assertTrue(posters.estaSendoFeita())

        posters.playerClosed()
        advanceUntilIdle()
        assertContentEquals(jpeg, result.await())
        assertFalse(posters.estaSendoFeita())
    }

    @Test
    fun semCapaPorFalhaDoExtratorOuPorExcecaoNaoEstaMaisSendoFeita() = runTest {
        val broken = posters(FakeExtractor { error("codec quebrou") })
        val unsupported = posters(FakeExtractor { null })

        assertNull(broken.capa())
        assertNull(unsupported.capa())

        assertFalse(broken.estaSendoFeita())
        assertFalse(unsupported.estaSendoFeita())
    }

    @Test
    fun cancelarTiraACapaDeSendoFeita() = runTest {
        val gate = CompletableDeferred<Unit>()
        val posters = posters(FakeExtractor { gate.await(); jpeg })

        val settling = async { posters.capa("att-1") }
        runCurrent()
        assertTrue(posters.estaSendoFeita("att-1"))
        settling.cancel() // o cartão saiu antes de assentar
        runCurrent()
        assertFalse(posters.estaSendoFeita("att-1"))

        val extracting = async { posters.capa("att-2") }
        advanceTimeBy(settle + 1)
        runCurrent()
        assertTrue(posters.estaSendoFeita("att-2"))
        extracting.cancel() // e outro no meio da extração
        runCurrent()
        assertFalse(posters.estaSendoFeita("att-2"))
    }

    @Test
    fun oQueJaFalhouOuNaoTemExtratorNuncaEstaSendoFeito() = runTest {
        val failing = posters(FakeExtractor { null })
        assertNull(failing.capa())
        failing.playerOpened() // se fosse pedir de novo, ficaria esperando o visualizador, e sendo feito

        val again = async { failing.capa() }
        runCurrent()

        assertTrue(again.isCompleted) // a memória de falhas responde na hora
        assertFalse(failing.estaSendoFeita())
        val without = posters(null)
        assertNull(without.capa())
        assertFalse(without.estaSendoFeita())
    }

    // Visualizador de vídeo aberto

    @Test
    fun comOVisualizadorAbertoAsExtracoesEsperamEleFechar() = runTest {
        val extractor = FakeExtractor { jpeg }
        val posters = posters(extractor)
        posters.playerOpened()

        val result = async { posters.capa() }
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(0, extractor.calls)
        assertFalse(result.isCompleted)

        posters.playerClosed()
        advanceUntilIdle()

        assertContentEquals(jpeg, result.await())
        assertEquals(1, extractor.calls)
    }

    @Test
    fun comDoisVisualizadoresSoComecaQuandoOsDoisFecham() = runTest {
        val extractor = FakeExtractor { jpeg }
        val posters = posters(extractor)
        posters.playerOpened()
        posters.playerOpened()

        val result = async { posters.capa() }
        advanceUntilIdle()
        posters.playerClosed()
        advanceUntilIdle()
        assertEquals(0, extractor.calls)

        posters.playerClosed()
        advanceUntilIdle()

        assertContentEquals(jpeg, result.await())
    }

    @Test
    fun fecharAMaisNaoDeixaAContagemNegativa() = runTest {
        val extractor = FakeExtractor { jpeg }
        val posters = posters(extractor)
        posters.playerClosed()
        posters.playerClosed()

        posters.playerOpened()
        posters.playerClosed()

        assertContentEquals(jpeg, posters.capa())
    }

    @Test
    fun aExtracaoQueJaComecouNaoEhInterrompidaPeloVisualizador() = runTest {
        val gate = CompletableDeferred<Unit>()
        val extractor = FakeExtractor { gate.await(); jpeg }
        val posters = posters(extractor)

        val result = async { posters.capa() }
        advanceTimeBy(settle + 1)
        runCurrent()
        posters.playerOpened()
        gate.complete(Unit)
        advanceUntilIdle()

        assertContentEquals(jpeg, result.await())
        assertEquals(1, extractor.calls)
    }

    @Test
    fun cancelarEsperandoOVisualizadorSaiDaFilaSemExtrair() = runTest {
        val extractor = FakeExtractor { jpeg }
        val posters = posters(extractor)
        posters.playerOpened()

        val result = async { posters.capa() }
        advanceUntilIdle()
        result.cancel()
        posters.playerClosed()
        advanceUntilIdle()

        assertEquals(0, extractor.calls)
    }

    // Contas isoladas

    @Test
    fun cadaContaTemSuaFilaESuaMemoriaDeFalhas() = runTest {
        val gate = CompletableDeferred<Unit>()
        val extractorAna = FakeExtractor { gate.await(); null }
        val extractorBia = FakeExtractor { jpeg }
        val ana = posters(extractorAna)
        val bia = posters(extractorBia)

        val anaResult = async { ana.capa("att-1") }
        val biaResult = async { bia.capa("att-1") }
        advanceTimeBy(settle + 1)
        runCurrent()

        // A fila da Ana está ocupada; a da Bia não espera por ela.
        assertEquals(1, extractorAna.active)
        assertContentEquals(jpeg, biaResult.await())

        gate.complete(Unit)
        assertNull(anaResult.await())

        // O mesmo id falhou na Ana e não conta para a Bia (nem para o mesmo id em outro servidor).
        assertNull(ana.capa("att-1"))
        assertContentEquals(jpeg, bia.capa("att-1"))
        assertEquals(1, extractorAna.calls)
        assertEquals(2, extractorBia.calls)
    }

    @Test
    fun cadaContaPedeComOCookieDela() = runTest {
        val seenAna = mutableListOf<HttpRequestData>()
        val seenBia = mutableListOf<HttpRequestData>()
        val read = FakeExtractor { reader -> reader.readAt(0, ByteArray(1), 0, 1); jpeg }
        val ana = posters(read, client("jwt-ana", seenAna))
        val bia = posters(read, client("jwt-bia", seenBia))

        ana.capa("att-1")
        bia.capa("att-1")

        assertEquals(listOf<String?>("Auth=jwt-ana"), seenAna.map { it.headers[HttpHeaders.Cookie] })
        assertEquals(listOf<String?>("Auth=jwt-bia"), seenBia.map { it.headers[HttpHeaders.Cookie] })
        assertTrue(read.readers.all { it.requests == 1 })
    }
}
