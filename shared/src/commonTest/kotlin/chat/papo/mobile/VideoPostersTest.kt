package chat.papo.mobile

import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.data.api.MemoryCookieStore
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.media.ByteBudgetExceeded
import chat.papo.mobile.media.POSTER_MAX_PX
import chat.papo.mobile.media.RangeReader
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A fila das capas de uma conta: uma extração por vez, sem repetir o que falhou, esperando o visualizador, e sempre pelo
 * cliente da própria conta. O extrator é um falso; o decodificador de verdade só roda no aparelho.
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

    private fun posters(extractor: VideoPosterExtractor?, client: PapoClient = client()) =
        VideoPosters(client, extractor, settleMs = settle, blockBytes = 100, budgetBytes = 300, pauseMs = 0)

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
