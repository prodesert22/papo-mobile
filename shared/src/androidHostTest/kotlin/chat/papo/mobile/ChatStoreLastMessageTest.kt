package chat.papo.mobile

import chat.papo.mobile.chat.Channel
import chat.papo.mobile.chat.ChatStore
import chat.papo.mobile.chat.Dm
import chat.papo.mobile.data.api.AttachmentDto
import chat.papo.mobile.data.ws.WsEvent
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A prévia da última mensagem (e com ela o "não lido") de canais e conversas diretas diante de mensagens
 * minhas vindas de outro cliente e de mensagens apagadas.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatStoreLastMessageTest {
    private fun ts(n: Int) = "2026-01-01T00:00:%02dZ".format(n)

    /** O servidor devolve o canal c1 e a conversa d1 já com a última mensagem [last] e lida até [read] (nulos: sem nenhuma). */
    private fun engine(last: Int?, read: Int?, dmUnread: Int = 0, dmRead: Int? = read) = MockEngine { request ->
        fun json(text: String) = respond(text, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        fun lastMessage(n: Int?) = n?.let { """"last_message":{"id":"m$it","content":"msg $it","author_username":"bia","created_at":"${ts(it)}"},""" }.orEmpty()
        fun readMark(n: Int?) = n?.let { ""","last_read_message":"m$it"""" }.orEmpty()
        when (request.url.encodedPath) {
            "/channels" -> json("""{"channels":[{"id":"c1","name":"papo","type":"text","position":1,${lastMessage(last)}"topic":null${readMark(read)}}]}""")
            "/dms" -> json(
                """{"dms":[{"id":"d1","user":{"id":"u2","username":"bia"},"created_at":"2026-01-01T00:00:00Z",""" +
                    """${lastMessage(last)}"unread_count":$dmUnread${readMark(dmRead)}}]}""",
            )
            "/users" -> json("""{"users":[{"id":"u1","username":"ana"},{"id":"u2","username":"bia"}],"has_more":false}""")
            else -> respond("", HttpStatusCode.NoContent)
        }
    }

    private fun created(n: Int, channel: String, author: String = "u2") =
        WsEvent.MessageCreated("m$n", channel, author, content = "msg $n", createdAt = ts(n))

    /** Sincroniza e guarda as mensagens [stored] de [channel], todas de [author], sem mexer na prévia que o servidor deu. */
    private suspend fun TestScope.open(
        engine: MockEngine,
        channel: String,
        stored: IntRange,
    ): ChatStore {
        val chat = TestResources.session(engine, this).chat
        chat.sync()
        runCurrent()
        stored.forEach { chat.handle(created(it, channel)) }
        // As mensagens guardadas só preparam o cenário: a prévia volta a ser a do servidor, como numa leitura da lista.
        chat.sync()
        runCurrent()
        return chat
    }

    private fun ChatStore.channel(): Channel = channels.value.single()
    private fun ChatStore.dm(): Dm = dms.value.single()

    @Test
    fun mensagemMinhaVindaDeOutroClienteNaoDeixaOCanalNaoLido() = runTest {
        val chat = open(engine(last = 1, read = 1), "c1", 1..1)
        assertFalse(chat.channel().unread)

        chat.handle(created(2, "c1", author = "u1"))
        runCurrent()

        assertEquals("m2", chat.channel().lastMessageId)
        assertEquals("m2", chat.channel().lastReadMessage)
        assertFalse(chat.channel().unread)
    }

    @Test
    fun mensagemMinhaVindaDeOutroClienteNaConversaDiretaZeraOsNaoLidos() = runTest {
        val chat = open(engine(last = 1, read = 1), "d1", 1..1)

        chat.handle(created(2, "d1", author = "u1"))
        runCurrent()

        assertEquals("m2", chat.dm().lastMessageId)
        assertEquals(0, chat.dm().unreadCount)
    }

    @Test
    fun apagarAUltimaDeOutraPessoaRefazAPreviaEDeixaOCanalLido() = runTest {
        val chat = open(engine(last = 3, read = 3), "c1", 1..3)
        assertFalse(chat.channel().unread)

        chat.handle(WsEvent.MessageDeleted("m3", "c1"))
        runCurrent()

        val channel = chat.channel()
        assertEquals(listOf("m2", "msg 2", "bia", "m2"), listOf(channel.lastMessageId, channel.lastMessageText, channel.lastMessageAuthor, channel.lastReadMessage))
        assertEquals(ts(2), channel.lastMessageAt)
        assertFalse(channel.unread)
        assertEquals(listOf("m2", "m1"), chat.messages("c1", 10).first().map { it.id })
    }

    @Test
    fun apagarAUltimaMinhaDepoisDeEnviarNaoDeixaOCanalNaoLido() = runTest {
        // Escrevo e apago logo em seguida: era o caso do ponto de "novas mensagens" que não existia.
        val chat = open(engine(last = 2, read = 2), "c1", 1..2)
        chat.handle(created(3, "c1", author = "u1"))
        runCurrent()
        assertEquals("m3", chat.channel().lastMessageId)
        assertFalse(chat.channel().unread)

        chat.handle(WsEvent.MessageDeleted("m3", "c1"))
        runCurrent()

        assertEquals("m2", chat.channel().lastMessageId)
        assertEquals("m2", chat.channel().lastReadMessage)
        assertFalse(chat.channel().unread)
    }

    @Test
    fun apagarAUltimaPeloAppRefazAPreviaSemEsperarOEvento() = runTest {
        val chat = open(engine(last = 3, read = 3), "c1", 1..3)

        chat.delete("m3")
        runCurrent()

        assertEquals(listOf("m2", "msg 2", "m2"), chat.channel().let { listOf(it.lastMessageId, it.lastMessageText, it.lastReadMessage) })
        assertFalse(chat.channel().unread)

        // O evento que o servidor manda depois chega com a prévia já certa e não muda nada.
        chat.handle(WsEvent.MessageDeleted("m3", "c1"))
        runCurrent()
        assertEquals("m2", chat.channel().lastMessageId)
    }

    @Test
    fun apagarUmaMensagemDoMeioNaoMudaAPrevia() = runTest {
        val chat = open(engine(last = 3, read = 1), "c1", 1..3)
        val before = chat.channel()

        chat.handle(WsEvent.MessageDeleted("m2", "c1"))
        runCurrent()

        assertEquals(before, chat.channel())
        assertTrue(chat.channel().unread)
        assertEquals(listOf("m3", "m1"), chat.messages("c1", 10).first().map { it.id })
    }

    @Test
    fun apagarAUnicaMensagemGuardadaLimpaAPrevia() = runTest {
        val chat = open(engine(last = 1, read = 1), "c1", 1..1)

        chat.handle(WsEvent.MessageDeleted("m1", "c1"))
        runCurrent()

        val channel = chat.channel()
        assertNull(channel.lastMessageId)
        assertNull(channel.lastMessageAt)
        assertNull(channel.lastMessageText)
        assertNull(channel.lastMessageAuthor)
        assertNull(channel.lastReadMessage)
        assertFalse(channel.unread)
    }

    @Test
    fun apagarAUltimaNaoLidaDeixaAAnteriorNaoLidaSeEraDeOutraPessoa() = runTest {
        val chat = open(engine(last = 3, read = 1), "c1", 1..3)
        assertTrue(chat.channel().unread)

        chat.handle(WsEvent.MessageDeleted("m3", "c1"))
        runCurrent()

        // m2 nunca foi lida: continua não lida, e o ponto de leitura não anda.
        assertEquals("m2", chat.channel().lastMessageId)
        assertEquals("m1", chat.channel().lastReadMessage)
        assertTrue(chat.channel().unread)
    }

    @Test
    fun apagarAUnicaNaoLidaDeixaOCanalLido() = runTest {
        val chat = open(engine(last = 2, read = 1), "c1", 1..2)
        assertTrue(chat.channel().unread)

        chat.handle(WsEvent.MessageDeleted("m2", "c1"))
        runCurrent()

        assertEquals("m1", chat.channel().lastMessageId)
        assertFalse(chat.channel().unread)
    }

    @Test
    fun mensagemApagadaDeOutroCanalOuSemPreviaNaoMexeNoCanal() = runTest {
        val chat = open(engine(last = 3, read = 1), "c1", 1..3)
        val before = chat.channel()

        chat.handle(WsEvent.MessageDeleted("m3", "nao-existe"))
        chat.handle(WsEvent.MessageDeleted("m9", "c1"))
        runCurrent()

        assertEquals(before, chat.channel())
    }

    @Test
    fun apagarAUltimaFicandoUmaMensagemSoComAnexoUsaOAnexoNaPrevia() = runTest {
        val chat = open(engine(last = 1, read = 1), "c1", 1..1)
        chat.handle(
            WsEvent.MessageCreated(
                "m1b", "c1", null, content = "", createdAt = "2026-01-01T00:00:01.500Z",
                attachments = listOf(AttachmentDto("a1", "image/png", "f.png", 10)),
            ),
        )
        chat.handle(created(2, "c1"))

        chat.handle(WsEvent.MessageDeleted("m2", "c1"))
        runCurrent()

        assertEquals("m1b", chat.channel().lastMessageId)
        assertEquals("📎 f.png", chat.channel().lastMessageText)
        assertNull(chat.channel().lastMessageAuthor)
    }

    @Test
    fun longeDoFimDoHistoricoApagarAUltimaLimpaAPreviaEmVezDeUsarUmaMensagemAntiga() = runTest {
        val server = PagedChannel(200)
        val chat = TestResources.session(server.engine, this).chat
        chat.handle(WsEvent.ChannelCreated("c1", "papo", "text", 1))
        chat.locate("c1", "m50", server.ts(50), approximate = false)
        assertTrue(chat.isDetached("c1"))
        chat.handle(WsEvent.MessageCreated("m201", "c1", "u2", content = "nova", createdAt = server.ts(201)))
        runCurrent()
        assertEquals("m201", chat.channel().lastMessageId)

        chat.handle(WsEvent.MessageDeleted("m201", "c1"))
        runCurrent()

        assertNull(chat.channel().lastMessageId)
        assertNull(chat.channel().lastMessageText)
    }

    @Test
    fun conversaDiretaApagarAUltimaNaoLidaRefazAPreviaEDiminuiOContador() = runTest {
        val chat = open(engine(last = 3, read = 1, dmUnread = 2), "d1", 1..3)
        assertEquals(2, chat.dm().unreadCount)

        chat.handle(WsEvent.MessageDeleted("m3", "d1"))
        runCurrent()

        assertEquals(listOf("m2", "msg 2", 1), chat.dm().let { listOf(it.lastMessageId, it.lastMessageText, it.unreadCount) })
    }

    @Test
    fun conversaDiretaApagarAUltimaJaLidaMantemTudoLido() = runTest {
        val chat = open(engine(last = 3, read = 3), "d1", 1..3)

        chat.handle(WsEvent.MessageDeleted("m3", "d1"))
        runCurrent()

        assertEquals("m2", chat.dm().lastMessageId)
        assertEquals(0, chat.dm().unreadCount)
    }

    @Test
    fun conversaDiretaApagarAUltimaSemContadorNaoFicaNegativa() = runTest {
        // Ponto de leitura atrás da última, mas o contador já zerado (por exemplo, lido em outro aparelho).
        val chat = open(engine(last = 3, read = 1, dmUnread = 0), "d1", 1..3)

        chat.handle(WsEvent.MessageDeleted("m3", "d1"))
        runCurrent()

        assertEquals("m2", chat.dm().lastMessageId)
        assertEquals(0, chat.dm().unreadCount)
    }

    @Test
    fun conversaDiretaApagarUmaDoMeioNaoMudaNada() = runTest {
        val chat = open(engine(last = 3, read = 1, dmUnread = 2), "d1", 1..3)
        val before = chat.dm()

        chat.handle(WsEvent.MessageDeleted("m2", "d1"))
        runCurrent()

        assertEquals(before, chat.dm())
    }

    @Test
    fun conversaDiretaApagarAUnicaMensagemLimpaAPrevia() = runTest {
        val chat = open(engine(last = 1, read = 1), "d1", 1..1)

        chat.handle(WsEvent.MessageDeleted("m1", "d1"))
        runCurrent()

        assertNull(chat.dm().lastMessageId)
        assertNull(chat.dm().lastMessageText)
        assertEquals(0, chat.dm().unreadCount)
    }

    @Test
    fun conversaDiretaApagarAMinhaUltimaDeOutroClienteNaoDeixaNaoLido() = runTest {
        val chat = open(engine(last = 2, read = 2), "d1", 1..2)
        chat.handle(created(3, "d1", author = "u1"))
        runCurrent()

        chat.handle(WsEvent.MessageDeleted("m3", "d1"))
        runCurrent()

        assertEquals("m2", chat.dm().lastMessageId)
        assertEquals(0, chat.dm().unreadCount)
    }
}
