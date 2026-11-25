package chat.papo.mobile

import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.chat.ChatStore
import chat.papo.mobile.chat.Located
import chat.papo.mobile.data.api.ChatApi
import chat.papo.mobile.data.api.MemoryCookieStore
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.data.db.account.AccountDb
import chat.papo.mobile.data.ws.WsClient
import chat.papo.mobile.data.ws.WsEvent
import chat.papo.mobile.media.MediaCache
import chat.papo.mobile.platform.JcaCrypto
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatStoreTest {
    private fun ts(n: Int) = "2026-01-01T00:%02d:%02dZ".format(n / 60, n % 60)
    private fun msg(n: Int, channel: String = "c1", author: String = "u2", extra: String = "") =
        """{"id":"m$n","channel_id":"$channel","author_id":"$author","content":"msg $n","created_at":"${ts(n)}"$extra}"""

    /** Servidor com [total] mensagens em c1, paginadas como o backend (desc, cursor since+last_id). */
    private class Backend(var total: Int, private val render: (Int) -> String) {
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            requests += request.method.value + " " + path + (request.url.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: "")
            fun json(body: String) = respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            when {
                path == "/channels" -> json(
                    """{"channels":[
                        {"id":"cat","name":"Geral","type":"category","position":1},
                        {"id":"c1","name":"papo","type":"text","position":2,"parent_id":"cat",
                         "last_message":{"id":"m$total","content":"msg $total","author_username":"bia","created_at":"x"},"last_read_message":"m1"},
                        {"id":"v1","name":"voz","type":"voice","position":3,"parent_id":"cat"}]}""",
                )
                path == "/dms" -> json(
                    """{"dms":[{"id":"d1","user":{"id":"u2","username":"bia"},"created_at":"2026-01-01T00:00:00Z","unread_count":2}]}""",
                )
                path == "/users" -> json(
                    """{"users":[{"id":"u1","username":"ana","created_at":"2026-01-01T00:00:00Z"},
                                 {"id":"u2","username":"bia","nickname":"Bia B.","created_at":"2026-01-01T00:00:01Z","roles":[{"id":"r","name":"adm","color":"#FF0000"}]}],
                        "has_more":false}""",
                )
                path == "/channels/c1/messages" -> {
                    val limit = request.url.parameters["limit"]!!.toInt()
                    val before = request.url.parameters["last_id"]?.removePrefix("m")?.toInt() ?: (total + 1)
                    val ids = (before - 1 downTo maxOf(1, before - limit)).toList()
                    json("""{"messages":[${ids.joinToString(",") { render(it) }}],"has_more":${(ids.lastOrNull() ?: 1) > 1}}""")
                }
                path == "/channels/c1/read" || path == "/channels/d1/read" -> respond("", HttpStatusCode.NoContent)
                path.endsWith("/reactions") -> json("{}")
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
    }

    private class Fixture(scope: TestScope, val backend: Backend) {
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        private val client = PapoClient(backend.engine, ServerUrl("https://papo.test"), MemoryCookieStore("jwt"))
        val store = ChatStore(
            myUserId = "u1",
            db = AccountDb(TestResources.driver()),
            api = ChatApi(client),
            ws = WsClient(client, scope.backgroundScope, sessionStillValid = { true }),
            scope = scope.backgroundScope,
            io = dispatcher,
        )

        suspend fun ids(limit: Long = 500) = store.messages("c1", limit).first().map { it.id }
    }

    @Test
    fun syncGravaCanaisDmsEUsuarios() = runTest {
        val f = Fixture(this, Backend(5) { msg(it) })
        f.store.sync()
        runCurrent()

        assertEquals(listOf("cat", "c1", "v1"), f.store.channels.value.map { it.id })
        val papo = f.store.channels.value.first { it.id == "c1" }
        assertTrue(papo.unread) // última m5, lida até m1
        assertFalse(f.store.channels.value.first { it.id == "v1" }.unread)
        assertEquals(2, f.store.dms.value.single().unreadCount)
        assertEquals("Bia B.", f.store.users.value.getValue("u2").displayName)
        assertEquals("#FF0000", f.store.users.value.getValue("u2").roleColor)
    }

    @Test
    fun paginaParaTrasAteOFimDoHistorico() = runTest {
        val f = Fixture(this, Backend(100) { msg(it) })
        f.store.refreshLatest("c1")
        assertEquals((100 downTo 61).map { "m$it" }, f.ids())

        assertTrue(f.store.loadOlder("c1"))
        assertEquals(80, f.ids().size)
        assertTrue(f.backend.requests.last().contains("last_id=m61"))

        assertFalse(f.store.loadOlder("c1")) // chegou em m1
        assertEquals((100 downTo 1).map { "m$it" }, f.ids())
        assertFalse(f.store.loadOlder("c1"))
    }

    @Test
    fun localizaMensagemJaGuardadaSemIrAoServidor() = runTest {
        val f = Fixture(this, Backend(100) { msg(it) })
        f.store.refreshLatest("c1")
        val before = f.backend.requests.size

        // m100 é a mais nova: m90 tem dez mensagens mais novas antes dela.
        assertEquals(Located.Found(10), f.store.locate("c1", "m90", ts(90)))
        assertEquals(Located.Found(0), f.store.locate("c1", "m100"))
        assertEquals(before, f.backend.requests.size)
    }

    @Test
    fun localizaMensagemAntigaPaginandoParaTras() = runTest {
        val f = Fixture(this, Backend(350) { msg(it) })
        f.store.refreshLatest("c1") // m350..m311

        assertEquals(Located.Found(290), f.store.locate("c1", "m60", ts(60)))

        // Três páginas de 100 (o limite do servidor), cada uma continuando da mensagem mais antiga guardada.
        val pages = f.backend.requests.drop(1)
        assertEquals(listOf("m311", "m211", "m111"), pages.map { Regex("last_id=(m\\d+)").find(it)!!.groupValues[1] })
        assertTrue(pages.all { "limit=100" in it })
        // O trecho guardado continua sem buracos, da mais nova até a página da mensagem.
        assertEquals((350 downTo 11).map { "m$it" }, f.ids())
    }

    @Test
    fun mensagemApagadaNaoPaginaAlemDoInstanteDela() = runTest {
        val f = Fixture(this, Backend(350) { msg(it) })
        f.store.refreshLatest("c1")

        // O instante dela fica entre m300 e m301: uma página basta para saber que não existe mais.
        assertEquals(Located.Missing, f.store.locate("c1", "apagada", "2026-01-01T00:05:00.5Z"))
        assertEquals(2, f.backend.requests.size)

        // Sem o instante, só o fim do histórico encerra a procura.
        val short = Fixture(this, Backend(30) { msg(it) })
        short.store.refreshLatest("c1")
        assertEquals(Located.Missing, short.store.locate("c1", "apagada"))
        assertEquals(1, short.backend.requests.size)
    }

    @Test
    fun mensagemDeOutroCanalNaoContaComoAchada() = runTest {
        val f = Fixture(this, Backend(30) { msg(it) })
        f.store.refreshLatest("c1")
        assertEquals(Located.Missing, f.store.locate("d1", "m5"))
    }

    @Test
    fun procuraParaNoLimiteDePaginas() = runTest {
        val f = Fixture(this, Backend(2000) { msg(it) })
        f.store.refreshLatest("c1")

        assertEquals(Located.TooOld, f.store.locate("c1", "m5", ts(5)))
        assertEquals(1 + 10, f.backend.requests.size)
        // O que foi lido fica guardado e continua contínuo.
        assertEquals((2000 downTo 961).map { "m$it" }, f.ids(limit = 5000))
    }

    @Test
    fun historicoQueNaoEncostaNoGuardadoDescartaOAntigo() = runTest {
        val backend = Backend(40) { msg(it) }
        val f = Fixture(this, backend)
        f.store.refreshLatest("c1")
        assertEquals(40, f.ids().size)

        // Ficou offline e chegaram 200 mensagens: a página nova (m240..m201) não encosta em m40.
        backend.total = 240
        f.store.refreshLatest("c1")
        assertEquals((240 downTo 201).map { "m$it" }, f.ids())

        // Poucas mensagens novas: a página encosta e nada é descartado.
        backend.total = 245
        f.store.refreshLatest("c1")
        assertEquals((245 downTo 201).map { "m$it" }, f.ids())
    }

    @Test
    fun eventosDeTempoRealAtualizamOBanco() = runTest {
        val f = Fixture(this, Backend(3) { msg(it, extra = ""","reactions":[{"unicode":"👍","count":1}]""") })
        f.store.sync()
        f.store.refreshLatest("c1")

        f.store.handle(WsEvent.MessageCreated("m4", "c1", "u2", content = "nova", createdAt = ts(4)))
        f.store.handle(WsEvent.MessageCreated("m4", "c1", "u2", content = "duplicada", createdAt = ts(4)))
        runCurrent()
        assertEquals(listOf("m4", "m3", "m2", "m1"), f.ids())
        assertEquals("nova", f.store.messages("c1", 10).first().first().content)
        val channel = f.store.channels.value.first { it.id == "c1" }
        assertEquals("m4", channel.lastMessageId)
        assertEquals("bia", channel.lastMessageAuthor)
        assertTrue(channel.unread)

        f.store.handle(WsEvent.MessageEdited("m4", "c1", "editada", ts(5)))
        f.store.handle(WsEvent.ReactionUpdated("m3", null, "👍", 3))
        f.store.handle(WsEvent.ReactionUpdated("m3", null, "🎉", 1))
        f.store.handle(WsEvent.MessageDeleted("m2", "c1"))
        val messages = f.store.messages("c1", 10).first()
        assertEquals(listOf("m4", "m3", "m1"), messages.map { it.id })
        assertEquals("editada", messages[0].content)
        assertTrue(messages[0].edited)
        assertEquals(mapOf<String?, Int>("👍" to 3, "🎉" to 1), messages[1].reactions.associate { it.unicode to it.count })

        f.store.handle(WsEvent.ReactionUpdated("m3", null, "👍", 0))
        assertEquals(listOf("🎉"), f.store.messages("c1", 10).first()[1].reactions.map { it.unicode })
    }

    @Test
    fun mensagemPropriaEMarcarComoLidoLimpamONaoLido() = runTest {
        val f = Fixture(this, Backend(3) { msg(it) })
        f.store.sync()
        runCurrent()

        f.store.markRead("c1", "m3")
        runCurrent()
        assertFalse(f.store.channels.value.first { it.id == "c1" }.unread)
        assertTrue("PUT /channels/c1/read" in f.backend.requests)

        // Repetir a mesma leitura não chama o servidor de novo.
        val before = f.backend.requests.size
        f.store.markRead("c1", "m3")
        assertEquals(before, f.backend.requests.size)

        f.store.handle(WsEvent.MessageCreated("m9", "c1", "u1", content = "minha", createdAt = ts(9)))
        runCurrent()
        assertFalse(f.store.channels.value.first { it.id == "c1" }.unread)
    }

    @Test
    fun naoLidasDeDmContamSoForaDaConversaAberta() = runTest {
        val f = Fixture(this, Backend(0) { msg(it) })
        f.store.sync()
        runCurrent()

        f.store.handle(WsEvent.MessageCreated("x1", "d1", "u2", content = "oi", createdAt = ts(1)))
        runCurrent()
        assertEquals(3, f.store.dms.value.single().unreadCount)

        f.store.setViewing("d1")
        f.store.handle(WsEvent.MessageCreated("x2", "d1", "u2", content = "aí?", createdAt = ts(2)))
        runCurrent()
        assertEquals(3, f.store.dms.value.single().unreadCount)

        f.store.markRead("d1", "x2")
        runCurrent()
        assertEquals(0, f.store.dms.value.single().unreadCount)
    }

    @Test
    fun digitacaoPresencaEVoz() = runTest {
        val f = Fixture(this, Backend(0) { msg(it) })
        f.store.handle(WsEvent.Typing("c1", "u2", true))
        f.store.handle(WsEvent.Typing("c1", "u1", true)) // eu mesmo não conto
        assertEquals(mapOf("c1" to setOf("u2")), f.store.typing.value)
        f.store.handle(WsEvent.Typing("c1", "u2", false))
        assertTrue(f.store.typing.value.isEmpty())

        f.store.handle(
            WsEvent.PresenceSync(listOf(WsEvent.PresenceMember("u2", "away", null, listOf("v1")), WsEvent.PresenceMember("u3", "offline"))),
        )
        assertTrue(f.store.presence.value.getValue("u2").online)
        assertFalse(f.store.presence.value.getValue("u3").online)
        assertEquals(setOf("u2"), f.store.voiceMembers.value["v1"])
        f.store.handle(WsEvent.VoiceStateUpdated("v1", "u3"))
        f.store.handle(WsEvent.VoiceLeft("v1", "u2"))
        assertEquals(setOf("u3"), f.store.voiceMembers.value["v1"])
    }

    @Test
    fun cacheDeMidiaGravaCifradoEDetectaAdulteracao() = runTest {
        val crypto = JcaCrypto()
        val dir = TestResources.tempDir()
        val key = crypto.randomBytes(32)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val secret = "conteúdo da imagem ".repeat(50).encodeToByteArray()
        var fetches = 0
        val fetch: suspend () -> ByteArray? = { fetches++; secret }

        val cache = MediaCache(dir, key, crypto, dispatcher)
        assertContentEquals(secret, cache.get("att/1", fetch))
        assertContentEquals(secret, cache.get("att/1", fetch))
        assertEquals(1, fetches)

        val file = File(dir).listFiles()!!.single()
        assertFalse(file.name.contains("att"))
        assertFalse(file.readBytes().decodeToString().contains("conteúdo"))

        // Outro processo com a mesma chave lê do disco sem ir à rede.
        assertContentEquals(secret, MediaCache(dir, key, crypto, dispatcher).get("att/1", fetch))
        assertEquals(1, fetches)

        // Arquivo adulterado é descartado e buscado de novo.
        file.writeBytes(file.readBytes().also { it[40] = (it[40].toInt() xor 1).toByte() })
        assertContentEquals(secret, MediaCache(dir, key, crypto, dispatcher).get("att/1", fetch))
        assertEquals(2, fetches)

        // Chave errada não abre o que outra conta gravou.
        assertNull(MediaCache(dir, crypto.randomBytes(32), crypto, dispatcher).get("att/1") { null })
    }
}
