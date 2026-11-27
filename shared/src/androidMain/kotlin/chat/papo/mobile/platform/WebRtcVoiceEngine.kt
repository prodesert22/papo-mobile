package chat.papo.mobile.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import chat.papo.mobile.voice.AudioOutput
import chat.papo.mobile.voice.EngineConnection
import chat.papo.mobile.voice.IceServer
import chat.papo.mobile.voice.LocalCandidate
import chat.papo.mobile.voice.MediaPermission
import chat.papo.mobile.voice.VideoTrackHandle
import chat.papo.mobile.voice.VoiceEngine
import chat.papo.mobile.voice.VoiceEngineFactory
import chat.papo.mobile.voice.VoiceSlots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Recursos do libwebrtc que vivem enquanto o processo existir. */
object WebRtcRuntime {
    val egl: EglBase by lazy { EglBase.create() }

    @Volatile
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions(),
            )
            initialized = true
        }
    }
}

class WebRtcVoiceEngineFactory(private val context: Context, private val requests: ActivityRequests) : VoiceEngineFactory {
    override fun create(): VoiceEngine = WebRtcVoiceEngine(context.applicationContext, requests)
}

class WebRtcVoiceEngine(private val context: Context, private val requests: ActivityRequests) : VoiceEngine {
    private val _connection = MutableStateFlow(EngineConnection.New)
    override val connection: StateFlow<EngineConnection> = _connection

    private val _remoteVideo = MutableStateFlow<List<VideoTrackHandle?>>(emptyList())
    override val remoteVideo: StateFlow<List<VideoTrackHandle?>> = _remoteVideo

    private val _localCamera = MutableStateFlow<VideoTrackHandle?>(null)
    override val localCamera: StateFlow<VideoTrackHandle?> = _localCamera

    private val _localScreen = MutableStateFlow<VideoTrackHandle?>(null)
    override val localScreen: StateFlow<VideoTrackHandle?> = _localScreen

    // Dono dos ouvintes de saída de áudio: começam em startAudioSession e saem em stopAudioSession.
    private val audioOutputs = AndroidAudioOutputs(context)
    override val outputs: StateFlow<List<AudioOutput>> get() = audioOutputs.outputs
    override val currentOutput: StateFlow<AudioOutput?> get() = audioOutputs.current

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var previousAudioMode = AudioManager.MODE_NORMAL
    private var focusRequest: AudioFocusRequest? = null

    private var factory: PeerConnectionFactory? = null
    private var audioDevice: JavaAudioDeviceModule? = null
    private var pc: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var micTrack: AudioTrack? = null

    private var cameraCapturer: CameraVideoCapturer? = null
    private var cameraHelper: SurfaceTextureHelper? = null
    private var cameraSource: VideoSource? = null
    private var cameraTrack: VideoTrack? = null
    private var cameraTransceiver: RtpTransceiver? = null
    private var frontCamera = true

    private var screenCapturer: VideoCapturer? = null
    private var screenHelper: SurfaceTextureHelper? = null
    private var screenSource: VideoSource? = null
    private var screenTrack: VideoTrack? = null
    private var screenTransceiver: RtpTransceiver? = null

    private var serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    private var remoteDescriptionSet = false
    private val queuedCandidates = ArrayList<IceCandidate>()
    private var closed = false

