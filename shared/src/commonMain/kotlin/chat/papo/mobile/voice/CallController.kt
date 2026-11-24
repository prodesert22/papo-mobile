package chat.papo.mobile.voice

import chat.papo.mobile.data.ws.WsCommand
import chat.papo.mobile.data.ws.WsEvent
import chat.papo.mobile.data.ws.WsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

enum class CallPhase { Joining, Connecting, Connected, Reconnecting, Ended }

enum class CallEnd { Left, Denied, Full, NoMicrophone, ConnectionLost, Failed, RoomClosed }

data class CallMember(
    val userId: String,
    val muted: Boolean = true,
    val cameraOn: Boolean = false,
    val screenSharing: Boolean = false,
    val screenAudio: Boolean = false,
)

/** O que cada slot receptor de vídeo está mostrando. */
data class VideoKey(val userId: String, val kind: String) {
    companion object {
        const val CAMERA = "video"
        const val SCREEN = "screen"
    }
}

data class CallState(
    val accountId: String,
    val channelId: String,
    val phase: CallPhase = CallPhase.Joining,
    val members: Map<String, CallMember> = emptyMap(),
    val speakers: Set<String> = emptySet(),
    val micMuted: Boolean = true,
    val cameraOn: Boolean = false,
    val screenOn: Boolean = false,
    val speakerOn: Boolean = true,
    val busy: Boolean = false,
    val videoSlots: Map<Int, VideoKey> = emptyMap(),
    val end: CallEnd? = null,
    val errorDetail: String? = null,
)

/**
 * Uma chamada de voz num canal. Sinalização do SFU do Papo:
 *
 * - o cliente cria TODAS as ofertas; o servidor só responde (`voice_answer`);
 * - a primeira oferta já leva as faixas receptoras (vídeo, microfones, áudio de
 *   tela) e o microfone; o servidor preenche as m-lines na ordem em que vieram;
 * - câmera e tela são anunciadas (`voice_camera`, `screen_share_start`) ANTES da
 *   oferta que as publica, uma por oferta;
 * - vídeo só é encaminhado após `track_subscribe`; o servidor usa o primeiro
 *   slot livre, então o cliente escolhe do mesmo jeito para saber onde vai chegar;
 * - o peer entra mutado no servidor: sem `voice_mute false` ninguém o ouve.
 */
/** O canal de sinalização que a chamada usa (o WebSocket da conta). */
interface VoiceSignal {
    val events: Flow<WsEvent>
    val state: StateFlow<WsState>
    suspend fun send(command: WsCommand): Boolean
}

