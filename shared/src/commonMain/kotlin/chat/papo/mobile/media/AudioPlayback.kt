package chat.papo.mobile.media

import chat.papo.mobile.data.api.PapoClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * O motor de áudio da plataforma: toca uma mídia por vez e avisa o que acontece pelo [AudioEngineListener] que recebeu ao
 * nascer. Todas as chamadas, nos dois sentidos, acontecem na thread principal. Quem o cria e fecha é o [AudioPlayback].
 */
interface AudioEngine {
    /**
     * Abre [media] e a toca a partir de [startAtMs], na velocidade [speed]; o que tocava antes sai. Os pedidos saem pelo `http`
     * de [client], que deve ser o da conta dona da mensagem (dele vêm o cookie `Auth` e a renovação única da sessão), e nada
     * vai para o disco. O motor pede o foco de áudio do sistema ao tocar: um vídeo que começa pausa o áudio e vice-versa.
     */
    fun open(client: PapoClient, media: PlayableMedia, startAtMs: Long, speed: Float)

    /** Retoma a mídia aberta, se houver. */
    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    fun setSpeed(speed: Float)

    /** A posição atual da mídia aberta em milissegundos; 0 sem mídia. */
    fun positionMs(): Long

    /** Para e solta a mídia aberta, com a conexão dela, mas mantém o motor para o próximo [open]. */
    fun stop()

    /** Fecha o motor de vez; ele não é usado depois. */
    fun release()
}

/** O que o [AudioEngine] conta de volta, sempre na thread principal. */
interface AudioEngineListener {
    /** O motor quer tocar (ou não): vira falso quando o sistema toma o foco de áudio, por exemplo, e volta se o usuário retoma. */
    fun onPlayingChanged(playing: Boolean)

    /** Falta dado para seguir tocando. */
    fun onBufferingChanged(buffering: Boolean)

    /** A duração da mídia ficou conhecida, em milissegundos. */
    fun onDuration(durationMs: Long)

    /** A mídia tocou até o fim. */
    fun onEnded()

    /** O motor desistiu, depois de esgotar a política de repetição (`retryDelay`); o motivo é definitivo. */
    fun onFailure(failure: PlaybackFailure)
}

/** Cria o [AudioEngine] de uma [AudioPlayback]; null onde a plataforma ainda não tem um motor de áudio. */
fun interface AudioEngineFactory {
    fun create(listener: AudioEngineListener): AudioEngine
}

/**
 * O áudio de um anexo, com o que a barra de áudio precisa para levar de volta à mensagem: a conta dona, o canal, a mensagem
 * e o instante dela dado pelo servidor.
 */
data class AudioTrack(
    val accountId: String,
    val media: PlayableMedia,
    val channelId: String,
    val messageId: String,
    val messageAtIso: String,
) {
    /** Identifica o áudio no app inteiro: o mesmo UUID em contas (e servidores) diferentes nomeia coisas diferentes. */
    val key: String get() = "$accountId/${media.key}"
}

/**
 * O que o player de áudio mostra. Sem [track] não há nada carregado. Com [failure], o motor já desistiu: [positionMs] é onde
 * parou, para quem tentar de novo recomeçar dali.
 */
data class AudioState(
    val track: AudioTrack? = null,
    val positionMs: Long = 0L,
    /** Null enquanto a duração não é conhecida. */
    val durationMs: Long? = null,
    /** O usuário quer ouvir: fica verdadeiro também enquanto carrega ([buffering]). */
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val failure: PlaybackFailure? = null,
)

/**
 * O áudio que a barra de áudio mostra: o que está carregado e sem falha, quando o cartão dele ([cardsOnScreen]) não está à
 * vista. Com o cartão à vista, é ele que tem os controles; sem falha, porque a falha só faz sentido no cartão.
 */
fun AudioState.barTrack(cardsOnScreen: Set<String>): AudioTrack? =
    track?.takeIf { failure == null && it.key !in cardsOnScreen }

/** As contas do app: qual está selecionada e os clientes das sessões abertas, que o [AudioPlayback] confere. */
data class AudioAccounts(val active: String?, val clients: List<PapoClient>)

