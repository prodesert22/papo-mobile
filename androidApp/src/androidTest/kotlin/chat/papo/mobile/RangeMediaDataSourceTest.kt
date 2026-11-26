package chat.papo.mobile

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.data.api.MemoryCookieStore
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.media.HttpRangeFetch
import chat.papo.mobile.media.RangeReader
import chat.papo.mobile.platform.AndroidVideoPosterExtractor
import chat.papo.mobile.platform.RangeMediaDataSource
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A fonte de dados da capa de vídeo sobre o `PapoClient` da conta, e o extrator inteiro com o `MediaMetadataRetriever`
 * de verdade (que só existe no aparelho), lendo MP4 pequenos de `assets/poster`. O motor HTTP é simulado e serve `Range`
 * como o servidor; nada sai para a rede nem toca nos dados do app instalado. Os testes rodam fora da thread principal.
 */
@RunWith(AndroidJUnit4::class)
class RangeMediaDataSourceTest {
    private val server = ServerUrl("https://papo.test")
    private val url = server.endpoint("/attachments/att-1")
    private val clients = mutableListOf<PapoClient>()
    private val blockSize = 4_096

    @After
    fun closeClients() = clients.forEach(PapoClient::close)

    private fun asset(name: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open("poster/$name").use { it.readBytes() }

    private fun client(engine: MockEngine, cookie: String? = "jwt-ana"): PapoClient =
        PapoClient(engine, server, MemoryCookieStore(cookie)).also(clients::add)

    private fun MockRequestHandleScope.partial(content: ByteArray, range: String): HttpResponseData {
        val (from, to) = Regex("bytes=(\\d+)-(\\d+)").matchEntire(range)!!.destructured
        val end = minOf(to.toLong(), content.size - 1L).toInt()
        return respond(
            content = content.copyOfRange(from.toInt(), end + 1),
            status = HttpStatusCode.PartialContent,
            headers = headersOf(HttpHeaders.ContentRange, "bytes $from-$end/${content.size}"),
        )
    }

    /** Serve [content] como o servidor serve um anexo: 206 para `Range`, e registra cada pedido. */
    private fun servingRanges(content: ByteArray, seen: MutableList<HttpRequestData> = CopyOnWriteArrayList()) = MockEngine { request ->
        seen += request
        val range = request.headers[HttpHeaders.Range]
        if (range == null) respond(content, HttpStatusCode.OK) else partial(content, range)
    }

    private fun reader(client: PapoClient, size: Int, budget: Long = 1_000_000, pauseMs: Long = 0) =
        RangeReader(size.toLong(), HttpRangeFetch(client, url), blockSize = blockSize, budget = budget, pauseMs = pauseMs)

    private val HttpRequestData.cookie get() = headers[HttpHeaders.Cookie]
    private val HttpRequestData.range get() = headers[HttpHeaders.Range]

    // A fonte de dados

    @Test
    fun lerPedeOBlocoComRangeEOCookieDaContaEDevolveOTamanhoDoArquivo() {
        val content = asset("moov-end.mp4")
        val seen = CopyOnWriteArrayList<HttpRequestData>()
        val source = RangeMediaDataSource(reader(client(servingRanges(content, seen)), content.size))

        try {
            assertEquals(content.size.toLong(), source.size)
            val buffer = ByteArray(20)
            assertEquals(20, source.readAt(100, buffer, 0, 20))
            assertContentEquals(content.copyOfRange(100, 120), buffer)
        } finally {
            source.close()
        }

        val request = seen.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals(url, request.url.toString())
        assertEquals("bytes=0-${blockSize - 1}", request.range)
        assertEquals("Auth=jwt-ana", request.cookie)
    }

    @Test
    fun lerDeNovoNoMesmoBlocoNaoPedeNada() {
        val content = asset("moov-end.mp4")
        val seen = CopyOnWriteArrayList<HttpRequestData>()
        val source = RangeMediaDataSource(reader(client(servingRanges(content, seen)), content.size))

        try {
            source.readAt(0, ByteArray(32), 0, 32)
            source.readAt(2_000, ByteArray(64), 0, 64)
        } finally {
            source.close()
        }

        assertEquals(1, seen.size)
    }

    @Test
    fun lerNoUltimoBlocoPedeSoOQueExisteELerAlemDoFimDevolveMenosUm() {
        val content = asset("moov-end.mp4")
        val seen = CopyOnWriteArrayList<HttpRequestData>()
        val source = RangeMediaDataSource(reader(client(servingRanges(content, seen)), content.size))

        try {
            val buffer = ByteArray(64)
            assertEquals(content.size - 9_400, source.readAt(9_400, buffer, 0, 64))
            assertEquals(-1, source.readAt(content.size.toLong(), buffer, 0, 64))
        } finally {
            source.close()
        }

        assertEquals("bytes=8192-${content.size - 1}", seen.single().range)
    }

    @Test
    fun oOrcamentoParaDeLerViraIOExceptionSemPedirOBlocoQueEstouraria() {
        val content = asset("moov-end.mp4")
        val seen = CopyOnWriteArrayList<HttpRequestData>()
        val source = RangeMediaDataSource(reader(client(servingRanges(content, seen)), content.size, budget = 2L * blockSize))

        try {
            source.readAt(0, ByteArray(8), 0, 8)
            source.readAt(blockSize.toLong(), ByteArray(8), 0, 8)
            assertFailsWith<IOException> { source.readAt(2L * blockSize, ByteArray(8), 0, 8) }
            // O que já veio continua lendo: o orçamento só impede o próximo pedido.
            assertEquals(8, source.readAt(10, ByteArray(8), 0, 8))
        } finally {
            source.close()
        }

        assertEquals(2, seen.size)
    }

    @Test
    fun falhaDeRedeEmUmPedidoViraIOExceptionEAFonteSegueUsavel() {
        val content = asset("moov-end.mp4")
        var broken = true
        val engine = MockEngine { request ->
            if (broken) throw IOException("conexão caiu")
            partial(content, request.range!!)
        }
        val source = RangeMediaDataSource(reader(client(engine), content.size))

        try {
            assertFailsWith<IOException> { source.readAt(0, ByteArray(8), 0, 8) }
            broken = false
            assertEquals(8, source.readAt(0, ByteArray(8), 0, 8))
        } finally {
            source.close()
        }
    }

    @Test
    fun servidorQueNegaViraIOExceptionSemRepetirOPedido() {
        val seen = CopyOnWriteArrayList<HttpRequestData>()
        val engine = MockEngine { request ->
            seen += request
            respond("""{"type":"about:blank","title":"erro","status":429}""", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.ContentType, "application/problem+json"))
        }
        val source = RangeMediaDataSource(reader(client(engine), 9_423))

        try {
            val error = assertFailsWith<IOException> { source.readAt(0, ByteArray(8), 0, 8) }
            assertNotNull(error.cause)
        } finally {
            source.close()
        }

        assertEquals(1, seen.size)
    }

