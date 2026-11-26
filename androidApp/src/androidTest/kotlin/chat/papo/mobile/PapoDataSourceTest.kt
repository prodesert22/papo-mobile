package chat.papo.mobile

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource.HttpDataSourceException
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.test.ext.junit.runners.AndroidJUnit4
import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.data.api.MemoryCookieStore
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.platform.papoDataSourceFactory
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A fonte de dados dos players sobre o `PapoClient` da conta, com um motor HTTP simulado. Prova o
 * que o cliente herda do interceptador (cookie `Auth`, uma única renovação depois de um 401, contas
 * isoladas) e o que a fonte faz com `Range`, 416 e leituras interrompidas. O motor é simulado,
 * exceto nos dois testes com socket de verdade, que falam só com um servidor no loopback do próprio
 * aparelho. Nada sai para a rede nem toca nos dados do app instalado. Os testes rodam fora da
 * thread principal, como o carregador.
 */
@RunWith(AndroidJUnit4::class)
class PapoDataSourceTest {
    private val server = ServerUrl("https://papo.test")
    private val url = server.endpoint("/attachments/att-1")
    private val clients = mutableListOf<PapoClient>()

    @After
    fun closeClients() = clients.forEach(PapoClient::close)

    private fun client(engine: HttpClientEngine, cookie: String? = "jwt-ana", onSessionExpired: suspend () -> Unit = {}): PapoClient =
        client(server, engine, cookie, onSessionExpired)

    private fun client(
        server: ServerUrl,
        engine: HttpClientEngine,
        cookie: String? = "jwt-ana",
        onSessionExpired: suspend () -> Unit = {},
    ): PapoClient = PapoClient(engine, server, MemoryCookieStore(cookie), onSessionExpired).also(clients::add)

    private fun source(client: PapoClient): DataSource = papoDataSourceFactory(client).createDataSource()

    private fun spec(position: Long = 0, length: Long = C.LENGTH_UNSET.toLong(), url: String = this.url): DataSpec =
        DataSpec.Builder().setUri(url).setPosition(position).setLength(length).build()

    private fun bytes(size: Int) = ByteArray(size) { (it % 251).toByte() }

    /**
     * Servidor HTTP/1.1 mínimo na interface de loopback, uma conexão por vez. [serve] recebe o
     * cabeçalho do pedido e o fluxo de saída, e responde por inteiro (status, cabeçalhos e corpo).
     */
    private class LoopbackServer(private val serve: (head: String, out: OutputStream) -> Unit) : AutoCloseable {
        // 127.0.0.1 explícito: no Android o loopback padrão pode ser o ::1, e a URL usa o IPv4.
        private val socket = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        val server = ServerUrl("http://127.0.0.1:${socket.localPort}")
        private val thread = thread(isDaemon = true, name = "loopback-server") {
            while (true) {
                val connection = try {
                    socket.accept()
                } catch (_: IOException) {
                    return@thread // o socket foi fechado: fim do teste
                }
                connection.use {
                    serve(readHead(it.getInputStream()), it.getOutputStream())
                    it.getOutputStream().flush()
                }
            }
        }

        private fun readHead(input: InputStream): String {
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val next = input.read()
                if (next < 0) break
                head.append(next.toChar())
            }
            return head.toString()
        }

