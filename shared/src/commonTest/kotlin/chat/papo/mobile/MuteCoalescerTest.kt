package chat.papo.mobile

import chat.papo.mobile.voice.MuteCoalescer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class MuteCoalescerTest {
    /** Cada envio registra o instante virtual e o estado. */
    private class Sink(private val scope: TestScope, var accept: Boolean = true) {
        val sent = mutableListOf<Pair<Long, Boolean>>()
        suspend fun send(muted: Boolean): Boolean {
            sent += scope.currentTime to muted
            return accept
        }
    }

    private fun TestScope.newCoalescer(sink: Sink, serverMuted: Boolean = true) =
        MuteCoalescer(backgroundScope, sink::send, serverMuted = serverMuted)

    @Test
    fun oPrimeiroPedidoSaiNaHora() = runTest {
        val sink = Sink(this)
        val coalescer = newCoalescer(sink)
        coalescer.submit(false)
        runCurrent()
        assertEquals(listOf(0L to false), sink.sent)
    }

    @Test
    fun rajadaViraUmEnvioComOUltimoEstado() = runTest {
        val sink = Sink(this)
        val coalescer = newCoalescer(sink)
        coalescer.submit(false)
        runCurrent()

        // Aperta e solta várias vezes dentro do intervalo: só o último estado sai, quando o intervalo acaba.
        advanceTimeBy(10.milliseconds)
        coalescer.submit(true)
        advanceTimeBy(10.milliseconds)
        coalescer.submit(false)
        advanceTimeBy(10.milliseconds)
        coalescer.submit(true)
        advanceTimeBy(100.milliseconds)
        assertEquals(listOf(0L to false), sink.sent)

        advanceTimeBy(130.milliseconds) // 250 ms desde o primeiro envio
        runCurrent()
        assertEquals(listOf(0L to false, 250L to true), sink.sent)
    }

    @Test
    fun nuncaMaisQueUmEnvioACadaIntervalo() = runTest {
        val sink = Sink(this)
        val coalescer = newCoalescer(sink)
        repeat(40) { i ->
            coalescer.submit(i % 2 == 1) // false, true, false... a cada 50 ms, durante 2 s
            advanceTimeBy(50.milliseconds)
        }
        advanceTimeBy(500.milliseconds)
        runCurrent()
        val times = sink.sent.map { it.first }
        assertEquals(times, times.sorted())
        assertEquals(true, times.zipWithNext().all { (a, b) -> b - a >= 250 })
        assertEquals(true, sink.sent.size <= 2_000 / 250 + 1)
    }

    @Test
    fun voltarAoQueOServidorTemNaoEnviaNada() = runTest {
        val sink = Sink(this)
        val coalescer = newCoalescer(sink, serverMuted = true)
        coalescer.submit(false)
        runCurrent()
        coalescer.submit(true) // durante o intervalo...
        coalescer.submit(false) // ...volta ao que o servidor já tem
        advanceTimeBy(300.milliseconds)
        runCurrent()
        assertEquals(listOf(0L to false), sink.sent)
    }

    @Test
    fun pedidoIgualAoEstadoInicialDoServidorNaoEnvia() = runTest {
        val sink = Sink(this)
        val coalescer = newCoalescer(sink, serverMuted = true)
        coalescer.submit(true)
        advanceTimeBy(1_000.milliseconds)
        assertEquals(emptyList(), sink.sent)

        val unmuted = Sink(this)
        newCoalescer(unmuted, serverMuted = false).submit(false)
        advanceTimeBy(1_000.milliseconds)
        assertEquals(emptyList(), unmuted.sent)
    }

    @Test
    fun depoisDeUmTempoParadoOProximoSaiNaHora() = runTest {
        val sink = Sink(this)
        val coalescer = newCoalescer(sink)
        coalescer.submit(false)
        advanceTimeBy(5_000.milliseconds)
        coalescer.submit(true)
        runCurrent()
        assertEquals(listOf(0L to false, 5_000L to true), sink.sent)
    }

    @Test
    fun envioQueFalhaNaoMudaOQueSeSabeDoServidor() = runTest {
        val sink = Sink(this, accept = false)
        val coalescer = newCoalescer(sink)
        coalescer.submit(false)
        runCurrent()
        assertEquals(listOf(0L to false), sink.sent)

        // O servidor continua mutado: pedir "mutado" de novo não envia, e pedir "falando" tenta outra vez.
        advanceTimeBy(300.milliseconds)
        coalescer.submit(true)
        advanceTimeBy(300.milliseconds)
        assertEquals(1, sink.sent.size)
        sink.accept = true
        coalescer.submit(false)
        advanceTimeBy(300.milliseconds)
        runCurrent()
        assertEquals(listOf(false, false), sink.sent.map { it.second })
    }

    @Test
    fun ordemDosEstadosEnviadosSegueAOrdemDosPedidos() = runTest {
        val sink = Sink(this)
        val coalescer = newCoalescer(sink)
        coalescer.submit(false)
        advanceTimeBy(300.milliseconds)
        coalescer.submit(true)
        advanceTimeBy(300.milliseconds)
        coalescer.submit(false)
        advanceTimeBy(300.milliseconds)
        assertEquals(listOf(false, true, false), sink.sent.map { it.second })
    }
}
