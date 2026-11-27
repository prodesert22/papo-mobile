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

enum class AudioOutputKind { Earpiece, Speaker, Wired, Usb, Bluetooth, HearingAid, Other }

/**
 * Uma saída de áudio da chamada. [id] é estável enquanto o aparelho estiver ligado;
 * [name] é o nome que o sistema dá ao aparelho (fone Bluetooth, por exemplo) e é
 * texto remoto ao app: só se mostra como texto.
 */
data class AudioOutput(val id: String, val kind: AudioOutputKind, val name: String? = null)

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

    /** Saídas de áudio disponíveis agora; vazia antes de [open] ou onde a plataforma não lista. */
    val outputs: StateFlow<List<AudioOutput>>

    /** A saída em uso; null antes de [open] ou se a plataforma não informa. */
    val currentOutput: StateFlow<AudioOutput?>

    /** Fixa a saída da chamada; um [id] que não está em [outputs] é ignorado. */
    fun selectOutput(id: String)

    /**
     * Ganho de uma faixa de áudio recebida, pelo id que o servidor dá a ela
     * (`papo-audio-N`, `papo-screen-audio-N`, os mesmos de `voice_audio_routes`).
     * 1.0 é o volume normal, 0.0 silencia; id desconhecido é ignorado.
     */
    fun setRemoteAudioGain(trackId: String, gain: Double)

    fun close()
}

fun interface VoiceEngineFactory {
    fun create(): VoiceEngine
}
