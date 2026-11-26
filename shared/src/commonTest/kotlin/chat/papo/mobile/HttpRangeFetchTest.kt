package chat.papo.mobile

import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.data.api.ApiException
import chat.papo.mobile.data.api.MemoryCookieStore
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.media.HttpRangeFetch
import chat.papo.mobile.media.RangeReadFailed
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * O pedido de um trecho do anexo pelo cliente da conta: o que sai (`Range`, cookie), o que se aceita de volta e o que
 * acontece nos erros. Motor HTTP simulado; nada sai para a rede.
 */
class HttpRangeFetchTest {
    private val server = ServerUrl("https://papo.test")
    private val url = server.endpoint("/attachments/att-1")
    private val clients = mutableListOf<PapoClient>()

    private fun client(engine: MockEngine, cookie: String? = "jwt-ana", onSessionExpired: suspend () -> Unit = {}) =
        PapoClient(engine, server, MemoryCookieStore(cookie), onSessionExpired).also(clients::add)

    private fun bytes(size: Int) = ByteArray(size) { (it % 251).toByte() }

    private fun MockRequestHandleScope.partial(body: ByteArray, from: Long, total: Long): HttpResponseData = respond(
        content = body,
        status = HttpStatusCode.PartialContent,
        headers = headersOf(HttpHeaders.ContentRange, "bytes $from-${from + body.size - 1}/$total"),
    )

    private fun MockRequestHandleScope.problem(status: HttpStatusCode): HttpResponseData = respond(
        content = """{"type":"about:blank","title":"erro","status":${status.value}}""",
        status = status,
        headers = headersOf(HttpHeaders.ContentType, "application/problem+json"),
    )

    private fun MockRequestHandleScope.refreshed(cookie: String): HttpResponseData = respond(
        content = "{}",
        status = HttpStatusCode.OK,
        headers = headersOf(
            HttpHeaders.ContentType to listOf("application/json"),
            HttpHeaders.SetCookie to listOf("Auth=$cookie; Path=/; HttpOnly"),
        ),
    )

    private val HttpRequestData.isRefresh get() = method == HttpMethod.Post && url.encodedPath == "/auth/refresh"
    private val HttpRequestData.cookie get() = headers[HttpHeaders.Cookie]

    @Test
    fun pedeOTrechoComOCookieDaContaESemCompressao() = runTest {
        val content = bytes(100)
        val seen = mutableListOf<HttpRequestData>()
        val fetch = HttpRangeFetch(client(MockEngine { request -> seen += request; partial(content, from = 100, total = 1_000) }), url)

        val result = fetch.fetch(100, 199)

        assertContentEquals(content, result)
        val request = seen.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals(url, request.url.toString())
        assertEquals("bytes=100-199", request.headers[HttpHeaders.Range])
        assertEquals("Auth=jwt-ana", request.cookie)
        assertEquals("identity", request.headers[HttpHeaders.AcceptEncoding])
    }

    @Test
    fun servidorSemSuporteAoRangeNoComecoDevolveSoOTamanhoPedido() = runTest {
        val whole = bytes(1_000)
        val fetch = HttpRangeFetch(client(MockEngine { respond(whole, HttpStatusCode.OK) }), url)

        assertContentEquals(whole.copyOf(100), fetch.fetch(0, 99))
    }

    @Test
    fun servidorSemSuporteAoRangeNoMeioFalhaEmVezDeBaixarOArquivo() = runTest {
        val fetch = HttpRangeFetch(client(MockEngine { respond(bytes(1_000), HttpStatusCode.OK) }), url)

        assertFailsWith<RangeReadFailed> { fetch.fetch(100, 199) }
    }

    @Test
    fun statusDeErroViraApiExceptionComOStatus() = runTest {
        for (status in listOf(HttpStatusCode.NotFound, HttpStatusCode.Forbidden, HttpStatusCode.TooManyRequests, HttpStatusCode.RequestedRangeNotSatisfiable, HttpStatusCode.BadGateway)) {
            val seen = mutableListOf<HttpRequestData>()
            val fetch = HttpRangeFetch(client(MockEngine { request -> seen += request; problem(status) }), url)

            val error = assertFailsWith<ApiException>("status ${status.value}") { fetch.fetch(0, 99) }

            assertEquals(status.value, error.status)
            // Nunca repete sozinho, nem no 429: a decisão de tentar de novo não é daqui.
            assertEquals(1, seen.size, "status ${status.value}")
        }
    }

    @Test
    fun em401RenovaASessaoUmaVezERepeteOTrechoComOCookieNovo() = runTest {
        val content = bytes(100)
        val seen = mutableListOf<String>()
        val engine = MockEngine { request ->
            seen += "${request.method.value} ${request.url.encodedPath} ${request.cookie} ${request.headers[HttpHeaders.Range]}"
            when {
                request.isRefresh -> refreshed("jwt-novo")
                request.cookie == "Auth=jwt-novo" -> partial(content, from = 0, total = 1_000)
                else -> problem(HttpStatusCode.Unauthorized)
            }
        }
        val fetch = HttpRangeFetch(client(engine, cookie = "jwt-velho"), url)

        assertContentEquals(content, fetch.fetch(0, 99))

        assertEquals(
            listOf(
                "GET /attachments/att-1 Auth=jwt-velho bytes=0-99",
                "POST /auth/refresh Auth=jwt-velho null",
                "GET /attachments/att-1 Auth=jwt-novo bytes=0-99",
            ),
            seen,
        )
    }

    @Test
    fun renovacaoRecusadaEncerraASessaoUmaVezEFalhaCom401() = runTest {
        var expired = 0
        val seen = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request -> seen += request; problem(HttpStatusCode.Unauthorized) }
        val fetch = HttpRangeFetch(client(engine, cookie = "jwt-velho") { expired++ }, url)

        val error = assertFailsWith<ApiException> { fetch.fetch(0, 99) }

        assertEquals(401, error.status)
        assertEquals(1, expired)
        assertEquals(1, seen.count { it.isRefresh })
    }

    @Test
    fun semSessaoNaoHaCookieNemRenovacao() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val fetch = HttpRangeFetch(client(MockEngine { request -> seen += request; problem(HttpStatusCode.Unauthorized) }, cookie = null), url)

        assertFailsWith<ApiException> { fetch.fetch(0, 99) }

        assertNull(seen.single().cookie)
        assertEquals(1, seen.size)
    }

    @Test
    fun duasContasNoMesmoServidorNaoTrocamDeCookie() = runTest {
        val seenAna = mutableListOf<String?>()
        val seenBia = mutableListOf<String?>()
        val ana = HttpRangeFetch(client(MockEngine { request -> seenAna += request.cookie; partial(bytes(10), 0, 10) }, cookie = "jwt-ana"), url)
        val bia = HttpRangeFetch(client(MockEngine { request -> seenBia += request.cookie; partial(bytes(10), 0, 10) }, cookie = "jwt-bia"), url)

        ana.fetch(0, 9)
        bia.fetch(0, 9)

        assertEquals(listOf<String?>("Auth=jwt-ana"), seenAna)
        assertEquals(listOf<String?>("Auth=jwt-bia"), seenBia)
    }
}
