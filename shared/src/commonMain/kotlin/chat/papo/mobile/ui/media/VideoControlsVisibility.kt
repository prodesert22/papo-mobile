package chat.papo.mobile.ui.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import chat.papo.mobile.ui.components.VideoPlayerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** Quanto tempo os controles ficam à vista, sem toque, com o vídeo tocando. */
internal const val CONTROLS_AUTO_HIDE_MS = 3_000L

/**
 * Quando os controles do vídeo aparecem. Quem desenha o que acompanha os controles (a barra de título do visualizador)
 * lê [visible], e não o estado de cada pedaço.
 *
 * Com o vídeo tocando os controles somem sozinhos depois de [CONTROLS_AUTO_HIDE_MS] sem toque; um toque no vídeo os mostra
 * ou esconde, e um toque nos próprios controles reinicia a espera. Parado (pausado, carregando, terminado) ou com o dedo
 * na barra de busca eles ficam presos à vista: sem eles não haveria como tocar de novo.
 */
@Stable
internal class VideoControlsVisibility(private val player: VideoPlayerState) {
    /** O que a pessoa pediu: começam à vista, e cada toque no vídeo alterna. */
    var requested: Boolean by mutableStateOf(true)
        private set

    /** Conta os toques; cada um reinicia a espera para esconder. */
    var touches: Int by mutableIntStateOf(0)
        private set

    /** O dedo está na barra de busca: soltá-la, e não o tempo, é que deixa os controles sumirem. */
    var holding: Boolean by mutableStateOf(false)

    /** Presos à vista: o vídeo não está tocando, ou o dedo está na barra. */
    val pinned: Boolean get() = !player.playing || holding

    val visible: Boolean get() = requested || pinned

    /** Um toque no vídeo: mostra se estavam escondidos e esconde se estavam à vista. Preso à vista, só garante que fiquem. */
    fun tap() {
        requested = pinned || !requested
        touches++
    }

    /** Um toque nos controles ou na barra: ficam à vista e a espera para esconder recomeça. */
    fun touch() {
        requested = true
        touches++
    }

    /**
     * Espera a hora de esconder e esconde. Quem a chama a cancela e a recomeça quando [touches], [requested] ou [pinned]
     * mudam. Preso à vista não espera: deixa pedido que, ao voltar a tocar, os controles apareçam e só então somem.
     */
    suspend fun autoHide() {
        if (pinned) {
            requested = true
            return
        }
        if (!requested) return
        delay(CONTROLS_AUTO_HIDE_MS)
        requested = false
    }
}

/** O [VideoControlsVisibility] de [player], com a espera de esconder ligada à composição. */
@Composable
internal fun rememberVideoControlsVisibility(player: VideoPlayerState): VideoControlsVisibility {
    val visibility = remember(player) { VideoControlsVisibility(player) }
    // Lido por `snapshotFlow` e não na composição: quem chama não recompõe a cada toque nem a cada mudança do player.
    LaunchedEffect(visibility) {
        snapshotFlow { Triple(visibility.pinned, visibility.requested, visibility.touches) }.collectLatest { visibility.autoHide() }
    }
    return visibility
}