    override suspend fun ensurePermission(permission: MediaPermission): Boolean {
        val name = when (permission) {
            MediaPermission.Microphone -> Manifest.permission.RECORD_AUDIO
            MediaPermission.Camera -> Manifest.permission.CAMERA
        }
        val wanted = buildList {
            add(name)
            // A notificação da chamada é o caminho para desligar com o app fora da tela.
            if (permission == MediaPermission.Microphone && Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return requests.request(wanted)[name] == true
    }

    override suspend fun open(iceServers: List<IceServer>, slots: VoiceSlots, onCandidate: (LocalCandidate) -> Unit) {
        withContext(Dispatchers.Default) {
            WebRtcRuntime.initialize(context)
            CallService.ensure(context, serviceTypes, "")
            startAudioSession()

            val adm = JavaAudioDeviceModule.builder(context)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule()
            audioDevice = adm
            val egl = WebRtcRuntime.egl.eglBaseContext
            val created = PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl))
                .createPeerConnectionFactory()
            factory = created

            val config = PeerConnection.RTCConfiguration(
                iceServers.map { server ->
                    PeerConnection.IceServer.builder(server.urls)
                        .setUsername(server.username.orEmpty())
                        .setPassword(server.credential.orEmpty())
                        .createIceServer()
                },
            ).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }
            val connection = created.createPeerConnection(config, Observer(onCandidate))
                ?: error("não foi possível criar a conexão WebRTC")
            pc = connection

            // A ordem das m-lines é o contrato com o SFU: vídeo, microfones, áudio de transmissões.
            val recvOnly = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            val video = List(slots.video) { connection.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, recvOnly) }
            val audio = List(slots.audio + slots.screenAudio) {
                connection.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, recvOnly)
            }
            _remoteVideo.value = video.map { transceiver -> (transceiver.receiver.track() as? VideoTrack)?.let(::VideoTrackHandle) }

            // O microfone ocupa a primeira m-line de áudio (vira sendrecv), como no cliente web.
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            }
            val source = created.createAudioSource(constraints)
            val track = created.createAudioTrack("papo-mic", source).apply { setEnabled(false) }
            audioSource = source
            micTrack = track
            audio.first().apply {
                direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                sender.setTrack(track, false)
                sender.streams = listOf("papo-mic")
            }
        }
    }

    override suspend fun createOffer(): String {
        val connection = pc ?: error("conexão fechada")
        val offer = awaitSdp { connection.createOffer(it, MediaConstraints()) }
        awaitSet { connection.setLocalDescription(it, offer) }
        return offer.description
    }

    override suspend fun applyAnswer(sdp: String) {
        val connection = pc ?: error("conexão fechada")
        awaitSet { connection.setRemoteDescription(it, SessionDescription(SessionDescription.Type.ANSWER, sdp)) }
        val queued = synchronized(queuedCandidates) {
            remoteDescriptionSet = true
            queuedCandidates.toList().also { queuedCandidates.clear() }
        }
        queued.forEach { connection.addIceCandidate(it) }
    }

    override fun addRemoteCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        val ice = IceCandidate(sdpMid, sdpMLineIndex ?: 0, candidate)
        val ready = synchronized(queuedCandidates) {
            if (!remoteDescriptionSet) queuedCandidates.add(ice)
            remoteDescriptionSet
        }
        if (ready) pc?.addIceCandidate(ice)
    }

    override fun setMicrophoneEnabled(enabled: Boolean) {
        micTrack?.setEnabled(enabled)
    }

    override suspend fun startCamera(): Boolean = withContext(Dispatchers.Default) {
        val connection = pc ?: return@withContext false
        val created = factory ?: return@withContext false
        try {
            if (!promoteService(ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)) return@withContext false
            val enumerator = Camera2Enumerator(context)
            val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) == frontCamera }
                ?: enumerator.deviceNames.firstOrNull()
                ?: return@withContext false
            frontCamera = enumerator.isFrontFacing(name)
            val capturer = enumerator.createCapturer(name, null)
            val helper = SurfaceTextureHelper.create("papo-camera", WebRtcRuntime.egl.eglBaseContext)
            val source = created.createVideoSource(false)
            capturer.initialize(helper, context, source.capturerObserver)
            capturer.startCapture(1280, 720, 30)
            val track = created.createVideoTrack("papo-camera", source)
            cameraCapturer = capturer
            cameraHelper = helper
            cameraSource = source
            cameraTrack = track
            // Uma m-line guarda o papel dela (câmera) pela vida da conexão: reaproveita ao religar.
            cameraTransceiver = publish(connection, cameraTransceiver, track, "papo-camera", maxBitrate = 1_800_000, maxFps = 30)
            _localCamera.value = VideoTrackHandle(track, mirrored = frontCamera)
            true
        } catch (e: Exception) {
            releaseCamera()
            false
        }
    }

    override suspend fun stopCamera() = withContext(Dispatchers.Default) {
        unpublish(cameraTransceiver)
        releaseCamera()
    }

    override fun switchCamera() {
        cameraCapturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) {
                frontCamera = isFront
                cameraTrack?.let { _localCamera.value = VideoTrackHandle(it, mirrored = isFront) }
            }

            override fun onCameraSwitchError(error: String?) = Unit
        })
    }

    override suspend fun startScreen(): Boolean {
        val connection = pc ?: return false
        val created = factory ?: return false
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        val result = requests.startForResult(manager.createScreenCaptureIntent()) ?: return false
        val data: Intent = result.data?.takeIf { result.resultCode == android.app.Activity.RESULT_OK } ?: return false
        return withContext(Dispatchers.Default) {
            try {
                // O sistema exige o serviço com o tipo de captura de tela ANTES de abrir a projeção.
                if (!promoteService(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)) return@withContext false
                val capturer = ScreenCapturerAndroid(
                    data,
                    object : MediaProjection.Callback() {
                        override fun onStop() {
                            // O usuário encerrou a captura pelo sistema; a faixa para de produzir quadros.
                            _localScreen.value = null
                        }
                    },
                )
                val helper = SurfaceTextureHelper.create("papo-screen", WebRtcRuntime.egl.eglBaseContext)
                val source = created.createVideoSource(true)
                capturer.initialize(helper, context, source.capturerObserver)
                val metrics = context.resources.displayMetrics
                val scale = 1280f / maxOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1280)
                capturer.startCapture((metrics.widthPixels * scale).toInt(), (metrics.heightPixels * scale).toInt(), 15)
                val track = created.createVideoTrack("papo-screen", source)
                screenCapturer = capturer
                screenHelper = helper
                screenSource = source
                screenTrack = track
                screenTransceiver = publish(connection, screenTransceiver, track, "papo-screen", maxBitrate = 2_500_000, maxFps = 15)
                _localScreen.value = VideoTrackHandle(track)
                true
            } catch (e: Exception) {
                releaseScreen()
                false
            }
        }
    }

    override suspend fun stopScreen() = withContext(Dispatchers.Default) {
        unpublish(screenTransceiver)
        releaseScreen()
        serviceTypes = serviceTypes and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION.inv()
        CallService.ensure(context, serviceTypes, "")
        Unit
    }

    override fun selectOutput(id: String) = audioOutputs.select(id)

    override fun setRemoteAudioGain(trackId: String, gain: Double) = Unit

    override fun close() {
        if (closed) return
        closed = true
        _connection.value = EngineConnection.Closed
        releaseCamera()
        releaseScreen()
        runCatching { pc?.dispose() }
        pc = null
        runCatching { audioSource?.dispose() }
        runCatching { factory?.dispose() }
        runCatching { audioDevice?.release() }
        factory = null
        _remoteVideo.value = emptyList()
        stopAudioSession()
        CallService.stop(context)
    }

    private fun publish(
        connection: PeerConnection,
        existing: RtpTransceiver?,
        track: VideoTrack,
        streamId: String,
        maxBitrate: Int,
        maxFps: Int,
    ): RtpTransceiver {
        val transceiver = existing?.also {
            it.sender.setTrack(track, false)
            it.direction = RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
        } ?: connection.addTransceiver(
            track,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, listOf(streamId)),
        )
        val parameters = transceiver.sender.parameters
        parameters.encodings.firstOrNull()?.let {
            it.maxBitrateBps = maxBitrate
            it.maxFramerate = maxFps
        }
        transceiver.sender.parameters = parameters
        return transceiver
    }

    private fun unpublish(transceiver: RtpTransceiver?) {
        transceiver ?: return
        runCatching {
            transceiver.sender.setTrack(null, false)
            transceiver.direction = RtpTransceiver.RtpTransceiverDirection.INACTIVE
        }
    }

    private suspend fun promoteService(type: Int): Boolean {
        serviceTypes = serviceTypes or type
        return CallService.ensure(context, serviceTypes, "")
    }

    private fun releaseCamera() {
        _localCamera.value = null
        runCatching { cameraCapturer?.stopCapture() }
        runCatching { cameraCapturer?.dispose() }
        runCatching { cameraTrack?.dispose() }
        runCatching { cameraSource?.dispose() }
        runCatching { cameraHelper?.dispose() }
        cameraCapturer = null
        cameraTrack = null
        cameraSource = null
        cameraHelper = null
    }

    private fun releaseScreen() {
        _localScreen.value = null
        runCatching { screenCapturer?.stopCapture() }
        runCatching { screenCapturer?.dispose() }
        runCatching { screenTrack?.dispose() }
        runCatching { screenSource?.dispose() }
        runCatching { screenHelper?.dispose() }
        screenCapturer = null
        screenTrack = null
        screenSource = null
        screenHelper = null
    }

    private fun startAudioSession() {
        previousAudioMode = audioManager.mode
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        focusRequest = request
        audioManager.requestAudioFocus(request)
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioOutputs.start()
    }

    private fun stopAudioSession() {
        runCatching { audioOutputs.stop() }
        runCatching {
            audioManager.mode = previousAudioMode
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        }
    }

    private suspend fun awaitSdp(block: (SdpObserver) -> Unit): SessionDescription = suspendCancellableCoroutine { continuation ->
        block(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) {
                if (continuation.isActive) continuation.resume(description)
            }

            override fun onCreateFailure(error: String?) {
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error ?: "falha ao criar a oferta"))
            }

            override fun onSetSuccess() = Unit
            override fun onSetFailure(error: String?) = Unit
        })
    }

    private suspend fun awaitSet(block: (SdpObserver) -> Unit): Unit = suspendCancellableCoroutine { continuation ->
        block(object : SdpObserver {
            override fun onSetSuccess() {
                if (continuation.isActive) continuation.resume(Unit)
            }

            override fun onSetFailure(error: String?) {
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error ?: "descrição de sessão recusada"))
            }

            override fun onCreateSuccess(description: SessionDescription?) = Unit
            override fun onCreateFailure(error: String?) = Unit
        })
    }

    private inner class Observer(private val onCandidate: (LocalCandidate) -> Unit) : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            onCandidate(LocalCandidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex))
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            if (closed) return
            _connection.value = when (state) {
                PeerConnection.PeerConnectionState.NEW -> EngineConnection.New
                PeerConnection.PeerConnectionState.CONNECTING -> EngineConnection.Connecting
                PeerConnection.PeerConnectionState.CONNECTED -> EngineConnection.Connected
                PeerConnection.PeerConnectionState.DISCONNECTED -> EngineConnection.Disconnected
                PeerConnection.PeerConnectionState.FAILED -> EngineConnection.Failed
                PeerConnection.PeerConnectionState.CLOSED -> EngineConnection.Closed
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit
    }
}
