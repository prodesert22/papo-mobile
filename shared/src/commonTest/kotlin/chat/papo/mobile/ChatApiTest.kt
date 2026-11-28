package chat.papo.mobile

import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.core.dayKey
import chat.papo.mobile.core.formatDate
import chat.papo.mobile.core.formatDateTime
import chat.papo.mobile.core.formatTime
import chat.papo.mobile.core.parseInstant
import chat.papo.mobile.data.api.ApiException
import chat.papo.mobile.data.api.ChannelPermissionEntryDto
import chat.papo.mobile.data.api.ChannelPermissionsDto
import chat.papo.mobile.data.api.ChatApi
import chat.papo.mobile.data.api.MemoryCookieStore
import chat.papo.mobile.data.api.FileUnavailableException
import chat.papo.mobile.data.api.OutgoingFile
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.data.api.ReactionRequest
import chat.papo.mobile.data.api.RolePermissionsDto
import chat.papo.mobile.data.api.SearchRequest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.concurrent.Volatile

class ChatApiTest {
    private val message = """{"id":"m1","channel_id":"c1","author_id":"u1","content":"oi","created_at":"2026-01-01T00:00:00Z"}"""

    private class Recorded(val request: HttpRequestData, val body: String)

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
        respond(body, status, headersOf(HttpHeaders.ContentType, if (status.value >= 400) "application/problem+json" else "application/json"))

    private fun api(seen: MutableList<Recorded>, reply: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): ChatApi {
        val engine = MockEngine { request ->
            seen += Recorded(request, request.body.toByteArray().decodeToString())
            reply(request)
        }
        return ChatApi(PapoClient(engine, ServerUrl("https://papo.test"), MemoryCookieStore("jwt")))
    }

    private fun Recorded.part(name: String): String? =
        Regex("""name="?$name"?[^\r\n]*\r\n(?:[^\r\n]+\r\n)*\r\n([^\r]*)""").find(body)?.groupValues?.get(1)

    @Test
    fun enviarMensagemUsaOsCamposDoMultipartDoBackend() = runTest {
        val seen = mutableListOf<Recorded>()
        val api = api(seen) { json(message, HttpStatusCode.Created) }
        val files = listOf(
            bytesFile("foto.png", "image/png", byteArrayOf(1, 2, 3)),
            bytesFile("nota \"final\".txt", "text/plain", "ab".encodeToByteArray()),
        )

        val sent = api.sendMessage("c1", "olá, mundo", replyTo = "m0", files = files)

        assertEquals("m1", sent.id)
        val recorded = seen.single()
        assertEquals(HttpMethod.Post, recorded.request.method)
        assertEquals("/messages", recorded.request.url.encodedPath)
        assertTrue(recorded.request.body.contentType.toString().startsWith("multipart/form-data"))
        assertEquals("c1", recorded.part("channel_id"))
        assertEquals("olá, mundo", recorded.part("content"))
        assertEquals("m0", recorded.part("reply_to"))
        // Arquivos vão todos no campo repetido `attachments`, com nome e tipo.
        assertEquals(2, Regex("""name="?attachments"?""").findAll(recorded.body).count())
        assertTrue("filename=\"foto.png\"" in recorded.body)
        assertTrue("filename=\"nota final.txt\"" in recorded.body) // aspas do nome não quebram o cabeçalho
        assertTrue("Content-Type: image/png" in recorded.body)
        assertEquals("Auth=jwt", recorded.request.headers[HttpHeaders.Cookie])
    }

    @Test
    fun mensagemSoComAnexoNaoMandaContentNemReplyTo() = runTest {
        val seen = mutableListOf<Recorded>()
        val api = api(seen) { json(message, HttpStatusCode.Created) }
        api.sendMessage("c1", "", replyTo = null, files = listOf(bytesFile("a.bin", "application/octet-stream", byteArrayOf(9))))
        val recorded = seen.single()
        assertNull(recorded.part("content"))
        assertNull(recorded.part("reply_to"))
        assertEquals("c1", recorded.part("channel_id"))
    }

    @Test
    fun enviarMensagemRelataOProgressoDoCorpoAteOTotal() = runTest {
        val seen = mutableListOf<Recorded>()
        val api = api(seen) { json(message, HttpStatusCode.Created) }
        val reported = mutableListOf<Pair<Long, Long?>>()

        api.sendMessage(
            "c1", "oi", replyTo = null,
            files = listOf(bytesFile("a.bin", "application/octet-stream", ByteArray(50_000))),
            onProgress = { sent, total -> reported += sent to total },
        )

        assertTrue(reported.isNotEmpty())
        val total = reported.last().second
        assertTrue(total != null && total > 50_000) // o corpo leva o arquivo e os campos do multipart
        assertEquals(total to total, reported.last().first to reported.last().second) // o último aviso é o corpo inteiro
        assertEquals(reported.map { it.first }.sorted(), reported.map { it.first }) // só cresce
    }

