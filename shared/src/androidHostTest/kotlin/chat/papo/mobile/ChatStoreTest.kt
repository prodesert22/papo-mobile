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
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ChatStoreTest {
    private fun ts(n: Int) = "2026-01-01T00:%02d:%02dZ".format(n / 60, n % 60)
    private fun msg(n: Int, channel: String = "c1", author: String = "u2", extra: String = "") =
        """{"id":"m$n","channel_id":"$channel","author_id":"$author","content":"msg $n","created_at":"${ts(n)}"$extra}"""

    /** Servidor com [total] mensagens em c1, paginadas como o backend: cursor since+last_id nas duas direções. */
    private class Backend(var total: Int, private val render: (Int) -> String) {
        val requests = mutableListOf<String>()

        /** A mensagem `n` foi criada `n` segundos depois da meia-noite. */
        private fun seconds(iso: String): Double {
            val instant = Instant.parse(iso)
            return (instant.epochSeconds - Instant.parse("2026-01-01T00:00:00Z").epochSeconds) + instant.nanosecondsOfSecond / 1e9
        }
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
                    val asc = request.url.parameters["order"] == "asc"
                    val lastId = request.url.parameters["last_id"]
                    val since = request.url.parameters["since"]?.let { seconds(it) }
                    val matching = (1..total).filter { n ->
                        when {
                            since == null -> true
                            asc -> n > since || (n.toDouble() == since && "m$n" > lastId!!)
                            else -> n < since || (n.toDouble() == since && "m$n" < lastId!!)
                        }
                    }
                    val ordered = if (asc) matching else matching.reversed()
                    json("""{"messages":[${ordered.take(limit).joinToString(",") { render(it) }}],"has_more":${ordered.size > limit}}""")
                }
                path == "/channels/d1/messages" -> json("""{"messages":[],"has_more":false}""")
                path == "/messages" -> {
                    total += 1
                    json(render(total))
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
        assertEquals(Located.Found(0), f.store.locate("c1", "m100", ts(100)))
        assertEquals(before, f.backend.requests.size)
        assertFalse(f.store.isDetached("c1"))
    }

    @Test
    fun mensagemMuitoAntigaAbreComDuasLeiturasPeloCursor() = runTest {
        val f = Fixture(this, Backend(2000) { msg(it) })
        f.store.refreshLatest("c1") // m2000..m1961

        // Por mais longe que esteja, são duas leituras: o que veio depois dela e a página que termina nela.
        assertEquals(Located.Found(100), f.store.locate("c1", "m5", ts(5)))
        val (after, before) = f.backend.requests.drop(1)
        assertTrue("order=asc" in after && "last_id=m5" in after && "limit=100" in after && "since=" in after)
        assertTrue("order=desc" in before && "last_id=m6" in before && "limit=40" in before)
        assertEquals(3, f.backend.requests.size)

        // O trecho guardado passa a ser a vizinhança dela, sem buraco, e o canal fica marcado como longe do fim.
        assertEquals((105 downTo 1).map { "m$it" }, f.ids())
        assertTrue(f.store.isDetached("c1"))
        assertFalse(f.store.hasOlder("c1")) // a página que termina nela chegou ao começo do canal

        // Voltar ao fim do histórico troca o trecho de novo, e o que valia para o antigo não vale mais.
        f.store.refreshLatest("c1")
        assertEquals((2000 downTo 1961).map { "m$it" }, f.ids())
        assertFalse(f.store.isDetached("c1"))
        assertTrue(f.store.loadOlder("c1"))
        assertEquals((2000 downTo 1921).map { "m$it" }, f.ids())
    }

    @Test
    fun trechoAntigoCarregaParaOsDoisLadosAteAlcancarOFim() = runTest {
        val f = Fixture(this, Backend(350) { msg(it) })
        f.store.refreshLatest("c1")
        assertEquals(Located.Found(100), f.store.locate("c1", "m60", ts(60)))
        assertEquals((160 downTo 21).map { "m$it" }, f.ids())
        assertTrue(f.store.hasOlder("c1"))

        // Para trás, como em qualquer conversa.
        assertFalse(f.store.loadOlder("c1")) // m20..m1: acabou o histórico
        assertEquals((160 downTo 1).map { "m$it" }, f.ids())

        // Para frente, página a página, a partir da mais nova guardada.
        assertEquals(40, f.store.loadNewer("c1"))
        assertTrue("order=asc" in f.backend.requests.last() && "last_id=m160" in f.backend.requests.last())
        assertEquals((200 downTo 1).map { "m$it" }, f.ids())
        assertTrue(f.store.isDetached("c1"))

        var rounds = 0
        while (f.store.isDetached("c1") && rounds++ < 10) f.store.loadNewer("c1")
        // Chegou à mensagem mais nova: o trecho é um só e o canal volta a acompanhar a conversa.
        assertFalse(f.store.isDetached("c1"))
        assertEquals((350 downTo 1).map { "m$it" }, f.ids())
        assertEquals(0, f.store.loadNewer("c1"))
    }

    @Test
    fun mensagemPertoDoTrechoGuardadoNaoSeparaOHistorico() = runTest {
        val f = Fixture(this, Backend(350) { msg(it) })
        f.store.refreshLatest("c1") // m350..m311

        // O que veio depois de m240 (m241..m340) encosta no que já está guardado: vira um trecho só.
        assertEquals(Located.Found(110), f.store.locate("c1", "m240", ts(240)))
        assertEquals((350 downTo 201).map { "m$it" }, f.ids())
        assertFalse(f.store.isDetached("c1"))
        assertTrue(f.store.loadOlder("c1"))
        assertEquals((350 downTo 161).map { "m$it" }, f.ids())
    }

    @Test
    fun mensagemApagadaNaoMexeNoTrechoGuardado() = runTest {
        val f = Fixture(this, Backend(350) { msg(it) })
        f.store.refreshLatest("c1")

        // O instante dela fica entre m300 e m301, mas o servidor não a tem mais.
        assertEquals(Located.Missing, f.store.locate("c1", "apagada", "2026-01-01T00:05:00.5Z"))
        assertEquals(3, f.backend.requests.size)
        // Nada depois do instante dela: nem a segunda leitura acontece.
        assertEquals(Located.Missing, f.store.locate("c1", "apagada", ts(999)))
        assertEquals(4, f.backend.requests.size)
        // Mensagem guardada de outro canal não conta como achada neste.
        assertEquals(Located.Missing, f.store.locate("d1", "m340", ts(340)))

        assertEquals((350 downTo 311).map { "m$it" }, f.ids())
        assertFalse(f.store.isDetached("c1"))
    }

    @Test
    fun mensagemNovaNaoEntraNoTrechoAntigo() = runTest {
        val backend = Backend(350) { msg(it) }
        val f = Fixture(this, backend)
        f.store.sync()
        f.store.refreshLatest("c1")
        f.store.locate("c1", "m60", ts(60))
        assertTrue(f.store.isDetached("c1"))

        // Entre o trecho antigo e ela há um buraco: fica de fora, mas o canal continua sabendo que há novidade.
        backend.total = 351
        f.store.handle(WsEvent.MessageCreated("m351", "c1", "u2", content = "nova", createdAt = ts(351)))
        runCurrent()
        assertEquals((160 downTo 21).map { "m$it" }, f.ids())
        val channel = f.store.channels.value.first { it.id == "c1" }
        assertEquals("m351", channel.lastMessageId)
        assertTrue(channel.unread)

        // Escrever dali leva de volta ao fim da conversa, onde a mensagem enviada entrou.
        f.store.enqueue("c1", "resposta", replyTo = null, files = emptyList())
        f.store.outbox.first { it.isEmpty() } // saiu em segundo plano e o fim da conversa já foi relido
        assertFalse(f.store.isDetached("c1"))
        assertEquals((352 downTo 313).map { "m$it" }, f.ids())
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
    fun conversaQueSaiDaTelaSoLimpaAVistaSeAindaEraAdela() = runTest {
        val f = Fixture(this, Backend(0) { msg(it) })
        f.store.sync()
        runCurrent()

        f.store.setViewing("d1")
        // Outra conversa que saiu da tela (a anterior do painel, liberada depois) não tira a que está à vista.
        f.store.clearViewing("c1")
        f.store.handle(WsEvent.MessageCreated("x1", "d1", "u2", content = "oi", createdAt = ts(1)))
        runCurrent()
        assertEquals(2, f.store.dms.value.single().unreadCount)

        f.store.clearViewing("d1")
        f.store.handle(WsEvent.MessageCreated("x2", "d1", "u2", content = "aí?", createdAt = ts(2)))
        runCurrent()
        assertEquals(3, f.store.dms.value.single().unreadCount)
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
