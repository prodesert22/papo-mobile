package chat.papo.mobile

import chat.papo.mobile.chat.Message
import chat.papo.mobile.chat.isAddressedTo
import chat.papo.mobile.chat.isEveryoneMessage
import chat.papo.mobile.chat.isHighlightedFor
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

    // @everyone no texto

    private fun everyone(content: String) = isEveryoneMessage(content)

    @Test
    fun everyoneNoComecoNoMeioEDepoisDeQuebraOuTabulacaoConta() {
        assertTrue(everyone("@everyone"))
        assertTrue(everyone("@everyone reunião às 15h"))
        assertTrue(everyone("atenção @everyone: reunião"))
        assertTrue(everyone("oi\n@everyone"))
        assertTrue(everyone("oi\t@everyone"))
    }

    @Test
    fun everyoneNaoDiferenciaMaiusculas() {
        assertTrue(everyone("@EVERYONE"))
        assertTrue(everyone("oi @Everyone"))
        assertTrue(everyone("oi @eVeRyOnE"))
    }

    @Test
    fun everyoneTerminaEmQualquerCoisaMenosLetraDigitoOuSublinhadoAscii() {
        assertTrue(everyone("@everyone,"))
        assertTrue(everyone("@everyone."))
        assertTrue(everyone("@everyone!"))
        assertTrue(everyone("(oi @everyone)"))
        assertTrue(everyone("@everyoneé")) // o \b do servidor e do web não trata `é` como letra
        assertFalse(everyone("@everyones"))
        assertFalse(everyone("@everyone_x"))
        assertFalse(everyone("@everyone1"))
        assertFalse(everyone("@everyoneX"))
    }

    @Test
    fun everyoneColadoEmOutraCoisaNaoConta() {
        assertFalse(everyone("ana@everyone.com"))
        assertFalse(everyone("escreva para ana@everyone.com"))
        assertFalse(everyone("x@everyone"))
        assertFalse(everyone("**@everyone**"))
        assertFalse(everyone("\\@everyone"))
    }

    @Test
    fun everyoneDentroDeCodigoNaoConta() {
        assertFalse(everyone("`@everyone`"))
        assertFalse(everyone("veja `ls @everyone` aqui"))
        assertFalse(everyone("```\n@everyone\n```"))
        assertFalse(everyone("```kotlin\noi @everyone\n```"))
    }

    @Test
    fun everyoneForaDeCodigoContaMesmoComCodigoNaMensagem() {
        assertTrue(everyone("`@everyone` e @everyone"))
        assertTrue(everyone("x@everyone @everyone"))
        assertTrue(everyone("```\n@everyone\n```\n@everyone"))
        assertTrue(everyone("`a` @everyone `b`"))
    }

    @Test
    fun crasesQueNaoFechamNaoFormamCodigo() {
        assertTrue(everyone("`oi @everyone"))
        assertTrue(everyone("@everyone`"))
    }

    @Test
    fun semEveryoneNaoConta() {
        assertFalse(everyone(""))
        assertFalse(everyone("oi, tudo bem?"))
        assertFalse(everyone("@ana e @mention(<@$me>)"))
        assertFalse(everyone("@every one"))
        assertFalse(everyone("@everyon"))
        assertFalse(everyone("everyone"))
    }

    // Destaque dourado

    private fun gold(
        author: String?,
        content: String,
        canEveryone: Boolean,
        replyTo: Message? = null,
        userId: String = me,
    ) = isHighlightedFor(userId, message("m1", author, content, replyTo?.id), replyTo, canEveryone)

    @Test
    fun everyoneDeQuemPodeDestacaParaOsOutros() {
        assertTrue(gold(bia, "@everyone reunião", canEveryone = true))
        assertTrue(gold(bia, "oi @EVERYONE", canEveryone = true))
    }

    @Test
    fun everyoneDeQuemNaoPodeNaoDestaca() {
        assertFalse(gold(caio, "@everyone pizza", canEveryone = false))
    }

    @Test
    fun everyoneDeWebhookNaoDestaca() {
        // Webhook não tem autor; `authorCanEveryone` já responde falso para ele.
        assertFalse(gold(author = null, content = "@everyone deploy", canEveryone = false))
    }

    @Test
    fun minhaMensagemComEveryoneDeQuemPodeTambemDestacaComoNoWeb() {
        assertTrue(gold(me, "@everyone reunião", canEveryone = true))
    }

    @Test
    fun minhaMensagemComEveryoneSemPermissaoNaoDestaca() {
        assertFalse(gold(me, "@everyone reunião", canEveryone = false))
    }

    @Test
    fun minhaMensagemSemEveryoneNaoDestacaMesmoComPermissao() {
        assertFalse(gold(me, "oi, bom dia", canEveryone = true))
        assertFalse(gold(me, "x@everyone e `@everyone`", canEveryone = true))
    }

    @Test
    fun minhaMencaoEMinhaRespostaContinuamSemDestaque() {
        val original = message("m0", me)
        assertFalse(gold(me, "lembrete para ${mention(me)}", canEveryone = true))
        assertFalse(gold(me, "respondo a mim", canEveryone = true, replyTo = original))
    }

    @Test
    fun mencaoERespostaAMimDestacamSemDependerDaPermissaoDeEveryone() {
        val original = message("m0", me)
        assertTrue(gold(bia, "oi ${mention(me)}", canEveryone = false))
        assertTrue(gold(bia, "respondo", canEveryone = false, replyTo = original))
    }

    @Test
    fun mensagemComumNaoDestacaMesmoDeQuemPodeEveryone() {
        assertFalse(gold(bia, "bom dia", canEveryone = true))
    }
}
