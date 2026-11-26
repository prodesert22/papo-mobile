package chat.papo.mobile

import androidx.compose.runtime.saveable.SaverScope
import chat.papo.mobile.ui.home.ConversationPanelState
import chat.papo.mobile.ui.home.PanelValue
import chat.papo.mobile.ui.home.ParkedChat
import chat.papo.mobile.ui.home.ParkedChats
import chat.papo.mobile.ui.home.StartupOpening
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class ConversationPanelStateTest {
    private val geral = ParkedChat("canal-geral")
    private val cafe = ParkedChat("canal-cafe")

    private fun saved(state: ConversationPanelState): Any = with(ConversationPanelState.Saver) { SaverScope { true }.save(state) }!!

    private fun restored(value: Any): ConversationPanelState = ConversationPanelState.Saver.restore(value)!!

    @Test
    fun comecaFechadoESemConversas() {
        val state = ConversationPanelState()

        assertEquals(PanelValue.Closed, state.anchored.targetValue)
        assertEquals(ParkedChats.Empty, state.chats)
        assertNull(state.request)
    }

    @Test
    fun comecaComAAberturaAutomaticaPorFazer() {
        assertEquals(StartupOpening.Fresh, ConversationPanelState().startup)
    }

    @Test
    fun abrirUmaConversaEncerraAAberturaAutomatica() {
        val state = ConversationPanelState()

        state.open("conta-ana", geral)

        assertEquals(StartupOpening.Done, state.startup)
    }

    @Test
    fun fecharOPainelEncerraAAberturaAutomatica() {
        val state = ConversationPanelState(ParkedChats.Empty, open = false, startup = StartupOpening.Waiting("conta-ana"))

        state.close()

        assertEquals(StartupOpening.Done, state.startup)
    }

    @Test
    fun abrirGuardaAConversaDaContaEPedeOPainelAberto() {
        val state = ConversationPanelState()

        state.open("conta-ana", geral)

        assertEquals(geral, state.chats["conta-ana"])
        assertEquals(PanelValue.Open, state.request?.target)
    }

    @Test
    fun abrirNaContaDeOutraNaoMexeNaPrimeira() {
        val state = ConversationPanelState()

        state.open("conta-ana", geral)
        state.open("conta-bia", cafe)

        assertEquals(geral, state.chats["conta-ana"])
        assertEquals(cafe, state.chats["conta-bia"])
    }

    @Test
    fun fecharPedeOPainelFechadoSemPerderAConversa() {
        val state = ConversationPanelState()
        state.open("conta-ana", geral)

        state.close()

        assertEquals(PanelValue.Closed, state.request?.target)
        assertEquals(geral, state.chats["conta-ana"])
    }

    @Test
    fun cadaPedidoEUmObjetoNovoMesmoComODestinoRepetido() {
        val state = ConversationPanelState()
        state.open("conta-ana", geral)
        val primeiro = state.request

        state.open("conta-ana", geral)

        assertNotSame(primeiro, state.request)
    }

    @Test
    fun pedidoAtendidoSaiEUmMaisNovoFica() {
        val state = ConversationPanelState()
        state.open("conta-ana", geral)
        val antigo = assertNotNull(state.request)

        state.consume(antigo)
        assertNull(state.request)

        state.open("conta-ana", cafe)
        val novo = state.request
        state.consume(antigo)
        assertSame(novo, state.request)
    }

    @Test
    fun contaQueSaiLevaAConversa() {
        val state = ConversationPanelState()
        state.open("conta-ana", geral)
        state.open("conta-bia", cafe)

        state.retain(setOf("conta-bia"))

        assertNull(state.chats["conta-ana"])
        assertEquals(cafe, state.chats["conta-bia"])
    }

    @Test
    fun girarOAparelhoGuardaAsConversasEOPainelAberto() {
        val before = ConversationPanelState(ParkedChats.Empty.opened("conta-ana", geral).opened("conta-bia", cafe), open = true)

        val after = restored(saved(before))

        assertEquals(before.chats, after.chats)
        assertEquals(PanelValue.Open, after.anchored.targetValue)
    }

    @Test
    fun girarOAparelhoComOPainelFechadoOMantemFechado() {
        val before = ConversationPanelState(ParkedChats.Empty.opened("conta-ana", geral), open = false)

        val after = restored(saved(before))

        assertEquals(geral, after.chats["conta-ana"])
        assertEquals(PanelValue.Closed, after.anchored.targetValue)
    }

    @Test
    fun estadoSalvoSemNadaVoltaFechadoESemConversasEComAAberturaAutomaticaResolvida() {
        val after = restored(emptyList<Any>())

        assertEquals(PanelValue.Closed, after.anchored.targetValue)
        assertEquals(ParkedChats.Empty, after.chats)
        assertEquals(StartupOpening.Done, after.startup)
    }

    @Test
    fun girarOAparelhoGuardaOndeEstaAAberturaAutomatica() {
        listOf(StartupOpening.Fresh, StartupOpening.Waiting("conta-ana"), StartupOpening.Done).forEach { phase ->
            val before = ConversationPanelState(ParkedChats.Empty.opened("conta-bia", cafe), open = false, startup = phase)

            val after = restored(saved(before))

            assertEquals(phase, after.startup)
            assertEquals(cafe, after.chats["conta-bia"])
        }
    }

    @Test
    fun girarOAparelhoNaoMisturaAFaseDaAberturaComAsConversas() {
        val before = ConversationPanelState(
            ParkedChats.Empty.opened("conta-ana", geral).opened("conta-bia", cafe),
            open = true,
            startup = StartupOpening.Waiting("conta-ana"),
        )

        val after = restored(saved(before))

        assertEquals(before.chats, after.chats)
        assertEquals(PanelValue.Open, after.anchored.targetValue)
    }
}
