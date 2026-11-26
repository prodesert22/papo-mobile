package chat.papo.mobile

import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.data.api.MemoryCookieStore
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.media.AudioAccounts
import chat.papo.mobile.media.AudioEngine
import chat.papo.mobile.media.AudioEngineListener
import chat.papo.mobile.media.AudioPlayback
import chat.papo.mobile.media.AudioSpeed
import chat.papo.mobile.media.AudioState
import chat.papo.mobile.media.AudioTrack
import chat.papo.mobile.media.PlayableMedia
import chat.papo.mobile.media.PlaybackFailure
import chat.papo.mobile.media.barTrack
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AudioPlaybackTest {
    /** O motor falso: registra o que o player pede e deixa o teste dizer o que ele "ouve". */
    private class FakeEngine(val listener: AudioEngineListener) : AudioEngine {
        val calls = mutableListOf<String>()
        var clients = mutableListOf<PapoClient>()
        var position = 0L
        var positionReads = 0

        override fun open(client: PapoClient, media: PlayableMedia, startAtMs: Long, speed: Float) {
            calls += "open ${media.key} $startAtMs $speed"
            clients += client
            position = startAtMs
        }

        override fun play() {
            calls += "play"
        }

        override fun pause() {
            calls += "pause"
        }

        override fun seekTo(positionMs: Long) {
            calls += "seek $positionMs"
            position = positionMs
        }

        override fun setSpeed(speed: Float) {
            calls += "speed $speed"
        }

        override fun positionMs(): Long {
            positionReads++
            return position
        }

        override fun stop() {
            calls += "stop"
        }

        override fun release() {
            calls += "release"
        }
    }

    private val clients = mutableListOf<PapoClient>()

    private fun newClient(): PapoClient =
        PapoClient(MockEngine { respondOk() }, ServerUrl("https://papo.test"), MemoryCookieStore("jwt")).also { clients += it }

    @AfterTest
    fun closeClients() = clients.forEach { it.close() }

    private fun track(id: String, account: String = "conta-1") = AudioTrack(
        accountId = account,
        media = PlayableMedia("attachment:$id", "https://papo.test/attachments/$id", "audio/ogg", "$id.ogg", null, null),
        channelId = "canal-1",
        messageId = "msg-$id",
        messageAtIso = "2026-11-26T10:00:00Z",
    )

    private class Fixture(scope: TestScope, val client: PapoClient) {
        val accounts = MutableStateFlow(AudioAccounts("conta-1", listOf(client)))
        lateinit var engine: FakeEngine
        val playback = AudioPlayback({ listener -> FakeEngine(listener).also { engine = it } }, scope.backgroundScope, accounts)
        val state get() = playback.state.value
    }

    private fun TestScope.fixture(client: PapoClient = newClient()) = Fixture(this, client).also { runCurrent() }

    // Tocar

    @Test
    fun tocarAbreOAudioDoComecoNaVelocidadeNormalEPedeOCarregamento() = runTest {
        val f = fixture()
        f.playback.toggle(track("a"), f.client)

        assertEquals(listOf("open attachment:a 0 1.0"), f.engine.calls)
        assertEquals(track("a"), f.state.track)
        assertTrue(f.state.playing)
        assertTrue(f.state.buffering)
        assertNull(f.state.durationMs)
        assertNull(f.state.failure)
    }

    @Test
    fun osPedidosSaemPeloClienteDaContaDonaDoAudio() = runTest {
        val f = fixture()
        f.playback.toggle(track("a"), f.client)

        assertSame(f.client, f.engine.clients.single())
    }

    @Test
    fun umAudioPorVez_tocarOutroTrocaOQueTocava() = runTest {
        val f = fixture()
        f.playback.toggle(track("a"), f.client)
        f.engine.position = 5_000
        f.playback.toggle(track("b"), f.client)

        assertEquals(listOf("open attachment:a 0 1.0", "open attachment:b 0 1.0"), f.engine.calls)
        assertEquals(track("b"), f.state.track)
        assertEquals(0L, f.state.positionMs)
    }

    @Test
    fun oMesmoAudioDeContasDiferentesSaoAudiosDiferentes() = runTest {
        val f = fixture()
        f.playback.toggle(track("a", account = "conta-1"), f.client)
        f.playback.toggle(track("a", account = "conta-2"), f.client)

        assertEquals(2, f.engine.calls.count { it.startsWith("open") })
        assertEquals("conta-2/attachment:a", f.state.track?.key)
    }

    // Pausar e retomar

    @Test
    fun tocarOAudioAbertoPausaERetoma() = runTest {
        val f = fixture()
        f.playback.toggle(track("a"), f.client)
        f.engine.position = 1_500

        f.playback.toggle(track("a"), f.client)
        assertEquals(listOf("pause"), f.engine.calls.drop(1))
        assertFalse(f.state.playing)
        assertEquals(1_500L, f.state.positionMs)

        f.playback.toggle(track("a"), f.client)
        assertEquals(listOf("pause", "play"), f.engine.calls.drop(1))
        assertTrue(f.state.playing)
    }

    @Test
    fun aBarraPausaERetomaSemSaberOCliente() = runTest {
        val f = fixture()
        f.playback.toggle(track("a"), f.client)

        f.playback.togglePause()
        assertFalse(f.state.playing)
        f.playback.togglePause()
        assertTrue(f.state.playing)
        assertEquals(listOf("pause", "play"), f.engine.calls.drop(1))
    }

    @Test
    fun aBarraNaoFazNadaSemAudioNemDepoisDeUmaFalha() = runTest {
        val f = fixture()
        f.playback.togglePause()
        assertTrue(f.engine.calls.isEmpty())

        f.playback.toggle(track("a"), f.client)
        f.engine.listener.onFailure(PlaybackFailure.Network)
        f.playback.togglePause()
        assertEquals(1, f.engine.calls.size)
    }

    @Test
    fun saindoDaTelaPausaOQueToca_eNaoFazNadaSeNaoToca() = runTest {
        val f = fixture()
        f.playback.pause()
        assertTrue(f.engine.calls.isEmpty())

        f.playback.toggle(track("a"), f.client)
        f.playback.pause()
        assertEquals(listOf("pause"), f.engine.calls.drop(1))
        assertFalse(f.state.playing)

        f.playback.pause()
        assertEquals(1, f.engine.calls.drop(1).size)
    }

    @Test
    fun omotorPausaPorContaPropria_aBarraFicaOndeEleParou() = runTest {
        val f = fixture()
        f.playback.toggle(track("a"), f.client)
        f.engine.position = 7_000

        // O foco de áudio foi para um vídeo ou outro app: o motor pausa sem ninguém pedir.
        f.engine.listener.onPlayingChanged(false)

        assertFalse(f.state.playing)
        assertEquals(7_000L, f.state.positionMs)
        f.engine.position = 9_000
        advanceTimeBy(1_000)
        assertEquals(7_000L, f.state.positionMs)

        f.engine.listener.onPlayingChanged(true)
        assertTrue(f.state.playing)
    }

    // Posição, duração e busca

    @Test
    fun aPosicaoSegueOMotorEnquantoToca_ePara_aoPausar() = runTest {
        val f = fixture()
        f.playback.toggle(track("a"), f.client)

        f.engine.position = 1_200
        advanceTimeBy(300)
        assertEquals(1_200L, f.state.positionMs)

        f.engine.position = 2_400
        advanceTimeBy(250)
        assertEquals(2_400L, f.state.positionMs)

        f.playback.toggle(track("a"), f.client)
        f.engine.position = 9_999
        advanceTimeBy(1_000)
        assertEquals(2_400L, f.state.positionMs)
    }

    @Test
    fun aDuracaoVemDoMotorEOBufferTerminaQuandoEleDiz() = runTest {
        val f = fixture()
        f.playback.toggle(track("a"), f.client)

        f.engine.listener.onDuration(90_000)
        f.engine.listener.onBufferingChanged(false)

        assertEquals(90_000L, f.state.durationMs)
        assertFalse(f.state.buffering)
        f.engine.listener.onBufferingChanged(true)
        assertTrue(f.state.buffering)
    }

    @Test
    fun buscarVaiParaOMotorDentroDaDuracao() = runTest {
        val f = fixture()
        f.playback.toggle(track("a"), f.client)
        f.engine.listener.onDuration(10_000)

        f.playback.seekTo(4_000)
        assertEquals(4_000L, f.state.positionMs)
        f.playback.seekTo(99_999)
        assertEquals(10_000L, f.state.positionMs)
        f.playback.seekTo(-5)
        assertEquals(0L, f.state.positionMs)
        assertEquals(listOf("seek 4000", "seek 10000", "seek 0"), f.engine.calls.drop(1))
    }

    @Test
    fun buscarComADuracaoDesconhecidaNaoTemTeto() = runTest {
        val f = fixture()
        f.playback.toggle(track("a"), f.client)

        f.playback.seekTo(42_000)

        assertEquals(42_000L, f.state.positionMs)
    }

    @Test
    fun buscarSemAudioOuDepoisDeUmaFalhaNaoFazNada() = runTest {
        val f = fixture()
        f.playback.seekTo(1_000)
        assertTrue(f.engine.calls.isEmpty())

        f.playback.toggle(track("a"), f.client)
        f.engine.listener.onFailure(PlaybackFailure.Network)
        f.playback.seekTo(1_000)
        assertEquals(1, f.engine.calls.size)
    }

    // Fim e falha

    @Test
    fun aoChegarAoFimOAudioSaiEOCartaoVoltaAoComeco() = runTest {
        val f = fixture()
        f.playback.toggle(track("a"), f.client)
        f.engine.listener.onDuration(3_000)

        f.engine.listener.onEnded()

        assertEquals(AudioState(), f.state)
        assertEquals("stop", f.engine.calls.last())
        // Tocar de novo abre a mídia outra vez, do começo.
        f.playback.toggle(track("a"), f.client)
        assertEquals("open attachment:a 0 1.0", f.engine.calls.last())
    }

    @Test
    fun aFalhaGuardaOMotivoEAPosicaoEPara() = runTest {
        val f = fixture()
        f.playback.toggle(track("a"), f.client)
        f.engine.position = 3_000

        f.engine.listener.onFailure(PlaybackFailure.Network)

        assertEquals(PlaybackFailure.Network, f.state.failure)
        assertFalse(f.state.playing)
        assertFalse(f.state.buffering)
        assertEquals(3_000L, f.state.positionMs)
        f.engine.position = 8_000
        advanceTimeBy(1_000)
        assertEquals(3_000L, f.state.positionMs)
    }

    @Test
    fun tentarDeNovoDepoisDaFalhaRecomecaDeOndeParou() = runTest {
        val f = fixture()
        f.playback.toggle(track("a"), f.client)
        f.engine.position = 3_000
        f.engine.listener.onFailure(PlaybackFailure.Unavailable)

        f.playback.toggle(track("a"), f.client)

        assertEquals("open attachment:a 3000 1.0", f.engine.calls.last())
        assertNull(f.state.failure)
        assertTrue(f.state.playing)
    }

    @Test
    fun outroAudioDepoisDeUmaFalhaComecaDoZero() = runTest {
        val f = fixture()
        f.playback.toggle(track("a"), f.client)
        f.engine.position = 3_000
        f.engine.listener.onFailure(PlaybackFailure.Network)

        f.playback.toggle(track("b"), f.client)

        assertEquals("open attachment:b 0 1.0", f.engine.calls.last())
    }

    @Test
    fun umaSegundaFalhaNaoApagaOMotivoDaPrimeira() = runTest {
        val f = fixture()
        f.playback.toggle(track("a"), f.client)
        f.engine.listener.onFailure(PlaybackFailure.Forbidden)
        f.engine.listener.onFailure(PlaybackFailure.Network)

        assertEquals(PlaybackFailure.Forbidden, f.state.failure)
    }

    @Test
    fun avisosAtrasadosDeUmAudioQueJaSaiuSaoIgnorados() = runTest {
        val f = fixture()
        f.playback.toggle(track("a"), f.client)
        f.playback.stop()

        f.engine.listener.onFailure(PlaybackFailure.Network)
        f.engine.listener.onDuration(5_000)
        f.engine.listener.onBufferingChanged(true)
        f.engine.listener.onPlayingChanged(true)
        f.engine.listener.onEnded()

        assertEquals(AudioState(), f.state)
        assertEquals(2, f.engine.calls.size) // o `open` e o `stop`
    }

    // Parar

    @Test
    fun pararSoltaOMotorEEsquece_eNaoFazNadaSeNaoHaAudio() = runTest {
        val f = fixture()
        f.playback.stop()
        assertTrue(f.engine.calls.isEmpty())

        f.playback.toggle(track("a"), f.client)
        f.playback.stop()

        assertEquals(AudioState(), f.state)
        assertEquals(listOf("stop"), f.engine.calls.drop(1))
    }

    // Velocidade

    @Test
    fun aVelocidadeAlternaEntreUmUmEMeioEDois() = runTest {
        val f = fixture()
        assertEquals(AudioSpeed.Normal, f.playback.speed.value)

        f.playback.cycleSpeed()
        assertEquals(AudioSpeed.Fast, f.playback.speed.value)
        f.playback.cycleSpeed()
        assertEquals(AudioSpeed.Faster, f.playback.speed.value)
        f.playback.cycleSpeed()
        assertEquals(AudioSpeed.Normal, f.playback.speed.value)
        // Sem áudio aberto não há o que pedir ao motor.
        assertTrue(f.engine.calls.isEmpty())
    }

    @Test
    fun mudarAVelocidadeComUmAudioAbertoMudaADele_eOProximoJaAbreNela() = runTest {
        val f = fixture()
        f.playback.toggle(track("a"), f.client)

        f.playback.cycleSpeed()
        assertEquals("speed 1.5", f.engine.calls.last())

        f.playback.toggle(track("b"), f.client)
        assertEquals("open attachment:b 0 1.5", f.engine.calls.last())
    }

    // Contas

    @Test
    fun trocarDeContaSelecionadaParaOAudio() = runTest {
        val f = fixture()
        f.playback.toggle(track("a"), f.client)

        f.accounts.value = AudioAccounts("conta-2", listOf(f.client, newClient()))
        runCurrent()

        assertEquals(AudioState(), f.state)
        assertEquals("stop", f.engine.calls.last())
    }

    @Test
    fun fecharASessaoDaContaParaOAudio() = runTest {
        val f = fixture()
        f.playback.toggle(track("a"), f.client)

        f.accounts.value = AudioAccounts("conta-1", emptyList())
        runCurrent()

        assertEquals(AudioState(), f.state)
        assertEquals("stop", f.engine.calls.last())
    }

    @Test
    fun entrarDeNovoNaMesmaContaTrocaOClienteEParaOAudio() = runTest {
        val f = fixture()
        f.playback.toggle(track("a"), f.client)

        f.accounts.value = AudioAccounts("conta-1", listOf(newClient()))
        runCurrent()

        assertEquals(AudioState(), f.state)
    }

    @Test
    fun mudancasEmOutrasContasNaoMexemNoAudio() = runTest {
        val f = fixture()
        f.playback.toggle(track("a"), f.client)

        f.accounts.value = AudioAccounts("conta-1", listOf(f.client, newClient()))
        runCurrent()

        assertEquals(track("a"), f.state.track)
        assertTrue(f.state.playing)
        assertEquals(1, f.engine.calls.size)
    }

    @Test
    fun mudancaDeContasSemAudioNaoFazNada() = runTest {
        val f = fixture()

        f.accounts.value = AudioAccounts(null, emptyList())
        runCurrent()

        assertTrue(f.engine.calls.isEmpty())
    }

    // Fechar

    @Test
    fun fecharLiberaOMotorEParaTudo() = runTest {
        val f = fixture()
        f.playback.toggle(track("a"), f.client)
        f.playback.cardShown("conta-1/attachment:a")

        f.playback.release()

        assertEquals(AudioState(), f.state)
        assertEquals("release", f.engine.calls.last())
        assertTrue(f.playback.cardsOnScreen.value.isEmpty())
        // A posição não é mais lida depois de fechar.
        val reads = f.engine.positionReads
        advanceTimeBy(2_000)
        assertEquals(reads, f.engine.positionReads)
    }

    @Test
    fun depoisDeFecharNadaMaisAbreNemMexeNoMotor() = runTest {
        val f = fixture()
        f.playback.release()
        f.playback.release()
        val before = f.engine.calls.toList()

        f.playback.toggle(track("a"), f.client)
        f.playback.togglePause()
        f.playback.pause()
        f.playback.seekTo(100)
        f.playback.cycleSpeed()
        f.playback.stop()

        assertEquals(listOf("release"), before)
        assertEquals(before, f.engine.calls)
        assertEquals(AudioState(), f.state)
        assertEquals(AudioSpeed.Normal, f.playback.speed.value)
    }

    // Cartões na tela e barra de áudio

    @Test
    fun oCartaoNaTelaContaPorChave_eSoSaiQuandoOUltimoSai() = runTest {
        val f = fixture()
        f.playback.cardShown("k")
        f.playback.cardShown("k")
        f.playback.cardShown("outro")
        assertEquals(setOf("k", "outro"), f.playback.cardsOnScreen.value)

        f.playback.cardHidden("k")
        assertEquals(setOf("k", "outro"), f.playback.cardsOnScreen.value)
        f.playback.cardHidden("k")
        assertEquals(setOf("outro"), f.playback.cardsOnScreen.value)
        // Sair sem ter entrado não faz nada.
        f.playback.cardHidden("k")
        assertEquals(setOf("outro"), f.playback.cardsOnScreen.value)
    }

    @Test
    fun aBarraSoMostraOAudioAbertoSemFalhaECujoCartaoNaoEstaNaTela() {
        val a = track("a")
        val open = AudioState(track = a, playing = true)

        assertNull(AudioState().barTrack(emptySet()))
        assertEquals(a, open.barTrack(emptySet()))
        assertEquals(a, open.barTrack(setOf("conta-1/attachment:outro")))
        assertNull(open.barTrack(setOf(a.key)))
        assertNull(open.copy(failure = PlaybackFailure.Network).barTrack(emptySet()))
    }

    @Test
    fun oEstadoDeUmaChaveSoApareceNoCartaoDoAudioAberto() = runTest {
        val f = fixture()
        val a = track("a")
        assertNull(f.playback.stateOf(a.key).first())

        f.playback.toggle(a, f.client)

        assertNotNull(f.playback.stateOf(a.key).first())
        assertNull(f.playback.stateOf(track("b").key).first())
    }
}