        override fun close() {
            socket.close()
            thread.join(5_000)
        }
    }

    private fun MockRequestHandleScope.partial(body: ByteArray, from: Long, total: Long): HttpResponseData = respond(
        content = body,
        status = HttpStatusCode.PartialContent,
        headers = headersOf(
            HttpHeaders.ContentRange to listOf("bytes $from-${from + body.size - 1}/$total"),
            HttpHeaders.ContentLength to listOf(body.size.toString()),
        ),
    )

    private fun MockRequestHandleScope.refreshed(cookie: String): HttpResponseData = respond(
        content = "{}",
        status = HttpStatusCode.OK,
        headers = headersOf(
            HttpHeaders.ContentType to listOf("application/json"),
            HttpHeaders.SetCookie to listOf("Auth=$cookie; Path=/; HttpOnly"),
        ),
    )

    private fun MockRequestHandleScope.problem(status: HttpStatusCode): HttpResponseData = respond(
        content = """{"type":"about:blank","title":"erro","status":${status.value}}""",
        status = status,
        headers = headersOf(HttpHeaders.ContentType, "application/problem+json"),
    )

    private val HttpRequestData.isRefresh get() = method == HttpMethod.Post && url.encodedPath == "/auth/refresh"
    private val HttpRequestData.cookie get() = headers[HttpHeaders.Cookie]
    private val HttpRequestData.range get() = headers[HttpHeaders.Range]

    /** Lê até o fim da resposta, como o carregador faz. */
    private fun DataSource.readAll(): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64)
        while (true) {
            val read = read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) return out.toByteArray()
            out.write(buffer, 0, read)
        }
    }

    // Range e cookie

    @Test
    fun buscarParaOMeioPedeOTrechoDaPosicaoComOCookieDaConta() {
        val content = bytes(900)
        val seen = CopyOnWriteArrayList<HttpRequestData>()
        val source = source(client(MockEngine { request -> seen += request; partial(content, from = 100, total = 1_000) }))

        try {
            assertEquals(900L, source.open(spec(position = 100)))
            assertContentEquals(content, source.readAll())
        } finally {
            source.close()
        }

        val request = seen.single()
        assertEquals("bytes=100-", request.range)
        assertEquals("Auth=jwt-ana", request.cookie)
        assertEquals(url, request.url.toString())
        // Sem compressão: o Range conta bytes do arquivo, não do conteúdo comprimido.
        assertEquals("identity", request.headers[HttpHeaders.AcceptEncoding])
    }

    @Test
    fun aPrimeiraAberturaNaoPedeTrecho() {
        val content = bytes(300)
        val seen = CopyOnWriteArrayList<HttpRequestData>()
        val engine = MockEngine { request ->
            seen += request
            respond(content, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, content.size.toString()))
        }
        val source = source(client(engine))

        try {
            assertEquals(300L, source.open(spec()))
            assertContentEquals(content, source.readAll())
        } finally {
            source.close()
        }

        assertNull(seen.single().range)
        assertEquals("Auth=jwt-ana", seen.single().cookie)
    }

    @Test
    fun semSessaoNaoHaCookieNemRenovacao() {
        val seen = CopyOnWriteArrayList<HttpRequestData>()
        val source = source(client(MockEngine { request -> seen += request; problem(HttpStatusCode.Unauthorized) }, cookie = null))

        val error = assertFailsWith<InvalidResponseCodeException> { source.open(spec()) }

        assertEquals(401, error.responseCode)
        assertNull(seen.single().cookie)
        assertEquals(1, seen.size)
    }

    // Renovação da sessão

    @Test
    fun em401RenovaUmaVezERepeteOTrechoComOCookieNovo() {
        val content = bytes(500)
        val seen = CopyOnWriteArrayList<String>()
        val engine = MockEngine { request ->
            seen += "${request.method.value} ${request.url.encodedPath} ${request.cookie} ${request.range}"
            when {
                request.isRefresh -> refreshed("jwt-novo")
                request.cookie == "Auth=jwt-novo" -> partial(content, from = 500, total = 1_000)
                else -> problem(HttpStatusCode.Unauthorized)
            }
        }
        val cookies = MemoryCookieStore("jwt-velho")
        val source = source(PapoClient(engine, server, cookies).also(clients::add))

        try {
            source.open(spec(position = 500))
            assertContentEquals(content, source.readAll())
        } finally {
            source.close()
        }

        assertEquals(
            listOf(
                "GET /attachments/att-1 Auth=jwt-velho bytes=500-",
                "POST /auth/refresh Auth=jwt-velho null",
                "GET /attachments/att-1 Auth=jwt-novo bytes=500-",
            ),
            seen,
        )
        assertEquals("jwt-novo", cookies.get())
    }

    @Test
    fun seOServidorAindaNegaDepoisDaRenovacaoOErroEhDefinitivo() {
        val seen = CopyOnWriteArrayList<HttpRequestData>()
        val engine = MockEngine { request ->
            seen += request
            if (request.isRefresh) refreshed("jwt-novo") else problem(HttpStatusCode.Unauthorized)
        }
        val source = source(client(engine))

        val error = assertFailsWith<InvalidResponseCodeException> { source.open(spec()) }

        assertEquals(401, error.responseCode)
        assertEquals(1, seen.count { it.isRefresh })
        assertEquals(3, seen.size) // pedido, renovação, pedido repetido: sem terceira volta
    }

    @Test
    fun renovacaoRecusadaEncerraASessaoUmaVezEDevolve401() {
        val seen = CopyOnWriteArrayList<HttpRequestData>()
        var expired = 0
        val engine = MockEngine { request -> seen += request; problem(HttpStatusCode.Unauthorized) }
        val cookies = MemoryCookieStore("jwt-velho")
        val source = source(PapoClient(engine, server, cookies) { expired++ }.also(clients::add))

        val error = assertFailsWith<InvalidResponseCodeException> { source.open(spec()) }

        assertEquals(401, error.responseCode)
        assertEquals(1, expired)
        assertNull(cookies.get())
        assertEquals(1, seen.count { it.isRefresh })
    }

    // Erros de status

    @Test
    fun statusDeErroViraInvalidResponseCodeEAFonteNaoRepeteSozinha() {
        for (status in listOf(HttpStatusCode.NotFound, HttpStatusCode.TooManyRequests, HttpStatusCode.BadGateway)) {
            val seen = CopyOnWriteArrayList<HttpRequestData>()
            val source = source(client(MockEngine { request -> seen += request; problem(status) }))

            val error = assertFailsWith<InvalidResponseCodeException>("status ${status.value}") { source.open(spec()) }

            assertEquals(status.value, error.responseCode)
            assertEquals(1, seen.size, "a repetição é da política do player, não da fonte (status ${status.value})")
        }
    }

    @Test
    fun pedidoAlemDoFimDoArquivoEhErroDeIntervalo() {
        val engine = MockEngine {
            respond("", HttpStatusCode.RequestedRangeNotSatisfiable, headersOf(HttpHeaders.ContentRange, "bytes */500"))
        }
        val source = source(client(engine))

        val error = assertFailsWith<InvalidResponseCodeException> { source.open(spec(position = 900)) }

        assertEquals(416, error.responseCode)
    }

    @Test
    fun pedidoNoFimExatoDoArquivoEhLeituraVazia() {
        val engine = MockEngine {
            respond("", HttpStatusCode.RequestedRangeNotSatisfiable, headersOf(HttpHeaders.ContentRange, "bytes */500"))
        }
        val source = source(client(engine))

        try {
            assertEquals(0L, source.open(spec(position = 500)))
            assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(8), 0, 8))
        } finally {
            source.close()
        }
    }

    // Fluxo interrompido e fechamento

    @Test
    fun conexaoQueCaiNoMeioDoFluxoNaoViraFimDoArquivo() {
        val body = ByteChannel()
        val engine = MockEngine {
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "100"))
        }
        val source = source(client(engine))
        runBlocking {
            body.writeFully(bytes(10))
            body.flush()
        }

        try {
            assertEquals(100L, source.open(spec()))
            assertEquals(10, source.read(ByteArray(64), 0, 64))
            body.cancel(IOException("conexão caiu"))
            // Um fim de arquivo aqui mostraria um vídeo cortado como se estivesse completo.
            assertIs<HttpDataSourceException>(assertFailsWith<IOException> { source.read(ByteArray(64), 0, 64) })
        } finally {
            source.close()
        }
    }

    // Socket de verdade: o motor simulado não mostra o que acontece na conexão nem nos cabeçalhos finais.

    @Test
    fun oRangeEOCookieChegamAoSocketPeloMotorDeProducao() {
        val content = bytes(5)
        val head = AtomicReference<String>()
        LoopbackServer { request, out ->
            head.set(request)
            out.write(
                (
                    "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes 100-104/105\r\n" +
                        "Content-Length: 5\r\nConnection: close\r\n\r\n"
                    ).toByteArray(),
            )
            out.write(content)
        }.use { loopback ->
            val source = source(client(loopback.server, OkHttp.create()))

            try {
                assertEquals(5L, source.open(spec(position = 100, url = loopback.server.endpoint("/attachments/att-1"))))
                assertContentEquals(content, source.readAll())
            } finally {
                source.close()
            }
        }

        val lines = head.get().lines()
        assertEquals("GET /attachments/att-1 HTTP/1.1", lines.first())
        assertTrue("Range: bytes=100-" in lines, head.get())
        assertTrue("Cookie: Auth=jwt-ana" in lines, head.get())
    }

    @Test
    fun fecharNoMeioEncerraAResposta() {
        val serverSawTheEnd = CountDownLatch(1)
        LoopbackServer { _, out ->
            out.write("HTTP/1.1 200 OK\r\nContent-Length: 1000000000\r\nConnection: close\r\n\r\n".toByteArray())
            val chunk = bytes(8_192)
            try {
                // Termina só quando o cliente larga a conexão: com ela aberta, a escrita bloquearia.
                while (true) out.write(chunk)
            } catch (_: IOException) {
                serverSawTheEnd.countDown()
            }
        }.use { loopback ->
            val source = source(client(loopback.server, OkHttp.create()))

            source.open(spec(url = loopback.server.endpoint("/attachments/att-1")))
            assertEquals(64, source.read(ByteArray(64), 0, 64))
            source.close()

            // O player fecha a fonte ao buscar outra posição ou ao sair da tela: a conexão não pode ficar presa.
            assertTrue(serverSawTheEnd.await(10, TimeUnit.SECONDS), "o servidor continuou escrevendo depois do close")
        }
    }

    // Contas e redirecionamento

    @Test
    fun duasContasNoMesmoServidorNaoTrocamDeCookie() {
        val seenAna = CopyOnWriteArrayList<HttpRequestData>()
        val seenBia = CopyOnWriteArrayList<HttpRequestData>()
        val ana = MemoryCookieStore("jwt-ana")
        val bia = MemoryCookieStore("jwt-bia")
        val engineAna = MockEngine { request ->
            seenAna += request
            when {
                request.isRefresh -> refreshed("jwt-ana-novo")
                request.cookie == "Auth=jwt-ana-novo" -> partial(bytes(10), from = 0, total = 10)
                else -> problem(HttpStatusCode.Unauthorized)
            }
        }
        val engineBia = MockEngine { request -> seenBia += request; partial(bytes(10), from = 0, total = 10) }
        val sourceAna = source(PapoClient(engineAna, server, ana).also(clients::add))
        val sourceBia = source(PapoClient(engineBia, server, bia).also(clients::add))

        try {
            // A renovação da Ana (a mesma URL, o mesmo servidor) não pode tocar na sessão da Bia.
            sourceAna.open(spec())
            sourceBia.open(spec())
        } finally {
            sourceAna.close()
            sourceBia.close()
        }

        assertEquals("jwt-ana-novo", ana.get())
        assertEquals("jwt-bia", bia.get())
        assertEquals(listOf("Auth=jwt-bia"), seenBia.map { it.cookie })
        assertTrue(seenAna.all { it.cookie?.startsWith("Auth=jwt-ana") == true })
        assertFalse(seenBia.any { it.isRefresh })
    }

    @Test
    fun registraOQueOPapoClientFazNumRedirecionamentoParaOutroHost() {
        // Hoje o PapoClient segue redirecionamentos e põe o cookie em toda execução. O servidor não
        // redireciona /attachments nem o relay, então isto não acontece em uso normal; o teste só
        // fixa o comportamento atual, para uma mudança futura no cliente não passar despercebida.
        val seen = CopyOnWriteArrayList<HttpRequestData>()
        val engine = MockEngine { request ->
            seen += request
            if (request.url.host == "papo.test") {
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://cdn.outro.test/arquivo"))
            } else {
                partial(bytes(10), from = 0, total = 10)
            }
        }
        val source = source(client(engine))

        try {
            source.open(spec())
        } finally {
            source.close()
        }

        val redirected = seen.single { it.url.host == "cdn.outro.test" }
        assertEquals("Auth=jwt-ana", redirected.cookie)
    }
}