    @Test
    fun fecharRecusaAsLeiturasSeguintesSemPedirNada() {
        val content = asset("moov-end.mp4")
        val seen = CopyOnWriteArrayList<HttpRequestData>()
        val source = RangeMediaDataSource(reader(client(servingRanges(content, seen)), content.size))
        source.readAt(0, ByteArray(8), 0, 8)

        source.close()

        assertFailsWith<IOException> { source.readAt(0, ByteArray(8), 0, 8) }
        assertFailsWith<IOException> { source.readAt(5_000, ByteArray(8), 0, 8) }
        assertEquals(1, seen.size)
    }

    @Test
    fun fecharNoMeioDeUmPedidoInterrompeAEsperaECancelaOPedido() {
        val requestSeen = CountDownLatch(1)
        val requestCancelled = CountDownLatch(1)
        val engine = MockEngine {
            requestSeen.countDown()
            try {
                CompletableDeferred<Unit>().await() // o servidor nunca responde
            } finally {
                requestCancelled.countDown()
            }
            error("inalcançável")
        }
        val source = RangeMediaDataSource(reader(client(engine), 9_423))
        val failure = AtomicReference<Throwable>()
        val reading = thread(name = "leitura-bloqueada") {
            try {
                source.readAt(0, ByteArray(8), 0, 8)
            } catch (e: Throwable) {
                failure.set(e)
            }
        }

        assertTrue(requestSeen.await(10, TimeUnit.SECONDS), "o pedido não chegou ao motor")
        source.close()
        reading.join(10_000)

        assertTrue(!reading.isAlive, "a leitura continuou bloqueada depois de fechar")
        assertTrue(failure.get() is IOException, "esperava IOException, veio ${failure.get()}")
        assertTrue(requestCancelled.await(10, TimeUnit.SECONDS), "o pedido não foi cancelado")
    }

    // O extrator com o MediaMetadataRetriever de verdade

    private fun extract(reader: RangeReader, maxPx: Int = 64): ByteArray? = runBlocking {
        withTimeout(60_000) { AndroidVideoPosterExtractor().extract(reader, maxPx) }
    }

    private fun assertOnlyRangeRequestsWithTheAccountCookie(seen: List<HttpRequestData>) {
        assertTrue(seen.isNotEmpty())
        seen.forEach { request ->
            assertNotNull(request.range, "pedido sem Range: ${request.url}")
            assertEquals("Auth=jwt-ana", request.cookie)
            assertEquals(url, request.url.toString())
        }
        // Cada bloco é pedido uma só vez.
        assertEquals(seen.map { it.range }.distinct().size, seen.size)
    }

    @Test
    fun tiraACapaDeUmMp4ComOMoovNoInicioSoPorTrechos() {
        val content = asset("moov-start.mp4")
        val seen = CopyOnWriteArrayList<HttpRequestData>()
        val reader = reader(client(servingRanges(content, seen)), content.size)

        val jpeg = assertNotNull(extract(reader))

        val bitmap = assertNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
        // 160 x 90 cabe em 64 x 64 como 64 x 36, na proporção do vídeo.
        assertEquals(64, bitmap.width)
        assertTrue(bitmap.height in 35..37, "altura ${bitmap.height}")
        assertOnlyRangeRequestsWithTheAccountCookie(seen)
        assertTrue(reader.fetchedBytes <= content.size + blockSize)
    }

