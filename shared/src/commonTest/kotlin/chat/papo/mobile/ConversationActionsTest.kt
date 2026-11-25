package chat.papo.mobile

import chat.papo.mobile.chat.ChannelType
import chat.papo.mobile.chat.ConversationAccess
import chat.papo.mobile.chat.Message
import chat.papo.mobile.ui.chat.MessageActionOffer
import chat.papo.mobile.ui.chat.composerOffered
import chat.papo.mobile.ui.chat.messageActionOffer
import chat.papo.mobile.ui.chat.pinnedListOffered
import chat.papo.mobile.ui.chat.reactionToggleable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** O que a conversa oferece em cada permissão: folha de ações, bolinhas de reação, compositor e fixadas. */
class ConversationActionsTest {
    private val me = "me"
    private val bia = "bia"

    private fun message(authorId: String?, content: String = "oi", webhookId: String? = null) = Message(
        id = "m1",
        channelId = "c1",
        authorId = authorId,
        content = content,
        createdAt = null,
        createdAtIso = "",
        edited = false,
        replyTo = null,
        webhookId = webhookId,
        externalAuthor = null,
        attachments = emptyList(),
        embeds = emptyList(),
        reactions = emptyList(),
        userReactions = emptyList(),
    )

    /** O que o servidor deixa fazer num canal só de leitura: ler, tirar a própria reação, e mais nada. */
    private val readOnly = ConversationAccess.Unrestricted.copy(
        send = false,
        attach = false,
        react = false,
        pin = false,
        joinVoice = false,
    )

    private val moderator = ConversationAccess.Unrestricted.copy(deleteOthers = true)

    // Folha de ações

    @Test
    fun semRestricaoMinhaMensagemOfereceTudo() {
        val offer = messageActionOffer(ConversationAccess.Unrestricted, message(me), me)
        assertEquals(MessageActionOffer(react = true, reply = true, pin = true, edit = true, delete = true, text = true), offer)
    }

    @Test
    fun semRestricaoMensagemAlheiaNaoOfereceEditarNemApagar() {
        val offer = messageActionOffer(ConversationAccess.Unrestricted, message(bia), me)
        assertTrue(offer.react && offer.reply && offer.pin && offer.text)
        assertFalse(offer.edit)
        assertFalse(offer.delete)
    }

    @Test
    fun comPermissaoParaApagarAlheiasApagaMasNaoEdita() {
        val offer = messageActionOffer(moderator, message(bia), me)
        assertTrue(offer.delete)
        assertFalse(offer.edit)
    }

    @Test
    fun mensagemDeWebhookNaoTemAutorNaoEMinhaEApagaSoComPermissao() {
        val webhook = message(authorId = null, webhookId = "w1")
        val denied = messageActionOffer(ConversationAccess.Unrestricted, webhook, me)
        assertFalse(denied.delete)
        assertFalse(denied.edit)
        val allowed = messageActionOffer(moderator, webhook, me)
        assertTrue(allowed.delete)
        assertFalse(allowed.edit)
    }

    @Test
    fun conversaDiretaNuncaOfereceApagarMensagemAlheia() {
        // Em DM o acesso já vem com `deleteOthers` falso, até para quem seria o dono do servidor.
        val dm = ConversationAccess.Unrestricted.copy(deleteOthers = false, joinVoice = false)
        assertFalse(messageActionOffer(dm, message(bia), me).delete)
        assertTrue(messageActionOffer(dm, message(me), me).delete)
    }

    @Test
    fun somenteLeituraTiraReacaoResponderEFixarMasMantemEditarEApagarAMinha() {
        val mine = messageActionOffer(readOnly, message(me), me)
        assertFalse(mine.react)
        assertFalse(mine.reply)
        assertFalse(mine.pin)
        assertTrue(mine.edit)
        assertTrue(mine.delete)
        assertTrue(mine.text)
    }

    @Test
    fun somenteLeituraEmMensagemAlheiaSoOfereceCopiarESelecionar() {
        val offer = messageActionOffer(readOnly, message(bia), me)
        assertEquals(MessageActionOffer(react = false, reply = false, pin = false, edit = false, delete = false, text = true), offer)
        assertTrue(offer.any)
    }

