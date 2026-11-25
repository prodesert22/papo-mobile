package chat.papo.mobile

import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.chat.EmojiCatalog
import chat.papo.mobile.core.dayKey
import chat.papo.mobile.core.formatDate
import chat.papo.mobile.core.formatDateTime
import chat.papo.mobile.core.formatTime
import chat.papo.mobile.core.parseInstant
import chat.papo.mobile.data.api.ApiException
import chat.papo.mobile.data.api.ChatApi
import chat.papo.mobile.data.api.MemoryCookieStore
import chat.papo.mobile.data.api.OutgoingFile
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.data.api.ReactionRequest
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
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
            OutgoingFile("foto.png", "image/png", 3) { byteArrayOf(1, 2, 3) },
            OutgoingFile("nota \"final\".txt", "text/plain", 2) { "ab".encodeToByteArray() },
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
        api.sendMessage("c1", "", replyTo = null, files = listOf(OutgoingFile("a.bin", "application/octet-stream", 1) { byteArrayOf(9) }))
        val recorded = seen.single()
        assertNull(recorded.part("content"))
        assertNull(recorded.part("reply_to"))
        assertEquals("c1", recorded.part("channel_id"))
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
    fun catalogoDeEmojisPaginaUmaVezEDecodificaAImagem() = runTest {
        val seen = mutableListOf<Recorded>()
        val api = api(seen) { request ->
            if (request.url.parameters["last_id"] == null) {
                json("""{"emojis":[{"id":"e1","name":"um","image_blob":"AQID","created_at":"2026-01-01T00:00:00Z"}],"has_more":true}""")
            } else {
                json("""{"emojis":[{"id":"e2","name":"dois","image_blob":"não é base64!","created_at":"2026-01-02T00:00:00Z"}],"has_more":false}""")
            }
        }
        val catalog = EmojiCatalog(api)

        assertEquals(listOf("e1", "e2"), catalog.all().keys.toList())
        assertContentEquals(byteArrayOf(1, 2, 3), catalog.imageBytes("e1"))
        assertNull(catalog.imageBytes("e2"))
        assertNull(catalog.imageBytes("inexistente"))
        // Tudo veio de duas páginas, buscadas uma única vez.
        assertEquals(2, seen.size)
        assertEquals("e1", seen[1].request.url.parameters["last_id"])
        assertEquals("2026-01-01T00:00:00Z", seen[1].request.url.parameters["since"])
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