    @Test
    fun tiraACapaDeUmMp4ComOMoovNoFimLendoOUltimoBloco() {
        val content = asset("moov-end.mp4")
        val seen = CopyOnWriteArrayList<HttpRequestData>()
        val reader = reader(client(servingRanges(content, seen)), content.size)

        val jpeg = assertNotNull(extract(reader))

        assertNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
        assertOnlyRangeRequestsWithTheAccountCookie(seen)
        // O moov está no último bloco (a partir de 8192): sem ele não haveria quadro.
        assertTrue(seen.any { it.range == "bytes=8192-${content.size - 1}" }, seen.map { it.range }.toString())
    }

    @Test
    fun aCapaDeUmVideoGiradoSaiEmPe() {
        val content = asset("rotated.mp4")
        val reader = reader(client(servingRanges(content)), content.size)

        val jpeg = assertNotNull(extract(reader, maxPx = 64))

        val bitmap = assertNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
        // 160 x 90 com rotação de 90 graus é um vídeo em pé (90 x 160): 36 x 64.
        assertEquals(64, bitmap.height)
        assertTrue(bitmap.width in 35..37, "largura ${bitmap.width}")
    }

    @Test
    fun oMaiorLadoDaCapaNuncaPassaDoPedidoENuncaAmplia() {
        val content = asset("moov-start.mp4")
        val reader = reader(client(servingRanges(content)), content.size)

        val jpeg = assertNotNull(extract(reader, maxPx = 640))

        val bitmap = assertNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
        assertEquals(160, bitmap.width)
        assertEquals(90, bitmap.height)
    }

    @Test
    fun arquivoQueNaoEhVideoDeixaOExtratorSemCapaSemLancar() {
        val content = ByteArray(9_000) { (it * 7).toByte() }
        val seen = CopyOnWriteArrayList<HttpRequestData>()
        val reader = reader(client(servingRanges(content, seen)), content.size)

        assertNull(extract(reader))

        assertOnlyRangeRequestsWithTheAccountCookie(seen)
    }

    @Test
    fun orcamentoCurtoDesisteSemBaixarOArquivo() {
        val content = asset("moov-end.mp4")
        val seen = CopyOnWriteArrayList<HttpRequestData>()
        // Um bloco só: o moov, no fim, nunca caberia junto com o começo.
        val reader = reader(client(servingRanges(content, seen)), content.size, budget = blockSize.toLong())

        assertNull(extract(reader))

        assertTrue(reader.fetchedBytes <= blockSize)
        assertTrue(seen.size <= 1, seen.map { it.range }.toString())
    }

    /** Um servidor que recebe o pedido e nunca responde; os travas dizem quando o pedido chegou e quando foi cancelado. */
    private class SilentServer {
        val requestSeen = CountDownLatch(1)
        val requestCancelled = CountDownLatch(1)
        val engine = MockEngine {
            requestSeen.countDown()
            try {
                CompletableDeferred<Unit>().await()
            } finally {
                requestCancelled.countDown()
            }
            error("inalcançável")
        }
    }

    @Test
    fun cancelarInterrompeALeituraELiberaOExtratorSemEsperarOServidor() {
        val silent = SilentServer()
        val reader = reader(client(silent.engine), 9_423)
        // Escopo próprio: se o extrator ficar preso, o teste falha pelo tempo em vez de esperar por ele para sempre.
        val scope = CoroutineScope(Dispatchers.Default + Job())

        try {
            runBlocking {
                val job = scope.async { AndroidVideoPosterExtractor().extract(reader, 64) }
                assertTrue(silent.requestSeen.await(30, TimeUnit.SECONDS), "o pedido não chegou ao motor")
                job.cancel()
                withTimeout(30_000) { job.join() }
                assertTrue(job.isCancelled)
            }
            assertTrue(silent.requestCancelled.await(10, TimeUnit.SECONDS), "o pedido não foi cancelado")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun passadoOLimiteDeTempoDevolveSemCapaECancelaOPedido() {
        val silent = SilentServer()
        val reader = reader(client(silent.engine), 9_423)
        val scope = CoroutineScope(Dispatchers.Default + Job())

        try {
            val poster = runBlocking {
                val job = scope.async { AndroidVideoPosterExtractor(timeoutMs = 1_000).extract(reader, 64) }
                withTimeout(30_000) { job.await() }
            }

            // Sem exceção: o limite de tempo é uma extração que falhou, não um cancelamento de quem pediu.
            assertNull(poster)
            assertTrue(silent.requestSeen.await(1, TimeUnit.SECONDS))
            assertTrue(silent.requestCancelled.await(10, TimeUnit.SECONDS), "o pedido não foi cancelado")
        } finally {
            scope.cancel()
        }
    }
}