/**
 * O player de áudio do app: um áudio por vez, de qualquer conta, sobre o [AudioEngine] da plataforma. Quem o cria é a
 * sessão do app, que o fecha ([release]) ao fechar e ao travar. Tudo roda na thread principal.
 *
 * O áudio é de uma conta: trocar a conta selecionada ou fechar a sessão dela para o que toca (regra 4), conferido a cada
 * mudança de [accounts]. Tocar em segundo plano exigiria um serviço de mídia, que o app não tem: [pause] é o que o app faz
 * ao sair da tela. Ao chegar ao fim o áudio sai: o cartão volta a ser o do começo, e tocar de novo abre a mídia outra vez.
 */
class AudioPlayback(
    engines: AudioEngineFactory,
    private val scope: CoroutineScope,
    accounts: Flow<AudioAccounts>,
) {
    private val _state = MutableStateFlow(AudioState())
    val state: StateFlow<AudioState> = _state.asStateFlow()

    private val _speed = MutableStateFlow(AudioSpeed.Normal)

    /** A velocidade vale para qualquer áudio e fica até o app travar; mudá-la com um áudio aberto muda a dele. */
    val speed: StateFlow<AudioSpeed> = _speed.asStateFlow()

    private val _cardsOnScreen = MutableStateFlow<Set<String>>(emptySet())

    /** As chaves ([AudioTrack.key]) dos cartões de áudio que estão na tela agora; ver [cardShown]. */
    val cardsOnScreen: StateFlow<Set<String>> = _cardsOnScreen.asStateFlow()

    /** Quantos cartões de cada chave estão na tela: a mesma mensagem pode aparecer em dois lugares. */
    private val cardCounts = HashMap<String, Int>()

    private val engine: AudioEngine = engines.create(EngineEvents())

    /** O cliente com que o áudio aberto foi pedido: só ele prova que a sessão do áudio ainda é a mesma. */
    private var clientInUse: PapoClient? = null
    private var ticker: Job? = null
    private var released = false

    init {
        scope.launch { accounts.collect(::onAccountsChanged) }
    }

    /** O estado de [key] ([AudioTrack.key]), ou null quando outro áudio (ou nenhum) está carregado: só o cartão dele recompõe. */
    fun stateOf(key: String): Flow<AudioState?> =
        _state.map { state -> state.takeIf { it.track?.key == key } }.distinctUntilChanged()

    /**
     * O botão do cartão de [track]: pausa o que toca, retoma o que está pausado, ou abre o áudio do começo. Depois de uma
     * falha abre de novo de onde parou. Abrir outro áudio troca o que tocava. Os pedidos saem por [client], o da conta dona.
     */
    fun toggle(track: AudioTrack, client: PapoClient) {
        if (released) return
        val current = _state.value
        val sameTrack = current.track?.key == track.key
        if (sameTrack && current.failure == null) togglePause() else open(track, client, startAtMs = if (sameTrack) current.positionMs else 0L)
    }

    /** O botão da barra de áudio: pausa o áudio aberto que toca, ou retoma o pausado. Sem áudio aberto, ou depois de uma falha, nada acontece. */
    fun togglePause() {
        val current = _state.value
        if (released || current.track == null || current.failure != null) return
        if (current.playing) pause() else resume()
    }

    /** Pausa o que toca (o app saiu da tela, ou o usuário tocou ⏸). O áudio continua aberto, no mesmo ponto. */
    fun pause() {
        val current = _state.value
        if (released || current.track == null || current.failure != null || !current.playing) return
        engine.pause()
        stopTicker()
        _state.value = current.copy(playing = false, positionMs = engine.positionMs().coerceAtLeast(0L))
    }

    /** Busca [positionMs] no áudio aberto, dentro da duração conhecida. Sem áudio aberto, ou depois de uma falha, nada acontece. */
    fun seekTo(positionMs: Long) {
        val current = _state.value
        if (released || current.track == null || current.failure != null) return
        val target = positionMs.coerceIn(0L, current.durationMs ?: Long.MAX_VALUE)
        engine.seekTo(target)
        _state.value = current.copy(positionMs = target)
    }

    /** Passa para a próxima velocidade (ver [AudioSpeed.next]). */
    fun cycleSpeed() {
        if (released) return
        val next = _speed.value.next()
        _speed.value = next
        if (_state.value.track != null) engine.setSpeed(next.factor)
    }

    /** Para o áudio, solta a conexão dele e esquece qual era. */
    fun stop() {
        if (released || _state.value.track == null) return
        stopTicker()
        engine.stop()
        clientInUse = null
        _state.value = AudioState()
    }

    /** Um cartão de áudio de [key] ([AudioTrack.key]) entrou na tela. Só a thread principal chama. */
    fun cardShown(key: String) {
        cardCounts[key] = (cardCounts[key] ?: 0) + 1
        _cardsOnScreen.value = cardCounts.keys.toSet()
    }

    /** Um cartão de áudio de [key] saiu da tela (rolou para fora, a conversa fechou ou o app travou). Só a thread principal chama. */
    fun cardHidden(key: String) {
        val remaining = (cardCounts[key] ?: return) - 1
        if (remaining > 0) cardCounts[key] = remaining else cardCounts.remove(key)
        _cardsOnScreen.value = cardCounts.keys.toSet()
    }

    /** Fecha o motor e para tudo; o player não toca mais depois disso. Vale uma vez. */
    fun release() {
        if (released) return
        released = true
        stopTicker()
        engine.release()
        clientInUse = null
        _state.value = AudioState()
        cardCounts.clear()
        _cardsOnScreen.value = emptySet()
    }

    private fun resume() {
        engine.play()
        _state.update { it.copy(playing = true) }
        startTicker()
    }

    private fun open(track: AudioTrack, client: PapoClient, startAtMs: Long) {
        clientInUse = client
        _state.value = AudioState(track = track, positionMs = startAtMs, playing = true, buffering = true)
        engine.open(client, track.media, startAtMs, _speed.value.factor)
        startTicker()
    }

    /** Para o áudio que não é da conta selecionada, nem de uma sessão que ainda está aberta (regra 4). */
    private fun onAccountsChanged(accounts: AudioAccounts) {
        val track = _state.value.track ?: return
        val sessionOpen = clientInUse.let { used -> used != null && accounts.clients.any { it === used } }
        if (track.accountId != accounts.active || !sessionOpen) stop()
    }

    /** A posição do motor não se observa: enquanto toca, ela é lida de [POSITION_INTERVAL_MS] em [POSITION_INTERVAL_MS]. */
    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                delay(POSITION_INTERVAL_MS)
                val current = _state.value
                if (current.track != null && current.failure == null) {
                    _state.value = current.copy(positionMs = engine.positionMs().coerceAtLeast(0L))
                }
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    /** O que o motor conta; ignora o que chega sem áudio carregado (um aviso atrasado de um áudio que já saiu). */
    private inner class EngineEvents : AudioEngineListener {
        override fun onPlayingChanged(playing: Boolean) {
            val current = _state.value
            if (current.track == null || current.failure != null || current.playing == playing) return
            if (playing) {
                _state.value = current.copy(playing = true)
                startTicker()
            } else {
                // Parou por conta própria (o foco de áudio foi para outro app ou para um vídeo): a barra fica onde ele parou.
                stopTicker()
                _state.value = current.copy(playing = false, positionMs = engine.positionMs().coerceAtLeast(0L))
            }
        }

        override fun onBufferingChanged(buffering: Boolean) {
            _state.update { if (it.track == null || it.buffering == buffering) it else it.copy(buffering = buffering) }
        }

        override fun onDuration(durationMs: Long) {
            _state.update { if (it.track == null || it.durationMs == durationMs) it else it.copy(durationMs = durationMs) }
        }

        override fun onEnded() = stop()

        override fun onFailure(failure: PlaybackFailure) {
            val current = _state.value
            if (current.track == null || current.failure != null) return
            stopTicker()
            _state.value = current.copy(
                positionMs = engine.positionMs().coerceAtLeast(0L),
                playing = false,
                buffering = false,
                failure = failure,
            )
        }
    }

    private companion object {
        /** De quanto em quanto tempo a posição vai para a barra de busca enquanto o áudio toca. */
        const val POSITION_INTERVAL_MS = 250L
    }
}
