package chat.papo.mobile

import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.chat.WebhookAvatars
import chat.papo.mobile.data.api.ChatApi
import chat.papo.mobile.data.api.MemoryCookieStore
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.media.MediaCache
import chat.papo.mobile.platform.JcaCrypto
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.isSuccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * O avatar de webhook vem do servidor uma vez por sessão e, depois, do cache de mídia cifrado. O motor HTTP falso
 * responde em outra thread: o que ele guarda é lido de volta com `waitUntil`, e o relógio dos 30 s é o virtual.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebhookAvatarsTest {
    private val crypto = JcaCrypto()
    private val key = crypto.randomBytes(32)
    private val red = byteArrayOf(1, 2, 3)
    private val blue = byteArrayOf(9, 8, 7, 6)

    /** Um servidor Papo com o avatar de um webhook; [body] e [status] mudam o que ele responde da próxima vez. */
    private class Server(@Volatile var body: ByteArray) {
        val requests = CopyOnWriteArrayList<String>()
        @Volatile var status = HttpStatusCode.OK
        @Volatile var failure: IOException? = null
        @Volatile var gate: CompletableDeferred<Unit>? = null

        val engine = MockEngine { request ->
            requests += request.url.encodedPath
            gate?.await()
            failure?.let { throw it }
            if (status.isSuccess()) {
                respond(body, status, headersOf(HttpHeaders.ContentType, "image/png"))
            } else {
                respond(
                    """{"type":"t","title":"Erro","status":${status.value},"detail":"falhou"}""",
                    status,
                    headersOf(HttpHeaders.ContentType, "application/problem+json"),
                )
            }
        }
    }

    private fun TestScope.cache() = MediaCache(TestResources.tempDir(), key, crypto, StandardTestDispatcher(testScheduler))

    /** Uma sessão da conta: o [WebhookAvatars] dela sobre o [media] da conta; abrir outra sobre o mesmo [media] é reabrir o app. */
    private fun TestScope.avatars(server: Server, media: MediaCache = cache()) = WebhookAvatars(
        api = ChatApi(PapoClient(server.engine, ServerUrl("https://papo.test"), MemoryCookieStore("jwt"))),
        media = media,
        scope = backgroundScope,
        timeSource = testScheduler.timeSource,
    )

    @Test
    fun chamadasSimultaneasDoMesmoWebhookFazemUmPedidoSo() = runTest {
        val server = Server(red).also { it.gate = CompletableDeferred() }
        val avatars = avatars(server)

        val callers = List(5) { async { avatars.get("w1") } }
        TestResources.waitUntil { server.requests.isNotEmpty() }
        runCurrent()
        server.gate!!.complete(Unit)

        callers.forEach { assertContentEquals(red, it.await()) }
        assertEquals(listOf("/webhooks/w1/avatar"), server.requests.toList())
    }

    @Test
    fun segundaChamadaNaSessaoNaoVaiAoServidorEVemDoCache() = runTest {
        val server = Server(red)
        val avatars = avatars(server)

        assertContentEquals(red, avatars.get("w1"))
        server.body = blue
        assertContentEquals(red, avatars.get("w1"))

        assertEquals(1, server.requests.size)
    }

    @Test
    fun webhooksDiferentesTemPedidosEAvataresProprios() = runTest {
        val server = Server(red)
        val avatars = avatars(server)

        avatars.get("w1")
        server.body = blue
        assertContentEquals(blue, avatars.get("w2"))
        assertContentEquals(red, avatars.get("w1"))

        assertEquals(listOf("/webhooks/w1/avatar", "/webhooks/w2/avatar"), server.requests.toList())
    }

    @Test
    fun sessaoNovaPerguntaDeNovoEAImagemNovaSubstituiAGuardada() = runTest {
        val server = Server(red)
        val media = cache()
        avatars(server, media).get("w1")
        server.body = blue

        val reopened = avatars(server, media)
        // Antes da resposta, o que ficou da sessão anterior já dá o que mostrar.
        assertContentEquals(red, reopened.cached("w1"))
        assertContentEquals(blue, reopened.get("w1"))

        assertEquals(2, server.requests.size)
        assertContentEquals(blue, reopened.cached("w1"))
        assertContentEquals(blue, avatars(Server(red), media).cached("w1"))
    }

    @Test
    fun respostaNaoEncontradaDevolveNuloEApagaOQueEstavaGuardado() = runTest {
        val server = Server(red)
        val media = cache()
        avatars(server, media).get("w1")
        server.status = HttpStatusCode.NotFound

        val reopened = avatars(server, media)
        assertNull(reopened.get("w1"))

        assertNull(reopened.cached("w1"))
        assertNull(media.peek(WebhookAvatars.mediaName("w1")))
        // O servidor já disse que não há avatar: não se pergunta de novo nesta sessão.
        assertNull(reopened.get("w1"))
        assertEquals(2, server.requests.size)
    }

    @Test
    fun webhookQueNuncaTeveAvatarNaoPedeDeNovoNaSessao() = runTest {
        val server = Server(red).also { it.status = HttpStatusCode.NotFound }
        val avatars = avatars(server)

        assertNull(avatars.get("w1"))
        assertNull(avatars.get("w1"))

        assertEquals(1, server.requests.size)
    }

    @Test
    fun erroDoServidorServeAImagemGuardadaESoPerguntaDeNovoDepoisDeTrintaSegundos() = runTest {
        val server = Server(red)
        val media = cache()
        avatars(server, media).get("w1")
        server.status = HttpStatusCode.TooManyRequests

        val reopened = avatars(server, media)
        assertContentEquals(red, reopened.get("w1"))
        assertEquals(2, server.requests.size)

        assertContentEquals(red, reopened.get("w1"))
        advanceTimeBy(29.seconds)
        assertContentEquals(red, reopened.get("w1"))
        assertEquals(2, server.requests.size)

        advanceTimeBy(2.seconds)
        server.status = HttpStatusCode.OK
        server.body = blue
        assertContentEquals(blue, reopened.get("w1"))
        assertEquals(3, server.requests.size)

        // Respondeu: a sessão não pergunta mais.
        assertContentEquals(blue, reopened.get("w1"))
        assertEquals(3, server.requests.size)
    }

    @Test
    fun redeForaDoArServeAImagemGuardada() = runTest {
        val server = Server(red)
        val media = cache()
        avatars(server, media).get("w1")
        server.failure = IOException("sem rede")

        assertContentEquals(red, avatars(server, media).get("w1"))
    }

    @Test
    fun falhaSemNadaGuardadoDevolveNuloENaoEstouraNemTravaANovaTentativa() = runTest {
        val server = Server(red).also { it.status = HttpStatusCode.InternalServerError }
        val avatars = avatars(server)

        assertNull(avatars.get("w1"))
        assertNull(avatars.get("w1"))
        assertEquals(1, server.requests.size)

        advanceTimeBy(31.seconds)
        server.status = HttpStatusCode.OK
        assertContentEquals(red, avatars.get("w1"))
        assertEquals(2, server.requests.size)
    }

    @Test
    fun duasContasNaoCompartilhamAvatarNemPedido() = runTest {
        val first = Server(red)
        val second = Server(blue)
        val firstAvatars = avatars(first)
        val secondAvatars = avatars(second)

        assertContentEquals(red, firstAvatars.get("w1"))
        // O que a primeira conta guardou não aparece na segunda, nem como imagem provisória.
        assertNull(secondAvatars.cached("w1"))
        assertContentEquals(blue, secondAvatars.get("w1"))

        assertContentEquals(red, firstAvatars.cached("w1"))
        assertEquals(1, first.requests.size)
        assertEquals(1, second.requests.size)
    }

    @Test
    fun cachedNaoFalaComOServidor() = runTest {
        val server = Server(red)
        val avatars = avatars(server)

        assertNull(avatars.cached("w1"))

        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun cancelarQuemPediuPrimeiroNaoDeixaOsOutrosSemRespostaNemRepetePedido() = runTest {
        val server = Server(red).also { it.gate = CompletableDeferred() }
        val avatars = avatars(server)
        val first = launch { avatars.get("w1") }
        val second = async { avatars.get("w1") }
        TestResources.waitUntil { server.requests.isNotEmpty() }
        runCurrent()

        first.cancel()
        runCurrent()
        server.gate!!.complete(Unit)

        assertContentEquals(red, second.await())
        assertTrue(first.isCancelled)
        assertContentEquals(red, avatars.get("w1"))
        assertEquals(1, server.requests.size)
    }

    @Test
    fun cancelarOUnicoQuePediuNaoDeixaAEntradaPresaEAImagemChegaAoCache() = runTest {
        val server = Server(red).also { it.gate = CompletableDeferred() }
        val avatars = avatars(server)
        val only = launch { avatars.get("w1") }
        TestResources.waitUntil { server.requests.isNotEmpty() }
        runCurrent()

        only.cancel()
        runCurrent()
        server.gate!!.complete(Unit)

        assertContentEquals(red, avatars.get("w1"))
        assertEquals(1, server.requests.size)
    }
}