    @Test
    fun cadaAcaoSegueSuaPropriaPermissao() {
        val none = readOnly.copy(removeOwnReaction = false)
        assertTrue(messageActionOffer(none.copy(react = true), message(bia), me).react)
        assertFalse(messageActionOffer(none.copy(react = true), message(bia), me).reply)
        assertTrue(messageActionOffer(none.copy(send = true), message(bia), me).reply)
        assertFalse(messageActionOffer(none.copy(send = true), message(bia), me).react)
        assertTrue(messageActionOffer(none.copy(pin = true), message(bia), me).pin)
    }

    @Test
    fun mensagemSemTextoNaoOfereceCopiarNemSelecionar() {
        assertFalse(messageActionOffer(ConversationAccess.Unrestricted, message(bia, content = ""), me).text)
        assertFalse(messageActionOffer(ConversationAccess.Unrestricted, message(bia, content = "  \n"), me).text)
    }

    @Test
    fun folhaSemAcaoNenhumaNaoAbre() {
        // Anexo sem texto, de outra pessoa, num canal só de leitura: nada a oferecer.
        val offer = messageActionOffer(readOnly, message(bia, content = ""), me)
        assertEquals(MessageActionOffer(false, false, false, false, false, false), offer)
        assertFalse(offer.any)
    }

    @Test
    fun umaAcaoSozinhaBastaParaAFolhaAbrir() {
        val none = MessageActionOffer(false, false, false, false, false, false)
        assertTrue(none.copy(react = true).any)
        assertTrue(none.copy(reply = true).any)
        assertTrue(none.copy(pin = true).any)
        assertTrue(none.copy(edit = true).any)
        assertTrue(none.copy(delete = true).any)
        assertTrue(none.copy(text = true).any)
    }

    // Bolinhas de reação

    @Test
    fun comPermissaoParaReagirTodaBolinhaResponde() {
        assertTrue(reactionToggleable(canReact = true, canRemoveOwn = true, mine = false))
        assertTrue(reactionToggleable(canReact = true, canRemoveOwn = true, mine = true))
        assertTrue(reactionToggleable(canReact = true, canRemoveOwn = false, mine = false))
    }

    @Test
    fun semPermissaoParaReagirSoAMinhaRespondeESoSePodeTirar() {
        assertTrue(reactionToggleable(canReact = false, canRemoveOwn = true, mine = true))
        assertFalse(reactionToggleable(canReact = false, canRemoveOwn = true, mine = false))
        assertFalse(reactionToggleable(canReact = false, canRemoveOwn = false, mine = true))
        assertFalse(reactionToggleable(canReact = false, canRemoveOwn = false, mine = false))
    }

    // Compositor

    @Test
    fun comEnvioOCompositorApareceEditandoOuNao() {
        assertTrue(composerOffered(ConversationAccess.Unrestricted, editing = false))
        assertTrue(composerOffered(ConversationAccess.Unrestricted, editing = true))
    }

    @Test
    fun semEnvioAbarraDeSomenteLeituraTomaOLugarDoCompositor() {
        assertFalse(composerOffered(readOnly, editing = false))
    }

    @Test
    fun semEnvioEditarAPropriaMensagemMantemOCompositor() {
        assertTrue(composerOffered(readOnly, editing = true))
    }

    // Mensagens fixadas

    @Test
    fun fixadasApareceEmCanalDeTextoEDeVoz() {
        assertTrue(pinnedListOffered(ConversationAccess.Unrestricted, isDm = false, channelType = ChannelType.Text))
        assertTrue(pinnedListOffered(ConversationAccess.Unrestricted, isDm = false, channelType = ChannelType.Voice))
    }

    @Test
    fun fixadasApareceEmConversaDireta() {
        assertTrue(pinnedListOffered(ConversationAccess.Unrestricted, isDm = true, channelType = null))
    }

    @Test
    fun fixadasNaoApareceEmCategoriaNemEmCanalQueAindaNaoChegou() {
        assertFalse(pinnedListOffered(ConversationAccess.Unrestricted, isDm = false, channelType = ChannelType.Category))
        assertFalse(pinnedListOffered(ConversationAccess.Unrestricted, isDm = false, channelType = null))
    }

    @Test
    fun fixadasNaoApareceSemLeituraNemEmDm() {
        val denied = ConversationAccess.Unrestricted.copy(readDenied = true)
        assertFalse(pinnedListOffered(denied, isDm = false, channelType = ChannelType.Text))
        assertFalse(pinnedListOffered(denied, isDm = true, channelType = null))
    }

    @Test
    fun fixadasNaoDependeDeEnvio() {
        assertTrue(pinnedListOffered(readOnly, isDm = false, channelType = ChannelType.Text))
    }
}
