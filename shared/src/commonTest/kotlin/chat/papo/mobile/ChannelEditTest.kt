package chat.papo.mobile

import chat.papo.mobile.chat.Channel
import chat.papo.mobile.chat.ChannelEditResult
import chat.papo.mobile.chat.ChannelNameProblem
import chat.papo.mobile.chat.ChannelType
import chat.papo.mobile.chat.PermissionSnapshot
import chat.papo.mobile.chat.RoleFlag
import chat.papo.mobile.chat.RoleFlags
import chat.papo.mobile.chat.canManageChannels
import chat.papo.mobile.chat.channelEditResult
import chat.papo.mobile.chat.channelNameProblem
import chat.papo.mobile.chat.channelTopicFits
import chat.papo.mobile.chat.codePointLength
import chat.papo.mobile.core.MutationResult
import chat.papo.mobile.ui.channel.ChannelSettingsState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A validação da edição de canal, o que a tela manda no pedido e quem a vê. */
class ChannelEditTest {
    // Nome

    @Test
    fun nomeVazioOuSoDeEspacosNaoServe() {
        assertEquals(ChannelNameProblem.Empty, channelNameProblem(""))
        assertEquals(ChannelNameProblem.Empty, channelNameProblem("   \n\t"))
    }

    @Test
    fun nomeDeUmATrintaEDoisCaracteresServe() {
        assertNull(channelNameProblem("a"))
        assertNull(channelNameProblem("a".repeat(32)))
    }

    @Test
    fun nomeDeTrintaETresCaracteresNaoServe() {
        assertEquals(ChannelNameProblem.TooLong, channelNameProblem("a".repeat(33)))
    }

    @Test
    fun espacosDasPontasNaoContamNoLimite() {
        assertNull(channelNameProblem("  " + "a".repeat(32) + "  "))
    }

    @Test
    fun acentosEEmojisContamComoUmPontoDeCodigo() {
        // 32 emojis de dois `Char` cada: 64 unidades de UTF-16, 32 pontos de código.
        val emojis = "😀".repeat(32)
        assertEquals(64, emojis.length)
        assertEquals(32, codePointLength(emojis))
        assertNull(channelNameProblem(emojis))
        assertEquals(ChannelNameProblem.TooLong, channelNameProblem(emojis + "á"))
        assertNull(channelNameProblem("ç".repeat(32)))
    }

    @Test
    fun substitutoSoltoContaComoUmCaractere() {
        assertEquals(1, codePointLength("\uD83D"))
        assertEquals(1, codePointLength("\uDE00"))
        assertEquals(2, codePointLength("\uDE00\uD83D"))
        assertEquals(0, codePointLength(""))
    }

    // Descrição

    @Test
    fun descricaoVaziaSoDeEspacosOuAteQuinhentosEDozeServe() {
        assertTrue(channelTopicFits(""))
        assertTrue(channelTopicFits("   "))
        assertTrue(channelTopicFits("a".repeat(512)))
        assertTrue(channelTopicFits("🙂".repeat(512)))
        assertTrue(channelTopicFits(" " + "a".repeat(512) + " "))
    }

    @Test
    fun descricaoDeQuinhentosETrezeNaoServe() {
        assertFalse(channelTopicFits("a".repeat(513)))
        assertFalse(channelTopicFits("🙂".repeat(513)))
    }

    // O pedido

    private fun channel(type: ChannelType = ChannelType.Text, name: String = "geral", topic: String? = "Conversa") = Channel(
        id = "c1", name = name, type = type, position = 1, topic = topic, parentId = "cat",
        lastMessageId = null, lastMessageAt = null, lastMessageText = null, lastMessageAuthor = null,
        lastReadMessage = null, notification = null,
    )

    private fun state(
        channel: Channel? = channel(),
        name: String = "geral",
        topic: String = "Conversa",
        canManage: Boolean = true,
        gone: Boolean = false,
        busy: Boolean = false,
        saved: Boolean = false,
    ) = ChannelSettingsState(channel, name, topic, canManage, gone, busy, null, saved)

    @Test
    fun semMudancaNaoHaNadaParaSalvar() {
        val s = state()
        assertFalse(s.changed)
        assertFalse(s.canSave)
        assertNull(s.topicToSend)
    }

    @Test
    fun soOTopicoMudouMandaOTopicoESalva() {
        val s = state(topic = "  Outra conversa ")
        assertEquals("Outra conversa", s.topicToSend)
        assertTrue(s.canSave)
    }

    @Test
    fun soONomeMudouDeixaOTopicoForaDoPedido() {
        val s = state(name = "papo")
        assertNull(s.topicToSend)
        assertTrue(s.canSave)
    }

