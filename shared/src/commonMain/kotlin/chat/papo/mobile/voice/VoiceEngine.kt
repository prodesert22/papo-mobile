package chat.papo.mobile.voice

import kotlinx.coroutines.flow.StateFlow

data class IceServer(val urls: List<String>, val username: String?, val credential: String?)

/**
 * Quantas faixas receptoras o cliente oferece. O SFU só preenche as m-lines que
 * vierem na primeira oferta, na ordem: vídeo, microfones, áudio de transmissões.
 * Os valores precisam acompanhar `VOICE_VIDEO_SLOTS`, `VOICE_AUDIO_SLOTS` e
 * `VOICE_SCREEN_AUDIO_SLOTS` do servidor (padrões abaixo).
 */
data class VoiceSlots(val video: Int = 6, val audio: Int = 8, val screenAudio: Int = 2)

enum class EngineConnection { New, Connecting, Connected, Disconnected, Failed, Closed }

/** Faixa de vídeo que a plataforma sabe desenhar. */
class VideoTrackHandle(val native: Any, val mirrored: Boolean = false)

class LocalCandidate(val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int?)

enum class MediaPermission { Microphone, Camera }

/**
 * Motor WebRTC da plataforma (libwebrtc no Android). Só cuida de mídia e SDP;
 * quem decide o que negociar e quando é o [CallController].
 */
interface VoiceEngine {
    val connection: StateFlow<EngineConnection>

    /** Vídeo recebido, um item por slot receptor, na ordem das m-lines. */
    val remoteVideo: StateFlow<List<VideoTrackHandle?>>

    val localCamera: StateFlow<VideoTrackHandle?>

    val localScreen: StateFlow<VideoTrackHandle?>

    /** Pede a permissão ao usuário se ainda não foi concedida. */
    suspend fun ensurePermission(permission: MediaPermission): Boolean

    /**
     * Cria a conexão já com todas as faixas receptoras e o microfone (desligado).
     * [onCandidate] recebe os candidatos ICE locais conforme são descobertos.
     */
    suspend fun open(iceServers: List<IceServer>, slots: VoiceSlots, onCandidate: (LocalCandidate) -> Unit)

    /** Gera a oferta e a aplica como descrição local. */
    suspend fun createOffer(): String

    suspend fun applyAnswer(sdp: String)

    fun addRemoteCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?)

    fun setMicrophoneEnabled(enabled: Boolean)

    /** Liga a câmera e a prepara para publicar; falta renegociar. False se não foi possível abrir. */
    suspend fun startCamera(): Boolean

    /** Para de enviar a câmera (a m-line fica inativa); falta renegociar. */
    suspend fun stopCamera()

    fun switchCamera()

    /** Pede a captura de tela ao sistema e a prepara para publicar; falta renegociar. */
    suspend fun startScreen(): Boolean

    suspend fun stopScreen()

    fun setSpeakerphone(enabled: Boolean)

    fun close()
}

fun interface VoiceEngineFactory {
    fun create(): VoiceEngine
}