    private fun apiOn(engine: MockEngine) = ChatApi(PapoClient(engine, ServerUrl("https://papo.test"), MemoryCookieStore("jwt")))

    private fun ByteArray.indexOfBytes(part: ByteArray): Int {
        for (start in 0..size - part.size) if ((part.indices).all { this[start + it] == part[it] }) return start
        return -1
    }

    @Test
    fun oArquivoSaiByteAByteNoCorpoEOTamanhoDeclaradoFechaComOCorpo() = runTest {
        val content = ByteArray(300) { it.toByte() } // todos os valores de byte, para não passar por texto
        var declared: Long? = null
        var body = ByteArray(0)
        val engine = MockEngine { request ->
            declared = request.body.contentLength
            body = request.body.toByteArray()
            json(message, HttpStatusCode.Created)
        }

        apiOn(engine).sendMessage("c1", "oi", replyTo = null, files = listOf(bytesFile("a.bin", "application/octet-stream", content)))

        assertTrue(body.indexOfBytes(content) > 0)
        assertEquals(body.size.toLong(), declared) // o tamanho do arquivo entra na conta do Content-Length
    }

    @Test
    fun arquivoSemTamanhoFazOCorpoSairEmPedacosSemTotal() = runTest {
        var declared: Long? = -1
        var body = ByteArray(0)
        val engine = MockEngine { request ->
            declared = request.body.contentLength
            body = request.body.toByteArray()
            json(message, HttpStatusCode.Created)
        }
        val content = "sem tamanho".encodeToByteArray()
        val file = OutgoingFile("a.txt", "text/plain", size = null, open = { ByteReadChannel(content) })
        val totals = mutableListOf<Long?>()

        apiOn(engine).sendMessage("c1", "", replyTo = null, files = listOf(file), onProgress = { _, total -> totals += total })

        assertNull(declared)
        assertTrue(body.indexOfBytes(content) > 0)
        assertTrue(totals.all { it == null })
    }

