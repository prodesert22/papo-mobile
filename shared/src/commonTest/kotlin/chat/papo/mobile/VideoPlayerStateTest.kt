package chat.papo.mobile

import chat.papo.mobile.ui.components.VideoPlayerCommands
import chat.papo.mobile.ui.components.VideoPlayerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** O estado do player de vídeo e o que se pede a ele, sem desenhar nada e com um player de mentira. */
class VideoPlayerStateTest {
    private class FakeCommands : VideoPlayerCommands {
        val calls = mutableListOf<String>()

        override fun play() {
            calls += "play"
        }

        override fun pause() {
            calls += "pause"
        }

        override fun seekTo(positionMs: Long) {
            calls += "seek:$positionMs"
        }
    }

    /** Um estado com o player de mentira ligado, já com a duração [durationMs] e o ponto [positionMs]. */
    private fun attached(
        durationMs: Long = 60_000,
        positionMs: Long = 0,
        playWhenReady: Boolean = true,
        loading: Boolean = false,
        ended: Boolean = false,
    ): Pair<VideoPlayerState, FakeCommands> {
        val state = VideoPlayerState()
        val commands = FakeCommands()
        state.attach(commands)
        state.update(playWhenReady, loading, ended, positionMs, durationMs, bufferedMs = 0)
        return state to commands
    }

    // Estado inicial e "tocando"

    @Test
    fun umEstadoNovoEOdeUmPlayerQueVaiTocar() {
        val state = VideoPlayerState()

        assertTrue(state.playWhenReady)
        assertTrue(state.loading)
        assertFalse(state.ended)
        assertFalse(state.hasPicture)
        assertFalse(state.playing)
        assertEquals(0L, state.positionMs)
        assertEquals(0L, state.durationMs)
        assertNull(state.scrubbingMs)
    }

    @Test
    fun estaTocandoSoQuandoQuerTocarTemDadosENaoTerminou() {
        val (state, _) = attached(playWhenReady = true, loading = false, ended = false)
        assertTrue(state.playing)

        state.update(playWhenReady = false, loading = false, ended = false, positionMs = 0, durationMs = 60_000, bufferedMs = 0)
        assertFalse(state.playing)

        state.update(playWhenReady = true, loading = true, ended = false, positionMs = 0, durationMs = 60_000, bufferedMs = 0)
        assertFalse(state.playing)

        state.update(playWhenReady = true, loading = false, ended = true, positionMs = 60_000, durationMs = 60_000, bufferedMs = 0)
        assertFalse(state.playing)
    }

    // togglePlay

    @Test
    fun tocarEPausarAlternamConformeOQueOPlayerQuer() {
        val (state, commands) = attached(playWhenReady = true)
        state.togglePlay()
        state.update(playWhenReady = false, loading = false, ended = false, positionMs = 0, durationMs = 60_000, bufferedMs = 0)
        state.togglePlay()

        assertEquals(listOf("pause", "play"), commands.calls)
    }

    @Test
    fun carregandoOBotaoPausaPorqueOPlayerQuerTocar() {
        val (state, commands) = attached(playWhenReady = true, loading = true)

        state.togglePlay()

        assertEquals(listOf("pause"), commands.calls)
    }

    @Test
    fun noFimOBotaoVoltaAoComecoEToca() {
        val (state, commands) = attached(positionMs = 60_000, ended = true)

        state.togglePlay()

        assertEquals(listOf("seek:0", "play"), commands.calls)
        assertEquals(0L, state.positionMs)
    }

    @Test
    fun semPlayerAsAcoesNaoFazemNemMudamNada() {
        val state = VideoPlayerState()
        state.update(playWhenReady = true, loading = false, ended = true, positionMs = 5_000, durationMs = 60_000, bufferedMs = 0)

        state.togglePlay()
        state.play()
        state.pause()
        state.replay()
        state.seekTo(30_000)
        state.seekBy(10_000)
        state.scrubTo(20_000)
        state.commitScrub()

        assertEquals(5_000L, state.positionMs)
        assertNull(state.scrubbingMs)
    }

    // Buscas

    @Test
    fun buscarAndaAPartirDoPontoEJaMostraOPontoNovo() {
        val (state, commands) = attached(positionMs = 30_000)

        state.seekBy(10_000)

        assertEquals(listOf("seek:40000"), commands.calls)
        assertEquals(40_000L, state.positionMs)
    }

