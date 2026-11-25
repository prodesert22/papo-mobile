package chat.papo.mobile

import chat.papo.mobile.chat.Message
import chat.papo.mobile.chat.isAddressedTo
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageHighlightTest {
    private val me = "11111111-1111-4111-8111-111111111111"
    private val bia = "22222222-2222-4222-8222-222222222222"
    private val caio = "33333333-3333-4333-8333-333333333333"

    private fun message(
        id: String,
        authorId: String?,
        content: String = "oi",
        replyTo: String? = null,
    ) = Message(
        id = id,
        channelId = "c1",
        authorId = authorId,
        content = content,
        createdAt = null,
        createdAtIso = "",
        edited = false,
        replyTo = replyTo,
        webhookId = null,
        externalAuthor = null,
        attachments = emptyList(),
        embeds = emptyList(),
        reactions = emptyList(),
        userReactions = emptyList(),
    )

    private fun mention(id: String) = "@mention(<@$id>)"

    // Menção

    @Test
    fun mencaoAMimDestacaMensagemDeOutraPessoa() {
        assertTrue(isAddressedTo(me, message("m1", bia, "oi ${mention(me)}, tudo bem?"), repliedTo = null))
    }

    @Test
    fun mencaoASoOutraPessoaNaoDestaca() {
        assertFalse(isAddressedTo(me, message("m1", bia, "oi ${mention(caio)}"), repliedTo = null))
    }

    @Test
    fun mencaoAMimEntreVariasDestaca() {
        val content = "${mention(caio)} e ${mention(bia)} e ${mention(me)}"
        assertTrue(isAddressedTo(me, message("m1", caio, content), repliedTo = null))
    }

    @Test
    fun nomeEscritoSemOFormatoGuardadoNaoContaComoMencao() {
        assertFalse(isAddressedTo(me, message("m1", bia, "@$me e @mention($me)"), repliedTo = null))
    }

    @Test
    fun mensagemSemMencaoNemRespostaNaoDestaca() {
        assertFalse(isAddressedTo(me, message("m1", bia), repliedTo = null))
    }

    @Test
    fun mencaoAMimEmMensagemSemAutorDestaca() {
        // Webhook: não tem autor, então não pode ser a própria pessoa.
        assertTrue(isAddressedTo(me, message("m1", authorId = null, content = mention(me)), repliedTo = null))
    }

    // Própria mensagem

    @Test
    fun minhaMensagemQueMencionaAMimNaoDestaca() {
        assertFalse(isAddressedTo(me, message("m1", me, "lembrete para ${mention(me)}"), repliedTo = null))
    }

    @Test
    fun minhaRespostaAMimMesmoNaoDestaca() {
        val original = message("m1", me)
        val reply = message("m2", me, replyTo = original.id)
        assertFalse(isAddressedTo(me, reply, repliedTo = original))
    }

    @Test
    fun minhaRespostaAOutraPessoaNaoDestaca() {
        val original = message("m1", bia)
        assertFalse(isAddressedTo(me, message("m2", me, replyTo = original.id), repliedTo = original))
    }

    // Resposta

    @Test
    fun respostaAUmaMensagemMinhaDestaca() {
        val original = message("m1", me)
        assertTrue(isAddressedTo(me, message("m2", bia, replyTo = original.id), repliedTo = original))
    }

    @Test
    fun respostaAUmaMensagemDeOutraPessoaNaoDestaca() {
        val original = message("m1", caio)
        assertFalse(isAddressedTo(me, message("m2", bia, replyTo = original.id), repliedTo = original))
    }

    @Test
    fun respostaCujaOriginalNaoEstaCarregadaNaoDestaca() {
        assertFalse(isAddressedTo(me, message("m2", bia, replyTo = "m1"), repliedTo = null))
    }

    @Test
    fun respostaAUmaMensagemSemAutorNaoDestaca() {
        val original = message("m1", authorId = null)
        assertFalse(isAddressedTo(me, message("m2", bia, replyTo = original.id), repliedTo = original))
    }

    @Test
    fun respostaAMimDeAutorDesconhecidoDestaca() {
        val original = message("m1", me)
        assertTrue(isAddressedTo(me, message("m2", authorId = null, replyTo = original.id), repliedTo = original))
    }

    @Test
    fun respostaAOutraPessoaQueMencionaAMimDestaca() {
        val original = message("m1", caio)
        val reply = message("m2", bia, "${mention(me)} veja isso", replyTo = original.id)
        assertTrue(isAddressedTo(me, reply, repliedTo = original))
    }
}
