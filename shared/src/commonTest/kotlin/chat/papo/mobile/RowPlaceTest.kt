package chat.papo.mobile

import chat.papo.mobile.chat.RowPlace
import chat.papo.mobile.chat.rowPlace
import chat.papo.mobile.voice.CallPhase
import chat.papo.mobile.voice.CallState
import chat.papo.mobile.voice.channelOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Onde quem usa a conta está na lista: a conversa do painel e o canal de voz da chamada. */
class RowPlaceTest {

    @Test
    fun aConversaDoPainelFicaMarcada() {
        assertEquals(RowPlace.Open, rowPlace("geral", openId = "geral", callId = null))
    }

    @Test
    fun outraConversaFicaSemMarca() {
        assertEquals(RowPlace.None, rowPlace("avisos", openId = "geral", callId = "sala"))
        assertEquals(RowPlace.None, rowPlace("geral", openId = null, callId = null))
    }

    @Test
    fun oCanalDaChamadaGanhaAMarcaDeChamada() {
        assertEquals(RowPlace.InCall, rowPlace("sala", openId = "geral", callId = "sala"))
    }

    @Test
    fun aChamadaVenceOChatDoMesmoCanalDeVozNoPainel() {
        assertEquals(RowPlace.InCall, rowPlace("sala", openId = "sala", callId = "sala"))
    }

    @Test
    fun oCanalDaChamadaValeDaEntradaAteOFim() {
        for (phase in CallPhase.entries - CallPhase.Ended) {
            assertEquals("sala", CallState("conta-a", "sala", phase = phase).channelOf("conta-a"), "fase $phase")
        }
    }

    @Test
    fun chamadaTerminadaNaoMarcaNada() {
        // O motivo do fim fica na tela até fechar, mas a pessoa já não está no canal.
        assertNull(CallState("conta-a", "sala", phase = CallPhase.Ended).channelOf("conta-a"))
    }

    @Test
    fun aChamadaDeUmaContaNaoMarcaALinhaDeOutra() {
        // Duas contas, até no mesmo servidor, são contextos separados: o mesmo id de canal na outra conta não é a chamada.
        assertNull(CallState("conta-a", "sala", phase = CallPhase.Connected).channelOf("conta-b"))
    }
}
