package chat.papo.mobile

import chat.papo.mobile.chat.MAX_ATTACHMENT_BYTES
import chat.papo.mobile.media.MAX_VOICE_MEMORY_MS
import chat.papo.mobile.media.MicrophoneAccess
import chat.papo.mobile.media.VoiceCapture
import chat.papo.mobile.media.VoiceClip
import chat.papo.mobile.media.VoiceClipPlayer
import chat.papo.mobile.media.VoiceClipPlayerListener
import chat.papo.mobile.media.VoiceFormat
import chat.papo.mobile.media.VoiceProblem
import chat.papo.mobile.media.VoiceRecorder
import chat.papo.mobile.media.VoiceRecorderException
import chat.papo.mobile.media.VoiceRecording
import chat.papo.mobile.media.VoiceRecordingState
import chat.papo.mobile.media.owner
import chat.papo.mobile.media.toOutgoingFile
import chat.papo.mobile.media.voiceMaxDurationMs
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val OWNER = "conta-1/canal-1"
private const val OTHER = "conta-1/canal-2"

/** Cobre a máquina de estados da gravação de voz com um gravador e um player falsos, sob o relógio virtual do teste. */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceRecordingTest {
    private class Fixture(test: TestScope, bitrate: Int = 32_000, val scope: CoroutineScope = test.backgroundScope) {
        val recorder = FakeVoiceRecorder(VoiceFormat("audio/webm", "webm", bitrate))
        val calls = MutableStateFlow(false)
        val players = mutableListOf<FakeVoicePlayer>()
        var otherAudioPaused = 0
        var wallClock = 1_700_000_000_000L
        val voice = VoiceRecording(
            recorder = recorder,
            players = { clip, listener -> FakeVoicePlayer(clip, listener).also { players += it } },
            scope = scope,
            io = StandardTestDispatcher(test.testScheduler),
            callActive = calls,
            pauseOtherAudio = { otherAudioPaused++ },
            wallClockMs = { wallClock },
            timeSource = test.testScheduler.timeSource,
        )
        val state get() = voice.state.value
        val problem get() = voice.problem.value
        val capture get() = recorder.captures.last()
        val player get() = players.last()
    }

    private fun TestScope.fixture(bitrate: Int = 32_000) = Fixture(this, bitrate).also { runCurrent() }

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    /** Começa a gravar para [OWNER] com o microfone já concedido. */
    private fun TestScope.recording(f: Fixture = fixture()): Fixture {
        f.voice.start(OWNER)
        runCurrent()
        assertIs<VoiceRecordingState.Recording>(f.state)
        return f
    }

    /** Grava por um segundo e para: a gravação fica na revisão. */
    private fun TestScope.reviewing(f: Fixture = fixture()): Fixture {
        recording(f)
        advance(1_000)
        f.voice.stop()
        runCurrent()
        assertIs<VoiceRecordingState.Review>(f.state)
        return f
    }

    private val Fixture.review get() = assertIs<VoiceRecordingState.Review>(state)

    // O teto

    @Test
    fun oTetoDeVozEAMeiaHoraPorqueOLimiteDoServidorDariaHorasNaMemoria() {
        assertEquals(MAX_VOICE_MEMORY_MS, voiceMaxDurationMs(32_000))
        // No bitrate do app o servidor aceitaria horas: a gravação cabe com folga no limite de um arquivo.
        assertTrue(voiceMaxDurationMs(32_000) / 1_000L * (32_000 / 8) < MAX_ATTACHMENT_BYTES)
    }

    @Test
    fun comUmBitrateAltoOTetoEODoLimiteDoServidorComFolgaParaOContainer() {
        val bitrate = 377_487_360 // 47 185 920 bytes/s: 90% de 100 MiB dão exatamente 2 s
        assertEquals(2_000L, voiceMaxDurationMs(bitrate))
        assertTrue(voiceMaxDurationMs(bitrate) / 1_000L * (bitrate / 8) <= MAX_ATTACHMENT_BYTES)
    }

    // Começar

    @Test
    fun comOMicrofoneConcedidoComecaAGravarPausaOAudioDoAppEPedeOTetoAoGravador() = runTest {
        val f = fixture()

        f.voice.start(OWNER)
        runCurrent()

        val recording = assertIs<VoiceRecordingState.Recording>(f.state)
        assertEquals(OWNER, recording.owner)
        assertEquals(0L, recording.elapsedMs)
        assertEquals(MAX_VOICE_MEMORY_MS, recording.maxMs)
        assertEquals(listOf(MAX_VOICE_MEMORY_MS), f.recorder.maxDurations)
        assertEquals(1, f.otherAudioPaused)
        assertNull(f.problem)
    }

    @Test
    fun microfoneNegadoNaoGravaEExplicaOPedidoPodeVirDeNovo() = runTest {
        val f = fixture()
        f.recorder.access = MicrophoneAccess.Denied

        f.voice.start(OWNER)
        runCurrent()

        assertEquals(VoiceRecordingState.Idle, f.state)
        assertEquals(VoiceProblem.MicrophoneDenied, f.problem)
        assertTrue(f.recorder.captures.isEmpty())
        assertEquals(0, f.otherAudioPaused)
    }

    @Test
    fun microfoneNegadoDeVezApontaParaAsConfiguracoes() = runTest {
        val f = fixture()
        f.recorder.access = MicrophoneAccess.Blocked

        f.voice.start(OWNER)
        runCurrent()
        f.voice.openSettings()

        assertEquals(VoiceRecordingState.Idle, f.state)
        assertEquals(VoiceProblem.MicrophoneBlocked, f.problem)
        assertEquals(1, f.recorder.settingsOpened)
    }

    @Test
    fun aoConcederDepoisDeNegarOProximoPedidoGravaEApagaOAviso() = runTest {
        val f = fixture()
        f.recorder.access = MicrophoneAccess.Denied
        f.voice.start(OWNER)
        runCurrent()
        f.recorder.access = MicrophoneAccess.Granted

        f.voice.start(OWNER)
        runCurrent()

        assertIs<VoiceRecordingState.Recording>(f.state)
        assertNull(f.problem)
        assertEquals(2, f.recorder.accessCalls)
    }

    @Test
    fun comUmaChamadaEmCursoNaoPedeOMicrofoneNemGrava() = runTest {
        val f = fixture()
        f.calls.value = true
        runCurrent()

        f.voice.start(OWNER)
        runCurrent()

        assertEquals(VoiceProblem.InCall, f.problem)
        assertEquals(0, f.recorder.accessCalls)
        assertTrue(f.recorder.captures.isEmpty())
    }

    @Test
    fun aChamadaQueComecaEnquantoOUsuarioRespondeAoPedidoImpedeAGravacao() = runTest {
        val f = fixture()
        f.recorder.accessGate = CompletableDeferred()

        f.voice.start(OWNER)
        runCurrent()
        f.calls.value = true
        f.recorder.accessGate!!.complete(Unit)
        runCurrent()

        assertEquals(VoiceProblem.InCall, f.problem)
        assertTrue(f.recorder.captures.isEmpty())
        assertEquals(VoiceRecordingState.Idle, f.state)
    }

    @Test
    fun gravadorQueNaoAbreDeixaOEstadoLivreEAvisa() = runTest {
        val f = fixture()
        f.recorder.startError = VoiceRecorderException("sem microfone")

        f.voice.start(OWNER)
        runCurrent()

        assertEquals(VoiceRecordingState.Idle, f.state)
        assertEquals(VoiceProblem.RecorderFailed, f.problem)
        assertEquals(1, f.otherAudioPaused) // o áudio já tinha parado para dar lugar ao microfone
    }

    @Test
    fun cancelamentoNoMomentoEmQueOGravadorAbreSoltaOMicrofone() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val f = Fixture(this, scope = scope)
        f.recorder.onStart = { scope.cancel() }

        f.voice.start(OWNER)
        runCurrent()

        assertEquals(1, f.recorder.captures.size)
        assertEquals(1, f.capture.cancelCalls)
        assertEquals(VoiceRecordingState.Idle, f.state)
    }

    @Test
    fun comecarDeNovoNoMesmoDonoNaoAbreOutroGravador() = runTest {
        val f = recording()

        f.voice.start(OWNER)
        runCurrent()

        assertEquals(1, f.recorder.captures.size)
        assertEquals(1, f.recorder.accessCalls)
    }

    @Test
    fun comecarEnquantoOMicrofoneAindaEPedidoNaoPedeDeNovo() = runTest {
        val f = fixture()
        f.recorder.accessGate = CompletableDeferred()

        f.voice.start(OWNER)
        runCurrent()
        f.voice.start(OWNER)
        runCurrent()
        f.recorder.accessGate!!.complete(Unit)
        runCurrent()

        assertEquals(1, f.recorder.accessCalls)
        assertEquals(1, f.recorder.captures.size)
    }

    @Test
    fun gravarParaOutraConversaDescartaAGravacaoDaAnterior() = runTest {
        val f = recording()
        val first = f.capture

        f.voice.start(OTHER)
        runCurrent()

        assertEquals(1, first.cancelCalls)
        assertEquals(2, f.recorder.captures.size)
        assertEquals(OTHER, f.state.owner)
    }

    // Gravando

    @Test
    fun oTempoDecorridoCresceComORelogio() = runTest {
        val f = recording()

        advance(1_000)

        assertEquals(1_000L, assertIs<VoiceRecordingState.Recording>(f.state).elapsedMs)
    }

    @Test
    fun noTetoAGravacaoParaSozinhaECaiNaRevisaoComOTetoComoDuracao() = runTest {
        val f = fixture(bitrate = 377_487_360) // teto de 2 s
        f.voice.start(OWNER)
        runCurrent()
        assertEquals(2_000L, assertIs<VoiceRecordingState.Recording>(f.state).maxMs)

        advance(1_800)
        assertIs<VoiceRecordingState.Recording>(f.state)
        advance(400)

        assertEquals(2_000L, f.review.clip.durationMs)
        assertEquals(1, f.capture.finishCalls)
    }

    @Test
    fun pararLevaARevisaoComOArquivoNomeadoComoOWeb() = runTest {
        val f = recording()
        advance(1_500)

        f.voice.stop()
        runCurrent()

        val review = f.review
        assertEquals(OWNER, review.owner)
        assertEquals("audio_1700000000000.webm", review.clip.name)
        assertEquals("audio/webm", review.clip.mimeType)
        assertContentEquals(byteArrayOf(1, 2, 3), review.clip.bytes)
        assertEquals(1_500L, review.clip.durationMs)
        assertEquals(0L, review.positionMs)
        assertFalse(review.playing)
        assertEquals(1, f.capture.finishCalls)
    }

    @Test
    fun pararSemGravarNaoFazNada() = runTest {
        val f = fixture()

        f.voice.stop()
        runCurrent()

        assertEquals(VoiceRecordingState.Idle, f.state)
        assertNull(f.problem)
    }

    @Test
    fun pararDuasVezesFechaOGravadorUmaVezSo() = runTest {
        val f = recording()

        f.voice.stop()
        f.voice.stop()
        runCurrent()

        assertEquals(1, f.capture.finishCalls)
        assertIs<VoiceRecordingState.Review>(f.state)
    }

    @Test
    fun gravacaoInvalidaVoltaAoInicioEAvisaESoltaOGravador() = runTest {
        val f = recording()
        f.capture.finishError = VoiceRecorderException("parou cedo demais")

        f.voice.stop()
        runCurrent()

        assertEquals(VoiceRecordingState.Idle, f.state)
        assertEquals(VoiceProblem.RecorderFailed, f.problem)
        assertEquals(1, f.capture.cancelCalls)
    }

    @Test
    fun gravacaoVaziaNaoViraRevisao() = runTest {
        val f = recording()
        f.capture.bytes = ByteArray(0)

        f.voice.stop()
        runCurrent()

        assertEquals(VoiceRecordingState.Idle, f.state)
        assertEquals(VoiceProblem.RecorderFailed, f.problem)
    }

    @Test
    fun cancelamentoAoPararSoltaOGravador() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val f = Fixture(this, scope = scope)
        f.voice.start(OWNER)
        runCurrent()
        f.capture.onFinish = { scope.cancel() }

        f.voice.stop()
        runCurrent()

        assertEquals(1, f.capture.cancelCalls)
    }

    @Test
    fun descartarEnquantoOGravadorParaSoltaOGravadorSemEntregarRevisao() = runTest {
        val f = recording()
        f.capture.onFinish = { f.voice.discard() }

        f.voice.stop()
        runCurrent()

        assertEquals(VoiceRecordingState.Idle, f.state)
        assertEquals(1, f.capture.cancelCalls)
    }

    @Test
    fun erroDoGravadorNoMeioDescartaEAvisa() = runTest {
        val f = recording()

        f.recorder.errorCallbacks.single().invoke()
        runCurrent()

        assertEquals(VoiceRecordingState.Idle, f.state)
        assertEquals(VoiceProblem.RecorderFailed, f.problem)
        assertEquals(1, f.capture.cancelCalls)
    }

    @Test
    fun erroAtrasadoDeUmaGravacaoQueJaSaiuNaoDerrubaAProxima() = runTest {
        val f = recording()
        f.voice.discard()
        f.voice.start(OWNER)
        runCurrent()

        f.recorder.errorCallbacks.first().invoke()
        runCurrent()

        assertIs<VoiceRecordingState.Recording>(f.state)
        assertNull(f.problem)
    }

    @Test
    fun erroDoGravadorDepoisDeParadoNaoMudaARevisao() = runTest {
        val f = reviewing()

        f.recorder.errorCallbacks.single().invoke()
        runCurrent()

        assertIs<VoiceRecordingState.Review>(f.state)
        assertNull(f.problem)
    }

    @Test
    fun umaChamadaQueComecaParaAGravacaoEAGuardaParaARevisao() = runTest {
        val f = recording()
        advance(700)

        f.calls.value = true
        runCurrent()

        assertEquals(700L, f.review.clip.durationMs)
    }

    @Test
    fun oAppEmSegundoPlanoParaAGravacaoEAGuardaParaARevisao() = runTest {
        val f = recording()
        advance(900)

        f.voice.onBackground()
        runCurrent()

        assertEquals(900L, f.review.clip.durationMs)
        assertEquals(1, f.capture.finishCalls)
    }

    @Test
    fun oAppEmSegundoPlanoSemGravacaoNaoFazNada() = runTest {
        val f = fixture()

        f.voice.onBackground()
        runCurrent()

        assertEquals(VoiceRecordingState.Idle, f.state)
    }

    // Descartar e enviar

    @Test
    fun descartarGravandoSoltaOMicrofoneEVoltaAoInicio() = runTest {
        val f = recording()

        f.voice.discard()
        advance(5_000)

        assertEquals(VoiceRecordingState.Idle, f.state)
        assertEquals(1, f.capture.cancelCalls)
        assertEquals(0, f.capture.finishCalls)
    }

    @Test
    fun descartarARevisaoFechaOPlayerEApagaOsBytesDaMemoria() = runTest {
        val f = reviewing()
        val clip = f.review.clip
        f.voice.togglePlay()

        f.voice.discard()

        assertEquals(VoiceRecordingState.Idle, f.state)
        assertTrue(f.player.released)
        assertContentEquals(byteArrayOf(0, 0, 0), clip.bytes)
    }

    @Test
    fun descartarSemNadaNaoFazNada() = runTest {
        val f = fixture()

        f.voice.discard()

        assertEquals(VoiceRecordingState.Idle, f.state)
    }

    @Test
    fun descartarPeloDonoSoValeParaOsDele() = runTest {
        val f = recording()

        f.voice.discard(OTHER)
        assertEquals(OWNER, f.state.owner)

        f.voice.discard(OWNER)
        assertEquals(VoiceRecordingState.Idle, f.state)
    }

    @Test
    fun entregarParaOEnvioDaOsBytesIntactosEFechaOPlayer() = runTest {
        val f = reviewing()
        val clip = f.review.clip
        f.voice.togglePlay()

        val taken = f.voice.takeClip(OWNER)

        assertSame(clip, taken)
        assertContentEquals(byteArrayOf(1, 2, 3), clip.bytes)
        assertEquals(VoiceRecordingState.Idle, f.state)
        assertTrue(f.player.released)
    }

    @Test
    fun entregarParaOutroDonoOuSemRevisaoDevolveNulo() = runTest {
        val f = recording()
        assertNull(f.voice.takeClip(OWNER)) // ainda gravando

        f.voice.stop()
        runCurrent()
        assertNull(f.voice.takeClip(OTHER))
        assertIs<VoiceRecordingState.Review>(f.state)
    }

    @Test
    fun oArquivoDeEnvioLeOsBytesDaMemoriaACadaAbertura() = runTest {
        val clip = VoiceClip("audio_1.webm", "audio/webm", byteArrayOf(9, 8, 7), 1_000L)
        val file = clip.toOutgoingFile()

        assertEquals("audio_1.webm", file.name)
        assertEquals("audio/webm", file.mimeType)
        assertEquals(3L, file.size)
        assertContentEquals(byteArrayOf(9, 8, 7), file.open().readBuffer().readByteArray())
        assertContentEquals(byteArrayOf(9, 8, 7), file.open().readBuffer().readByteArray())
    }

    // Revisão

    @Test
    fun tocarPausaOAudioDoAppAbreOPlayerEMostraAPosicaoEnquantoToca() = runTest {
        val f = reviewing()
        val paused = f.otherAudioPaused

        f.voice.togglePlay()
        f.player.position = 400L
        advance(250)

        assertEquals(listOf("play"), f.player.calls)
        assertEquals(paused + 1, f.otherAudioPaused)
        assertTrue(f.review.playing)
        assertEquals(400L, f.review.positionMs)
    }

    @Test
    fun pausarGuardaAPosicaoEPara() = runTest {
        val f = reviewing()
        f.voice.togglePlay()
        f.player.position = 600L

        f.voice.togglePlay()
        f.player.position = 900L
        advance(1_000)

        assertEquals(listOf("play", "pause"), f.player.calls)
        assertFalse(f.review.playing)
        assertEquals(600L, f.review.positionMs)
    }

    @Test
    fun buscarFicaDentroDaDuracaoEVaiAoPlayer() = runTest {
        val f = reviewing() // 1 s de gravação

        f.voice.seekTo(400L)
        assertEquals(400L, f.review.positionMs)
        f.voice.seekTo(5_000L)
        assertEquals(1_000L, f.review.positionMs)
        f.voice.seekTo(-10L)
        assertEquals(0L, f.review.positionMs)

        assertEquals(listOf("seek 400", "seek 1000", "seek 0"), f.player.calls)
        assertEquals(1, f.players.size)
    }

    @Test
    fun tocarOuBuscarForaDaRevisaoNaoFazNada() = runTest {
        val f = recording()

        f.voice.togglePlay()
        f.voice.seekTo(100L)

        assertTrue(f.players.isEmpty())
        assertIs<VoiceRecordingState.Recording>(f.state)
    }

    @Test
    fun tocarAteOFimVoltaAoComecoParado() = runTest {
        val f = reviewing()
        f.voice.togglePlay()
        f.player.position = 1_000L

        f.player.listener.onEnded()
        advance(1_000)

        assertFalse(f.review.playing)
        assertEquals(0L, f.review.positionMs)
        assertEquals("seek 0", f.player.calls.last())
    }

    @Test
    fun oFocoDeAudioPerdidoPausaARevisaoEORetomadoVoltaATocar() = runTest {
        val f = reviewing()
        f.voice.togglePlay()
        f.player.position = 300L

        f.player.listener.onPlayingChanged(false)
        assertFalse(f.review.playing)
        assertEquals(300L, f.review.positionMs)

        f.player.listener.onPlayingChanged(true)
        assertTrue(f.review.playing)
        f.player.listener.onPlayingChanged(true) // repetido: sem efeito
        assertTrue(f.review.playing)
    }

    @Test
    fun playerQueFalhaAvisaMasAGravacaoAindaPodeSerEnviada() = runTest {
        val f = reviewing()
        f.voice.togglePlay()

        f.player.listener.onFailure()

        assertEquals(VoiceProblem.PlaybackFailed, f.problem)
        assertFalse(f.review.playing)
        assertNotNull(f.voice.takeClip(OWNER))
    }

    @Test
    fun avisosDoPlayerSemRevisaoSaoIgnorados() = runTest {
        val f = reviewing()
        f.voice.togglePlay()
        val listener = f.player.listener
        f.voice.discard()

        listener.onPlayingChanged(false)
        listener.onPlayingChanged(true)
        listener.onEnded()
        listener.onFailure()

        assertEquals(VoiceRecordingState.Idle, f.state)
        assertNull(f.problem)
    }

    @Test
    fun oAppEmSegundoPlanoPausaARevisaoQueToca() = runTest {
        val f = reviewing()
        f.voice.togglePlay()

        f.voice.onBackground()

        assertFalse(f.review.playing)
        assertEquals("pause", f.player.calls.last())
    }

    @Test
    fun oAppEmSegundoPlanoComARevisaoParadaNaoMexeNela() = runTest {
        val f = reviewing()

        f.voice.onBackground()

        assertTrue(f.players.isEmpty())
        assertFalse(f.review.playing)
    }

    // Sair da conversa

    @Test
    fun aTelaQueSaiESoVoltaDepoisDaFolgaDescartaAGravacao() = runTest {
        val f = recording()

        f.voice.hostHidden(OWNER)
        advance(1_999)
        assertIs<VoiceRecordingState.Recording>(f.state)
        advance(2)

        assertEquals(VoiceRecordingState.Idle, f.state)
        assertEquals(1, f.capture.cancelCalls)
    }

    @Test
    fun aTelaQueVoltaDentroDaFolgaMantemAGravacaoComoNoGiroDoAparelho() = runTest {
        val f = recording()

        f.voice.hostHidden(OWNER)
        advance(500)
        f.voice.hostShown(OWNER)
        advance(5_000)

        assertIs<VoiceRecordingState.Recording>(f.state)
    }

    @Test
    fun aSaidaDeOutraConversaNaoMexeNaGravacaoNemNaFolga() = runTest {
        val f = recording()

        f.voice.hostHidden(OTHER)
        f.voice.hostShown(OTHER)
        advance(5_000)

        assertIs<VoiceRecordingState.Recording>(f.state)
    }

    @Test
    fun aVoltaDeOutraConversaNaoCancelaAFolgaDaQueSaiu() = runTest {
        val f = recording()

        f.voice.hostHidden(OWNER)
        f.voice.hostShown(OTHER)
        advance(2_100)

        assertEquals(VoiceRecordingState.Idle, f.state)
    }

    @Test
    fun sairDaConversaApagaOAvisoDeProblema() = runTest {
        val f = fixture()
        f.recorder.access = MicrophoneAccess.Denied
        f.voice.start(OWNER)
        runCurrent()

        f.voice.hostHidden(OWNER)

        assertNull(f.problem)
    }

    @Test
    fun fecharOAvisoApagaOProblema() = runTest {
        val f = fixture()
        f.recorder.access = MicrophoneAccess.Denied
        f.voice.start(OWNER)
        runCurrent()

        f.voice.dismissProblem()

        assertNull(f.problem)
    }

    // Fechar a sessão

    @Test
    fun fecharASessaoGravandoSoltaOMicrofoneEnaoGravaMais() = runTest {
        val f = recording()

        f.voice.release()
        f.voice.release()
        f.voice.start(OWNER)
        runCurrent()

        assertEquals(VoiceRecordingState.Idle, f.state)
        assertEquals(1, f.capture.cancelCalls)
        assertEquals(1, f.recorder.captures.size)
    }

    @Test
    fun fecharASessaoComARevisaoApagaOsBytes() = runTest {
        val f = reviewing()
        val clip = f.review.clip

        f.voice.release()

        assertEquals(VoiceRecordingState.Idle, f.state)
        assertContentEquals(byteArrayOf(0, 0, 0), clip.bytes)
    }

    @Test
    fun depoisDeFecharAFolgaPendenteNaoFazNada() = runTest {
        val f = recording()
        f.voice.hostHidden(OWNER)

        f.voice.release()
        f.voice.hostHidden(OWNER)
        advance(5_000)

        assertEquals(VoiceRecordingState.Idle, f.state)
        assertEquals(1, f.capture.cancelCalls)
    }
}
