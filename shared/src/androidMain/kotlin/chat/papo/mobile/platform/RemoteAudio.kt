package chat.papo.mobile.platform

import org.webrtc.AudioTrack
import org.webrtc.RtpTransceiver

/**
 * Os receptores de áudio da conexão e o ganho de cada um. O servidor identifica as faixas por
 * `papo-audio-N`/`papo-screen-audio-N`; o libwebrtc, por transceptor. A ponte é o `mid` de cada m-line
 * com o `msid` da resposta ([trackIdsByMid]), refeita a cada resposta.
 *
 * O ganho pedido fica guardado por id de faixa: um pedido que chega antes da primeira resposta (ou de a
 * m-line ganhar `mid`) é aplicado quando a ligação existir. Não tem trava própria: quem usa sincroniza
 * no objeto.
 */
internal class RemoteAudio {
    var transceivers: List<RtpTransceiver> = emptyList()
    var trackIdsByMid: Map<String, String> = emptyMap()
    private val gains = HashMap<String, Double>()

    fun setGain(trackId: String, gain: Double) {
        gains[trackId] = gain.coerceIn(0.0, MAX_GAIN)
        apply(trackId)
    }

    fun applyAll() {
        for (trackId in gains.keys) apply(trackId)
    }

    /** Solta os transceptores (a conexão vai ser descartada) e esquece os ganhos. */
    fun clear() {
        transceivers = emptyList()
        trackIdsByMid = emptyMap()
        gains.clear()
    }

    private fun apply(trackId: String) {
        val gain = gains[trackId] ?: return
        val mid = trackIdsByMid.entries.firstOrNull { it.value == trackId }?.key ?: return
        val track = transceivers.firstOrNull { it.mid == mid }?.receiver?.track() as? AudioTrack ?: return
        track.setVolume(gain)
    }

    private companion object {
        /** O libwebrtc aceita de 0 a 10. */
        const val MAX_GAIN = 10.0
    }
}
