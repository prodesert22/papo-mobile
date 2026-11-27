package chat.papo.mobile

import chat.papo.mobile.data.ws.WsState
import chat.papo.mobile.ui.components.showsOffline
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionIndicatorTest {
    @Test
    fun desconectadoOuTentandoConectarContaComoSemConexao() {
        assertTrue(showsOffline(WsState.Disconnected))
        assertTrue(showsOffline(WsState.Connecting))
    }

    @Test
    fun conectadoNaoMostraOIcone() {
        assertFalse(showsOffline(WsState.Connected))
    }

    @Test
    fun sessaoQueAcabouTemOProprioAvisoENaoMostraOIcone() {
        assertFalse(showsOffline(WsState.Unauthorized))
    }
}
