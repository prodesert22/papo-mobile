package chat.papo.mobile

import chat.papo.mobile.media.ByteBudgetExceeded
import chat.papo.mobile.media.RangeFetch
import chat.papo.mobile.media.RangeReadFailed
import chat.papo.mobile.media.RangeReader
import chat.papo.mobile.media.RequestPacer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** O leitor em blocos com orçamento de bytes: o que vira pedido `Range`, o que fica em memória e quando ele desiste. */
class RangeReaderTest {
    private val block = 100

    /** Um arquivo de [size] bytes em que cada byte diz onde está (posição módulo 251), e o registro dos pedidos. */
    private class FakeFile(val size: Int, private val onFetch: suspend () -> Unit = {}) : RangeFetch {
        val requests = mutableListOf<LongRange>()
        var active = 0
        var maxActive = 0
        val times = mutableListOf<Long>()
        var clock: () -> Long = { 0L }

        fun content(from: Int, to: Int) = ByteArray(to - from + 1) { ((from + it) % 251).toByte() }

        override suspend fun fetch(start: Long, endInclusive: Long): ByteArray {
            requests += start..endInclusive
            times += clock()
            active++
            maxActive = maxOf(maxActive, active)
            try {
                onFetch()
                return content(start.toInt(), endInclusive.toInt())
            } finally {
                active--
            }
        }
    }

    private fun reader(file: FakeFile, budget: Long = 10_000, pacer: RequestPacer = RequestPacer(0)) =
        RangeReader(file.size.toLong(), file, blockSize = block, budget = budget, pacer = pacer)

    private suspend fun RangeReader.read(position: Long, length: Int): ByteArray {
        val into = ByteArray(length)
        val count = readAt(position, into, 0, length)
        return into.copyOf(maxOf(count, 0))
    }

    // Blocos e cache

    @Test
    fun lerDentroDeUmBlocoPedeOBlocoInteiroUmaVez() = runTest {
        val file = FakeFile(1_000)
        val reader = reader(file)

        assertContentEquals(file.content(130, 149), reader.read(130, 20))
        assertContentEquals(file.content(170, 179), reader.read(170, 10))

        assertEquals(listOf(100L..199L), file.requests)
        assertEquals(1, reader.requests)
        assertEquals(100L, reader.fetchedBytes)
    }

    @Test
    fun lerAtravessandoDoisBlocosPedeOsDoisEJuntaOsBytes() = runTest {
        val file = FakeFile(1_000)
        val reader = reader(file)

        assertContentEquals(file.content(190, 209), reader.read(190, 20))

        assertEquals(listOf(100L..199L, 200L..299L), file.requests)
    }

    @Test
    fun lerSobreOsBytesDeUmBufferMaiorRespeitaODeslocamento() = runTest {
        val file = FakeFile(1_000)
        val reader = reader(file)
        val into = ByteArray(10) { 99 }

        assertEquals(4, reader.readAt(50, into, 3, 4))

        assertContentEquals(byteArrayOf(99, 99, 99) + file.content(50, 53) + byteArrayOf(99, 99, 99), into)
    }

    @Test
    fun oMoovNoFimDoArquivoSoPedeOInicioOFimEOQuadro() = runTest {
        val file = FakeFile(1_050)
        val reader = reader(file)

        // ftyp no começo, depois o pulo para o moov no último bloco, e por fim o quadro no começo do mdat.
        reader.read(0, 32)
        reader.read(1_040, 10)
        reader.read(40, 50)
        reader.read(60, 40)

        assertEquals(listOf(0L..99L, 1_000L..1_049L), file.requests)
        assertEquals(150L, reader.fetchedBytes)
    }

    // Fim do arquivo

    @Test
    fun oUltimoBlocoParcialPedeSoOQueExiste() = runTest {
        val file = FakeFile(250)
        val reader = reader(file)

        assertContentEquals(file.content(230, 249), reader.read(230, 20))

        assertEquals(listOf(200L..249L), file.requests)
        assertEquals(50L, reader.fetchedBytes)
    }

    @Test
    fun lerAlemDoFimDevolveSoOQueResta() = runTest {
        val file = FakeFile(250)
        val reader = reader(file)

        assertContentEquals(file.content(240, 249), reader.read(240, 64))
    }

