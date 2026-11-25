package chat.papo.mobile

import chat.papo.mobile.chat.Message
import chat.papo.mobile.chat.newArrivals
import chat.papo.mobile.data.api.ExternalAuthorDto
import kotlin.test.Test
import kotlin.test.assertEquals

class NewArrivalsTest {
    private val me = "eu"

    private fun message(id: String, authorId: String? = "ana", webhookName: String? = null) = Message(
        id = id,
        channelId = "c1",
        authorId = authorId,
        content = "oi",
        createdAt = null,
        createdAtIso = "",
        edited = false,
        replyTo = null,
        webhookId = webhookName?.let { "wh" },
        externalAuthor = webhookName?.let { ExternalAuthorDto(it) },
        attachments = emptyList(),
        embeds = emptyList(),
        reactions = emptyList(),
        userReactions = emptyList(),
    )

    // A lista da conversa vem da mais nova para a mais antiga.

    @Test
    fun semMensagemNovaConta0() {
        val messages = listOf(message("m3"), message("m2"), message("m1"))
        assertEquals(0, newArrivals("m3", messages, me))
    }

    @Test
    fun umaMensagemNovaConta1() {
        val messages = listOf(message("m4"), message("m3"), message("m2"))
        assertEquals(1, newArrivals("m3", messages, me))
    }

    @Test
    fun variasMensagensNovasContamTodas() {
        val messages = listOf(message("m6", "bia"), message("m5", "caio"), message("m4"), message("m3"), message("m2"))
        assertEquals(3, newArrivals("m3", messages, me))
    }

    @Test
    fun asMinhasNaoContam() {
        val messages = listOf(message("m5", me), message("m4", "ana"), message("m3", me), message("m2"))
        assertEquals(1, newArrivals("m2", messages, me))
    }

    @Test
    fun soMinhasConta0() {
        val messages = listOf(message("m3", me), message("m2", me), message("m1"))
        assertEquals(0, newArrivals("m1", messages, me))
    }

    @Test
    fun mensagemDeWebhookSemAutorConta() {
        val messages = listOf(message("m2", authorId = null, webhookName = "Robô"), message("m1"))
        assertEquals(1, newArrivals("m1", messages, me))
    }

    @Test
    fun anteriorForaDaJanelaConta0() {
        val messages = listOf(message("m9"), message("m8"), message("m7"))
        assertEquals(0, newArrivals("m3", messages, me))
    }

    @Test
    fun semMensagemAnteriorConta0() {
        val messages = listOf(message("m2"), message("m1"))
        assertEquals(0, newArrivals(null, messages, me))
    }

    @Test
    fun listaVaziaConta0() {
        assertEquals(0, newArrivals("m1", emptyList(), me))
        assertEquals(0, newArrivals(null, emptyList(), me))
    }

    @Test
    fun anteriorNoFimDaJanelaContaTudoQueEstaAcima() {
        val messages = listOf(message("m3"), message("m2"), message("m1"))
        assertEquals(2, newArrivals("m1", messages, me))
    }
}
