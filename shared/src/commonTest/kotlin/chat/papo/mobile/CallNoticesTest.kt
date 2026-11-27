package chat.papo.mobile

import chat.papo.mobile.voice.CallNoticeTexts
import chat.papo.mobile.voice.CallPhase
import chat.papo.mobile.voice.CallState
import chat.papo.mobile.voice.callNotice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallNoticesTest {
    private val texts = CallNoticeTexts(
        channelLabel = "Chamadas",
        defaultTitle = "Chamada de voz",
        publicText = "Chamada em andamento",
        joining = "Entrando",
        connecting = "Conectando",
        reconnecting = "Reconectando",
        micOn = "Microfone ligado",
        micOff = "Microfone desligado",
        cameraOn = "Câmera ligada",
        sharingScreen = "Transmitindo a tela",
        mute = "Silenciar",
        unmute = "Ativar microfone",
        hangup = "Desligar",
    )

    private fun state(
        phase: CallPhase = CallPhase.Connected,
        micMuted: Boolean = false,
        cameraOn: Boolean = false,
        screenOn: Boolean = false,
        pushToTalk: Boolean = false,
    ) = CallState("conta", "v1", phase = phase, micMuted = micMuted, cameraOn = cameraOn, screenOn = screenOn, pushToTalk = pushToTalk)

    @Test
    fun oTituloEONomeDoCanal() {
        assertEquals("Sala de teste", callNotice(state(), "Sala de teste", texts).title)
    }

    @Test
    fun semNomeDeCanalUsaOTituloPadrao() {
        assertEquals("Chamada de voz", callNotice(state(), null, texts).title)
        assertEquals("Chamada de voz", callNotice(state(), "  ", texts).title)
    }

    @Test
    fun aVersaoDaTelaDeBloqueioNaoTemNomeDeCanal() {
        val notice = callNotice(state(cameraOn = true), "Sala de teste", texts)
        assertEquals("Chamada em andamento", notice.publicText)
        assertFalse("Sala de teste" in notice.publicText)
    }

    @Test
    fun noTextoOQueEstaAtivo() {
        assertEquals("Microfone ligado", callNotice(state(), null, texts).text)
        assertEquals("Microfone ligado", callNotice(state(phase = CallPhase.Ended), null, texts).text)
        assertEquals("Microfone desligado", callNotice(state(micMuted = true), null, texts).text)
        assertEquals("Microfone ligado · Câmera ligada", callNotice(state(cameraOn = true), null, texts).text)
        assertEquals("Microfone ligado · Transmitindo a tela", callNotice(state(screenOn = true), null, texts).text)
        assertEquals(
            "Microfone desligado · Câmera ligada · Transmitindo a tela",
            callNotice(state(micMuted = true, cameraOn = true, screenOn = true), null, texts).text,
        )
    }

    @Test
    fun antesDeEstarDePeODizOndeAChamadaEsta() {
        assertEquals("Entrando", callNotice(state(phase = CallPhase.Joining, cameraOn = true), null, texts).text)
        assertEquals("Conectando", callNotice(state(phase = CallPhase.Connecting), null, texts).text)
        assertEquals("Reconectando", callNotice(state(phase = CallPhase.Reconnecting, screenOn = true), null, texts).text)
    }

    @Test
    fun oBotaoDeSilenciarSegueOEstadoDoMicrofone() {
        val open = callNotice(state(micMuted = false), null, texts)
        assertEquals("Silenciar", open.muteLabel)
        assertFalse(open.muted)
        val muted = callNotice(state(micMuted = true), null, texts)
        assertEquals("Ativar microfone", muted.muteLabel)
        assertTrue(muted.muted)
    }

    @Test
    fun comApertarParaFalarNaoHaBotaoDeSilenciar() {
        assertTrue(callNotice(state(), null, texts).canMute)
        assertFalse(callNotice(state(pushToTalk = true), null, texts).canMute)
    }

    @Test
    fun oRotuloDoCanalEODeDesligarVemDosTextos() {
        val notice = callNotice(state(), null, texts)
        assertEquals("Chamadas", notice.channelLabel)
        assertEquals("Desligar", notice.hangupLabel)
    }
}
