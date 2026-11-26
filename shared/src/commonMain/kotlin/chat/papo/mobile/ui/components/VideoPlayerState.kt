package chat.papo.mobile.ui.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * O que o player de vídeo da plataforma aceita fazer. Quem o desenha registra uma implementação com
 * [VideoPlayerState.attach], e a tela comum só fala com o [VideoPlayerState].
 */
internal interface VideoPlayerCommands {
    /** Toca a partir do ponto em que está (se ainda não abriu, abre). */
    fun play()

    fun pause()

    fun seekTo(positionMs: Long)
}

/**
 * O estado de um player de vídeo e o que se pode pedir a ele, para a tela comum desenhar os controles. A plataforma
 * (`PlatformUi.VideoPlayer`) só desenha o vídeo: ela escreve aqui o que o player diz de si e executa o que a tela pede.
 *
 * Quem quer ver ou comandar um player cria o seu estado (`remember { VideoPlayerState() }`) e o entrega ao
 * `PlatformUi.VideoPlayer`; enquanto o player está na composição o estado o acompanha, e ao sair ele volta ao ponto de
 * partida de um player que vai tocar. Sem player as ações não fazem nada. Tudo roda na thread principal.
 */
@Stable
class VideoPlayerState {
    /** Quer tocar: não foi pausado, ainda que esteja carregando ou tenha chegado ao fim. */
    var playWhenReady: Boolean by mutableStateOf(true)
        internal set

    /** Quer tocar mas ainda não tem dados para isso: abrindo, ou parado esperando o que baixar. */
    var loading: Boolean by mutableStateOf(true)
        internal set

    /** Chegou ao fim do vídeo. */
    var ended: Boolean by mutableStateOf(false)
        internal set

    /** O primeiro quadro já foi desenhado: até lá quem está em cima do player é a capa, e não um vazio preto. */
    var hasPicture: Boolean by mutableStateOf(false)
        internal set

    /** O ponto do vídeo em milissegundos. Avança em passos curtos enquanto toca, sem esperar pelo segundo cheio. */
    var positionMs: Long by mutableLongStateOf(0L)
        internal set

    /** A duração em milissegundos, ou 0 enquanto o player ainda não a conhece. */
    var durationMs: Long by mutableLongStateOf(0L)
        internal set

    /** Até onde o vídeo já foi baixado, em milissegundos. */
    var bufferedMs: Long by mutableLongStateOf(0L)
        internal set

    /** O ponto para onde o dedo levou a barra de busca, ainda sem buscar; null sem o dedo nela. */
    var scrubbingMs: Long? by mutableStateOf(null)
        private set

    private var commands: VideoPlayerCommands? = null

    /** Está tocando de fato: quer tocar, tem dados e não terminou. */
    val playing: Boolean get() = playWhenReady && !loading && !ended

    /** O ponto que a tela mostra: o do dedo na barra enquanto ele arrasta; senão o do player. */
    val shownPositionMs: Long get() = scrubbingMs ?: positionMs

    /** O ponto de onde outro player continua este vídeo: o começo se ele terminou, para não reabrir um vídeo parado no fim. */
    val resumePositionMs: Long get() = if (ended) 0L else positionMs

    /** Pausa se quer tocar (inclusive carregando); toca se está pausado; do fim, volta ao começo e toca. */
    fun togglePlay() {
        when {
            ended -> replay()
            playWhenReady -> pause()
            else -> play()
        }
    }

    fun play() {
        commands?.play()
    }

    fun pause() {
        commands?.pause()
    }

    /** Volta ao começo e toca. */
    fun replay() {
        if (commands == null) return
        seekTo(0L)
        play()
    }

    /**
     * Busca [ms] (dentro de 0 e da duração, quando ela é conhecida). O ponto mostrado já vai para lá, sem esperar o
     * player confirmar, para a barra não voltar um instante ao ponto antigo.
     */
    fun seekTo(ms: Long) {
        val commands = commands ?: return
        val target = clampToVideo(ms)
        positionMs = target
        commands.seekTo(target)
    }

    /** Anda [deltaMs] a partir do ponto atual (negativo volta). */
    fun seekBy(deltaMs: Long) = seekTo(positionMs + deltaMs)

    /**
     * O dedo levou a barra a [ms]: só o ponto mostrado muda. Cada busca num trecho que não está baixado é um pedido
     * `Range` e o servidor limita os pedidos por endereço (10 por segundo), então a busca fica para [commitScrub].
     */
    fun scrubTo(ms: Long) {
        scrubbingMs = clampToVideo(ms)
    }

    /** O dedo soltou a barra: busca uma vez o último ponto a que ele a levou. Sem arrasto nenhum não faz nada. */
    fun commitScrub() {
        val target = scrubbingMs ?: return
        scrubbingMs = null
        seekTo(target)
    }

    /** A plataforma registra o player que este estado acompanha; o estado volta ao de um player que vai tocar. */
    internal fun attach(commands: VideoPlayerCommands) {
        reset()
        this.commands = commands
    }

    /** O player de [commands] saiu. Um que já foi trocado por outro não desfaz o do novo. */
    internal fun detach(commands: VideoPlayerCommands) {
        if (this.commands !== commands) return
        this.commands = null
        reset()
    }

    /** Escreve de uma vez o que o player diz de si. */
    internal fun update(
        playWhenReady: Boolean,
        loading: Boolean,
        ended: Boolean,
        positionMs: Long,
        durationMs: Long,
        bufferedMs: Long,
    ) {
        this.playWhenReady = playWhenReady
        this.loading = loading
        this.ended = ended
        this.positionMs = positionMs
        this.durationMs = durationMs
        this.bufferedMs = bufferedMs
    }

    private fun clampToVideo(ms: Long): Long = if (durationMs > 0L) ms.coerceIn(0L, durationMs) else ms.coerceAtLeast(0L)

    private fun reset() {
        playWhenReady = true
        loading = true
        ended = false
        hasPicture = false
        positionMs = 0L
        durationMs = 0L
        bufferedMs = 0L
        scrubbingMs = null
    }
}