    @Test
    fun apagarADescricaoMandaStringVazia() {
        assertEquals("", state(topic = "").topicToSend)
        assertEquals("", state(topic = "   ").topicToSend)
        assertTrue(state(topic = "").canSave)
    }

    @Test
    fun canalSemDescricaoQueContinuaVazioNaoMandaTopico() {
        assertNull(state(channel = channel(topic = null), topic = "").topicToSend)
        assertNull(state(channel = channel(topic = null), topic = "  ").topicToSend)
        assertEquals("nova", state(channel = channel(topic = null), topic = "nova").topicToSend)
    }

    @Test
    fun categoriaNaoTemDescricaoNemMandaTopico() {
        val s = state(channel = channel(ChannelType.Category, topic = null), name = "Outra", topic = "texto qualquer")
        assertFalse(s.hasTopic)
        assertTrue(s.topicFits)
        assertNull(s.topicToSend)
        assertTrue(s.canSave)
    }

    @Test
    fun nomeVazioOuLongoOuDescricaoLongaImpedemSalvar() {
        assertFalse(state(name = "  ").canSave)
        assertFalse(state(name = "a".repeat(33)).canSave)
        assertFalse(state(topic = "a".repeat(513)).canSave)
        assertTrue(state(name = "a".repeat(32), topic = "a".repeat(512)).canSave)
    }

    @Test
    fun descricaoLongaDeCategoriaNaoImpedeSalvar() {
        assertTrue(state(channel = channel(ChannelType.Category), name = "Outra", topic = "a".repeat(600)).canSave)
    }

    @Test
    fun cadaCondicaoDeEditarImpedeSalvarSozinha() {
        val changed = state(name = "papo")
        assertTrue(changed.canSave)
        assertFalse(changed.copy(canManage = false).canSave)
        assertFalse(changed.copy(gone = true).canSave)
        assertFalse(changed.copy(busy = true).canSave)
        assertFalse(changed.copy(saved = true).canSave)
        assertFalse(changed.copy(channel = null).canSave)
        assertFalse(changed.copy(channel = null).changed)
    }

    // O resultado

    @Test
    fun cadaRecusaDoServidorViraOResultadoDaTela() {
        val rejected = MutationResult.Rejected("x", null)
        assertEquals(ChannelEditResult.Saved, channelEditResult(MutationResult.Done, null))
        assertEquals(ChannelEditResult.NameTaken, channelEditResult(rejected, 409))
        assertEquals(ChannelEditResult.Forbidden, channelEditResult(rejected, 403))
        assertEquals(ChannelEditResult.NotFound, channelEditResult(rejected, 404))
        assertEquals(ChannelEditResult.Failed(rejected), channelEditResult(rejected, 400))
        assertEquals(ChannelEditResult.Failed(rejected), channelEditResult(rejected, null))
    }

    @Test
    fun osResultadosSemStatusPassamComoFalha() {
        for (result in listOf(MutationResult.RateLimited, MutationResult.NotSent, MutationResult.Unauthorized, MutationResult.Uncertain)) {
            assertEquals(ChannelEditResult.Failed(result), channelEditResult(result, 409))
        }
    }

    // Quem vê as configurações

    private fun snapshot(owner: String? = "boss", ownerKnown: Boolean = true, flags: Set<RoleFlag>? = null, mine: Set<String> = emptySet()) =
        PermissionSnapshot(
            ownerId = owner,
            ownerKnown = ownerKnown,
            roles = flags?.let { mapOf("r" to RoleFlags(it)) },
            userRoles = mapOf("me" to mine),
            usersComplete = true,
        )

    @Test
    fun donoVeAsConfiguracoes() {
        assertTrue(canManageChannels(snapshot(owner = "me"), "me"))
    }

    @Test
    fun cargoComManageChannelsVe() {
        assertTrue(canManageChannels(snapshot(flags = setOf(RoleFlag.ManageChannels), mine = setOf("r")), "me"))
    }

    @Test
    fun cargoSemAFlagNaoVe() {
        assertFalse(canManageChannels(snapshot(flags = setOf(RoleFlag.PinMessage), mine = setOf("r")), "me"))
    }

    @Test
    fun desconhecidoNaoVe() {
        assertFalse(canManageChannels(PermissionSnapshot.EMPTY, "me"))
        assertFalse(canManageChannels(snapshot(flags = null, mine = setOf("r")), "me"))
        assertFalse(canManageChannels(snapshot(ownerKnown = false, flags = setOf(RoleFlag.PinMessage), mine = setOf("r")), "me"))
    }
}