    @Test
    fun lerNoFimOuDepoisDeleDevolveMenosUmSemPedirNada() = runTest {
        val file = FakeFile(250)
        val reader = reader(file)

        assertEquals(-1, reader.readAt(250, ByteArray(8), 0, 8))
        assertEquals(-1, reader.readAt(9_999, ByteArray(8), 0, 8))

        assertEquals(emptyList(), file.requests)
    }

    @Test
    fun lerZeroBytesDevolveZeroSemPedirNada() = runTest {
        val file = FakeFile(250)
        val reader = reader(file)

        assertEquals(0, reader.readAt(10, ByteArray(8), 0, 0))
        // Também no fim: não há erro a dar para um pedido vazio.
        assertEquals(0, reader.readAt(250, ByteArray(8), 0, 0))

        assertEquals(emptyList(), file.requests)
    }

    @Test
    fun argumentosForaDoBufferSaoRecusados() = runTest {
        val reader = reader(FakeFile(250))

        assertFailsWith<IllegalArgumentException> { reader.readAt(-1, ByteArray(8), 0, 8) }
        assertFailsWith<IllegalArgumentException> { reader.readAt(0, ByteArray(8), -1, 8) }
        assertFailsWith<IllegalArgumentException> { reader.readAt(0, ByteArray(8), 0, -1) }
        assertFailsWith<IllegalArgumentException> { reader.readAt(0, ByteArray(8), 4, 8) }
    }

    @Test
    fun blocoDeTamanhoZeroNaoExiste() {
        assertFailsWith<IllegalArgumentException> { RangeReader(10, FakeFile(10), blockSize = 0) }
    }

    @Test
    fun arquivoVazioSoTemFim() = runTest {
        val file = FakeFile(0)

        assertEquals(-1, reader(file).readAt(0, ByteArray(8), 0, 8))
        assertEquals(emptyList(), file.requests)
    }

    // Orçamento

    @Test
    fun oOrcamentoParaAntesDoPedidoQueEstouraria() = runTest {
        val file = FakeFile(1_000)
        val reader = reader(file, budget = 300)

        reader.read(0, 100)
        reader.read(100, 100)
        reader.read(200, 100)
        assertFailsWith<ByteBudgetExceeded> { reader.read(300, 10) }

        // Três blocos de 100 cabem em 300; o quarto nem é pedido.
        assertEquals(3, file.requests.size)
        assertEquals(300L, reader.fetchedBytes)
    }

    @Test
    fun oOrcamentoNaoTiraOQueJaEstaEmMemoria() = runTest {
        val file = FakeFile(1_000)
        val reader = reader(file, budget = 200)

        reader.read(0, 100)
        reader.read(100, 100)
        assertFailsWith<ByteBudgetExceeded> { reader.read(200, 10) }

        // O que já veio continua legível, e a falha não gasta nem esconde nada.
        assertContentEquals(file.content(150, 169), reader.read(150, 20))
        assertEquals(2, file.requests.size)
    }

    @Test
    fun oOrcamentoContaOTamanhoRealDoUltimoBlocoParcial() = runTest {
        val file = FakeFile(250)
        val reader = reader(file, budget = 250)

        reader.read(0, 250)

        assertEquals(250L, reader.fetchedBytes)
    }

    @Test
    fun umByteAMenosNoOrcamentoDesisteNoUltimoBloco() = runTest {
        val file = FakeFile(250)
        val reader = reader(file, budget = 249)

        assertFailsWith<ByteBudgetExceeded> { reader.read(0, 250) }

        assertEquals(2, file.requests.size)
    }

    @Test
    fun oOrcamentoEsgotadoSoSeVeNoQueFaltaBuscar() = runTest {
        val file = FakeFile(1_000)
        val reader = reader(file, budget = 100)

        assertContentEquals(file.content(0, 9), reader.read(0, 10))
        assertContentEquals(file.content(90, 99), reader.read(90, 10))
        assertFailsWith<ByteBudgetExceeded> { reader.read(100, 1) }
    }

    // Falhas do servidor