class CallController(
    val accountId: String,
    val channelId: String,
    private val myUserId: String,
    private val iceServers: suspend () -> List<IceServer>,
    private val ws: VoiceSignal,
    private val engine: VoiceEngine,
    parentScope: CoroutineScope,
    private val slots: VoiceSlots = VoiceSlots(),
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext.job))
    private val _state = MutableStateFlow(CallState(accountId, channelId))
    val state: StateFlow<CallState> = _state.asStateFlow()

    val remoteVideo: StateFlow<List<VideoTrackHandle?>> get() = engine.remoteVideo
    val localCamera: StateFlow<VideoTrackHandle?> get() = engine.localCamera
    val localScreen: StateFlow<VideoTrackHandle?> get() = engine.localScreen

    private val negotiation = Mutex()
    private val mediaActions = Mutex()
    private var joined: CompletableDeferred<WsEvent.VoiceJoined>? = null
    private var answer: CompletableDeferred<String>? = null
    private var joinSent = false
    private var subscriptionsReady = false
    private val firstOfferSent = CompletableDeferred<Unit>()
    private val outgoingCandidates = Channel<LocalCandidate>(Channel.UNLIMITED)
    private var finished = false

    fun start() {
        scope.launch { ws.events.collect(::onEvent) }
        scope.launch {
            // Antes de a primeira oferta chegar, o servidor ainda não tem conexão para receber candidatos.
            firstOfferSent.await()
            // Candidatos contam no limite de sinalização do servidor (10/s): envia espaçado.
            for (candidate in outgoingCandidates) {
                ws.send(WsCommand.VoiceIceCandidate(channelId, candidate.candidate, candidate.sdpMid, candidate.sdpMLineIndex))
                delay(120.milliseconds)
            }
        }
        scope.launch {
            // A chamada pertence à conexão WebSocket que entrou: se ela cai, o servidor remove o peer.
            ws.state.collect { if (joinSent && it != WsState.Connected) finish(CallEnd.ConnectionLost) }
        }
        scope.launch {
            engine.connection.collect { connection ->
                when (connection) {
                    EngineConnection.Connected -> _state.update { if (it.phase != CallPhase.Ended) it.copy(phase = CallPhase.Connected) else it }
                    EngineConnection.Disconnected -> _state.update { if (it.phase == CallPhase.Connected) it.copy(phase = CallPhase.Reconnecting) else it }
                    EngineConnection.Failed -> finish(CallEnd.ConnectionLost)
                    else -> Unit
                }
            }
        }
        scope.launch { runJoin() }
    }

    private suspend fun runJoin() {
        try {
            if (!engine.ensurePermission(MediaPermission.Microphone)) return finish(CallEnd.NoMicrophone)
            val servers = iceServers()

            val waiter = CompletableDeferred<WsEvent.VoiceJoined>().also { joined = it }
            joinSent = true
            if (!ws.send(WsCommand.VoiceJoin(channelId))) return finish(CallEnd.ConnectionLost)
            val event = withTimeoutOrNull(JOIN_TIMEOUT) { waiter.await() } ?: return finish(CallEnd.Failed, "O servidor não respondeu à entrada na chamada")
            applyRoster(event)

            _state.update { it.copy(phase = CallPhase.Connecting) }
            engine.open(servers, slots) { candidate -> outgoingCandidates.trySend(candidate) }
            negotiate()
            subscriptionsReady = true
            reconcileVideo()

            // Chamada no celular começa falando, como uma ligação.
            setMuted(false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            finish(CallEnd.Failed, e.message)
        }
    }

    /** Uma oferta por vez: a próxima só sai depois da resposta da anterior. */
    private suspend fun negotiate() = negotiation.withLock {
        val waiter = CompletableDeferred<String>().also { answer = it }
        val sdp = engine.createOffer()
        if (!ws.send(WsCommand.VoiceOffer(channelId, sdp))) throw IllegalStateException("sem conexão para negociar")
        firstOfferSent.complete(Unit)
        val remote = withTimeoutOrNull(ANSWER_TIMEOUT) { waiter.await() }
            ?: throw IllegalStateException("o servidor não respondeu à negociação")
        engine.applyAnswer(remote)
    }

    // Ações do usuário

    fun toggleMute() = action { setMuted(!_state.value.micMuted) }

    private suspend fun setMuted(muted: Boolean) {
        if (!ws.send(WsCommand.VoiceMute(channelId, muted))) return
        engine.setMicrophoneEnabled(!muted)
        _state.update { it.copy(micMuted = muted) }
    }

    fun toggleCamera() = action {
        if (_state.value.cameraOn) {
            ws.send(WsCommand.VoiceCamera(channelId, false))
            engine.stopCamera()
            negotiate()
            _state.update { it.copy(cameraOn = false) }
        } else {
            if (!engine.ensurePermission(MediaPermission.Camera) || !engine.startCamera()) return@action
            try {
                // O servidor decide o papel da nova m-line pela intenção recebida antes da oferta.
                ws.send(WsCommand.VoiceCamera(channelId, true))
                negotiate()
                _state.update { it.copy(cameraOn = true) }
            } catch (e: Exception) {
                // Negociação recusada: desfaz para o estado local não divergir do servidor.
                ws.send(WsCommand.VoiceCamera(channelId, false))
                engine.stopCamera()
                throw e
            }
        }
    }

    fun switchCamera() = engine.switchCamera()

    fun toggleScreen() = action {
        if (_state.value.screenOn) {
            ws.send(WsCommand.ScreenShareStop(channelId))
            engine.stopScreen()
            negotiate()
            _state.update { it.copy(screenOn = false) }
        } else {
            if (!engine.startScreen()) return@action
            try {
                ws.send(WsCommand.ScreenShareStart(channelId))
                negotiate()
                _state.update { it.copy(screenOn = true) }
            } catch (e: Exception) {
                ws.send(WsCommand.ScreenShareStop(channelId))
                engine.stopScreen()
                throw e
            }
        }
    }

    fun toggleSpeaker() {
        val on = !_state.value.speakerOn
        engine.setSpeakerphone(on)
        _state.update { it.copy(speakerOn = on) }
    }

    fun leave() {
        scope.launch { finish(CallEnd.Left) }
    }

    /** Câmera e tela mexem na mesma conexão: uma ação por vez, cada uma com a própria oferta. */
    private fun action(block: suspend () -> Unit) {
        if (_state.value.phase == CallPhase.Ended || _state.value.busy) return
        scope.launch {
            mediaActions.withLock {
                _state.update { it.copy(busy = true) }
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(errorDetail = e.message) }
                } finally {
                    _state.update { it.copy(busy = false) }
                }
            }
        }
    }

    // Eventos

    private suspend fun onEvent(event: WsEvent) {
        when (event) {
            is WsEvent.VoiceJoined -> if (event.channelId == channelId) joined?.complete(event)
            is WsEvent.VoiceAnswer -> if (event.channelId == channelId) answer?.complete(event.sdp)
            is WsEvent.VoiceIceCandidate ->
                if (event.channelId == channelId) engine.addRemoteCandidate(event.candidate, event.sdpMid, event.sdpMLineIndex)
            is WsEvent.VoiceStateUpdated -> if (event.channelId == channelId) {
                val member = CallMember(event.userId, event.muted, event.cameraOn, event.screenSharing, event.screenAudio)
                _state.update { it.copy(members = it.members + (event.userId to member)) }
                reconcileVideo()
            }
            is WsEvent.VoiceLeft -> if (event.channelId == channelId) {
                if (event.userId == myUserId) {
                    if (_state.value.phase != CallPhase.Joining) finish(CallEnd.Left)
                } else {
                    _state.update { it.copy(members = it.members - event.userId, speakers = it.speakers - event.userId) }
                    reconcileVideo()
                }
            }
            is WsEvent.ActiveSpeakers -> if (event.channelId == channelId) _state.update { it.copy(speakers = event.userIds.toSet()) }
            is WsEvent.ServerError -> onServerError(event)
            else -> Unit
        }
    }

    private suspend fun onServerError(error: WsEvent.ServerError) {
        val code = error.code ?: return
        if (!code.startsWith("voice-")) return
        val joining = _state.value.phase == CallPhase.Joining
        when (code) {
            "voice-forbidden" -> finish(CallEnd.Denied, error.message)
            "voice-room-closed" -> finish(CallEnd.RoomClosed, error.message)
            "voice-room-full" -> if (joining) finish(CallEnd.Full, error.message)
            "voice-already-in-room" -> if (joining) finish(CallEnd.Failed, error.message)
            // Fora da entrada, costuma ser corrida (ex.: assinar quem acabou de parar de transmitir).
            "voice-not-found" -> if (joining) finish(CallEnd.Failed, error.message)
            "voice-invalid-sdp", "voice-codec-unsupported" -> {
                answer?.completeExceptionally(IllegalStateException(error.message))
                _state.update { it.copy(errorDetail = error.message) }
            }
            else -> _state.update { it.copy(errorDetail = error.message) }
        }
    }

    private fun applyRoster(event: WsEvent.VoiceJoined) = _state.update {
        it.copy(
            members = event.members.associate { m -> m.userId to CallMember(m.userId, m.muted, m.cameraOn, m.screenSharing, m.screenAudio) },
            speakers = event.activeSpeakers.toSet(),
        )
    }

    /**
     * Assina câmeras e telas dos outros participantes, até onde há slots. O
     * servidor coloca cada assinatura no primeiro slot livre; repetir a mesma
     * regra aqui diz em qual faixa o vídeo de cada um vai chegar.
     */
    private suspend fun reconcileVideo() {
        if (!subscriptionsReady) return
        val wanted = LinkedHashSet<VideoKey>()
        for (member in _state.value.members.values) {
            if (member.userId == myUserId) continue
            if (member.screenSharing) wanted += VideoKey(member.userId, VideoKey.SCREEN)
            if (member.cameraOn) wanted += VideoKey(member.userId, VideoKey.CAMERA)
        }
        val current = _state.value.videoSlots.toMutableMap()
        for ((slot, key) in current.toMap()) {
            if (key !in wanted) {
                ws.send(WsCommand.TrackUnsubscribe(channelId, key.userId, key.kind))
                current.remove(slot)
            }
        }
        for (key in wanted) {
            if (key in current.values) continue
            val free = (0 until slots.video).firstOrNull { it !in current } ?: break
            if (ws.send(WsCommand.TrackSubscribe(channelId, key.userId, key.kind))) current[free] = key
        }
        _state.update { it.copy(videoSlots = current) }
    }

    private suspend fun finish(end: CallEnd, detail: String? = null) {
        if (finished) return
        finished = true
        _state.update { it.copy(phase = CallPhase.Ended, end = end, errorDetail = detail ?: it.errorDetail) }
        withContext(NonCancellable) {
            if (joinSent) runCatching { ws.send(WsCommand.VoiceLeave(channelId)) }
            runCatching { engine.close() }
        }
        scope.cancel()
    }

    private companion object {
        val JOIN_TIMEOUT = 10.seconds
        val ANSWER_TIMEOUT = 12.seconds
    }
}
