package chat.papo.mobile

import chat.papo.mobile.chat.ChannelType
import chat.papo.mobile.data.api.AttachmentDto
import chat.papo.mobile.data.api.DirectConversationDto
import chat.papo.mobile.data.api.EmbedDto
import chat.papo.mobile.data.api.EmbedMediaDto
import chat.papo.mobile.data.api.OutgoingFile
import chat.papo.mobile.data.api.UserSummaryDto
import chat.papo.mobile.data.ws.WsEvent
import chat.papo.mobile.media.relayVideo
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Eventos de tempo real e ações do [chat.papo.mobile.chat.ChatStore] que o outro teste não cobre. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatStoreEventsTest {
    private val requests = mutableListOf<String>()
    private var counter = 100

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        val body = request.body.toByteArray().decodeToString()
        requests += "${request.method.value} $path" + if (body.isNotEmpty() && !body.startsWith("--")) " $body" else ""
        fun json(text: String, status: HttpStatusCode = HttpStatusCode.OK) =
            respond(text, status, headersOf(HttpHeaders.ContentType, "application/json"))
        when {
            path == "/channels" -> json(
                """{"channels":[{"id":"c1","name":"papo","type":"text","position":1,
                    "last_message":{"id":"m1","content":"oi","created_at":"2026-01-01T00:00:01Z"},"last_read_message":"m0"}]}""",
            )
            path == "/dms" -> json("""{"dms":[]}""")
            path == "/users" -> json("""{"users":[{"id":"u1","username":"ana"},{"id":"u2","username":"bia"}],"has_more":false}""")
            path == "/messages" -> {
                val id = "m${counter++}"
                val attachment = if ("filename=" in body) """[{"id":"a1","mime_type":"image/png","original_file_name":"f.png","size_bytes":3,"thumbnail_id":"a1"}]""" else "[]"
                json("""{"id":"$id","channel_id":"c1","author_id":"u1","content":"minha","created_at":"2026-01-01T00:10:00Z","attachments":$attachment}""", HttpStatusCode.Created)
            }
            path == "/channels/c1/pinned" -> json(
                """{"channel_id":"c1","pinned":[{"id":"m1","channel_id":"c1","author_id":"u2","content":"fixada","created_at":"2026-01-01T00:00:01Z"}]}""",
            )
            path == "/dms" -> json("{}")
            else -> respond("", HttpStatusCode.NoContent)
        }
    }

    private fun message(id: String, at: Int, author: String = "u2") =
        WsEvent.MessageCreated(id, "c1", author, content = "msg $id", createdAt = "2026-01-01T00:00:%02dZ".format(at))

    @Test
    fun canaisCriadosAlteradosEApagadosEmTempoReal() = runTest {
        val chat = TestResources.session(engine, this).chat
        chat.sync()
        chat.handle(WsEvent.ChannelCreated("v1", "voz", "voice", 2, null, null))
        chat.handle(WsEvent.ChannelCreated("c1", "duplicado", "text", 9)) // já existe: não sobrescreve
        runCurrent()
        assertEquals(listOf("papo" to ChannelType.Text, "voz" to ChannelType.Voice), chat.channels.value.map { it.name to it.type })

        chat.handle(WsEvent.ChannelUpdated("v1", "sala", 0, "tópico novo", null))
        runCurrent()
        assertEquals(listOf("sala", "papo"), chat.channels.value.map { it.name }) // reordenou pela posição
        assertEquals("tópico novo", chat.channels.value.first().topic)

        chat.handle(message("m5", 5))
        chat.handle(WsEvent.ChannelDeleted("c1"))
        runCurrent()
        assertEquals(listOf("sala"), chat.channels.value.map { it.name })
        assertTrue(chat.messages("c1", 10).first().isEmpty()) // o histórico do canal vai junto
    }

    @Test
    fun embedsModeracaoEAvatar() = runTest {
        val chat = TestResources.session(engine, this).chat
        chat.sync()
        chat.handle(
            WsEvent.MessageCreated(
                "m5", "c1", "u2", content = "https://x.test", createdAt = "2026-01-01T00:00:05Z",
                attachments = listOf(AttachmentDto("a1", "image/png", "f.png", 10, moderationStatus = "pending")),
            ),
        )
        chat.handle(WsEvent.EmbedsUpdated("c1", "m5", listOf(EmbedDto("e1", title = "Título", url = "https://x.test"))))
        chat.handle(WsEvent.AttachmentModeration("c1", "m5", "a1", "sensitive"))
        val stored = chat.messages("c1", 10).first().single()
        assertEquals("Título", stored.embeds.single().title)
        assertTrue(stored.attachments.single().isSensitive)

        chat.handle(WsEvent.EmbedsUpdated("c1", "m5", emptyList())) // lista vazia remove os embeds
        assertTrue(chat.messages("c1", 10).first().single().embeds.isEmpty())

        runCurrent()
        val before = chat.users.value.getValue("u2").avatarVersion
        chat.handle(WsEvent.AvatarUpdated("u2"))
        runCurrent()
        assertEquals(before + 1, chat.users.value.getValue("u2").avatarVersion) // muda a chave do cache da imagem

        // Eventos sobre mensagens que não estão guardadas são ignorados sem erro.
        chat.handle(WsEvent.EmbedsUpdated("c1", "desconhecida", emptyList()))
        chat.handle(WsEvent.ReactionUpdated("desconhecida", null, "👍", 1))
        chat.handle(WsEvent.AttachmentModeration("c1", "desconhecida", "a", "sensitive"))
    }

    @Test
    fun avatarTrocadoPelaPropriaContaSobeAVersaoDepoisDeGuardarAImagemComONomeNovo() = runTest {
        val chat = TestResources.session(engine, this).chat
        chat.sync()
        runCurrent()
        var stored: Long? = null

        chat.avatarReplaced("u1") { version ->
            // A imagem entra no cache antes de a interface ver a versão nova.
            assertEquals(0L, chat.users.value.getValue("u1").avatarVersion)
            stored = version
        }
        runCurrent()

        assertEquals(1L, stored)
        assertEquals(1L, chat.users.value.getValue("u1").avatarVersion)
        assertEquals(0L, chat.users.value.getValue("u2").avatarVersion)
    }

    @Test
    fun avatarTrocadoComFalhaAoGuardarAImagemAindaSobeAVersaoERepassaOCancelamento() = runTest {
        val chat = TestResources.session(engine, this).chat
        chat.sync()
        runCurrent()

        chat.avatarReplaced("u1") { throw java.io.IOException("disco cheio") }
        runCurrent()
        assertEquals(1L, chat.users.value.getValue("u1").avatarVersion)

        assertFailsWith<kotlinx.coroutines.CancellationException> {
            chat.avatarReplaced("u1") { throw kotlinx.coroutines.CancellationException("fechou") }
        }
        runCurrent()
        assertEquals(1L, chat.users.value.getValue("u1").avatarVersion)
    }

    @Test
    fun avatarDeUsuarioQueOAppNaoConheceNaoFazNada() = runTest {
        val chat = TestResources.session(engine, this).chat
        var called = false

        chat.avatarReplaced("u9") { called = true }

        assertFalse(called)
    }

    @Test
    fun videoDoEmbedSobreviveAGravacaoELeitura() = runTest {
        val session = TestResources.session(engine, this)
        val chat = session.chat
        chat.sync()
        chat.handle(message("m5", 5))
        // O evento como o servidor o manda (openapi: `message_embeds_update`), com campos que o app não usa.
        val event = WsEvent.parse(
            """{"type":"message_embeds_update","channel_id":"c1","message_id":"m5","embeds":[{
                "id":"e1","source_type":"link","fetch_method":"opengraph","site_name":"Exemplo","url":"https://videos.example.com/p",
                "title":"Passeio","thumbnail":{"mime_type":"image/png","width":320,"height":180},
                "video":{"url":"https://cdn.example.com/passeio.mp4","mime_type":"video/mp4","width":640,"height":360,"size_bytes":1234},
                "created_at":"2026-01-01T00:00:06Z","fetched_at":"2026-01-01T00:00:06Z"}]}""",
        )
        assertNotNull(event)
        chat.handle(event)

        val embed = chat.messages("c1", 10).first().single().embeds.single()
        assertEquals(EmbedMediaDto("https://cdn.example.com/passeio.mp4", "video/mp4", 640, 360, sizeBytes = 1234), embed.video)
        // O que o `EmbedCard` decide: o vídeo toca pelo relay do servidor da conta, e a imagem continua sendo a da miniatura.
        val media = relayVideo(session.client.server, embed)
        assertNotNull(media)
        assertEquals("embed:e1", media.key)
        assertEquals("https://papo.test/embeds/e1/video", media.url)
        assertEquals("image/png", embed.thumbnail?.mimeType)
    }

    @Test
    fun embedSemVideoContinuaSemVideoDepoisDeGravar() = runTest {
        val chat = TestResources.session(engine, this).chat
        chat.sync()
        chat.handle(message("m5", 5))
        chat.handle(WsEvent.EmbedsUpdated("c1", "m5", listOf(EmbedDto("e1", title = "Página", url = "https://x.test"))))
        assertNull(chat.messages("c1", 10).first().single().embeds.single().video)
    }

    @Test
    fun fixadasSeguemOsEventos() = runTest {
        val chat = TestResources.session(engine, this).chat
        chat.sync()
        chat.handle(message("m1", 1))
        chat.handle(message("m2", 2))

        // Antes de carregar a lista, o evento não tem onde ser aplicado.
        chat.handle(WsEvent.MessagePinned("m2", true))
        assertNull(chat.pinned.value["c1"])

        assertEquals(listOf("fixada"), chat.loadPinned("c1").map { it.content })
        assertEquals(setOf("m1"), chat.pinned.value["c1"])

        chat.handle(WsEvent.MessagePinned("m2", true))
        assertEquals(setOf("m1", "m2"), chat.pinned.value["c1"])
        chat.handle(WsEvent.MessagePinned("m1", false))
        assertEquals(setOf("m2"), chat.pinned.value["c1"])

        chat.setPinned("c1", "m1", true)
        chat.setPinned("c1", "m2", false)
        assertEquals(setOf("m1"), chat.pinned.value["c1"])
        assertTrue("POST /channels/c1/messages/m1/pin" in requests && "DELETE /channels/c1/messages/m2/pin" in requests)
    }

    @Test
    fun enviarGravaAMensagemEDeixaOCanalLido() = runTest {
        val chat = TestResources.session(engine, this).chat
        chat.sync()
        runCurrent()
        assertTrue(chat.channels.value.single().unread)

        chat.enqueue("c1", "  minha  ", replyTo = null, files = listOf(bytesFile("f.png", "image/png", byteArrayOf(1, 2, 3))))
        chat.outbox.first { it.isEmpty() } // o envio acontece em segundo plano e o motor falso responde em outra thread
        runCurrent()

        val stored = chat.messages("c1", 10).first().single()
        assertEquals("a1", stored.attachments.single().thumbnailId)
        val channel = chat.channels.value.single()
        assertEquals(stored.id, channel.lastMessageId)
        assertFalse(channel.unread)

        // O eco da própria mensagem pelo WebSocket não duplica nem apaga os anexos já gravados.
        chat.handle(WsEvent.MessageCreated(stored.id, "c1", "u1", content = "minha", createdAt = "2026-01-01T00:10:00Z"))
        assertEquals(1, chat.messages("c1", 10).first().single().attachments.size)
    }

    @Test
    fun reagirAlternaOEuReagiEChamaOEndpointCerto() = runTest {
        val chat = TestResources.session(engine, this).chat
        chat.handle(message("m1", 1))
        var stored = chat.messages("c1", 10).first().single()

        chat.toggleReaction(stored, null, "🎉")
        chat.handle(WsEvent.ReactionUpdated("m1", null, "🎉", 1)) // a contagem vem do servidor
        stored = chat.messages("c1", 10).first().single()
        assertTrue(stored.reactedWith(stored.reactions.single()))

        chat.toggleReaction(stored, null, "🎉")
        chat.handle(WsEvent.ReactionUpdated("m1", null, "🎉", 0))
        stored = chat.messages("c1", 10).first().single()
        assertTrue(stored.reactions.isEmpty() && stored.userReactions.isEmpty())
        assertEquals(
            listOf("POST /channels/c1/messages/m1/reactions {\"unicode\":\"🎉\"}", "DELETE /channels/c1/messages/m1/reactions {\"unicode\":\"🎉\"}"),
            requests,
        )
    }

    @Test
    fun conversaDiretaChegaPeloEventoJaComOUsuario() = runTest {
        val chat = TestResources.session(engine, this).chat
        val dm = DirectConversationDto("d1", UserSummaryDto("u9", "novato", nickname = "Novato"), "2026-01-01T00:00:00Z", unreadCount = 1)
        chat.handle(WsEvent.DmUpdated(dm))
        runCurrent()
        assertEquals(1, chat.dms.value.single().unreadCount)
        assertEquals("Novato", chat.users.value.getValue("u9").displayName)

        chat.handle(WsEvent.PresenceUpdated("u9", "busy", "em reunião", typing = "digitando forte", nickname = "Novo Nome"))
        runCurrent()
        assertEquals("busy", chat.presence.value.getValue("u9").status)
        val user = chat.users.value.getValue("u9")
        assertEquals(listOf("Novo Nome", "em reunião", "digitando forte"), listOf(user.displayName, user.statusMessage, user.typingPhrase))

        chat.closeDm("d1")
        runCurrent()
        assertTrue(chat.dms.value.isEmpty())
        assertTrue("DELETE /dms/d1" in requests)
    }

    @Test
    fun mensagemDeQuemEstavaDigitandoLimpaOIndicador() = runTest {
        val chat = TestResources.session(engine, this).chat
        chat.handle(WsEvent.Typing("c1", "u2"))
        assertEquals(setOf("u2"), chat.typing.value["c1"])
        chat.handle(message("m1", 1, author = "u2"))
        assertTrue(chat.typing.value.isEmpty())
    }
}
