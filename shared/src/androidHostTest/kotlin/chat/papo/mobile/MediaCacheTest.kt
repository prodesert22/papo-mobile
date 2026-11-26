package chat.papo.mobile

import chat.papo.mobile.media.MediaCache
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
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * Busca única por nome no cache de mídia. Tudo roda numa thread só, com o relógio virtual; a contenção
 * do mutex, que em produção vem de threads reais, é forçada segurando-o do teste.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaCacheTest {
    private val crypto = JcaCrypto()
    private val key = crypto.randomBytes(32)
    private val secret = "conteúdo da imagem".encodeToByteArray()

    private fun TestScope.cache() = MediaCache(TestResources.tempDir(), key, crypto, StandardTestDispatcher(testScheduler))

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
    fun recursoInexistenteNaoFicaGuardadoEEBuscadoDeNovo() = runTest(timeout = 5.seconds) {
        val cache = cache()
        var fetches = 0

        assertNull(cache.get("att/1") { fetches++; null })
        assertNull(cache.get("att/1") { fetches++; null })
        assertContentEquals(secret, cache.get("att/1") { fetches++; secret })

        assertEquals(3, fetches)
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
    fun falhaDaBuscaChegaAQuemEsperaELiberaONomeParaUmaNovaTentativa() = runTest(timeout = 5.seconds) {
        val cache = cache()
        val gate = CompletableDeferred<Unit>()
        val owner = async { runCatching { cache.get("att/1") { gate.await(); throw IOException("rede caiu") } } }
        runCurrent()
        val waiter = async { runCatching { cache.get("att/1") { secret } } }
        runCurrent()

        gate.complete(Unit)

        assertEquals("rede caiu", assertFailsWith<IOException> { owner.await().getOrThrow() }.message)
        assertEquals("rede caiu", assertFailsWith<IOException> { waiter.await().getOrThrow() }.message)
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
