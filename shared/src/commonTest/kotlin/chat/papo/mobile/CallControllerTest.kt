package chat.papo.mobile

import chat.papo.mobile.data.ws.WsCommand
import chat.papo.mobile.data.ws.WsEvent
import chat.papo.mobile.data.ws.WsState
import chat.papo.mobile.voice.AudioOutput
import chat.papo.mobile.voice.AudioRoute
import chat.papo.mobile.voice.CallController
import chat.papo.mobile.voice.CallEnd
import chat.papo.mobile.voice.CallPhase
import chat.papo.mobile.voice.EngineConnection
import chat.papo.mobile.voice.IceServer
import chat.papo.mobile.voice.LocalCandidate
import chat.papo.mobile.voice.MediaPermission
import chat.papo.mobile.voice.VideoKey
import chat.papo.mobile.voice.VideoTrackHandle
import chat.papo.mobile.voice.VoiceEngine
import chat.papo.mobile.voice.VoiceSignal
import chat.papo.mobile.voice.VoiceSlots
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class CallControllerTest {
    /** Servidor de sinalização falso: registra o que o cliente envia e responde como o SFU. */
    private class FakeSignal : VoiceSignal {
        override val events = MutableSharedFlow<WsEvent>(extraBufferCapacity = 64)
        override val state = MutableStateFlow(WsState.Connected)
        val sent = mutableListOf<WsCommand>()
        var answerOffers = true
        var rejectNextOffer = false
        var failSubscribe = false
        var answerJoin = true

        override suspend fun send(command: WsCommand): Boolean {
            sent += command
            when (command) {
                is WsCommand.VoiceJoin -> if (answerJoin) events.emit(
                    WsEvent.VoiceJoined(command.channelId, listOf(WsEvent.VoiceMember("eu"), WsEvent.VoiceMember("bia", muted = false))),
                )
                is WsCommand.VoiceOffer -> when {
                    rejectNextOffer -> {
                        rejectNextOffer = false
                        events.emit(WsEvent.ServerError("SDP inválido", "voice-invalid-sdp"))
                    }
                    answerOffers -> events.emit(WsEvent.VoiceAnswer(command.channelId, "answer-for-" + command.sdp))
                }
                else -> Unit
            }
            return !(failSubscribe && command is WsCommand.TrackSubscribe)
        }

        val kinds get() = sent.map { it::class.simpleName }
    }

    private class FakeEngine : VoiceEngine {
        override val connection = MutableStateFlow(EngineConnection.New)
        override val remoteVideo = MutableStateFlow<List<VideoTrackHandle?>>(emptyList())
        override val localCamera = MutableStateFlow<VideoTrackHandle?>(null)
        override val localScreen = MutableStateFlow<VideoTrackHandle?>(null)
        val log = mutableListOf<String>()
        var micPermission = true
        var offers = 0
        var onCandidate: ((LocalCandidate) -> Unit)? = null
        var closed = false

        override suspend fun ensurePermission(permission: MediaPermission) =
            if (permission == MediaPermission.Microphone) micPermission else true

        override suspend fun open(iceServers: List<IceServer>, slots: VoiceSlots, onCandidate: (LocalCandidate) -> Unit) {
            log += "open ${iceServers.size} ${slots.video}/${slots.audio}/${slots.screenAudio}"
            this.onCandidate = onCandidate
            // Um candidato surge já na criação, antes de a oferta existir.
            onCandidate(LocalCandidate("cand-early", "0", 0))
        }

        override suspend fun createOffer(): String = "offer-${++offers}".also { log += it }
        override suspend fun applyAnswer(sdp: String) {
            log += "answer:$sdp"
            connection.value = EngineConnection.Connected
        }

        override fun addRemoteCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
            log += "remote-cand:$candidate"
        }

        override fun setMicrophoneEnabled(enabled: Boolean) {
            log += "mic:$enabled"
        }

        override suspend fun startCamera(): Boolean = true.also { log += "camera:start" }
        override suspend fun stopCamera() {
            log += "camera:stop"
        }

        override fun switchCamera() = Unit
        override suspend fun startScreen(): Boolean = true.also { log += "screen:start" }
        override suspend fun stopScreen() {
            log += "screen:stop"
        }

        override fun setSpeakerphone(enabled: Boolean) = Unit
        override val outputs = MutableStateFlow<List<AudioOutput>>(emptyList())
        override val currentOutput = MutableStateFlow<AudioOutput?>(null)
        override fun selectOutput(id: String) = Unit
        override fun setRemoteAudioGain(trackId: String, gain: Double) = Unit
        override fun close() {
            closed = true
        }
    }

    private class Fixture(scope: TestScope) {
        val signal = FakeSignal()
        val engine = FakeEngine()
        val call = CallController(
            accountId = "conta",
            channelId = "v1",
            myUserId = "eu",
            iceServers = { listOf(IceServer(listOf("stun:x"), null, null)) },
            ws = signal,
            engine = engine,
            parentScope = scope.backgroundScope,
            timeSource = scope.testTimeSource,
        )
    }

    @Test
    fun entraNaOrdemDoContratoEAbreOMicrofone() = runTest {
        val f = Fixture(this)
        f.call.start()
        advanceTimeBy(1.seconds)

        // voice_join → (voice_joined) → oferta com tudo → candidatos só depois da oferta → desmuta.
        assertEquals(listOf("VoiceJoin", "VoiceOffer"), f.signal.kinds.take(2))
        assertEquals(setOf("VoiceIceCandidate", "VoiceMute"), f.signal.kinds.drop(2).toSet())
        assertEquals(listOf("open 1 6/8/2", "offer-1", "answer:answer-for-offer-1", "mic:true"), f.engine.log)
        assertTrue(WsCommand.VoiceMute("v1", false) in f.signal.sent)
        // O candidato descoberto antes da oferta ficou retido até ela sair.
        assertEquals(WsCommand.VoiceIceCandidate("v1", "cand-early", "0", 0), f.signal.sent.first { it is WsCommand.VoiceIceCandidate })

        val state = f.call.state.value
        assertEquals(CallPhase.Connected, state.phase)
        assertFalse(state.micMuted)
        assertEquals(setOf("eu", "bia"), state.members.keys)
    }

    @Test
    fun cameraAnunciaAIntencaoAntesDaOfertaERenegocia() = runTest {
        val f = Fixture(this)
        f.call.start()
        advanceTimeBy(1.seconds)
        f.signal.sent.clear()

        f.call.toggleCamera()
        advanceTimeBy(1.seconds)
        assertEquals<List<WsCommand>>(listOf(WsCommand.VoiceCamera("v1", true), WsCommand.VoiceOffer("v1", "offer-2")), f.signal.sent)
        assertTrue(f.call.state.value.cameraOn)

        f.signal.sent.clear()
        f.call.toggleCamera()
        advanceTimeBy(1.seconds)
        assertEquals<List<WsCommand>>(listOf(WsCommand.VoiceCamera("v1", false), WsCommand.VoiceOffer("v1", "offer-3")), f.signal.sent)
        assertEquals("camera:stop", f.engine.log[f.engine.log.indexOf("offer-3") - 1])
        assertFalse(f.call.state.value.cameraOn)
    }

    @Test
    fun cameraETelaVaoEmOfertasSeparadas() = runTest {
        val f = Fixture(this)
        f.call.start()
        advanceTimeBy(1.seconds)
        f.signal.sent.clear()

        f.call.toggleCamera()
        advanceTimeBy(1.seconds)
        f.call.toggleScreen()
        advanceTimeBy(1.seconds)
        assertEquals(listOf("VoiceCamera", "VoiceOffer", "ScreenShareStart", "VoiceOffer"), f.signal.kinds)
        assertTrue(f.call.state.value.cameraOn && f.call.state.value.screenOn)
    }

    @Test
    fun ofertaRecusadaDesfazACamera() = runTest {
        val f = Fixture(this)
        f.call.start()
        advanceTimeBy(1.seconds)
        f.signal.sent.clear()
        f.signal.rejectNextOffer = true

        f.call.toggleCamera()
        advanceTimeBy(1.seconds)
        assertEquals(listOf("VoiceCamera", "VoiceOffer", "VoiceCamera"), f.signal.kinds)
        assertEquals(WsCommand.VoiceCamera("v1", false), f.signal.sent.last())
        assertEquals("camera:stop", f.engine.log.last())
        assertFalse(f.call.state.value.cameraOn)
        assertEquals(CallPhase.Connected, f.call.state.value.phase)
    }

    @Test
    fun assinaVideoNoPrimeiroSlotLivreComoOServidor() = runTest {
        val f = Fixture(this)
        f.call.start()
        advanceTimeBy(1.seconds)
        f.signal.sent.clear()

        f.signal.events.emit(WsEvent.VoiceStateUpdated("v1", "bia", muted = false, cameraOn = true))
        runCurrent()
        f.signal.events.emit(WsEvent.VoiceStateUpdated("v1", "caio", muted = false, screenSharing = true))
        runCurrent()
        assertEquals<List<WsCommand>>(
            listOf(WsCommand.TrackSubscribe("v1", "bia", "video"), WsCommand.TrackSubscribe("v1", "caio", "screen")),
            f.signal.sent,
        )
        assertEquals(mapOf(0 to VideoKey("bia", "video"), 1 to VideoKey("caio", "screen")), f.call.state.value.videoSlots)

        // Bia desliga a câmera: o slot 0 fica livre e é o próximo a ser usado.
        f.signal.sent.clear()
        f.signal.events.emit(WsEvent.VoiceStateUpdated("v1", "bia", muted = false, cameraOn = false))
        runCurrent()
        f.signal.events.emit(WsEvent.VoiceStateUpdated("v1", "dani", muted = false, cameraOn = true))
        runCurrent()
        assertEquals<List<WsCommand>>(
            listOf(WsCommand.TrackUnsubscribe("v1", "bia", "video"), WsCommand.TrackSubscribe("v1", "dani", "video")),
            f.signal.sent,
        )
        assertEquals(mapOf(0 to VideoKey("dani", "video"), 1 to VideoKey("caio", "screen")), f.call.state.value.videoSlots)

        // Quem sai libera o slot e some da lista.
        f.signal.events.emit(WsEvent.VoiceLeft("v1", "caio"))
        runCurrent()
        assertEquals(mapOf(0 to VideoKey("dani", "video")), f.call.state.value.videoSlots)
        assertFalse("caio" in f.call.state.value.members)
    }

    @Test
    fun naoAssinaOProprioVideo() = runTest {
        val f = Fixture(this)
        f.call.start()
        advanceTimeBy(1.seconds)
        f.signal.sent.clear()
        f.signal.events.emit(WsEvent.VoiceStateUpdated("v1", "eu", muted = false, cameraOn = true))
        runCurrent()
        assertTrue(f.signal.sent.isEmpty())
    }

    @Test
    fun sairAvisaOServidorEFechaOMotor() = runTest {
        val f = Fixture(this)
        f.call.start()
        advanceTimeBy(1.seconds)
        f.call.leave()
        advanceTimeBy(1.seconds)
        assertEquals(WsCommand.VoiceLeave("v1"), f.signal.sent.last())
        assertTrue(f.engine.closed)
        assertEquals(CallEnd.Left, f.call.state.value.end)
    }

    @Test
    fun quedaDoWebSocketEncerraAChamada() = runTest {
        val f = Fixture(this)
        f.call.start()
        advanceTimeBy(1.seconds)
        f.signal.state.value = WsState.Disconnected
        advanceTimeBy(1.seconds)
        assertEquals(CallEnd.ConnectionLost, f.call.state.value.end)
        assertTrue(f.engine.closed)
    }

    @Test
    fun semPermissaoDeMicrofoneNemEntra() = runTest {
        val f = Fixture(this)
        f.engine.micPermission = false
        f.call.start()
        advanceTimeBy(1.seconds)
        assertEquals(CallEnd.NoMicrophone, f.call.state.value.end)
        assertTrue(f.signal.sent.isEmpty())
    }

    @Test
    fun errosDoServidorNaEntrada() = runTest {
        val f = Fixture(this)
        f.signal.answerOffers = false
        f.call.start()
        runCurrent()
        f.signal.events.emit(WsEvent.ServerError("sem permissão", "voice-forbidden"))
        advanceTimeBy(1.seconds)
        assertEquals(CallEnd.Denied, f.call.state.value.end)
    }

    @Test
    fun servidorMudoNaNegociacaoFalhaPorTempo() = runTest {
        val f = Fixture(this)
        f.signal.answerOffers = false
        f.call.start()
        advanceTimeBy(13.seconds)
        assertEquals(CallEnd.Failed, f.call.state.value.end)
        assertEquals(WsCommand.VoiceLeave("v1"), f.signal.sent.last())
    }

    // Áudio de tela e erros de assinatura

    private suspend fun TestScope.connected(): Fixture {
        val f = Fixture(this)
        f.call.start()
        advanceTimeBy(1.seconds)
        f.signal.sent.clear()
        return f
    }

    private suspend fun TestScope.screenAudio(f: Fixture, userId: String, on: Boolean = true) {
        f.signal.events.emit(WsEvent.VoiceStateUpdated("v1", userId, muted = false, screenAudio = on))
        runCurrent()
    }

    private fun sub(user: String) = WsCommand.TrackSubscribe("v1", user, "screen_audio")
    private fun unsub(user: String) = WsCommand.TrackUnsubscribe("v1", user, "screen_audio")

    private fun Fixture.audioCommands() = signal.sent.filter {
        (it is WsCommand.TrackSubscribe && it.kind == "screen_audio") || (it is WsCommand.TrackUnsubscribe && it.kind == "screen_audio")
    }

    @Test
    fun assinaOAudioDeTelaDeQuemTemAFlagEMaisNada() = runTest {
        val f = connected()

        // Sem a flag (só vídeo de tela) e a própria transmissão não geram assinatura de áudio.
        f.signal.events.emit(WsEvent.VoiceStateUpdated("v1", "bia", muted = false, screenSharing = true, screenAudio = false))
        runCurrent()
        f.signal.events.emit(WsEvent.VoiceStateUpdated("v1", "eu", muted = false, screenSharing = true, screenAudio = true))
        runCurrent()
        assertTrue(f.audioCommands().isEmpty())
        assertEquals(emptySet(), f.call.state.value.screenAudioSubs)

        screenAudio(f, "bia")
        assertEquals<List<WsCommand>>(listOf(sub("bia")), f.audioCommands())
        assertEquals(setOf("bia"), f.call.state.value.screenAudioSubs)
        assertEquals(emptySet(), f.call.state.value.screenAudioOverflow)

        // Outra atualização da mesma pessoa não repete o comando.
        f.signal.sent.clear()
        screenAudio(f, "bia")
        assertTrue(f.audioCommands().isEmpty())
    }

    @Test
    fun naoAssinaNadaAntesDeAsAssinaturasEstaremProntas() = runTest {
        val f = Fixture(this)
        f.signal.answerOffers = false
        f.call.start()
        runCurrent()
        screenAudio(f, "bia")
        assertTrue(f.audioCommands().isEmpty())

        f.signal.events.emit(WsEvent.VoiceAnswer("v1", "resposta"))
        advanceTimeBy(1.seconds)
        assertTrue(sub("bia") in f.signal.sent)
        assertEquals(setOf("bia"), f.call.state.value.screenAudioSubs)
    }

    @Test
    fun noMaximoDoisAudiosDeTelaEQuemSobraFicaNoAviso() = runTest {
        val f = connected()
        screenAudio(f, "bia")
        screenAudio(f, "caio")
        f.signal.sent.clear()

        screenAudio(f, "dani")
        // As assinaturas atuais têm preferência: nada é enviado e a terceira pessoa vira aviso.
        assertTrue(f.audioCommands().isEmpty())
        assertEquals(setOf("bia", "caio"), f.call.state.value.screenAudioSubs)
        assertEquals(setOf("dani"), f.call.state.value.screenAudioOverflow)

        // Caio para: o lugar dele vai para Dani, com um cancelamento e uma assinatura, nessa ordem.
        screenAudio(f, "caio", on = false)
        assertEquals<List<WsCommand>>(listOf(unsub("caio"), sub("dani")), f.audioCommands())
        assertEquals(setOf("bia", "dani"), f.call.state.value.screenAudioSubs)
        assertEquals(emptySet(), f.call.state.value.screenAudioOverflow)
    }

    @Test
    fun quemSaiDaChamadaTemOAudioCancelado() = runTest {
        val f = connected()
        screenAudio(f, "bia")
        f.signal.sent.clear()

        f.signal.events.emit(WsEvent.VoiceLeft("v1", "bia"))
        runCurrent()
        assertEquals<List<WsCommand>>(listOf(unsub("bia")), f.audioCommands())
        assertEquals(emptySet(), f.call.state.value.screenAudioSubs)
    }

    @Test
    fun pedidoQueNaoSaiuNaoContaComoAssinadoNemComoLimite() = runTest {
        val f = connected()
        f.signal.failSubscribe = true
        screenAudio(f, "bia")
        assertEquals(emptySet(), f.call.state.value.screenAudioSubs)
        assertEquals(emptySet(), f.call.state.value.screenAudioOverflow)
    }

    @Test
    fun salaCheiaTiraAAssinaturaEMostraOAvisoSemRepetir() = runTest {
        val f = connected()
        screenAudio(f, "bia")
        f.signal.events.emit(WsEvent.ServerError("sem slot livre", "voice-room-full"))
        runCurrent()
        assertEquals(emptySet(), f.call.state.value.screenAudioSubs)
        assertEquals(setOf("bia"), f.call.state.value.screenAudioOverflow)

        // Outras mudanças na sala não reenviam o pedido recusado.
        f.signal.sent.clear()
        screenAudio(f, "caio")
        assertEquals<List<WsCommand>>(listOf(sub("caio")), f.audioCommands())

        // Bia desliga e liga de novo: a situação mudou, então tenta outra vez.
        f.signal.sent.clear()
        screenAudio(f, "bia", on = false)
        assertEquals(emptySet(), f.call.state.value.screenAudioOverflow)
        screenAudio(f, "bia", on = true)
        assertEquals<List<WsCommand>>(listOf(sub("bia")), f.audioCommands())
        assertEquals(setOf("caio", "bia"), f.call.state.value.screenAudioSubs)
    }

    @Test
    fun faixaNaoEncontradaTiraAAssinaturaSemDizerQueNaoCouber() = runTest {
        val f = connected()
        screenAudio(f, "bia")
        f.signal.events.emit(WsEvent.ServerError("faixa não encontrada", "voice-not-found"))
        runCurrent()
        assertEquals(emptySet(), f.call.state.value.screenAudioSubs)
        assertEquals(emptySet(), f.call.state.value.screenAudioOverflow)
        assertEquals(CallPhase.Connected, f.call.state.value.phase)
    }

    @Test
    fun limiteDeTaxaTiraOVideoDoSlotENaoReenvia() = runTest {
        val f = connected()
        f.signal.events.emit(WsEvent.VoiceStateUpdated("v1", "bia", muted = false, cameraOn = true))
        runCurrent()
        assertEquals(mapOf(0 to VideoKey("bia", "video")), f.call.state.value.videoSlots)

        f.signal.events.emit(WsEvent.ServerError("muitas assinaturas", "voice-rate-limited"))
        runCurrent()
        assertTrue(f.call.state.value.videoSlots.isEmpty())
        assertEquals("muitas assinaturas", f.call.state.value.errorDetail)

        // Outra mudança de Bia com a câmera ainda ligada não reenvia o pedido recusado.
        f.signal.sent.clear()
        f.signal.events.emit(WsEvent.VoiceStateUpdated("v1", "bia", muted = true, cameraOn = true))
        runCurrent()
        assertTrue(f.signal.sent.isEmpty())

        // Desligar e ligar a câmera é outra situação.
        f.signal.events.emit(WsEvent.VoiceStateUpdated("v1", "bia", muted = true, cameraOn = false))
        runCurrent()
        f.signal.sent.clear()
        f.signal.events.emit(WsEvent.VoiceStateUpdated("v1", "bia", muted = true, cameraOn = true))
        runCurrent()
        assertEquals<List<WsCommand>>(listOf(WsCommand.TrackSubscribe("v1", "bia", "video")), f.signal.sent)
        assertEquals(mapOf(0 to VideoKey("bia", "video")), f.call.state.value.videoSlots)
    }

    @Test
    fun erroSemPedidoRecenteNaoMexeNasAssinaturas() = runTest {
        val f = connected()
        screenAudio(f, "bia")
        advanceTimeBy(4.seconds)
        f.signal.events.emit(WsEvent.ServerError("muitas mensagens", "voice-rate-limited"))
        runCurrent()
        assertEquals(setOf("bia"), f.call.state.value.screenAudioSubs)
    }

    @Test
    fun erroSemNenhumPedidoNaoMexeEmNada() = runTest {
        val f = connected()
        f.signal.events.emit(WsEvent.ServerError("sem slot", "voice-room-full"))
        runCurrent()
        assertEquals(CallPhase.Connected, f.call.state.value.phase)
        assertEquals(emptySet(), f.call.state.value.screenAudioSubs)
        assertEquals(emptySet(), f.call.state.value.screenAudioOverflow)
    }

    @Test
    fun erroDepoisDeUmCancelamentoNaoDerrubaAAssinaturaAnterior() = runTest {
        val f = connected()
        screenAudio(f, "bia")
        screenAudio(f, "caio")
        screenAudio(f, "bia", on = false) // o último pedido é o cancelamento de Bia
        f.signal.events.emit(WsEvent.ServerError("muitas assinaturas", "voice-rate-limited"))
        runCurrent()
        assertEquals(setOf("caio"), f.call.state.value.screenAudioSubs)
    }

    @Test
    fun cadaErroTiraSoUmaAssinatura() = runTest {
        val f = connected()
        screenAudio(f, "bia")
        screenAudio(f, "caio")
        f.signal.events.emit(WsEvent.ServerError("muitas assinaturas", "voice-rate-limited"))
        runCurrent()
        assertEquals(setOf("bia"), f.call.state.value.screenAudioSubs)
        f.signal.events.emit(WsEvent.ServerError("muitas assinaturas", "voice-rate-limited"))
        runCurrent()
        assertEquals(emptySet(), f.call.state.value.screenAudioSubs)
    }

    @Test
    fun salaCheiaNaEntradaContinuaEncerrandoAChamada() = runTest {
        val f = Fixture(this)
        f.signal.answerJoin = false
        f.call.start()
        runCurrent()
        f.signal.events.emit(WsEvent.ServerError("sala cheia", "voice-room-full"))
        advanceTimeBy(1.seconds)
        assertEquals(CallEnd.Full, f.call.state.value.end)
    }

    @Test
    fun rotasDeAudioDoServidorViramEstado() = runTest {
        val f = connected()
        f.signal.events.emit(
            WsEvent.VoiceAudioRoutes(
                "v1",
                listOf(WsEvent.AudioRoute("papo-audio-0", "bia"), WsEvent.AudioRoute("papo-screen-audio-0", "bia", "screen_audio")),
            ),
        )
        runCurrent()
        assertEquals(
            mapOf("papo-audio-0" to AudioRoute("bia", "audio"), "papo-screen-audio-0" to AudioRoute("bia", "screen_audio")),
            f.call.state.value.audioRoutes,
        )

        // Rotas de outro canal não valem aqui; um snapshot vazio limpa.
        f.signal.events.emit(WsEvent.VoiceAudioRoutes("outro", listOf(WsEvent.AudioRoute("papo-audio-3", "caio"))))
        runCurrent()
        assertEquals(2, f.call.state.value.audioRoutes.size)
        f.signal.events.emit(WsEvent.VoiceAudioRoutes("v1", emptyList()))
        runCurrent()
        assertTrue(f.call.state.value.audioRoutes.isEmpty())
    }
}
