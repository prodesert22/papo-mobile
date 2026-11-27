package chat.papo.mobile.media

import chat.papo.mobile.chat.MAX_ATTACHMENT_BYTES
import chat.papo.mobile.data.api.OutgoingFile
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** O que o usuário respondeu ao pedido do microfone. [Blocked] é a recusa que o sistema não deixa mais perguntar. */
enum class MicrophoneAccess { Granted, Denied, Blocked }

/** O formato que o gravador da plataforma produz. [bitrate] é o de áudio, em bits por segundo. */
class VoiceFormat(val mimeType: String, val extension: String, val bitrate: Int)

/**
 * Uma gravação pronta, só na memória (regra 8: nada de uma gravação vai para o disco). [name] segue o nome que o web dá a
 * uma gravação de voz (`audio_*.webm`), o que faz o app e o web mostrarem o anexo como áudio.
 */
class VoiceClip(val name: String, val mimeType: String, val bytes: ByteArray, val durationMs: Long) {
    /** Apaga os bytes da memória; só quando a gravação foi descartada, nunca depois de entregue ao envio. */
    internal fun wipe() = bytes.fill(0)
}

/** O arquivo de envio de [this]: a fila lê os bytes da memória a cada tentativa. */
fun VoiceClip.toOutgoingFile(): OutgoingFile =
    OutgoingFile(name = name, mimeType = mimeType, size = bytes.size.toLong(), open = { ByteReadChannel(bytes) })

/** O gravador não abriu o microfone, falhou ao gravar ou não entregou uma gravação válida. */
class VoiceRecorderException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * O gravador de voz da plataforma. Grava para a memória e nunca para o disco. Quem o usa é o [VoiceRecording], que fecha
 * cada [VoiceCapture] que abre.
 */
interface VoiceRecorder {
    val format: VoiceFormat

    /** Pede o microfone ao usuário se ainda não foi concedido. Devolve [MicrophoneAccess.Blocked] quando o sistema não pergunta mais. */
    suspend fun requestAccess(): MicrophoneAccess

    /** Abre a tela do app nas configurações do sistema, onde o usuário libera o microfone. */
    fun openSettings()

    /**
     * Abre o microfone e começa a gravar, com no máximo [maxDurationMs]. Bloqueia por pouco tempo: quem chama o faz fora da
     * thread principal. [onError] avisa de um erro depois de começar (o microfone foi tomado por outro app, por exemplo),
     * de qualquer thread; depois dele a captura não serve mais e quem a abriu a cancela.
     */
    fun start(maxDurationMs: Long, onError: () -> Unit): VoiceCapture
}

/** Uma gravação em curso. Quem a recebe é o dono: chama [finish] ou [cancel], e [cancel] sempre vale, também depois de [finish]. */
interface VoiceCapture {
    /** Para a gravação e devolve o que foi gravado, já solta o gravador. Bloqueia; lança [VoiceRecorderException] se não há gravação válida. */
    fun finish(): ByteArray

    /** Solta o gravador e esquece o que gravou. Pode ser chamada várias vezes e de qualquer thread. */
    fun cancel()
}

/** O player de uma gravação na revisão. Todas as chamadas, nos dois sentidos, acontecem na thread principal. */
interface VoiceClipPlayer {
    /** Toca a partir da posição atual; do começo, se tocou até o fim. */
    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    fun positionMs(): Long

    /** Fecha o player de vez. */
    fun release()
}

interface VoiceClipPlayerListener {
    /** O player toca ou não; falso também quando o sistema toma o foco de áudio. */
    fun onPlayingChanged(playing: Boolean)

    /** Tocou até o fim. */
    fun onEnded()

    /** O player não conseguiu tocar a gravação. */
    fun onFailure()
}

fun interface VoiceClipPlayerFactory {
    fun create(clip: VoiceClip, listener: VoiceClipPlayerListener): VoiceClipPlayer
}

/** Por que a gravação não começou ou não seguiu. */
enum class VoiceProblem {
    /** O usuário negou o microfone; o pedido pode vir de novo. */
    MicrophoneDenied,

    /** O sistema não pergunta mais: só as configurações do aparelho liberam o microfone. */
    MicrophoneBlocked,

    /** O microfone é da chamada de voz. */
    InCall,

    /** O gravador não abriu, falhou no meio ou não entregou uma gravação. */
    RecorderFailed,

    /** A gravação não toca na revisão; ainda pode ser enviada. */
    PlaybackFailed,
}

sealed interface VoiceRecordingState {
    data object Idle : VoiceRecordingState