    @Test
    fun repetirDepoisDe401AbreOArquivoDeNovoERecomecaOCorpoDoZero() = runTest {
        var opens = 0
        val bodies = mutableListOf<ByteArray>()
        val cookies = mutableListOf<String?>()
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/auth/refresh" -> respond(
                    """{"connection":{"id":"c1"}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.SetCookie to listOf("Auth=novo; Path=/")),
                )
                else -> {
                    cookies += request.headers[HttpHeaders.Cookie]
                    bodies += request.body.toByteArray()
                    if (bodies.size == 1) {
                        json("""{"type":"t","title":"Não autorizado","status":401,"detail":"Token expirado"}""", HttpStatusCode.Unauthorized)
                    } else {
                        json(message, HttpStatusCode.Created)
                    }
                }
            }
        }
        val content = byteArrayOf(7, 8, 9)
        val file = OutgoingFile("a.bin", "application/octet-stream", 3, open = { opens++; ByteReadChannel(content) })

        val sent = apiOn(engine).sendMessage("c1", "oi", replyTo = null, files = listOf(file))

        assertEquals("m1", sent.id)
        assertEquals(2, opens) // a repetição lê o arquivo do zero, não reaproveita o que já saiu
        assertEquals(listOf<String?>("Auth=jwt", "Auth=novo"), cookies)
        assertTrue(bodies.all { it.indexOfBytes(content) > 0 })
    }

    @Test
    fun arquivoQueNaoAbreViraFileUnavailableComONomeEACausa() = runTest {
        val engine = MockEngine { request ->
            request.body.toByteArray()
            json(message, HttpStatusCode.Created)
        }
        val gone = IOException("sumiu")
        val file = OutgoingFile("foto.jpg", "image/jpeg", 10, open = { throw gone })

        val failure = assertFailsWith<FileUnavailableException> { apiOn(engine).sendMessage("c1", "oi", replyTo = null, files = listOf(file)) }

        assertEquals("foto.jpg", failure.fileName)
        assertEquals(gone, failure.cause)
    }

    @Test
    fun arquivoQueFalhaNoMeioDaLeituraTambemViraFileUnavailable() = runTest {
        val engine = MockEngine { request ->
            request.body.toByteArray()
            json(message, HttpStatusCode.Created)
        }
        val lost = FileUnavailableException("video.mp4", IOException("o provedor fechou"))
        val source = FailingSource(ByteArray(5000), failAfter = 2000, error = lost)
        val file = OutgoingFile("video.mp4", "video/mp4", 5000, open = { ByteReadChannel(source.buffered()) })

        val failure = assertFailsWith<FileUnavailableException> { apiOn(engine).sendMessage("c1", "oi", replyTo = null, files = listOf(file)) }

        assertEquals("video.mp4", failure.fileName)
        assertTrue(source.closed) // o fluxo que o envio abriu é fechado mesmo quando a leitura falha no meio
    }

    @Test
    fun falharAoFecharOFluxoNaoMudaOResultadoDoEnvio() = runTest {
        val source = ClosingSource("conteúdo".encodeToByteArray(), failToClose = true)
        val engine = MockEngine { request ->
            request.body.toByteArray()
            json(message, HttpStatusCode.Created)
        }
        val file = OutgoingFile("a.txt", "text/plain", 9, open = { ByteReadChannel(source.buffered()) })

        val sent = apiOn(engine).sendMessage("c1", "oi", replyTo = null, files = listOf(file))

        assertEquals("m1", sent.id)
        assertTrue(source.closed)
    }

    @Test
    fun oEnvioFechaOFluxoDeCadaArquivoQueAbriuMesmoSeORestoDoCorpoNaoFoiLido() = runTest {
        val first = GeneratedSource(1L shl 20) // grande o bastante para o motor parar de ler com o arquivo ainda no meio
        val second = ClosingSource(ByteArray(100))
        var secondOpened = false
        val engine = MockEngine { request ->
            // A rede cai no começo do corpo, com o primeiro arquivo aberto e lido só em parte.
            val channel = ByteChannel()
            val content = request.body as OutgoingContent.WriteChannelContent
            coroutineScope {
                launch(Dispatchers.Default) { runCatching { content.writeTo(channel) } }
                // O corpo começa com as partes de texto: 8 KiB garantem que o primeiro arquivo (1 MiB) já abriu.
                val start = ByteArray(8192)
                var read = 0
                while (read < start.size) {
                    val n = channel.readAvailable(start, read, start.size - read)
                    if (n < 0) break
                    read += n
                }
                channel.cancel(IOException("rede caiu"))
            }
            throw IOException("rede caiu")
        }
        val files = listOf(
            OutgoingFile("a.bin", "application/octet-stream", 1L shl 20, open = { ByteReadChannel(first.buffered()) }),
            OutgoingFile("b.bin", "application/octet-stream", 100, open = {
                secondOpened = true
                ByteReadChannel(second.buffered())
            }),
        )

        assertFailsWith<IOException> { apiOn(engine).sendMessage("c1", "oi", replyTo = null, files = files) }

        assertTrue(first.closed)
        // Depois da queda o escritor multipart do Ktor às vezes ainda passa para a parte seguinte e abre o segundo
        // arquivo (visto no CI); isso não é garantido nem proibido. O que vale é que o que foi aberto foi fechado.
        assertEquals(secondOpened, second.closed)
    }

    @Test
    fun umArquivoGrandeNuncaViraByteArrayESaiAoRitmoDoMotor() = runTest {
        val size = 200L * 1024 * 1024
        val source = GeneratedSource(size)
        var declared: Long? = null
        var consumed = 0L
        var producedAtFirstByte = -1L
        var peakAhead = 0L
        val engine = MockEngine { request ->
            declared = request.body.contentLength
            val channel = ByteChannel()
            val content = request.body as? OutgoingContent.WriteChannelContent
                ?: error("o corpo multipart sai por um canal de escrita, não de um `ByteArray` pronto: ${request.body::class}")
            coroutineScope {
                launch(Dispatchers.Default) {
                    content.writeTo(channel)
                    channel.flushAndClose()
                }
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = channel.readAvailable(buffer)
                    if (read < 0) break
                    if (producedAtFirstByte < 0) producedAtFirstByte = source.produced
                    consumed += read
                    peakAhead = maxOf(peakAhead, source.produced - consumed)
                }
            }
            json(message, HttpStatusCode.Created)
        }
        val file = OutgoingFile("grande.bin", "application/octet-stream", size, open = { ByteReadChannel(source.buffered()) })

        apiOn(engine).sendMessage("c1", "", replyTo = null, files = listOf(file))

        assertEquals(size, source.produced)
        assertTrue(source.closed) // lido até o fim, e o envio ainda fecha o fluxo que abriu
        assertEquals(consumed, declared) // o Content-Length conhecido de antemão é o corpo inteiro
        assertTrue(consumed > size)
        // Se o arquivo tivesse sido lido de uma vez, o gerador já teria produzido os 200 MiB quando o primeiro byte saísse.
        val limit = 8L * 1024 * 1024
        assertTrue(producedAtFirstByte in 0 until limit, "produzido até o primeiro byte: $producedAtFirstByte")
        assertTrue(peakAhead < limit, "o gerador chegou a ficar $peakAhead bytes na frente de quem consome")
    }

    @Test
    fun paginacaoDeMensagensMandaOCursorCompleto() = runTest {
        val seen = mutableListOf<Recorded>()
        val api = api(seen) { json("""{"messages":[$message],"has_more":true}""") }

        val first = api.messages("c1", limit = 40)
        api.messages("c1", since = "2026-01-01T00:00:00.123456Z", lastId = "m9", limit = 40)

        assertTrue(first.hasMore)
        assertEquals("order=desc&limit=40", seen[0].request.url.encodedQuery)
        val params = seen[1].request.url.parameters
        assertEquals("/channels/c1/messages", seen[1].request.url.encodedPath)
        // O backend compara (created_at, id): o texto de `since` vai exatamente como veio.
        assertEquals("2026-01-01T00:00:00.123456Z", params["since"])
        assertEquals("m9", params["last_id"])
        assertEquals("desc", params["order"])
    }

    @Test
    fun removerReacaoMandaOEmojiNoCorpoDoDelete() = runTest {
        val seen = mutableListOf<Recorded>()
        val api = api(seen) { respond("", HttpStatusCode.NoContent) }

        api.react("c1", "m1", ReactionRequest(unicode = "👍"))
        api.unreact("c1", "m1", ReactionRequest(unicode = "👍"))
        api.unreact("c1", "m1", ReactionRequest(emojiId = "e1"))

        assertEquals(listOf(HttpMethod.Post, HttpMethod.Delete, HttpMethod.Delete), seen.map { it.request.method })
        assertTrue(seen.all { it.request.url.encodedPath == "/channels/c1/messages/m1/reactions" })
        // Exatamente um dos dois campos: o outro nem aparece.
        assertEquals("""{"unicode":"👍"}""", seen[1].body)
        assertEquals("""{"emoji_id":"e1"}""", seen[2].body)
    }

    @Test
    fun quemReagiuPedeDescSemCursorNaPrimeiraPaginaEComOCursorExatoNaSeguinte() = runTest {
        val seen = mutableListOf<Recorded>()
        val api = api(seen) {
            json(
                """{"message_id":"m1","has_more":true,"campo_do_futuro":1,"reactions":[
                    {"emoji_id":null,"unicode":"🔥","count":2,"users":[
                      {"id":"r1","user_id":"u2","created_at":"2026-01-01T00:00:03.5Z"},
                      {"id":"r2","user_id":"u3","created_at":"2026-01-01T00:00:01Z"}]},
                    {"emoji_id":"e1","unicode":null,"count":1,"users":[{"id":"r3","user_id":"u4","created_at":"2026-01-01T00:00:02.123456Z"}]}]}""",
            )
        }

        val first = api.reactions("c1", "m1")
        api.reactions("c1", "m1", since = "2026-01-01T00:00:01Z", lastId = "r2")

        assertEquals("m1", first.messageId)
        assertTrue(first.hasMore)
        assertEquals(listOf(null to "🔥", "e1" to null), first.reactions.map { it.emojiId to it.unicode })
        assertEquals(listOf("r1", "r2"), first.reactions[0].users.map { it.id })
        assertEquals("u2", first.reactions[0].users[0].userId)
        // O horário vem como texto e não é reescrito: ele é metade do cursor.
        assertEquals("2026-01-01T00:00:03.5Z", first.reactions[0].users[0].createdAt)
        assertTrue(seen.all { it.request.method == HttpMethod.Get && it.request.url.encodedPath == "/channels/c1/messages/m1/reactions" })
        assertEquals("order=desc", seen[0].request.url.encodedQuery)
        val params = seen[1].request.url.parameters
        assertEquals("desc", params["order"])
        assertEquals("2026-01-01T00:00:01Z", params["since"])
        assertEquals("r2", params["last_id"])
    }

    @Test
    fun quemReagiuNuncaMandaMetadeDoCursor() = runTest {
        val seen = mutableListOf<Recorded>()
        val api = api(seen) { json("""{"message_id":"m1","reactions":[],"has_more":false}""") }

        // O servidor lê `since` sozinho como "criado depois", que em desc volta ao começo: o cliente não o manda.
        api.reactions("c1", "m1", since = "2026-01-01T00:00:01Z")
        api.reactions("c1", "m1", lastId = "r2")

        assertEquals(listOf("order=desc", "order=desc"), seen.map { it.request.url.encodedQuery })
    }

    @Test
    fun quemReagiuSemLeituraDoCanalViraErroDoServidor() = runTest {
        val api = api(mutableListOf()) {
            json("""{"type":"t","title":"Acesso negado","status":403,"detail":"usuário não tem permissão para ler o canal"}""", HttpStatusCode.Forbidden)
        }
        val error = assertFailsWith<ApiException> { api.reactions("c1", "m1") }
        assertTrue(error.isForbidden)
        assertEquals("usuário não tem permissão para ler o canal", error.detail)
    }

    @Test
    fun quemReagiuComListasNulasOuAusentesNaoQuebra() = runTest {
        val api = api(mutableListOf()) { json("""{"message_id":"m1","reactions":[{"unicode":"🔥","count":0,"users":null}]}""") }
        val page = api.reactions("c1", "m1")
        assertEquals(emptyList(), page.reactions.single().users)
        assertTrue(!page.hasMore)
        assertEquals(emptyList(), api(mutableListOf()) { json("""{"message_id":"m1","reactions":null,"has_more":false}""") }.reactions("c1", "m1").reactions)
    }

    @Test
    fun leituraEdicaoPinEDm() = runTest {
        val seen = mutableListOf<Recorded>()
        val api = api(seen) { request ->
            when {
                request.url.encodedPath == "/dms" -> json("""{"id":"d1","user":{"id":"u2","username":"bia"},"created_at":"x"}""")
                request.method == HttpMethod.Put && request.url.encodedPath.startsWith("/messages/") -> json(message)
                else -> respond("", HttpStatusCode.NoContent)
            }
        }
        api.markRead("c1", "m7")
        api.editMessage("m1", "novo texto")
        api.pin("c1", "m1")
        api.unpin("c1", "m1")
        assertEquals("d1", api.openDm("u2").id)
        api.closeDm("d1")

        assertEquals(
            listOf(
                "PUT /channels/c1/read {\"message_id\":\"m7\"}",
                "PUT /messages/m1 {\"content\":\"novo texto\"}",
                "POST /channels/c1/messages/m1/pin ",
                "DELETE /channels/c1/messages/m1/pin ",
                "POST /dms {\"user_id\":\"u2\"}",
                "DELETE /dms/d1 ",
            ),
            seen.map { "${it.request.method.value} ${it.request.url.encodedPath} ${it.body}" },
        )
    }

    @Test
    fun buscaMandaOsFiltrosNoCorpoEOCursorNaQuery() = runTest {
        val seen = mutableListOf<Recorded>()
        val api = api(seen) {
            json(
                """{"results":[
                    {"type":"message","id":"m1","content":"deploy feito","channel_id":"c1","channel_name":"papo","author_id":"u2",
                     "author_username":"bia","created_at":"2026-01-01T00:00:00.123456Z","score":0.6},
                    {"type":"message","id":"m2","content":null,"channel_id":"c1","channel_name":"papo","author_id":null,
                     "author_username":"Robô","webhook_id":"w1","external_author":{"name":"Robô","avatar_url":"https://x.test/a.png"},
                     "created_at":"2026-01-01T00:00:00Z","campo_do_futuro":1}],
                    "has_more":true}""",
            )
        }

        val first = api.search(SearchRequest(text = "deploy", channelId = "c1", order = "desc", containsAttachment = false))
        // Cursor pela metade não é mandado: o servidor o recusaria com 400.
        api.search(SearchRequest(author = "u2"), since = "2026-01-01T00:00:00.123456Z")
        api.search(SearchRequest(author = "u2"), since = "2026-01-01T00:00:00.123456Z", lastId = "m1")

        assertTrue(first.hasMore)
        assertEquals(listOf("m1", "m2"), first.results.map { it.id })
        assertEquals(0.6, first.results[0].score)
        // Resultado de webhook: sem autor nem texto, com a identidade externa para mostrar.
        assertNull(first.results[1].authorId)
        assertNull(first.results[1].content)
        assertNull(first.results[1].score)
        assertEquals("Robô", first.results[1].externalAuthor?.name)

        assertTrue(seen.all { it.request.method == HttpMethod.Post && it.request.url.encodedPath == "/search" })
        assertEquals("""{"text":"deploy","channel_id":"c1","order":"desc","contains_attachment":false}""", seen[0].body)
        assertEquals("", seen[0].request.url.encodedQuery)
        assertEquals("", seen[1].request.url.encodedQuery)
        assertEquals("""{"author":"u2"}""", seen[2].body)
        // O texto de `since` vai exatamente como veio no resultado.
        assertEquals("2026-01-01T00:00:00.123456Z", seen[2].request.url.parameters["since"])
        assertEquals("m1", seen[2].request.url.parameters["last_id"])
    }

    @Test
    fun buscaRecusadaPeloServidorViraErroComOMotivo() = runTest {
        val api = api(mutableListOf()) {
            json("""{"type":"t","title":"Parâmetro inválido","status":400,"detail":"pelo menos 1 filtro é obrigatório"}""", HttpStatusCode.BadRequest)
        }
        val error = assertFailsWith<ApiException> { api.search(SearchRequest(order = "asc")) }
        assertEquals(400, error.status)
        assertEquals("pelo menos 1 filtro é obrigatório", error.detail)
    }

    @Test
    fun avatarInexistenteENuloMasErroDeServidorNao() = runTest {
        val seen = mutableListOf<Recorded>()
        var status = HttpStatusCode.NotFound
        val api = api(seen) {
            if (status == HttpStatusCode.OK) respond(byteArrayOf(1, 2, 3), status)
            else json("""{"type":"t","title":"Erro","status":${status.value},"detail":"avatar não encontrado"}""", status)
        }
        assertNull(api.avatar("u1"))
        status = HttpStatusCode.OK
        assertContentEquals(byteArrayOf(1, 2, 3), api.avatar("u1"))
        status = HttpStatusCode.InternalServerError
        assertEquals(500, assertFailsWith<ApiException> { api.avatar("u1") }.status)
        status = HttpStatusCode.Forbidden
        assertTrue(assertFailsWith<ApiException> { api.attachment("f1") }.isForbidden)
    }

    @Test
    fun respostasComListasNulasOuCamposNovosNaoQuebram() = runTest {
        val seen = mutableListOf<Recorded>()
        val api = api(seen) {
            json(
                """{"messages":[{"id":"m1","channel_id":"c1","author_id":null,"content":null,"created_at":"x",
                    "attachments":null,"embeds":null,"reactions":null,"user_reactions":null,"campo_do_futuro":{"a":1},
                    "external_author":{"name":"Bot","source":"gh"}}],"has_more":false}""",
            )
        }
        val only = api.messages("c1").messages.single()
        assertNull(only.content)
        assertTrue(only.attachments.isEmpty() && only.reactions.isEmpty() && only.userReactions.isEmpty())
        assertEquals("Bot", only.externalAuthor?.name)
    }

    @Test
    fun cargosTrazemAsFlagsEFlagAusenteOuPermissoesNulasValemFalso() = runTest {
        val seen = mutableListOf<Recorded>()
        val api = api(seen) {
            json(
                """{"roles":[
                    {"id":"r1","name":"Moderação","color":"#FF0000","created_at":"2026-01-01T00:00:00Z",
                     "permissions":{"manage_server":false,"manage_channels":true,"manage_roles":false,"manage_webhooks":false,
                       "ban_members":false,"pin_message":true,"everyone_message":true,"send_attachment":true}},
                    {"id":"r2","name":"Leitores","color":null,"permissions":{"pin_message":true,"flag_do_futuro":true}},
                    {"id":"r3","name":"Sem flags","permissions":null}]}""",
            )
        }

        val roles = api.roles()

        assertEquals("GET /roles", seen.single().let { "${it.request.method.value} ${it.request.url.encodedPath}" })
        assertEquals(listOf("r1", "r2", "r3"), roles.map { it.id })
        assertEquals("#FF0000", roles[0].color)
        assertEquals(
            RolePermissionsDto(manageChannels = true, pinMessage = true, everyoneMessage = true, sendAttachment = true),
            roles[0].permissions,
        )
        assertNull(roles[1].color)
        assertEquals(RolePermissionsDto(pinMessage = true), roles[1].permissions)
        assertEquals(RolePermissionsDto(), roles[2].permissions)
    }

    @Test
    fun cargosSemListaViramListaVazia() = runTest {
        assertTrue(api(mutableListOf()) { json("""{"roles":null}""") }.roles().isEmpty())
        assertTrue(api(mutableListOf()) { json("""{}""") }.roles().isEmpty())
    }

    @Test
    fun canaisTrazemAsEntradasDePermissaoPorCargo() = runTest {
        val api = api(mutableListOf()) {
            json(
                """{"channels":[
                    {"id":"c1","name":"avisos","type":"text","position":1,"parent_id":null,"permissions":[
                      {"role_id":"r1","role_name":"Moderação","permissions":{"read_channel":true,"send_messages":true,"delete_messages":true,"connect_voice":false}},
                      {"role_id":"r2","role_name":"Leitores","permissions":{"read_channel":true}},
                      {"role_id":"r3","role_name":"","permissions":{}}]},
                    {"id":"c2","name":"geral","type":"text","position":2,"permissions":[]},
                    {"id":"c3","name":"voz","type":"voice","position":3,"permissions":null},
                    {"id":"c4","name":"antigo","type":"text","position":4}]}""",
            )
        }

        val channels = api.channels()

        val (avisos, geral, voz, antigo) = channels
        assertEquals(
            listOf(
                ChannelPermissionEntryDto("r1", "Moderação", ChannelPermissionsDto(readChannel = true, sendMessages = true, deleteMessages = true)),
                ChannelPermissionEntryDto("r2", "Leitores", ChannelPermissionsDto(readChannel = true)),
                ChannelPermissionEntryDto("r3", "", ChannelPermissionsDto()),
            ),
            avisos.permissions,
        )
        assertTrue(!avisos.permissions[1].permissions.connectVoice) // flag ausente: o servidor trata como falsa
        // Canal aberto (lista vazia), `null` e campo ausente são o mesmo: sem entradas.
        assertTrue(geral.permissions.isEmpty() && voz.permissions.isEmpty() && antigo.permissions.isEmpty())
    }

    @Test
    fun caixaDeNotificacoesPedeDescEMandaOCursorSoComSince() = runTest {
        val seen = mutableListOf<Recorded>()
        val api = api(seen) {
            json(
                """{"notifications":[
                    {"id":"n2","message_id":"m2","channel_id":"c1","author_id":"u2","message_content":"oi @mention(<@u1>)","read":false,"created_at":"2026-01-01T00:00:02.5Z"},
                    {"id":"n1","message_id":"m1","channel_id":"c2","webhook_id":"w1","external_author":{"name":"CI","avatar_url":"/webhooks/w1/avatar"},"message_content":"build","read":true,"created_at":"2026-01-01T00:00:01Z"}
                ],"has_more":true}""",
            )
        }

        val page = api.notifications("u1")
        api.notifications("u1", since = "2026-01-01T00:00:01Z", lastId = "n1")
        api.notifications("u1", since = "2026-01-01T00:00:02.5Z")
        api.notifications("u1", lastId = "n1")

        assertTrue(page.hasMore)
        assertEquals(listOf("n2", "n1"), page.notifications.map { it.id })
        val first = page.notifications[0]
        assertEquals(listOf("m2", "c1", "u2", null, "oi @mention(<@u1>)", false, "2026-01-01T00:00:02.5Z"),
            listOf(first.messageId, first.channelId, first.authorId, first.webhookId, first.messageContent, first.read, first.createdAt))
        val webhook = page.notifications[1]
        assertEquals("CI", webhook.externalAuthor?.name)
        assertNull(webhook.authorId)
        assertTrue(webhook.read)
        assertTrue(seen.all { it.request.method == HttpMethod.Get && it.request.url.encodedPath == "/users/u1/notifications" })
        assertEquals("desc", seen[0].request.url.parameters["order"])
        assertEquals(setOf("order"), seen[0].request.url.parameters.names()) // sem cursor na primeira página
        assertEquals("2026-01-01T00:00:01Z", seen[1].request.url.parameters["since"])
        assertEquals("n1", seen[1].request.url.parameters["last_id"])
        // `since` sozinho são só as mais novas; `last_id` sem `since` o servidor ignora, e o cliente nem o manda.
        assertNull(seen[2].request.url.parameters["last_id"])
        assertEquals("2026-01-01T00:00:02.5Z", seen[2].request.url.parameters["since"])
        assertEquals(setOf("order"), seen[3].request.url.parameters.names())
    }

    @Test
    fun caixaDeNotificacoesComCamposAusentesOuNovosNaoQuebra() = runTest {
        val api = api(mutableListOf()) {
            json("""{"notifications":[{"id":"n1","message_id":"m1","channel_id":"c1","author_id":null,"created_at":"x","campo_novo":1}]}""")
        }
        val page = api.notifications("u1")
        assertFalse(page.hasMore)
        val item = page.notifications.single()
        assertEquals("", item.messageContent)
        assertFalse(item.read)
        assertNull(item.authorId)
        assertEquals(emptyList(), api(mutableListOf()) { json("""{"notifications":null}""") }.notifications("u1").notifications)
    }

    @Test
    fun caixaDeNotificacoesRecusadaViraErroDoServidor() = runTest {
        val api = api(mutableListOf()) { json("""{"type":"t","title":"Proibido","status":403}""", HttpStatusCode.Forbidden) }
        assertTrue(assertFailsWith<ApiException> { api.notifications("u2") }.isForbidden)
    }

    @Test
    fun marcarNotificacoesComoLidasMandaOsIdsNoCorpo() = runTest {
        val seen = mutableListOf<Recorded>()
        val api = api(seen) { json("""{"updated":2}""") }

        val result = api.markNotificationsRead("u1", listOf("n1", "n2"))

        assertEquals(2, result.updated)
        val recorded = seen.single()
        assertEquals(HttpMethod.Put, recorded.request.method)
        assertEquals("/users/u1/read_notification", recorded.request.url.encodedPath)
        assertEquals("""{"notification_ids":["n1","n2"]}""", recorded.body)
    }

    @Test
    fun marcarNotificacoesNaoAceitaListaVaziaNemMaisDeMil() = runTest {
        val seen = mutableListOf<Recorded>()
        val api = api(seen) { json("""{"updated":1}""") }

        assertFailsWith<IllegalArgumentException> { api.markNotificationsRead("u1", emptyList()) }
        assertFailsWith<IllegalArgumentException> { api.markNotificationsRead("u1", List(1001) { "n$it" }) }
        api.markNotificationsRead("u1", List(1000) { "n$it" })
        assertEquals(1, seen.size) // só o de mil saiu
    }

    @Test
    fun marcarNotificacoesSemLinhaNenhumaE404ComoErroDoServidor() = runTest {
        val api = api(mutableListOf()) { json("""{"type":"t","title":"Não encontrado","status":404}""", HttpStatusCode.NotFound) }
        assertTrue(assertFailsWith<ApiException> { api.markNotificationsRead("u1", listOf("n1")) }.isNotFound)
    }

    @Test
    fun nivelDeNotificacaoDoCanalVaiEmPostComUmCampoEVoltaOQueOServidorGuardou() = runTest {
        val seen = mutableListOf<Recorded>()
        val api = api(seen) {
            json("""{"user_id":"u1","channel_id":"c1","notification_settings":"off","updated_at":"2026-01-01T00:00:00Z","extra":1}""")
        }

        val saved = api.setChannelNotification("c1", "u1", "off")

        assertEquals("c1", saved.channelId)
        assertEquals("u1", saved.userId)
        assertEquals("off", saved.notificationSettings)
        assertEquals("2026-01-01T00:00:00Z", saved.updatedAt)
        val recorded = seen.single()
        assertEquals(HttpMethod.Post, recorded.request.method)
        assertEquals("/channels/c1/user/u1/settings", recorded.request.url.encodedPath)
        assertEquals("""{"notification_settings":"off"}""", recorded.body)
    }

    @Test
    fun nivelDeNotificacaoRecusadoViraErroDoServidorComOCodigo() = runTest {
        for (status in listOf(HttpStatusCode.Forbidden, HttpStatusCode.NotFound, HttpStatusCode.BadRequest, HttpStatusCode.TooManyRequests)) {
            val api = api(mutableListOf()) { json("""{"type":"t","title":"Erro","status":${status.value}}""", status) }
            assertEquals(status.value, assertFailsWith<ApiException> { api.setChannelNotification("c1", "u1", "all") }.status)
        }
    }

    @Test
    fun respostaSemOsCamposTrazTudoNulo() = runTest {
        val saved = api(mutableListOf()) { json("{}") }.setChannelNotification("c1", "u1", "all")
        assertNull(saved.notificationSettings)
        assertNull(saved.channelId)
    }

    @Test
    fun conversaDiretaPorIdLeUmaConversaQueNaoEstaNaLista() = runTest {
        val seen = mutableListOf<Recorded>()
        val api = api(seen) {
            json("""{"id":"d1","user":{"id":"u2","username":"bia"},"created_at":"2026-01-01T00:00:00Z","unread_count":3}""")
        }

        val dm = api.dm("d1")

        assertEquals("d1", dm.id)
        assertEquals("u2", dm.user.id)
        assertEquals(3, dm.unreadCount)
        assertEquals(HttpMethod.Get, seen.single().request.method)
        assertEquals("/dms/d1", seen.single().request.url.encodedPath)
    }

    @Test
    fun conversaDiretaBloqueadaOuInexistenteViraErroDoServidor() = runTest {
        val blocked = api(mutableListOf()) {
            json("""{"type":"https://papo.test/errors/dm-blocked","title":"Bloqueada","status":403}""", HttpStatusCode.Forbidden)
        }
        val missing = api(mutableListOf()) { json("""{"type":"t","title":"Não encontrada","status":404}""", HttpStatusCode.NotFound) }

        assertTrue(assertFailsWith<ApiException> { blocked.dm("d1") }.isForbidden)
        assertTrue(assertFailsWith<ApiException> { missing.dm("d1") }.isNotFound)
    }

    @Test
    fun datasNoFusoPedido() {
        val instant = parseInstant("2026-03-05T23:07:09.5Z")!!
        val saoPaulo = TimeZone.of("America/Sao_Paulo") // UTC-3
        assertEquals("05/03/2026 20:07", instant.formatDateTime(saoPaulo))
        assertEquals("20:07", instant.formatTime(saoPaulo))
        assertEquals("05/03/2026", instant.formatDate(saoPaulo))
        assertEquals(20260305, instant.dayKey(saoPaulo))
        // A mesma mensagem cai em outro dia para quem está mais a leste.
        assertEquals(20260306, instant.dayKey(TimeZone.of("Asia/Tokyo")))
        assertEquals("06/03/2026 08:07", instant.formatDateTime(TimeZone.of("Asia/Tokyo")))
        // O backend manda offset local, não só Z.
        assertEquals(parseInstant("2026-09-18T14:05:07.032154Z"), parseInstant("2026-09-18T10:05:07.032154-04:00"))
        assertNull(parseInstant("ontem"))
        assertNull(parseInstant(null))
    }
}

/** Fonte que entrega [data] até [failAfter] bytes e depois lança [error], como um arquivo que some no meio da leitura. */
private class FailingSource(private val data: ByteArray, private val failAfter: Int, private val error: Throwable) : RawSource {
    private var position = 0

    @Volatile
    var closed = false
        private set

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        if (position >= failAfter) throw error
        val count = minOf(byteCount, (failAfter - position).toLong(), 1024L).toInt()
        sink.write(data, position, position + count)
        position += count
        return count.toLong()
    }

    override fun close() {
        closed = true
    }
}

/** [data] inteiro, só para saber se o envio fechou o fluxo; com [failToClose], fechar lança, como um provedor que já caiu. */
private class ClosingSource(private val data: ByteArray, private val failToClose: Boolean = false) : RawSource {
    private var position = 0

    @Volatile
    var closed = false
        private set

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        if (position >= data.size) return -1
        val count = minOf(byteCount, (data.size - position).toLong(), 1024L).toInt()
        sink.write(data, position, position + count)
        position += count
        return count.toLong()
    }

    override fun close() {
        closed = true
        if (failToClose) throw IOException("provedor caiu ao fechar")
    }
}

/** [size] bytes gerados sob demanda, num bloco reaproveitado: nada de um arquivo inteiro na memória. [produced] diz quanto já saiu. */
private class GeneratedSource(private val size: Long) : RawSource {
    private val block = ByteArray(64 * 1024)

    @Volatile
    var produced = 0L
        private set

    @Volatile
    var closed = false
        private set

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        if (produced >= size) return -1
        val count = minOf(byteCount, size - produced, block.size.toLong()).toInt()
        sink.write(block, 0, count)
        produced += count
        return count.toLong()
    }

    override fun close() {
        closed = true
    }
}
