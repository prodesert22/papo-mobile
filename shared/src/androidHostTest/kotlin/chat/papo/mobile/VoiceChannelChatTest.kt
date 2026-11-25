package chat.papo.mobile

import androidx.compose.ui.text.input.TextFieldValue
import chat.papo.mobile.chat.ChannelType
import chat.papo.mobile.data.ws.WsEvent
import chat.papo.mobile.ui.chat.ChatViewModel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * O chat de um canal de voz: a OpenAPI aceita mensagens nele (`CreateMessageRequest`), e o tratamento de
 * tempo real não olha o tipo do canal. Aqui só se vê o que o cliente faz; que o servidor entregue o evento
 * `message` a quem não está na chamada não dá para provar com o motor simulado.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceChannelChatTest {
    private val posted = mutableListOf<String>()

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        val body = request.body.toByteArray().decodeToString()
        fun json(text: String, status: HttpStatusCode = HttpStatusCode.OK) =
            respond(text, status, headersOf(HttpHeaders.ContentType, "application/json"))
        when {
            path == "/channels" -> json(
                """{"channels":[{"id":"v1","name":"Sala de voz","type":"voice","position":1,"last_read_message":"m0"}]}""",
            )
            path == "/dms" -> json("""{"dms":[]}""")
            path == "/users" -> json("""{"users":[{"id":"u1","username":"ana"},{"id":"u2","username":"bia"}],"has_more":false}""")
            path == "/messages" && request.method == HttpMethod.Post -> {
                posted += body
                json("""{"id":"m9","channel_id":"v1","author_id":"u1","content":"oi, sala","created_at":"2026-01-01T00:00:09Z"}""", HttpStatusCode.Created)
            }
            else -> json("""{"messages":[],"has_more":false}""")
        }
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun voiceMessage(id: String, author: String, at: Int) =
        WsEvent.MessageCreated(id, "v1", author, content = "mensagem $id", createdAt = "2026-01-01T00:00:%02dZ".format(at))

    @Test
    fun mensagemEmCanalDeVozChegaEmTempoRealParaQuemNaoEstaNaChamadaNemNaConversa() = runTest {
        val chat = TestResources.session(engine, this).chat
        chat.sync()
        runCurrent()
        assertEquals(ChannelType.Voice, chat.channels.value.single().type)
        assertFalse(chat.channels.value.single().unread)

        chat.handle(voiceMessage("m1", "u2", 1))
        runCurrent()

        assertEquals(listOf("m1"), chat.messages("v1", 10).first().map { it.id })
        val channel = chat.channels.value.single()
        assertTrue(channel.unread) // a lista de canais mostra que há conversa nova ali
        assertEquals("m1", channel.lastMessageId)
    }

    @Test
    fun conversaAbertaDeUmCanalDeVozMostraAsMensagensQueChegamEEnviaParaOCanal() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val session = TestResources.session(engine, this)
        session.chat.sync()
        runCurrent()
        val vm = ChatViewModel(session, "v1")
        vm.state.first { it.loadedOnce }

        session.chat.handle(voiceMessage("m1", "u2", 1))
        session.chat.handle(voiceMessage("m2", "u2", 2))
        assertEquals(listOf("m2", "m1"), vm.messages.first { it.size == 2 }.map { it.id })

        vm.onDraftChange(TextFieldValue("oi, sala"))
        vm.submit()
        session.chat.outbox.first { it.isEmpty() }
        assertTrue("name=\"channel_id\"" in posted.single() && "v1" in posted.single())
        assertEquals("m9", vm.messages.first { list -> list.any { it.id == "m9" } }.first().id)
    }
}
