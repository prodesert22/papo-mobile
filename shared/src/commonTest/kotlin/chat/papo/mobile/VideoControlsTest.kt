package chat.papo.mobile

import chat.papo.mobile.ui.components.VideoPlayerState
import chat.papo.mobile.ui.media.CONTROLS_AUTO_HIDE_MS
import chat.papo.mobile.ui.media.CenterAction
import chat.papo.mobile.ui.media.VideoControlsVisibility
import chat.papo.mobile.ui.media.centerAction
import chat.papo.mobile.ui.media.fractionOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Os controles do vídeo: o botão do meio, a barra e quando os controles aparecem e somem, sem desenhar nada. */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoControlsTest {
    private fun player(playWhenReady: Boolean = true, loading: Boolean = false, ended: Boolean = false) =
        VideoPlayerState().also {
            it.update(playWhenReady, loading, ended, positionMs = 0, durationMs = 60_000, bufferedMs = 0)
        }

    // centerAction

    @Test
    fun noFimOBotaoDoMeioEDeTocarDeNovoMesmoComOPlayerQuerendoTocar() {
        assertEquals(CenterAction.Replay, centerAction(player(playWhenReady = true, loading = true, ended = true)))
    }

    @Test
    fun carregandoNaoHaBotaoSoOIndicador() {
        assertEquals(CenterAction.Loading, centerAction(player(playWhenReady = true, loading = true)))
    }

    @Test
    fun tocandoOBotaoDoMeioPausa() {
        assertEquals(CenterAction.Pause, centerAction(player(playWhenReady = true)))
    }

    @Test
    fun pausadoOBotaoDoMeioToca() {
        assertEquals(CenterAction.Play, centerAction(player(playWhenReady = false)))
    }

    // fractionOf

    @Test
    fun aFracaoFicaEntreZeroEUm() {
        assertEquals(0.25f, fractionOf(15_000, 60_000))
        assertEquals(0f, fractionOf(-5, 60_000))
        assertEquals(1f, fractionOf(90_000, 60_000))
    }

    @Test
    fun semDuracaoAFracaoEZero() {
        assertEquals(0f, fractionOf(15_000, 0))
        assertEquals(0f, fractionOf(15_000, -1))
    }

    // VideoControlsVisibility: o que prende os controles à vista

    @Test
    fun osControlesComecamAVistaMesmoComOVideoAindaParado() {
        val visibility = VideoControlsVisibility(VideoPlayerState())

        assertTrue(visibility.visible)
        assertTrue(visibility.pinned)
    }

    @Test
    fun pausadoCarregandoOuTerminadoOsControlesFicamPresos() {
        assertTrue(VideoControlsVisibility(player(playWhenReady = false)).pinned)
        assertTrue(VideoControlsVisibility(player(loading = true)).pinned)
        assertTrue(VideoControlsVisibility(player(ended = true)).pinned)
    }

    @Test
    fun tocandoSemODedoNaBarraOsControlesNaoFicamPresos() {
        assertFalse(VideoControlsVisibility(player()).pinned)
    }

    @Test
    fun comODedoNaBarraOsControlesFicamPresosMesmoTocando() {
        val visibility = VideoControlsVisibility(player())

        visibility.holding = true

        assertTrue(visibility.pinned)
    }

    // VideoControlsVisibility: tocar no vídeo

    @Test
    fun umToqueNoVideoTocandoEscondeEOutroMostra() {
        val visibility = VideoControlsVisibility(player())

        visibility.tap()
        assertFalse(visibility.visible)

        visibility.tap()
        assertTrue(visibility.visible)
    }

    @Test
    fun cadaToqueEContado() {
        val visibility = VideoControlsVisibility(player())

        visibility.tap()
        visibility.touch()

        assertEquals(2, visibility.touches)
    }

    @Test
    fun umToqueNoVideoParadoNaoEscondeOsControles() {
        val state = player(playWhenReady = false)
        val visibility = VideoControlsVisibility(state)

        visibility.tap()
        assertTrue(visibility.visible)

        // Ao voltar a tocar eles seguem à vista, para sumirem só depois da espera.
        state.update(playWhenReady = true, loading = false, ended = false, positionMs = 0, durationMs = 60_000, bufferedMs = 0)
        assertTrue(visibility.visible)
    }

    @Test
    fun umToqueNosControlesOsTrazDeVoltaDepoisDeSumirem() = runTest {
        val visibility = VideoControlsVisibility(player())
        val job = launch { visibility.autoHide() }
        advanceTimeBy(CONTROLS_AUTO_HIDE_MS)
        runCurrent()
        job.join()
        assertFalse(visibility.visible)

        visibility.touch()

        assertTrue(visibility.visible)
    }

    // VideoControlsVisibility: a espera para esconder

    @Test
    fun tocandoOsControlesSomemDepoisDeTresSegundos() = runTest {
        val visibility = VideoControlsVisibility(player())
        launch { visibility.autoHide() }
        runCurrent()

        advanceTimeBy(CONTROLS_AUTO_HIDE_MS - 1)
        runCurrent()
        assertTrue(visibility.visible)

        advanceTimeBy(1)
        runCurrent()
        assertFalse(visibility.visible)
    }

    @Test
    fun umToqueNosControlesRecomecaAEspera() = runTest {
        val visibility = VideoControlsVisibility(player())
        // A composição cancela a espera e começa outra a cada mudança de `touches`; aqui é o que se faz à mão.
        val first = launch { visibility.autoHide() }
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()

        visibility.touch()
        first.cancel()
        launch { visibility.autoHide() }
        runCurrent()

        advanceTimeBy(CONTROLS_AUTO_HIDE_MS - 1)
        runCurrent()
        assertTrue(visibility.visible)

        advanceTimeBy(1)
        runCurrent()
        assertFalse(visibility.visible)
    }

    @Test
    fun jaEscondidosNaoHaEsperaNenhuma() = runTest {
        val visibility = VideoControlsVisibility(player())
        visibility.tap()

        visibility.autoHide()

        assertEquals(0L, testScheduler.currentTime)
        assertFalse(visibility.visible)
    }

    @Test
    fun presosNaoEsperamEDeixamPedidoQueOsControlesAparecamAoVoltarATocar() = runTest {
        val state = player(playWhenReady = false)
        val visibility = VideoControlsVisibility(state)
        // Tocando, eles somem...
        state.update(playWhenReady = true, loading = false, ended = false, positionMs = 0, durationMs = 60_000, bufferedMs = 0)
        visibility.tap()
        assertFalse(visibility.visible)
        // ...e ao pausar voltam e ficam.
        state.update(playWhenReady = false, loading = false, ended = false, positionMs = 0, durationMs = 60_000, bufferedMs = 0)
        assertTrue(visibility.visible)

        visibility.autoHide()
        assertEquals(0L, testScheduler.currentTime)

        state.update(playWhenReady = true, loading = false, ended = false, positionMs = 0, durationMs = 60_000, bufferedMs = 0)
        assertTrue(visibility.visible)
        launch { visibility.autoHide() }
        runCurrent()
        advanceTimeBy(CONTROLS_AUTO_HIDE_MS)
        runCurrent()
        assertFalse(visibility.visible)
    }

    @Test
    fun comODedoNaBarraNaoSomeESoltarDeixaSumirOutraVez() = runTest {
        val visibility = VideoControlsVisibility(player())
        visibility.holding = true

        visibility.autoHide()
        assertEquals(0L, testScheduler.currentTime)
        assertTrue(visibility.visible)

        visibility.holding = false
        launch { visibility.autoHide() }
        runCurrent()
        advanceTimeBy(CONTROLS_AUTO_HIDE_MS)
        runCurrent()
        assertFalse(visibility.visible)
    }
}
