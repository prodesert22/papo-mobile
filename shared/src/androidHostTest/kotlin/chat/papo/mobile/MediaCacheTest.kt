package chat.papo.mobile

import chat.papo.mobile.media.MediaCache
import chat.papo.mobile.media.MediaUnavailableException
import chat.papo.mobile.platform.JcaCrypto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource

/**
 * Busca única por nome no cache de mídia. Tudo roda numa thread só, com o relógio virtual; a contenção
 * do mutex, que em produção vem de threads reais, é forçada segurando-o do teste.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaCacheTest {
    private val crypto = JcaCrypto()
    private val key = crypto.randomBytes(32)
    private val secret = "conteúdo da imagem".encodeToByteArray()

    private fun TestScope.cache(timeSource: TimeSource = TimeSource.Monotonic) =
        MediaCache(TestResources.tempDir(), key, crypto, StandardTestDispatcher(testScheduler), timeSource = timeSource)

    /** Instância sobre um disco e a chave dadas, como depois de reabrir a conta. */
    private fun TestScope.cacheOn(directory: String) = MediaCache(directory, key, crypto, StandardTestDispatcher(testScheduler))

    /** `get` que entrega a exceção como valor, para o teste falhar com mensagem em vez de abortar no meio. */
    private suspend fun MediaCache.getOrCancelled(name: String, fetch: suspend () -> ByteArray?): ByteArray? =
        try {
            get(name, fetch)
        } catch (e: CancellationException) {
            fail("get(\"$name\") lançou $e: restou uma entrada em voo de uma busca já cancelada")
        }

    @Test
    fun buscaUmaVezEServeAsSeguintesDaMemoria() = runTest(timeout = 5.seconds) {
        val cache = cache()
        var fetches = 0

        assertContentEquals(secret, cache.get("att/1") { fetches++; secret })
        assertContentEquals(secret, cache.get("att/1") { fetches++; secret })

        assertEquals(1, fetches)
    }

    @Test
    fun recursoInexistenteFicaLembradoEOServidorNaoEPerguntadoDeNovo() = runTest(timeout = 5.seconds) {
        val cache = cache()
        var fetches = 0

        repeat(5) { assertNull(cache.get("avatar/u1/0") { fetches++; null }) }

        assertEquals(1, fetches)
    }

    @Test
    fun quemEsperavaUmaBuscaSemResultadoRecebeNuloEOsSeguintesNaoBuscam() = runTest(timeout = 5.seconds) {
        val cache = cache()
        val gate = CompletableDeferred<Unit>()
        var fetches = 0
        val owner = async { cache.get("avatar/u1/0") { fetches++; gate.await(); null } }
        runCurrent()
        val waiter = async { cache.get("avatar/u1/0") { fetches++; secret } }
        runCurrent()

        gate.complete(Unit)

        assertNull(owner.await())
        assertNull(waiter.await())
        assertNull(cache.get("avatar/u1/0") { fetches++; secret })
        assertEquals(1, fetches)
    }

    @Test
    fun outroNomeDoMesmoUsuarioNaoHerdaAAusencia() = runTest(timeout = 5.seconds) {
        val cache = cache()
        var fetches = 0
        assertNull(cache.get("avatar/u1/0") { fetches++; null })

        // O servidor trocou o avatar: a versão sobe e o nome é outro.
        assertContentEquals(secret, cache.get("avatar/u1/1") { fetches++; secret })
        assertNull(cache.get("avatar/u1/0") { fetches++; secret })

        assertEquals(2, fetches)
    }

    @Test
    fun evictEsqueceAAusenciaEAFalhaDoNome() = runTest(timeout = 5.seconds) {
        val cache = cache(TestTimeSource())
        var fetches = 0
        assertNull(cache.get("a") { fetches++; null })
        assertFailsWith<IOException> { cache.get("b") { fetches++; throw IOException("rede caiu") } }

        cache.evict("a")
        cache.evict("b")

        assertContentEquals(secret, cache.get("a") { fetches++; secret })
        assertContentEquals(secret, cache.get("b") { fetches++; secret })
        assertEquals(4, fetches)
    }

    @Test
    fun clearMemoryEsqueceAAusenciaEAFalhaDosNomes() = runTest(timeout = 5.seconds) {
        val cache = cache(TestTimeSource())
        var fetches = 0
        assertNull(cache.get("a") { fetches++; null })
        assertFailsWith<IOException> { cache.get("b") { fetches++; throw IOException("rede caiu") } }

        cache.clearMemory()

        assertContentEquals(secret, cache.get("a") { fetches++; secret })
        assertContentEquals(secret, cache.get("b") { fetches++; secret })
        assertEquals(4, fetches)
    }

    @Test
    fun putDepoisDeAusenciaOuFalhaServeOQueFoiGuardado() = runTest(timeout = 5.seconds) {
        val cache = cache(TestTimeSource())
        var fetches = 0
        assertNull(cache.get("a") { fetches++; null })
        assertFailsWith<IOException> { cache.get("b") { fetches++; throw IOException("rede caiu") } }

        cache.put("a", secret)
        cache.put("b", secret, keepInMemory = false)

        assertContentEquals(secret, cache.get("a") { fetches++; null })
        assertContentEquals(secret, cache.get("b") { fetches++; null })
        assertEquals(2, fetches)
    }

    @Test
    fun putDuranteABuscaQueTerminaSemResultadoNaoDeixaAusenciaLembrada() = runTest(timeout = 5.seconds) {
        val cache = cache()
        val gate = CompletableDeferred<Unit>()
        var fetches = 0
        val owner = async { cache.get("a") { fetches++; gate.await(); null } }
        runCurrent()

        cache.put("a", secret, keepInMemory = false)
        gate.complete(Unit)
        owner.await()

        assertContentEquals(secret, cache.get("a") { fetches++; null })
        assertEquals(1, fetches)
    }

    @Test
    fun duasInstanciasNaoCompartilhamAusenciaNemFalha() = runTest(timeout = 5.seconds) {
        val directory = TestResources.tempDir()
        val first = cacheOn(directory)
        val second = cacheOn(directory)
        var fetches = 0
        assertNull(first.get("a") { fetches++; null })
        assertFailsWith<IOException> { first.get("b") { fetches++; throw IOException("rede caiu") } }

        assertContentEquals(secret, second.get("a") { fetches++; secret })
        assertContentEquals(secret, second.get("b") { fetches++; secret })

        assertNull(first.get("a") { fetches++; secret })
        assertFailsWith<MediaUnavailableException> { first.get("b") { fetches++; secret } }
        assertEquals(4, fetches)
    }

    @Test
    fun falhaDaBuscaNaoEBuscadaDeNovoDentroDaJanelaEUmaVezDepoisDela() = runTest(timeout = 5.seconds) {
        val time = TestTimeSource()
        val cache = cache(time)
        var fetches = 0
        val failure = IOException("rede caiu")

        assertSame(failure, assertFailsWith<IOException> { cache.get("a") { fetches++; throw failure } })
        val blocked = assertFailsWith<MediaUnavailableException> { cache.get("a") { fetches++; secret } }
        time += MediaCache.FAILURE_WINDOW - 1.milliseconds
        assertFailsWith<MediaUnavailableException> { cache.get("a") { fetches++; secret } }

        assertSame(failure, blocked.cause)
        assertEquals(1, fetches)
        // Outro nome não é afetado pela falha deste.
        assertContentEquals(secret, cache.get("b") { fetches++; secret })

        time += 1.milliseconds
        assertContentEquals(secret, cache.get("a") { fetches++; secret })
        assertContentEquals(secret, cache.get("a") { fetches++; null })
        assertEquals(3, fetches)
    }

    @Test
    fun novaFalhaDepoisDaJanelaAbreOutraJanela() = runTest(timeout = 5.seconds) {
        val time = TestTimeSource()
        val cache = cache(time)
        var fetches = 0
        assertFailsWith<IOException> { cache.get("a") { fetches++; throw IOException("rede caiu") } }

        time += MediaCache.FAILURE_WINDOW
        assertFailsWith<IOException> { cache.get("a") { fetches++; throw IOException("ainda fora") } }
        assertFailsWith<MediaUnavailableException> { cache.get("a") { fetches++; secret } }

        assertEquals(2, fetches)
        time += MediaCache.FAILURE_WINDOW
        assertContentEquals(secret, cache.get("a") { fetches++; secret })
    }

    @Test
    fun chamadasSimultaneasDepoisDaJanelaCompartilhamUmaSoBusca() = runTest(timeout = 5.seconds) {
        val time = TestTimeSource()
        val cache = cache(time)
        val gate = CompletableDeferred<Unit>()
        var fetches = 0
        assertFailsWith<IOException> { cache.get("a") { fetches++; throw IOException("rede caiu") } }
        time += MediaCache.FAILURE_WINDOW

        val owner = async { cache.get("a") { fetches++; gate.await(); secret } }
        runCurrent()
        val waiter = async { cache.get("a") { fetches++; secret } }
        runCurrent()
        gate.complete(Unit)

        assertContentEquals(secret, owner.await())
        assertContentEquals(secret, waiter.await())
        assertEquals(2, fetches)
    }

    @Test
    fun cancelamentoNaoViraAusenciaNemFalha() = runTest(timeout = 5.seconds) {
        val cache = cache(TestTimeSource())
        val gate = CompletableDeferred<Unit>()
        var fetches = 0
        val owner = launch { cache.get("a") { fetches++; gate.await(); null } }
        runCurrent()
        owner.cancel()
        runCurrent()

        assertContentEquals(secret, cache.get("a") { fetches++; secret })
        assertEquals(2, fetches)
    }

    @Test
    fun cancelamentoLancadoPelaBuscaNaoViraFalha() = runTest(timeout = 5.seconds) {
        val cache = cache(TestTimeSource())
        var fetches = 0

        assertFailsWith<CancellationException> { cache.get("a") { fetches++; throw CancellationException("tempo esgotado") } }

        assertContentEquals(secret, cache.get("a") { fetches++; secret })
        assertEquals(2, fetches)
    }

    @Test
    fun falhaDeQuemJaFoiCanceladoNaoViraFalhaDoRecurso() = runTest(timeout = 5.seconds) {
        val cache = cache(TestTimeSource())
        val gate = CompletableDeferred<Unit>()
        var fetches = 0
        // O motor HTTP aborta o pedido cancelado com IOException, não com CancellationException.
        val owner = launch {
            runCatching {
                cache.get("a") {
                    fetches++
                    try {
                        gate.await()
                    } catch (e: CancellationException) {
                        throw IOException("Canceled")
                    }
                    secret
                }
            }
        }
        runCurrent()
        owner.cancel()
        runCurrent()

        assertContentEquals(secret, cache.get("a") { fetches++; secret })
        assertEquals(2, fetches)
    }

    @Test
    fun passandoDoLimiteEsqueceOsNomesMaisAntigos() = runTest(timeout = 20.seconds) {
        val cache = cache()
        var fetches = 0
        for (n in 0..MediaCache.MAX_MISSES) assertNull(cache.get("avatar/u$n/0") { fetches++; null })
        val total = fetches

        assertNull(cache.get("avatar/u${MediaCache.MAX_MISSES}/0") { fetches++; null })
        assertEquals(total, fetches, "o mais recente continua lembrado")
        assertNull(cache.get("avatar/u0/0") { fetches++; null })
        assertEquals(total + 1, fetches, "o mais antigo foi esquecido e busca de novo")
    }

    @Test
    fun chamadasSimultaneasDoMesmoNomeCompartilhamUmaBusca() = runTest(timeout = 5.seconds) {
        val cache = cache()
        val gate = CompletableDeferred<Unit>()
        var fetches = 0
        val owner = async { cache.get("att/1") { fetches++; gate.await(); secret } }
        runCurrent()
        val waiter = async { cache.get("att/1") { fetches++; secret } }
        runCurrent()

        gate.complete(Unit)

        assertContentEquals(secret, owner.await())
        assertContentEquals(secret, waiter.await())
        assertEquals(1, fetches)
    }

    @Test
    fun falhaDaBuscaChegaAQuemEsperaELiberaONomeParaUmaNovaTentativaDepoisDaJanela() = runTest(timeout = 5.seconds) {
        val time = TestTimeSource()
        val cache = cache(time)
        val gate = CompletableDeferred<Unit>()
        val owner = async { runCatching { cache.get("att/1") { gate.await(); throw IOException("rede caiu") } } }
        runCurrent()
        val waiter = async { runCatching { cache.get("att/1") { secret } } }
        runCurrent()

        gate.complete(Unit)

        assertEquals("rede caiu", assertFailsWith<IOException> { owner.await().getOrThrow() }.message)
        assertEquals("rede caiu", assertFailsWith<IOException> { waiter.await().getOrThrow() }.message)
        time += MediaCache.FAILURE_WINDOW
        assertContentEquals(secret, cache.get("att/1") { secret })
    }

    @Test
    fun cancelarQuemBuscaRelancaOCancelamentoELiberaONome() = runTest(timeout = 5.seconds) {
        val cache = cache()
        val gate = CompletableDeferred<Unit>()
        var caught: CancellationException? = null
        val owner = launch {
            try {
                cache.get("att/1") { gate.await(); secret }
            } catch (e: CancellationException) {
                caught = e
                throw e
            }
        }
        runCurrent()

        owner.cancel()
        runCurrent()

        assertNotNull(caught)
        assertTrue(owner.isCancelled)
        assertContentEquals(secret, cache.getOrCancelled("att/1") { secret })
    }

    @Test
    fun cancelarQuemEsperaNaoAtrapalhaQuemBuscaNemOutrosQueEsperam() = runTest(timeout = 5.seconds) {
        val cache = cache()
        val gate = CompletableDeferred<Unit>()
        var fetches = 0
        val owner = async { cache.get("att/1") { fetches++; gate.await(); secret } }
        runCurrent()
        val cancelled = launch { cache.get("att/1") { fetches++; secret } }
        val other = async { cache.get("att/1") { fetches++; secret } }
        runCurrent()

        cancelled.cancel()
        runCurrent()
        gate.complete(Unit)

        assertTrue(cancelled.isCancelled)
        assertContentEquals(secret, owner.await())
        assertContentEquals(secret, other.await())
        assertEquals(1, fetches)
    }

    @Test
    fun cancelamentoSobContencaoDoMutexNaoDeixaEntradaEmVoo() = runTest(timeout = 5.seconds) {
        val cache = cache()
        val gate = CompletableDeferred<Unit>()
        var fetches = 0
        val owner = launch { cache.get("att/1") { fetches++; gate.await(); secret } }
        runCurrent()
        val waiter = launch { cache.get("att/1") { fetches++; secret } }
        runCurrent()

        // O `finally` do dono, já cancelado, precisa do mutex e o encontra ocupado.
        cache.mutex.lock()
        owner.cancel()
        runCurrent()
        cache.mutex.unlock()
        runCurrent()

        assertTrue(owner.isCancelled)
        assertTrue(waiter.isCompleted, "quem esperava a busca cancelada não pode ficar preso")
        assertContentEquals(secret, cache.getOrCancelled("att/1") { fetches++; secret })
        assertEquals(2, fetches)
    }

    @Test
    fun cancelarEsperandoOMutexParaGuardarNaMemoriaNaoDeixaEntradaEmVooNemPerdeOQueFoiBaixado() = runTest(timeout = 5.seconds) {
        val cache = cache()
        val gate = CompletableDeferred<Unit>()
        var fetches = 0
        val owner = launch { cache.get("att/1") { fetches++; gate.await(); secret } }
        runCurrent()

        // A busca termina e grava em disco, mas guardar na memória fica esperando o mutex.
        cache.mutex.lock()
        gate.complete(Unit)
        runCurrent()
        owner.cancel()
        runCurrent()
        cache.mutex.unlock()
        runCurrent()

        assertTrue(owner.isCancelled)
        assertContentEquals(secret, cache.getOrCancelled("att/1") { fetches++; null })
        assertEquals(1, fetches)
    }
}