    @Test
    fun voltarNaoPassaDoComecoEAvancarNaoPassaDoFim() {
        val (state, commands) = attached(durationMs = 60_000, positionMs = 5_000)

        state.seekBy(-10_000)
        state.seekTo(58_000)
        state.seekBy(10_000)

        assertEquals(listOf("seek:0", "seek:58000", "seek:60000"), commands.calls)
    }

    @Test
    fun semDuracaoConhecidaABuscaSoLimitaOComeco() {
        val (state, commands) = attached(durationMs = 0, positionMs = 5_000)

        state.seekBy(10_000)
        state.seekBy(-100_000)

        assertEquals(listOf("seek:15000", "seek:0"), commands.calls)
    }

    @Test
    fun repetirDoFimVoltaAoComecoETocaDeNovo() {
        val (state, commands) = attached(positionMs = 60_000, ended = true)

        state.replay()

        assertEquals(listOf("seek:0", "play"), commands.calls)
    }

    // Arrastar a barra

    @Test
    fun arrastarSoMudaOPontoMostradoEnaoBusca() {
        val (state, commands) = attached(positionMs = 10_000)

        state.scrubTo(20_000)
        state.scrubTo(25_000)

        assertEquals(25_000L, state.shownPositionMs)
        assertEquals(10_000L, state.positionMs)
        assertEquals(emptyList(), commands.calls)
    }

    @Test
    fun soltarBuscaUmaVezOUltimoPontoELimpaOArrasto() {
        val (state, commands) = attached(positionMs = 10_000)
        state.scrubTo(20_000)
        state.scrubTo(25_000)

        state.commitScrub()
        state.commitScrub()

        assertEquals(listOf("seek:25000"), commands.calls)
        assertNull(state.scrubbingMs)
        assertEquals(25_000L, state.shownPositionMs)
    }

    @Test
    fun soltarSemTerArrastadoNaoBusca() {
        val (state, commands) = attached(positionMs = 10_000)

        state.commitScrub()

        assertEquals(emptyList(), commands.calls)
        assertEquals(10_000L, state.shownPositionMs)
    }

    @Test
    fun oArrastoFicaDentroDoVideo() {
        val (state, _) = attached(durationMs = 60_000)

        state.scrubTo(90_000)
        assertEquals(60_000L, state.shownPositionMs)

        state.scrubTo(-5_000)
        assertEquals(0L, state.shownPositionMs)
    }

    // Ligar e desligar o player

    @Test
    fun aoLigarUmPlayerOEstadoVoltaAoInicial() {
        val state = VideoPlayerState()
        state.update(playWhenReady = false, loading = false, ended = true, positionMs = 9_000, durationMs = 60_000, bufferedMs = 30_000)
        state.hasPicture = true
        state.scrubTo(5_000)

        state.attach(FakeCommands())

        assertTrue(state.playWhenReady)
        assertTrue(state.loading)
        assertFalse(state.ended)
        assertFalse(state.hasPicture)
        assertEquals(0L, state.positionMs)
        assertEquals(0L, state.durationMs)
        assertEquals(0L, state.bufferedMs)
        assertNull(state.scrubbingMs)
    }

    @Test
    fun quandoOPlayerSaiOEstadoVoltaAoInicialEAsAcoesNaoChegamMais() {
        val (state, commands) = attached(positionMs = 9_000, loading = false)
        state.hasPicture = true

        state.detach(commands)
        state.togglePlay()
        state.seekTo(1_000)

        assertTrue(state.loading)
        assertFalse(state.hasPicture)
        assertEquals(0L, state.positionMs)
        assertEquals(emptyList(), commands.calls)
    }

    @Test
    fun umPlayerQueJaFoiTrocadoNaoDesfazOEstadoDoNovo() {
        val state = VideoPlayerState()
        val old = FakeCommands()
        val new = FakeCommands()
        state.attach(old)
        state.attach(new)
        state.update(playWhenReady = true, loading = false, ended = false, positionMs = 4_000, durationMs = 60_000, bufferedMs = 0)

        state.detach(old)
        state.pause()

        assertEquals(4_000L, state.positionMs)
        assertEquals(listOf("pause"), new.calls)
        assertEquals(emptyList(), old.calls)
    }

    // Onde outro player continua

    @Test
    fun outroPlayerContinuaDoPontoAtualEDoComecoSeOVideoTerminou() {
        val (state, _) = attached(positionMs = 42_000)
        assertEquals(42_000L, state.resumePositionMs)

        state.update(playWhenReady = true, loading = false, ended = true, positionMs = 60_000, durationMs = 60_000, bufferedMs = 60_000)
        assertEquals(0L, state.resumePositionMs)
    }
}