    @Test
    fun respostaComTamanhoDiferenteDoPedidoFalhaESemGuardarOBloco() = runTest {
        var short = true
        val source = RangeFetch { start, end ->
            val bytes = ByteArray((end - start + 1).toInt())
            if (short) bytes.copyOf(bytes.size - 1) else bytes
        }
        val reader = RangeReader(1_000, source, blockSize = block, budget = 10_000, pacer = RequestPacer(0))

        assertFailsWith<RangeReadFailed> { reader.read(0, 10) }
        assertEquals(0L, reader.fetchedBytes)

        // O bloco que falhou não ficou: a leitura seguinte pede de novo.
        short = false
        assertEquals(10, reader.read(0, 10).size)
        assertEquals(2, reader.requests)
    }

    @Test
    fun erroDaBuscaSobeEOLeitorSegueUsavel() = runTest {
        var broken = true
        val source = RangeFetch { start, end ->
            if (broken) error("conexão caiu")
            ByteArray((end - start + 1).toInt())
        }
        val reader = RangeReader(1_000, source, blockSize = block, budget = 10_000, pacer = RequestPacer(0))

        assertFailsWith<IllegalStateException> { reader.read(0, 10) }
        broken = false

        assertEquals(10, reader.read(0, 10).size)
    }

    // Ritmo dos pedidos

    @Test
    fun osPedidosSaemEspacadosPeloRitmoMasNaoParaOQueEstaEmCache() = runTest {
        val file = FakeFile(1_000)
        file.clock = { testScheduler.currentTime }
        val reader = reader(file, pacer = RequestPacer(100) { testScheduler.currentTime })

        reader.read(0, 10) // o primeiro sai na hora
        reader.read(5, 10) // do mesmo bloco: sem pedido, sem espera
        reader.read(200, 10)
        reader.read(500, 10)

        assertEquals(listOf(0L, 100L, 200L), file.times)
        assertEquals(200L, testScheduler.currentTime)
    }

    @Test
    fun leitoresQueDividemORitmoEspacamOsPedidosUmDoOutro() = runTest {
        val first = FakeFile(1_000)
        val second = FakeFile(1_000)
        val clock = { testScheduler.currentTime }
        first.clock = clock
        second.clock = clock
        val pacer = RequestPacer(100, clock)
        val a = reader(first, pacer = pacer)
        val b = reader(second, pacer = pacer)

        a.read(0, 10)
        b.read(0, 10)
        a.read(200, 10)
        b.read(200, 10)

        assertEquals(listOf(0L, 200L), first.times)
        assertEquals(listOf(100L, 300L), second.times)
    }

    @Test
    fun oOrcamentoEstouradoNaoGastaAVagaDoRitmo() = runTest {
        val file = FakeFile(1_000)
        val pacer = RequestPacer(100) { testScheduler.currentTime }
        val reader = reader(file, budget = 100, pacer = pacer)

        reader.read(0, 10)
        assertFailsWith<ByteBudgetExceeded> { reader.read(100, 10) }
        pacer.pace() // a vaga seguinte é só a do primeiro pedido mais o intervalo

        assertEquals(100L, testScheduler.currentTime)
    }

    // Concorrência e cancelamento

    @Test
    fun leiturasConcorrentesFazUmPedidoPorVezECadaBlocoUmaVez() = runTest {
        val file = FakeFile(1_000) { delay(50) }
        val reader = reader(file)

        val reads = listOf(
            async { reader.read(0, 10) },
            async { reader.read(5, 10) },
            async { reader.read(300, 10) },
        ).awaitAll()

        assertContentEquals(file.content(0, 9), reads[0])
        assertContentEquals(file.content(5, 14), reads[1])
        assertContentEquals(file.content(300, 309), reads[2])
        assertEquals(1, file.maxActive)
        assertEquals(2, file.requests.size)
    }

    @Test
    fun cancelarNoMeioDoPedidoLiberaOLeitorParaOProximo() = runTest {
        val gate = CompletableDeferred<Unit>()
        val file = FakeFile(1_000) { gate.await() }
        val reader = reader(file)

        val pending = async(start = CoroutineStart.UNDISPATCHED) { reader.read(0, 10) }
        pending.cancel()
        gate.complete(Unit)

        // O bloco cancelado não ficou pela metade: a leitura seguinte o pede de novo e funciona.
        assertContentEquals(file.content(0, 9), reader.read(0, 10))
        assertTrue(pending.isCancelled)
        assertEquals(0, file.active)
    }
}
