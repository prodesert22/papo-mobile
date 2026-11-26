package chat.papo.mobile

import chat.papo.mobile.media.RequestPacer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** O ritmo dos pedidos: quando cada um pode sair, com o relógio virtual do teste. */
class RequestPacerTest {
    private fun TestScope.pacer(intervalMs: Long) = RequestPacer(intervalMs) { testScheduler.currentTime }

    /** Os instantes em que cada chamada de [RequestPacer.pace] voltou. */
    private suspend fun TestScope.paceTimes(pacer: RequestPacer, calls: Int) = List(calls) {
        pacer.pace()
        testScheduler.currentTime
    }

    @Test
    fun oPrimeiroPedidoSaiNaHora() = runTest {
        assertEquals(listOf(0L), paceTimes(pacer(100), 1))
    }

    @Test
    fun pedidosSeguidosSaemEspacadosDoIntervalo() = runTest {
        assertEquals(listOf(0L, 100L, 200L, 300L), paceTimes(pacer(100), 4))
    }

    @Test
    fun depoisDeUmaPausaMaiorQueOIntervaloOPedidoSaiNaHoraEORitmoRecomeca() = runTest {
        val pacer = pacer(100)
        pacer.pace()

        delay(250)
        val times = paceTimes(pacer, 2)

        assertEquals(listOf(250L, 350L), times)
    }

    @Test
    fun umaPausaMenorQueOIntervaloSoCompletaOQueFalta() = runTest {
        val pacer = pacer(100)
        pacer.pace()

        delay(30)
        pacer.pace()

        assertEquals(100L, testScheduler.currentTime)
    }

    @Test
    fun chamadasSimultaneasRecebemVagasEmSequencia() = runTest {
        val pacer = pacer(100)

        val times = List(3) { async { pacer.pace(); testScheduler.currentTime } }.awaitAll()

        assertEquals(listOf(0L, 100L, 200L), times)
    }

    @Test
    fun semIntervaloNuncaEspera() = runTest {
        assertEquals(listOf(0L, 0L, 0L), paceTimes(pacer(0), 3))
    }

    @Test
    fun quemCancelaEsperandoDeixaAVagaPassadaEOsOutrosNaoSeAtrasamMais() = runTest {
        val pacer = pacer(100)
        pacer.pace() // vaga 0

        val cancelled = async { pacer.pace() } // vaga 100
        runCurrent()
        cancelled.cancel()
        val next = async { pacer.pace(); testScheduler.currentTime }

        assertEquals(200L, next.await())
    }

    @Test
    fun intervaloNegativoEhRecusado() {
        assertFailsWith<IllegalArgumentException> { RequestPacer(-1) }
    }

    @Test
    fun comORelogioPadraoOPrimeiroPedidoSaiSemEspera() = runTest {
        // Sem intervalo o relógio real não decide nada: só prova que o padrão funciona.
        val pacer = RequestPacer(0)
        pacer.pace()
        pacer.pace()

        assertEquals(0L, testScheduler.currentTime)
    }
}