    /** Gravando para [owner] (ver [VoiceRecording.start]); [elapsedMs] cresce até [maxMs], onde a gravação para sozinha. */
    data class Recording(val owner: String, val elapsedMs: Long, val maxMs: Long) : VoiceRecordingState

    /** A gravação de [owner] pronta para ouvir, descartar ou enviar. */
    data class Review(val owner: String, val clip: VoiceClip, val positionMs: Long, val playing: Boolean) : VoiceRecordingState
}

/** De quem é a gravação em curso, ou null sem gravação. */
val VoiceRecordingState.owner: String?
    get() = when (this) {
        VoiceRecordingState.Idle -> null
        is VoiceRecordingState.Recording -> owner
        is VoiceRecordingState.Review -> owner
    }

/**
 * Teto de uma gravação de voz, em milissegundos. O servidor aceita [MAX_ATTACHMENT_BYTES] por arquivo; a duração que isso dá
 * no [bitrate] (com 10% de folga para o contêiner) passa de horas, e a gravação inteira fica na memória, uma vez no gravador
 * e outra na lista de bytes. Por isso vale o menor entre os dois: [MAX_VOICE_MEMORY_MS] e o limite do servidor.
 */
fun voiceMaxDurationMs(bitrate: Int): Long {
    val bytesPerSecond = bitrate / 8L
    val byServer = MAX_ATTACHMENT_BYTES / 10L * 9L * 1_000L / bytesPerSecond
    return minOf(byServer, MAX_VOICE_MEMORY_MS)
}

/** O teto pela memória: meia hora de voz no [bitrate] do gravador cabe em poucos MiB. */
const val MAX_VOICE_MEMORY_MS = 30L * 60L * 1_000L

/** Quanto tempo a tela pode ficar fora antes de a gravação ser descartada: girar o aparelho a recria e volta em instantes. */
private const val HOST_GRACE_MS = 2_000L

private const val TICK_MS = 200L

/**
 * A gravação de uma mensagem de voz: pedir o microfone, gravar, parar e revisar (ouvir, descartar ou enviar). Há uma por
 * vez no app, dona do microfone e da gravação, e a sessão do app a fecha ([release]) ao fechar e ao travar. Todas as
 * chamadas vêm da thread principal.
 *
 * A gravação é de um [owner] (a conta e a conversa, texto opaco aqui): a tela só mostra a que é dela. Ela não sobrevive ao
 * que a trouxe: quem a descarta é o usuário, a saída da conversa ([hostHidden], com uma folga que cobre girar o aparelho),
 * outra gravação, a sessão que fecha e uma chamada de voz que começa. O app saindo da tela ([onBackground]) para a gravação
 * e a guarda para a revisão: o Android não deixa capturar o microfone em segundo plano sem um serviço, que o app não tem.
 */
