package chat.papo.mobile

import chat.papo.mobile.voice.answerTrackIds
import kotlin.test.Test
import kotlin.test.assertEquals

class SdpTracksTest {
    private fun sdp(vararg lines: String, eol: String = "\r\n") = lines.joinToString(eol) + eol

    @Test
    fun ligaCadaMidAFaixaDoServidor() {
        val answer = sdp(
            "v=0",
            "a=msid-semantic: WMS",
            "m=video 9 UDP/TLS/RTP/SAVPF 96",
            "a=mid:0",
            "a=inactive",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111",
            "a=mid:1",
            "a=msid:papo-audio papo-audio-0",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111",
            "a=mid:2",
            "a=msid:papo-audio papo-audio-1",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111",
            "a=mid:3",
            "a=msid:papo-screen-audio papo-screen-audio-0",
        )
        assertEquals(
            mapOf("1" to "papo-audio-0", "2" to "papo-audio-1", "3" to "papo-screen-audio-0"),
            answerTrackIds(answer),
        )
    }

    @Test
    fun mLineSemMsidFicaDeFora() {
        val answer = sdp(
            "m=audio 9 UDP/TLS/RTP/SAVPF 111",
            "a=mid:a",
            "a=inactive",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111",
            "a=mid:b",
            "a=msid:papo-audio papo-audio-3",
        )
        assertEquals(mapOf("b" to "papo-audio-3"), answerTrackIds(answer))
    }

    @Test
    fun streamTracoUsaOIdDaFaixa() {
        val answer = sdp("m=audio 9 UDP/TLS/RTP/SAVPF 111", "a=mid:0", "a=msid:- papo-audio-2")
        assertEquals(mapOf("0" to "papo-audio-2"), answerTrackIds(answer))
    }

    @Test
    fun msidSemIdDeFaixaNaoConta() {
        val answer = sdp("m=audio 9 UDP/TLS/RTP/SAVPF 111", "a=mid:0", "a=msid:papo-audio")
        assertEquals(emptyMap(), answerTrackIds(answer))
    }

    @Test
    fun soAPrimeiraMsidDaMLineVale() {
        val answer = sdp(
            "m=audio 9 UDP/TLS/RTP/SAVPF 111",
            "a=mid:0",
            "a=msid:papo-audio papo-audio-0",
            "a=msid:outro papo-audio-9",
        )
        assertEquals(mapOf("0" to "papo-audio-0"), answerTrackIds(answer))
    }

    @Test
    fun msidAntesDeUmaMsidSemFaixaEUsaASeguinte() {
        val answer = sdp("m=audio 9 UDP/TLS/RTP/SAVPF 111", "a=mid:0", "a=msid:so-stream", "a=msid:papo-audio papo-audio-4")
        assertEquals(mapOf("0" to "papo-audio-4"), answerTrackIds(answer))
    }

    @Test
    fun aceitaSoLFEMidAposAMsid() {
        val answer = sdp(
            "m=audio 9 UDP/TLS/RTP/SAVPF 111",
            "a=msid:papo-audio papo-audio-0",
            "a=mid:0",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111",
            "a=msid:papo-audio papo-audio-1",
            "a=mid:1",
            eol = "\n",
        )
        assertEquals(mapOf("0" to "papo-audio-0", "1" to "papo-audio-1"), answerTrackIds(answer))
    }

    @Test
    fun mLineSemMidFicaDeFora() {
        val answer = sdp("m=audio 9 UDP/TLS/RTP/SAVPF 111", "a=msid:papo-audio papo-audio-0", "m=audio 9 UDP/TLS/RTP/SAVPF 111", "a=mid:  ", "a=msid:x papo-audio-1")
        assertEquals(emptyMap(), answerTrackIds(answer))
    }

    @Test
    fun linhasAntesDaPrimeiraMLineSaoIgnoradas() {
        val answer = sdp("a=mid:0", "a=msid:papo-audio papo-audio-0", "m=audio 9 UDP/TLS/RTP/SAVPF 111")
        assertEquals(emptyMap(), answerTrackIds(answer))
    }

    @Test
    fun textoVazioNaoTemFaixas() {
        assertEquals(emptyMap(), answerTrackIds(""))
    }
}
