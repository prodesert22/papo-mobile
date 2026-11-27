package chat.papo.mobile

import chat.papo.mobile.data.ws.WsCommand
import chat.papo.mobile.data.ws.WsEvent
import chat.papo.mobile.data.ws.WsState
import chat.papo.mobile.voice.AudioOutput
import chat.papo.mobile.voice.AudioOutputKind
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

        override suspend fun send(command: WsCommand): Boolean {
            sent += command
            when (command) {
                is WsCommand.VoiceJoin -> events.emit(
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
            return true
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

        override suspend fun startCamera(): Boolean = true.also {
            log += "camera:start"
            localCamera.value = VideoTrackHandle(Any())
        }
        override suspend fun stopCamera() {
            log += "camera:stop"
            localCamera.value = null
        }

        override fun switchCamera() = Unit
        override suspend fun startScreen(): Boolean = true.also {
            log += "screen:start"
            localScreen.value = VideoTrackHandle(Any())
        }
        override suspend fun stopScreen() {
            log += "screen:stop"
            localScreen.value = null
        }

        override val outputs = MutableStateFlow<List<AudioOutput>>(emptyList())
        override val currentOutput = MutableStateFlow<AudioOutput?>(null)
        val selected = mutableListOf<String>()
        override fun selectOutput(id: String) {
            selected += id
        }
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

    @Test
    fun estadoEspelhaAsSaidasDoMotor() = runTest {
        val f = Fixture(this)
        f.call.start()
        advanceTimeBy(1.seconds)
        assertTrue(f.call.state.value.outputs.isEmpty())
        assertEquals(null, f.call.state.value.output)

        val earpiece = AudioOutput("1", AudioOutputKind.Earpiece)
        val speaker = AudioOutput("2", AudioOutputKind.Speaker)
        f.engine.outputs.value = listOf(earpiece, speaker)
        f.engine.currentOutput.value = speaker
        runCurrent()
        assertEquals(listOf(earpiece, speaker), f.call.state.value.outputs)
        assertEquals(speaker, f.call.state.value.output)

        // Fone Bluetooth ligado: o motor troca a saída e a lista, e o estado acompanha.
        val headset = AudioOutput("3", AudioOutputKind.Bluetooth, "Fone de teste")
        f.engine.outputs.value = listOf(earpiece, speaker, headset)
        f.engine.currentOutput.value = headset
        runCurrent()
        assertEquals(headset, f.call.state.value.output)
    }

    @Test
    fun escolherUmaSaidaChegaAoMotor() = runTest {
        val f = Fixture(this)
        f.call.start()
        advanceTimeBy(1.seconds)
        f.call.selectOutput("3")
        assertEquals(listOf("3"), f.engine.selected)
    }

    @Test
    fun alternarVaiDoAltoFalanteAoAuricularEVolta() = runTest {
        val f = Fixture(this)
        f.call.start()
        advanceTimeBy(1.seconds)
        val earpiece = AudioOutput("1", AudioOutputKind.Earpiece)
        val speaker = AudioOutput("2", AudioOutputKind.Speaker)
        f.engine.outputs.value = listOf(earpiece, speaker)
        f.engine.currentOutput.value = speaker
        runCurrent()

        f.call.toggleSpeaker()
        f.engine.currentOutput.value = earpiece
        runCurrent()
        f.call.toggleSpeaker()
        assertEquals(listOf("1", "2"), f.engine.selected)
    }

    @Test
    fun alternarSemSaidasNaoFazNada() = runTest {
        val f = Fixture(this)
        f.call.start()
        advanceTimeBy(1.seconds)
        f.call.toggleSpeaker()
        assertTrue(f.engine.selected.isEmpty())
    }

    @Test
    fun telaEncerradaPeloSistemaDesfazAPublicacao() = runTest {
        val f = Fixture(this)
        f.call.start()
        advanceTimeBy(1.seconds)
        f.call.toggleScreen()
        advanceTimeBy(1.seconds)
        assertTrue(f.call.state.value.screenOn)
        f.signal.sent.clear()

        // A pessoa parou a transmissão pelo aviso do Android, ou o aparelho bloqueou a tela: o motor fica sem a faixa.
        f.engine.localScreen.value = null
        advanceTimeBy(1.seconds)

        assertEquals(listOf("ScreenShareStop", "VoiceOffer"), f.signal.kinds)
        assertFalse(f.call.state.value.screenOn)
        assertEquals(1, f.engine.log.count { it == "screen:stop" })
        assertEquals(CallPhase.Connected, f.call.state.value.phase)
    }

    @Test
    fun cameraTomadaPorOutroAppDesfazAPublicacao() = runTest {
        val f = Fixture(this)
        f.call.start()
        advanceTimeBy(1.seconds)
        f.call.toggleCamera()
        advanceTimeBy(1.seconds)
        f.signal.sent.clear()

        f.engine.localCamera.value = null
        advanceTimeBy(1.seconds)

        assertEquals(listOf("VoiceCamera", "VoiceOffer"), f.signal.kinds)
        assertEquals(WsCommand.VoiceCamera("v1", false), f.signal.sent.first())
        assertFalse(f.call.state.value.cameraOn)
    }

    @Test
    fun cameraETelaEncerradasJuntasSaemUmaDeCadaVez() = runTest {
        val f = Fixture(this)
        f.call.start()
        advanceTimeBy(1.seconds)
        f.call.toggleCamera()
        advanceTimeBy(1.seconds)
        f.call.toggleScreen()
        advanceTimeBy(1.seconds)
        f.signal.sent.clear()

        f.engine.localCamera.value = null
        f.engine.localScreen.value = null
        advanceTimeBy(1.seconds)

        assertEquals(listOf("ScreenShareStop", "VoiceOffer", "VoiceCamera", "VoiceOffer"), f.signal.kinds)
        assertFalse(f.call.state.value.cameraOn || f.call.state.value.screenOn)
    }

    @Test
    fun capturaEncerradaComRenegociacaoRecusadaNaoFicaTentandoParaSempre() = runTest {
        val f = Fixture(this)
        f.call.start()
        advanceTimeBy(1.seconds)
        f.call.toggleScreen()
        advanceTimeBy(1.seconds)
        f.signal.sent.clear()
        f.signal.rejectNextOffer = true

        f.engine.localScreen.value = null
        advanceTimeBy(30.seconds)

        // Uma tentativa só: o estado local desliga mesmo com a oferta recusada, e o erro fica à mostra.
        assertEquals(listOf("ScreenShareStop", "VoiceOffer"), f.signal.kinds)
        assertFalse(f.call.state.value.screenOn)
        assertEquals("SDP inválido", f.call.state.value.errorDetail)
        assertEquals(CallPhase.Connected, f.call.state.value.phase)
    }

    @Test
    fun faixaAusenteComACapturaDesligadaNaoFazNada() = runTest {
        val f = Fixture(this)
        f.call.start()
        advanceTimeBy(1.seconds)
        f.signal.sent.clear()
        f.engine.localCamera.value = null
        f.engine.localScreen.value = null
        advanceTimeBy(1.seconds)
        assertTrue(f.signal.sent.isEmpty())
    }
}
