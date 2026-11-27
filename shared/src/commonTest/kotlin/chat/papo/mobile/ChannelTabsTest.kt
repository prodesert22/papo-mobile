package chat.papo.mobile

import chat.papo.mobile.chat.MentionCounts
import chat.papo.mobile.ui.chat.ListTab
import chat.papo.mobile.ui.chat.ListTabs
import chat.papo.mobile.ui.chat.tabBadges
import chat.papo.mobile.ui.home.ConversationPanelState
import chat.papo.mobile.ui.home.ParkedChat
import chat.papo.mobile.ui.home.ParkedChats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ChannelTabsTest {
    private fun counts(perChannel: Map<String, Int> = emptyMap(), perDm: Map<String, Int> = emptyMap(), capped: Boolean = false) =
        MentionCounts(perChannel, perDm, perChannel.values.sum() + perDm.values.sum(), capped)

    @Test
    fun semMencoesNenhumaAbaTemSelo() {
        val badges = tabBadges(counts())

        assertNull(badges.channels)
        assertNull(badges.direct)
    }

    @Test
    fun cadaAbaSomaSoAsLinhasDela() {
        val badges = tabBadges(counts(perChannel = mapOf("c1" to 2, "c2" to 1), perDm = mapOf("d1" to 4)))

        assertEquals("3", badges.channels)
        assertEquals("4", badges.direct)
    }

    @Test
    fun soUmaAbaComMencaoDeixaAOutraSemSelo() {
        val badges = tabBadges(counts(perDm = mapOf("d1" to 1)))

        assertNull(badges.channels)
        assertEquals("1", badges.direct)
    }

    @Test
    fun aCaixaLidaEmParteMarcaSoOSeloDosCanais() {
        val badges = tabBadges(counts(perChannel = mapOf("c1" to 5), perDm = mapOf("d1" to 2), capped = true))

        assertEquals("5+", badges.channels)
        assertEquals("2", badges.direct)
    }

    @Test
    fun acimaDeNoventaENoveOSeloSaturaEmCadaAba() {
        val badges = tabBadges(counts(perChannel = mapOf("c1" to 60, "c2" to 50), perDm = mapOf("d1" to 120)))

        assertEquals("99+", badges.channels)
        assertEquals("99+", badges.direct)
    }

    @Test
    fun contaSemEscolhaAbreNosCanais() {
        assertEquals(ListTab.Channels, ListTabs.Empty["conta-ana"])
    }

    @Test
    fun aAbaEscolhidaFicaPorConta() {
        val tabs = ListTabs.Empty.selected("conta-ana", ListTab.Direct)

        assertEquals(ListTab.Direct, tabs["conta-ana"])
        assertEquals(ListTab.Channels, tabs["conta-bia"])
    }

    @Test
    fun voltarAosCanaisDevolveOPadraoESemEntrada() {
        val tabs = ListTabs.Empty.selected("conta-ana", ListTab.Direct).selected("conta-ana", ListTab.Channels)

        assertEquals(ListTab.Channels, tabs["conta-ana"])
        assertEquals(ListTabs.Empty, tabs)
    }

    @Test
    fun escolherAAbaQueJaEstaNaoCriaOutroValor() {
        val tabs = ListTabs.Empty.selected("conta-ana", ListTab.Direct)

        assertSame(tabs, tabs.selected("conta-ana", ListTab.Direct))
        assertSame(ListTabs.Empty, ListTabs.Empty.selected("conta-ana", ListTab.Channels))
    }

    @Test
    fun oEstadoSalvoVoltaIgual() {
        val tabs = ListTabs.Empty.selected("conta-ana", ListTab.Direct).selected("conta-bia", ListTab.Direct)

        assertEquals(tabs, ListTabs.decode(tabs.encode()))
        assertEquals(ListTabs.Empty, ListTabs.decode(ListTabs.Empty.encode()))
    }

    @Test
    fun estadoSalvoEstragadoVoltaAoPadrao() {
        assertEquals(ListTabs.Empty, ListTabs.decode(listOf("conta-ana", "Inexistente")))
        assertEquals(ListTabs.Empty, ListTabs.decode(listOf("", "Direct")))
        assertEquals(ListTabs.Empty, ListTabs.decode(listOf("conta-ana")))
        assertEquals(ListTabs.Empty, ListTabs.decode(listOf("conta-ana", "Channels")))
    }

    @Test
    fun fecharUmaDmEstacionadaFazOPainelEsquecelaSoNaContaDela() {
        val dm = ParkedChat("dm-1")
        val chats = ParkedChats.Empty.opened("conta-ana", dm).opened("conta-bia", dm)

        val without = chats.without("conta-ana", "dm-1")

        assertNull(without["conta-ana"])
        assertEquals(dm, without["conta-bia"])
    }

    @Test
    fun esquecerOutraConversaOuOutraContaNaoMudaNada() {
        val chats = ParkedChats.Empty.opened("conta-ana", ParkedChat("dm-1"))

        assertSame(chats, chats.without("conta-ana", "dm-2"))
        assertSame(chats, chats.without("conta-bia", "dm-1"))
    }

    @Test
    fun oPainelEsquecaADmFechadaEMantemAsOutras() {
        val state = ConversationPanelState()
        state.open("conta-ana", ParkedChat("dm-1"))
        state.open("conta-bia", ParkedChat("canal-geral"))

        state.forget("conta-ana", "dm-1")
        state.forget("conta-bia", "dm-1")

        assertNull(state.chats["conta-ana"])
        assertEquals(ParkedChat("canal-geral"), state.chats["conta-bia"])
    }
}