class VoiceRecording(
    private val recorder: VoiceRecorder,
    private val players: VoiceClipPlayerFactory,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    /** Há chamada em curso: o microfone é dela, e a gravação não começa. */
    val callActive: StateFlow<Boolean>,
    /** Pausa o áudio do próprio app, que não deve tocar sobre a gravação nem sobre a revisão. */
    private val pauseOtherAudio: () -> Unit,
    /** O relógio de parede, em milissegundos, só para o nome do arquivo. */
    private val wallClockMs: () -> Long,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    val maxDurationMs: Long = voiceMaxDurationMs(recorder.format.bitrate)

    private val _state = MutableStateFlow<VoiceRecordingState>(VoiceRecordingState.Idle)
    val state: StateFlow<VoiceRecordingState> = _state.asStateFlow()

    private val _problem = MutableStateFlow<VoiceProblem?>(null)

    /** O último problema, até o usuário fechar o aviso ([dismissProblem]) ou começar de novo. */
    val problem: StateFlow<VoiceProblem?> = _problem.asStateFlow()

    private var capture: VoiceCapture? = null
    private var startedAt: TimeMark? = null

    /** Cada gravação tem a sua: um erro atrasado de uma captura que já saiu não derruba a seguinte. */
    private var generation = 0
    private var finishing = false
    private var player: VoiceClipPlayer? = null
    private var startJob: Job? = null
    private var finishJob: Job? = null
    private var tickerJob: Job? = null
    private var graceJob: Job? = null
    private var graceOwner: String? = null
    private var released = false

    init {
        // A chamada que começa tomou o microfone: o que já foi gravado fica para a revisão.
        scope.launch { callActive.collect { if (it) stop() } }
    }

    /**
     * Começa a gravar para [owner]: pede o microfone se preciso e abre o gravador. Sem o microfone, ou com uma chamada em
     * curso, nada começa e [problem] diz por quê. Uma gravação de outro dono é descartada; a do mesmo dono segue como está.
     */
    fun start(owner: String) {
        if (released || startJob?.isActive == true) return
        val current = _state.value
        if (current != VoiceRecordingState.Idle) {
            if (current.owner == owner) return
            discard()
        }
        _problem.value = null
        startJob = scope.launch { begin(owner) }
    }

    private suspend fun begin(owner: String) {
        if (callActive.value) return fail(VoiceProblem.InCall)
        when (recorder.requestAccess()) {
            MicrophoneAccess.Granted -> Unit
            MicrophoneAccess.Denied -> return fail(VoiceProblem.MicrophoneDenied)
            MicrophoneAccess.Blocked -> return fail(VoiceProblem.MicrophoneBlocked)
        }
        // O pedido ao usuário leva tempo: a chamada pode ter começado, ou a sessão fechado, enquanto ele respondia.
        if (callActive.value) return fail(VoiceProblem.InCall)
        pauseOtherAudio()
        val mine = ++generation
        var opened: VoiceCapture? = null
        val started = try {
            withContext(io) {
                recorder.start(maxDurationMs) { scope.launch { onCaptureError(mine) } }.also { opened = it }
            }
        } catch (e: CancellationException) {
            // O gravador pode ter aberto no instante em que o cancelamento chegou: sem dono ele ficaria com o microfone.
            opened?.cancel()
            throw e
        } catch (e: VoiceRecorderException) {
            return fail(VoiceProblem.RecorderFailed)
        }
        capture = started
        startedAt = timeSource.markNow()
        _state.value = VoiceRecordingState.Recording(owner, 0L, maxDurationMs)
        startTicker(owner)
    }

    /** Para de gravar e passa à revisão. Sem gravação em curso, ou já parando, nada acontece. */
    fun stop() {
        val recording = _state.value as? VoiceRecordingState.Recording ?: return
        val open = capture ?: return
        if (finishing) return
        finishing = true
        stopTicker()
        val elapsed = elapsedMs()
        finishJob = scope.launch { finish(recording.owner, open, elapsed) }
    }

    private suspend fun finish(owner: String, open: VoiceCapture, elapsedMs: Long) {
        val bytes = try {
            withContext(io) { open.finish() }
        } catch (e: CancellationException) {
            open.cancel()
            throw e
        } catch (e: VoiceRecorderException) {
            // Um descarte que chegou junto com o erro vale mais: a gravação já não é desta rotina.
            currentCoroutineContext().ensureActive()
            open.cancel()
            capture = null
            finishing = false
            _state.value = VoiceRecordingState.Idle
            return fail(VoiceProblem.RecorderFailed)
        }
        capture = null
        finishing = false
        if (bytes.isEmpty()) {
            _state.value = VoiceRecordingState.Idle
            return fail(VoiceProblem.RecorderFailed)
        }
        val format = recorder.format
        val clip = VoiceClip("audio_${wallClockMs()}.${format.extension}", format.mimeType, bytes, elapsedMs)
        _state.value = VoiceRecordingState.Review(owner, clip, 0L, playing = false)
    }

    private fun onCaptureError(forGeneration: Int) {
        if (forGeneration != generation || _state.value !is VoiceRecordingState.Recording) return
        discard()
        _problem.value = VoiceProblem.RecorderFailed
    }

    /** Descarta a gravação em curso ou a que está na revisão: solta o microfone e o player e apaga os bytes da memória. */
    fun discard() {
        startJob?.cancel()
        startJob = null
        finishJob?.cancel()
        finishJob = null
        stopTicker()
        generation++
        // Parando agora, o gravador está ocupado em outra thread: quem o solta é a rotina de parar, ao ver o cancelamento.
        if (!finishing) capture?.cancel()
        capture = null
        finishing = false
        startedAt = null
        player?.release()
        player = null
        (_state.value as? VoiceRecordingState.Review)?.clip?.wipe()
        _state.value = VoiceRecordingState.Idle
    }

    /** Descarta a gravação só se for de [owner]. */
    fun discard(owner: String) {
        if (_state.value.owner == owner) discard()
    }

    /**
     * Entrega a gravação de [owner] para o envio, que passa a ser dono dos bytes; null se não há gravação pronta dele.
     * Fecha o player e volta ao começo.
     */
    fun takeClip(owner: String): VoiceClip? {
        val review = _state.value as? VoiceRecordingState.Review ?: return null
        if (review.owner != owner) return null
        stopTicker()
        player?.release()
        player = null
        _state.value = VoiceRecordingState.Idle
        return review.clip
    }

    /** ▶ ou ⏸ na revisão. */
    fun togglePlay() {
        val review = _state.value as? VoiceRecordingState.Review ?: return
        val current = ensurePlayer(review.clip)
        if (review.playing) {
            current.pause()
            stopTicker()
            _state.value = review.copy(playing = false, positionMs = current.positionMs().coerceAtLeast(0L))
        } else {
            pauseOtherAudio()
            current.play()
            _state.value = review.copy(playing = true)
            startPositionTicker()
        }
    }

    /** Leva a revisão a [positionMs], dentro da duração. */
    fun seekTo(positionMs: Long) {
        val review = _state.value as? VoiceRecordingState.Review ?: return
        val target = positionMs.coerceIn(0L, review.clip.durationMs)
        ensurePlayer(review.clip).seekTo(target)
        _state.value = review.copy(positionMs = target)
    }

    /** O app saiu da tela: a gravação em curso para e vira revisão, e o que toca na revisão pausa. */
    fun onBackground() {
        when (val current = _state.value) {
            is VoiceRecordingState.Recording -> stop()
            is VoiceRecordingState.Review -> if (current.playing) togglePlay()
            VoiceRecordingState.Idle -> Unit
        }
    }

    /** A tela de [owner] está à vista (de novo): a folga de [hostHidden] termina. */
    fun hostShown(owner: String) {
        if (graceOwner != owner) return
        graceJob?.cancel()
        graceJob = null
        graceOwner = null
    }

    /** A tela de [owner] saiu. Se não voltar logo (girar o aparelho a recria), a gravação dela é descartada. */
    fun hostHidden(owner: String) {
        if (released) return
        _problem.value = null
        if (_state.value.owner != owner) return
        graceJob?.cancel()
        graceOwner = owner
        graceJob = scope.launch {
            delay(HOST_GRACE_MS)
            graceJob = null
            graceOwner = null
            discard(owner)
        }
    }

    fun dismissProblem() {
        _problem.value = null
    }

    /** Abre as configurações do sistema, para quem negou o microfone de vez. */
    fun openSettings() = recorder.openSettings()

    /** Descarta tudo e não grava mais; vale uma vez. */
    fun release() {
        if (released) return
        discard()
        released = true
        graceJob?.cancel()
        graceJob = null
    }

    private fun fail(problem: VoiceProblem) {
        _problem.value = problem
    }

    private fun elapsedMs(): Long = (startedAt?.elapsedNow()?.inWholeMilliseconds ?: 0L).coerceIn(0L, maxDurationMs)

    private fun startTicker(owner: String) {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (true) {
                delay(TICK_MS)
                val elapsed = elapsedMs()
                if (elapsed >= maxDurationMs) {
                    stop()
                    return@launch
                }
                _state.value = VoiceRecordingState.Recording(owner, elapsed, maxDurationMs)
            }
        }
    }

    /** A posição do player não se observa: enquanto toca, ela é lida de [TICK_MS] em [TICK_MS]. */
    private fun startPositionTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (true) {
                delay(TICK_MS)
                val review = _state.value as? VoiceRecordingState.Review ?: return@launch
                val at = player?.positionMs() ?: return@launch
                _state.value = review.copy(positionMs = at.coerceIn(0L, review.clip.durationMs))
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun ensurePlayer(clip: VoiceClip): VoiceClipPlayer = player ?: players.create(clip, PlayerEvents()).also { player = it }

    /** O que o player conta; ignora o que chega sem revisão aberta (um aviso atrasado de um player que já saiu). */
    private inner class PlayerEvents : VoiceClipPlayerListener {
        override fun onPlayingChanged(playing: Boolean) {
            val review = _state.value as? VoiceRecordingState.Review ?: return
            if (review.playing == playing) return
            if (playing) {
                _state.value = review.copy(playing = true)
                startPositionTicker()
            } else {
                // O foco de áudio foi para outro app: a revisão fica onde parou.
                stopTicker()
                _state.value = review.copy(playing = false, positionMs = player?.positionMs()?.coerceAtLeast(0L) ?: review.positionMs)
            }
        }

        override fun onEnded() {
            val review = _state.value as? VoiceRecordingState.Review ?: return
            stopTicker()
            player?.seekTo(0L)
            _state.value = review.copy(playing = false, positionMs = 0L)
        }

        override fun onFailure() {
            val review = _state.value as? VoiceRecordingState.Review ?: return
            stopTicker()
            _state.value = review.copy(playing = false)
            fail(VoiceProblem.PlaybackFailed)
        }
    }
}
