package chat.papo.mobile

import chat.papo.mobile.data.ws.WsCommand
import chat.papo.mobile.data.ws.WsEvent
import chat.papo.mobile.data.ws.WsState
import chat.papo.mobile.voice.AudioOutput
import chat.papo.mobile.voice.AudioOutputKind
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
import chat.papo.mobile.voice.VoiceVolumes
import chat.papo.mobile.voice.VolumeLevels
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
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

        val gains = mutableMapOf<String, Double>()
        override fun setRemoteAudioGain(trackId: String, gain: Double) {
            gains[trackId] = gain
        }

        override val headsetButton = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

        override fun close() {
            closed = true
        }
    }

    /** Volumes por pessoa em memória; [loaded] segura a leitura do banco como se ainda estivesse em andamento. */
    private class FakeVolumes(
        initial: VolumeLevels = VolumeLevels(),
        val loaded: CompletableDeferred<Unit> = CompletableDeferred(Unit),
    ) : VoiceVolumes {
        override val levels = MutableStateFlow(initial)
        override suspend fun awaitLoaded() = loaded.await()
        override fun setVoice(userId: String, percent: Int) = levels.update { it.copy(voice = it.voice + (userId to percent)) }
        override fun setScreen(userId: String, percent: Int) = levels.update { it.copy(screen = it.screen + (userId to percent)) }
        override fun flush() = Unit
    }

    private class Fixture(scope: TestScope, volumes: VoiceVolumes? = null) {
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
            volumes = volumes,
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
    fun faixaNaoEncontradaTiraOVideoDoSlotENaoReenvia() = runTest {
        val f = connected()
        f.signal.events.emit(WsEvent.VoiceStateUpdated("v1", "bia", muted = false, cameraOn = true))
        runCurrent()
        assertEquals(mapOf(0 to VideoKey("bia", "video")), f.call.state.value.videoSlots)

        f.signal.events.emit(WsEvent.ServerError("faixa não encontrada", "voice-not-found"))
        runCurrent()
        assertTrue(f.call.state.value.videoSlots.isEmpty())

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
        f.signal.events.emit(WsEvent.ServerError("sem slot livre", "voice-room-full"))
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
        f.signal.events.emit(WsEvent.ServerError("faixa não encontrada", "voice-not-found"))
        runCurrent()
        assertEquals(setOf("caio"), f.call.state.value.screenAudioSubs)
    }

    @Test
    fun limiteDeTaxaLogoDepoisDeAssinarMantemAsAssinaturas() = runTest {
        val f = connected()
        f.signal.events.emit(WsEvent.VoiceStateUpdated("v1", "bia", muted = false, cameraOn = true))
        runCurrent()
        screenAudio(f, "caio")
        val slots = f.call.state.value.videoSlots
        f.signal.sent.clear()

        // O limite de sinalização (mute, ICE, câmera) usa o mesmo código do de assinaturas: não é erro de assinatura.
        f.signal.events.emit(WsEvent.ServerError("muitas mensagens", "voice-rate-limited"))
        runCurrent()
        assertEquals(slots, f.call.state.value.videoSlots)
        assertEquals(setOf("caio"), f.call.state.value.screenAudioSubs)
        assertEquals("muitas mensagens", f.call.state.value.errorDetail)

        // E nada é reenviado nem cancelado por causa dele.
        screenAudio(f, "dani", on = false)
        assertTrue(f.signal.sent.none { it is WsCommand.TrackSubscribe || it is WsCommand.TrackUnsubscribe })
    }

    @Test
    fun cadaErroTiraSoUmaAssinatura() = runTest {
        val f = connected()
        screenAudio(f, "bia")
        screenAudio(f, "caio")
        f.signal.events.emit(WsEvent.ServerError("faixa não encontrada", "voice-not-found"))
        runCurrent()
        assertEquals(setOf("bia"), f.call.state.value.screenAudioSubs)
        f.signal.events.emit(WsEvent.ServerError("faixa não encontrada", "voice-not-found"))
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

    // Volume por pessoa

    private suspend fun TestScope.routes(f: Fixture, vararg routes: Triple<String, String, String>) {
        f.signal.events.emit(WsEvent.VoiceAudioRoutes("v1", routes.map { (track, user, kind) -> WsEvent.AudioRoute(track, user, kind) }))
        runCurrent()
    }

    @Test
    fun ganhoDeCadaFaixaSegueOVolumeDeQuemToca() = runTest {
        val volumes = FakeVolumes(VolumeLevels(voice = mapOf("bia" to 30)))
        val f = Fixture(this, volumes)
        f.call.start()
        advanceTimeBy(1.seconds)

        routes(f, Triple("papo-audio-0", "bia", "audio"), Triple("papo-screen-audio-0", "bia", "screen_audio"), Triple("papo-audio-1", "caio", "audio"))
        assertEquals(mapOf("papo-audio-0" to 0.3, "papo-screen-audio-0" to 1.0, "papo-audio-1" to 1.0), f.engine.gains)

        volumes.setVoice("bia", 0)
        volumes.setScreen("bia", 150)
        runCurrent()
        assertEquals(mapOf("papo-audio-0" to 0.0, "papo-screen-audio-0" to 1.5, "papo-audio-1" to 1.0), f.engine.gains)
    }

    @Test
    fun servidorTrocaAPessoaDeFaixaEOGanhoAcompanha() = runTest {
        val f = Fixture(this, FakeVolumes(VolumeLevels(voice = mapOf("bia" to 0, "caio" to 200))))
        f.call.start()
        advanceTimeBy(1.seconds)

        routes(f, Triple("papo-audio-0", "bia", "audio"))
        assertEquals(0.0, f.engine.gains["papo-audio-0"])
        routes(f, Triple("papo-audio-0", "caio", "audio"))
        assertEquals(2.0, f.engine.gains["papo-audio-0"])
    }

    @Test
    fun nadaTocaNoNormalEnquantoOsVolumesNaoForemLidos() = runTest {
        val loaded = CompletableDeferred<Unit>()
        val f = Fixture(this, FakeVolumes(VolumeLevels(voice = mapOf("bia" to 0)), loaded))
        f.call.start()
        advanceTimeBy(1.seconds)

        routes(f, Triple("papo-audio-0", "bia", "audio"))
        assertTrue(f.engine.gains.isEmpty())

        loaded.complete(Unit)
        runCurrent()
        assertEquals(mapOf("papo-audio-0" to 0.0), f.engine.gains)
    }

    @Test
    fun semVolumesNaoMexeNoGanho() = runTest {
        val f = connected()
        routes(f, Triple("papo-audio-0", "bia", "audio"))
        assertTrue(f.engine.gains.isEmpty())
    }

    // Botão do fone e apertar-para-falar

    private fun Fixture.mutes() = signal.sent.filterIsInstance<WsCommand.VoiceMute>()

    private fun Fixture.mics() = engine.log.filter { it.startsWith("mic:") }

    @Test
    fun alternarOMuteFechaOMicrofoneEAvisaOServidor() = runTest {
        val f = connected()
        assertFalse(f.call.state.value.micMuted)

        f.call.toggleMute()
        assertTrue(f.call.state.value.micMuted)
        assertEquals("mic:false", f.engine.log.last()) // o microfone local fecha na hora
        runCurrent()
        assertEquals(listOf(WsCommand.VoiceMute("v1", true)), f.mutes())

        advanceTimeBy(300.milliseconds)
        f.call.toggleMute()
        runCurrent()
        assertFalse(f.call.state.value.micMuted)
        assertEquals(listOf(WsCommand.VoiceMute("v1", true), WsCommand.VoiceMute("v1", false)), f.mutes())
    }

    @Test
    fun toquesRapidosNoMuteViramNoMaximoUmComandoPorIntervalo() = runTest {
        val f = connected()
        repeat(9) {
            f.call.toggleMute()
            advanceTimeBy(20.milliseconds)
        }
        advanceTimeBy(500.milliseconds)
        // Nove alternâncias a partir de "falando" terminam em "mudo": um comando na frente e o estado final depois.
        assertTrue(f.call.state.value.micMuted)
        assertEquals(listOf(WsCommand.VoiceMute("v1", true)), f.mutes().takeLast(1))
        assertTrue(f.mutes().size <= 2)
    }

    @Test
    fun muteSoValeComAChamadaNoAr() = runTest {
        val f = Fixture(this)
        f.signal.answerOffers = false
        f.call.start()
        runCurrent()
        f.call.toggleMute() // ainda entrando
        assertTrue(f.call.state.value.micMuted)
        assertTrue(f.mutes().isEmpty())
    }

    @Test
    fun botaoDoFoneAlternaOMute() = runTest {
        val f = connected()
        f.engine.headsetButton.emit(Unit)
        runCurrent()
        assertTrue(f.call.state.value.micMuted)
        assertEquals("mic:false", f.engine.log.last())

        advanceTimeBy(300.milliseconds)
        f.engine.headsetButton.emit(Unit)
        runCurrent()
        assertFalse(f.call.state.value.micMuted)
        assertEquals(listOf(WsCommand.VoiceMute("v1", true), WsCommand.VoiceMute("v1", false)), f.mutes())
    }

    @Test
    fun apertarParaFalarFechaOMicrofoneEMandaMutadoAoLigar() = runTest {
        val f = connected()
        f.call.setPushToTalk(true)
        runCurrent()

        val state = f.call.state.value
        assertTrue(state.pushToTalk)
        assertTrue(state.micMuted)
        assertFalse(state.talking)
        assertEquals("mic:false", f.engine.log.last())
        assertEquals(listOf(WsCommand.VoiceMute("v1", true)), f.mutes())
    }

    @Test
    fun segurarAbreOMicrofoneNaHoraESoltarFechaNaHora() = runTest {
        val f = connected()
        f.call.setPushToTalk(true)
        advanceTimeBy(300.milliseconds)
        f.signal.sent.clear()

        f.call.setTalking(true)
        assertEquals("mic:true", f.engine.log.last()) // antes de qualquer comando sair
        runCurrent()
        assertEquals(listOf(WsCommand.VoiceMute("v1", false)), f.mutes())
        assertTrue(f.call.state.value.talking)
        assertFalse(f.call.state.value.micMuted)

        advanceTimeBy(300.milliseconds)
        f.signal.sent.clear()
        f.call.setTalking(false)
        assertEquals("mic:false", f.engine.log.last())
        runCurrent()
        assertEquals(listOf(WsCommand.VoiceMute("v1", true)), f.mutes())
        assertFalse(f.call.state.value.talking)
        assertTrue(f.call.state.value.micMuted)
    }

    @Test
    fun apertosRapidosNaoPassamDoLimiteDeSinalizacao() = runTest {
        val f = connected()
        f.call.setPushToTalk(true)
        advanceTimeBy(300.milliseconds)
        f.signal.sent.clear()
        val micsBefore = f.mics().size

        repeat(10) {
            f.call.setTalking(true)
            advanceTimeBy(10.milliseconds)
            f.call.setTalking(false)
            advanceTimeBy(10.milliseconds)
        }
        advanceTimeBy(500.milliseconds)

        // O microfone local acompanhou cada aperto e cada solta; o servidor recebeu só o primeiro e o estado final.
        assertEquals(20, f.mics().size - micsBefore)
        assertEquals(listOf(WsCommand.VoiceMute("v1", false), WsCommand.VoiceMute("v1", true)), f.mutes())
    }

    @Test
    fun desligarApertarParaFalarVoltaAoEstadoAnterior() = runTest {
        val f = connected() // falando
        f.call.setPushToTalk(true)
        advanceTimeBy(300.milliseconds)
        f.call.setPushToTalk(false)
        runCurrent()

        val state = f.call.state.value
        assertFalse(state.pushToTalk)
        assertFalse(state.micMuted)
        assertEquals("mic:true", f.engine.log.last())
        assertEquals(listOf(WsCommand.VoiceMute("v1", true), WsCommand.VoiceMute("v1", false)), f.mutes())
    }

    @Test
    fun desligarApertarParaFalarVoltaAoMuteQueTinha() = runTest {
        val f = connected()
        f.call.toggleMute() // já estava mudo
        advanceTimeBy(300.milliseconds)
        f.call.setPushToTalk(true)
        f.call.setTalking(true)
        advanceTimeBy(300.milliseconds)
        f.call.setPushToTalk(false) // soltando junto: o microfone fecha
        advanceTimeBy(300.milliseconds)

        assertTrue(f.call.state.value.micMuted)
        assertFalse(f.call.state.value.talking)
        assertEquals("mic:false", f.engine.log.last())
        assertEquals(WsCommand.VoiceMute("v1", true), f.mutes().last())
    }

    @Test
    fun ligarEDesligarLogoSaiOPrimeiroEORestauradoSoDepoisDoIntervalo() = runTest {
        val f = connected()
        f.signal.sent.clear()
        f.call.setPushToTalk(true)
        f.call.setPushToTalk(false)
        runCurrent()
        // O primeiro pedido depois de um tempo parado sai na hora; o seguinte espera o intervalo.
        assertEquals(listOf(WsCommand.VoiceMute("v1", true)), f.mutes())
        advanceTimeBy(300.milliseconds)
        assertEquals(listOf(WsCommand.VoiceMute("v1", true), WsCommand.VoiceMute("v1", false)), f.mutes())
        assertFalse(f.call.state.value.micMuted)
        assertEquals("mic:true", f.engine.log.last())
    }

    @Test
    fun comApertarParaFalarOMuteComumENaoFazNada() = runTest {
        val f = connected()
        f.call.setPushToTalk(true)
        advanceTimeBy(300.milliseconds)
        f.signal.sent.clear()
        val micsBefore = f.mics().size

        f.call.toggleMute()
        f.engine.headsetButton.emit(Unit)
        advanceTimeBy(300.milliseconds)
        assertTrue(f.call.state.value.micMuted)
        assertEquals(micsBefore, f.mics().size)
        assertTrue(f.mutes().isEmpty())
    }

    @Test
    fun segurarSemApertarParaFalarEIgnorado() = runTest {
        val f = connected()
        f.signal.sent.clear()
        f.call.setTalking(true)
        advanceTimeBy(300.milliseconds)
        assertFalse(f.call.state.value.talking)
        assertFalse(f.call.state.value.micMuted)
        assertTrue(f.mutes().isEmpty())
    }

    @Test
    fun soltarSemEstarFalandoOuLigarDeNovoNaoFazNada() = runTest {
        val f = connected()
        f.call.setPushToTalk(true)
        advanceTimeBy(300.milliseconds)
        f.signal.sent.clear()
        val micsBefore = f.mics().size

        f.call.setTalking(false)
        f.call.setPushToTalk(true)
        advanceTimeBy(300.milliseconds)
        assertEquals(micsBefore, f.mics().size)
        assertTrue(f.mutes().isEmpty())
    }

    @Test
    fun apertarParaFalarSoLigaComAChamadaNoAr() = runTest {
        val f = Fixture(this)
        f.signal.answerOffers = false
        f.call.start()
        runCurrent()
        f.call.setPushToTalk(true)
        assertFalse(f.call.state.value.pushToTalk)
    }

    @Test
    fun apertarParaFalarLigadoAntesDeAbrirOMicrofoneNaoAbre() = runTest {
        val f = Fixture(this)
        f.signal.answerOffers = false
        f.call.start()
        runCurrent()
        // A conexão sobe antes de a entrada terminar; o usuário liga o apertar-para-falar nesse intervalo.
        f.engine.connection.value = EngineConnection.Connected
        runCurrent()
        f.call.setPushToTalk(true)
        f.signal.events.emit(WsEvent.VoiceAnswer("v1", "resposta"))
        advanceTimeBy(1.seconds)

        assertTrue(f.call.state.value.pushToTalk)
        assertTrue(f.call.state.value.micMuted)
        assertTrue(f.mics().none { it == "mic:true" })
        assertTrue(f.mutes().none { !it.muted })
    }

    @Test
    fun sairSegurandoFechaTudoESoltaOBotao() = runTest {
        val f = connected()
        f.call.setPushToTalk(true)
        advanceTimeBy(300.milliseconds)
        f.call.setTalking(true)
        runCurrent()

        f.call.leave()
        advanceTimeBy(1.seconds)
        assertEquals(CallEnd.Left, f.call.state.value.end)
        assertFalse(f.call.state.value.talking)
        assertTrue(f.engine.closed)
        assertEquals(WsCommand.VoiceLeave("v1"), f.signal.sent.last())

        // Depois do fim, soltar o botão não mexe em nada.
        val sent = f.signal.sent.size
        f.call.setTalking(false)
        advanceTimeBy(1.seconds)
        assertEquals(sent, f.signal.sent.size)
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
