package chat.papo.mobile

import chat.papo.mobile.chat.Channel
import chat.papo.mobile.chat.ChannelType
import chat.papo.mobile.chat.encodeMentions
import chat.papo.mobile.chat.groupChannels
import chat.papo.mobile.chat.mentionedIds
import chat.papo.mobile.chat.renderMentions
import chat.papo.mobile.data.ws.WsCommand
import chat.papo.mobile.data.ws.WsEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class WsEventTest {
    @Test
    fun mensagemComCamposOpcionaisAusentesOuNulos() {
        val event = WsEvent.parse(
            """{"type":"message","id":"m1","channel_id":"c1","author_id":"u1","content":"oi","reply_to":null,
               "attachments":[{"id":"a1","mime_type":"image/png","original_file_name":"f.png","size_bytes":10,"thumbnail_id":"t1"}],
               "created_at":"2026-01-02T03:04:05.123456Z","campo_novo":true}""",
        )
        val message = assertIs<WsEvent.MessageCreated>(event)
        assertEquals("c1", message.channelId)
        assertNull(message.replyTo)
        assertEquals("f.png", message.attachments.single().fileName)
    }

    @Test
    fun eventosDeConversa() {
        assertEquals(
            WsEvent.MessageEdited("m1", "c1", "novo", "2026-01-02T03:04:05Z"),
            WsEvent.parse("""{"type":"message_edit","id":"m1","channel_id":"c1","content":"novo","edited_at":"2026-01-02T03:04:05Z"}"""),
        )
        assertEquals(WsEvent.MessageDeleted("m1", "c1"), WsEvent.parse("""{"type":"message_delete","id":"m1","channel_id":"c1"}"""))
        assertEquals(WsEvent.MessagePinned("m1", true), WsEvent.parse("""{"type":"message_pin","message_id":"m1","is_pinned":true}"""))
        assertEquals(
            WsEvent.ReactionUpdated("m1", null, "👍", 2),
            WsEvent.parse("""{"type":"react_update","message_id":"m1","emoji_id":null,"unicode":"👍","count":2}"""),
        )
        assertEquals(
            WsEvent.Typing("c1", "u2", false),
            WsEvent.parse("""{"type":"typing","channel_id":"c1","user_id":"u2","is_typing":false}"""),
        )
        assertEquals(
            WsEvent.ChannelCreated("c9", "geral", "voice", 3, null, "cat"),
            WsEvent.parse("""{"type":"channel_create","channel_id":"c9","name":"geral","channel_type":"voice","position":3,"topic":null,"parent_id":"cat"}"""),
        )
        assertEquals(WsEvent.HeartbeatAck, WsEvent.parse("""{"type":"heartbeat_ack"}"""))
    }

    @Test
    fun presencaEVoz() {
        val sync = assertIs<WsEvent.PresenceSync>(
            WsEvent.parse("""{"type":"presence_sync","members":[{"user_id":"u1","status":"busy","status_message":null,"user_voice":["v1"]}]}"""),
        )
        assertEquals(listOf("v1"), sync.members.single().voiceChannels)

        val joined = assertIs<WsEvent.VoiceJoined>(
            WsEvent.parse("""{"type":"voice_joined","channel_id":"v1","members":[{"user_id":"u1","muted":true,"camera_on":false,"screen_sharing":true}],"active_speakers":["u1"]}"""),
        )
        assertEquals(true, joined.members.single().screenSharing)

        assertEquals(
            WsEvent.VoiceIceCandidate("v1", "candidate:1", "0", 0),
            WsEvent.parse("""{"type":"voice_ice_candidate","channel_id":"v1","candidate":"candidate:1","sdp_mid":"0","sdp_mline_index":0}"""),
        )
        val routes = assertIs<WsEvent.VoiceAudioRoutes>(
            WsEvent.parse("""{"type":"voice_audio_routes","channel_id":"v1","routes":[{"track_id":"papo-audio-0","user_id":"u2"}]}"""),
        )
        assertEquals("papo-audio-0", routes.routes.single().trackId)
    }

    @Test
    fun tipoDesconhecidoOuLixoViraNull() {
        assertNull(WsEvent.parse("""{"type":"evento_do_futuro","x":1}"""))
        assertNull(WsEvent.parse("""{"sem_tipo":1}"""))
        assertNull(WsEvent.parse("não é json"))
        assertNull(WsEvent.parse("""{"type":"message","id":"m1"}"""))
    }

    @Test
    fun comandosLevamOTipoNoFormatoDoBackend() {
        assertEquals("""{"type":"heartbeat"}""", WsCommand.encode(WsCommand.Heartbeat))
        assertEquals("""{"type":"typing","channel_id":"c1"}""", WsCommand.encode(WsCommand.Typing("c1")))
        assertEquals("""{"type":"voice_mute","channel_id":"v1","muted":false}""", WsCommand.encode(WsCommand.VoiceMute("v1", false)))
        assertEquals(
            """{"type":"track_subscribe","channel_id":"v1","publisher_id":"u2","kind":"screen"}""",
            WsCommand.encode(WsCommand.TrackSubscribe("v1", "u2", "screen")),
        )
        assertEquals(
            """{"type":"voice_ice_candidate","channel_id":"v1","candidate":"c"}""",
            WsCommand.encode(WsCommand.VoiceIceCandidate("v1", "c")),
        )
    }

    private fun channel(id: String, type: ChannelType, position: Int, parent: String? = null) =
        Channel(id, id, type, position, null, parent, null, null, null, null, null)

    @Test
    fun agrupaCanaisPorCategoriaComParentIdOuPelaOrdem() {
        val groups = groupChannels(
            listOf(
                channel("solto", ChannelType.Text, 1),
                channel("cat-a", ChannelType.Category, 2),
                channel("antigo", ChannelType.Text, 3), // sem parent_id: fica na categoria anterior
                channel("cat-b", ChannelType.Category, 4),
                channel("voz", ChannelType.Voice, 5, parent = "cat-a"), // parent_id vence a ordem
                channel("b1", ChannelType.Text, 6, parent = "cat-b"),
            ),
        )
        assertEquals(
            listOf(null to listOf("solto"), "cat-a" to listOf("antigo", "voz"), "cat-b" to listOf("b1")),
            groups.map { it.category?.id to it.channels.map { c -> c.id } },
        )
    }

    @Test
    fun mencoes() {
        val id = "0b0e7c1e-1111-2222-3333-444455556666"
        val raw = "oi @mention(<@$id>), tudo bem?"
        assertEquals("oi @Ana, tudo bem?", renderMentions(raw) { if (it == id) "Ana" else null })
        assertEquals("oi @usuário, tudo bem?", renderMentions(raw) { null })
        assertEquals(listOf(id), mentionedIds(raw))
        assertEquals(raw, encodeMentions("oi @ana, tudo bem?", mapOf("ana" to id)))
        // Não confunde um nome que é prefixo de outro, nem e-mails.
        assertEquals("@anabela e @mention(<@$id>)", encodeMentions("@anabela e @ana", mapOf("ana" to id)))
    }
}
