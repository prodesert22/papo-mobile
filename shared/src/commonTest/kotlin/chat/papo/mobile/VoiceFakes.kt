package chat.papo.mobile

import chat.papo.mobile.media.MicrophoneAccess
import chat.papo.mobile.media.VoiceCapture
import chat.papo.mobile.media.VoiceClip
import chat.papo.mobile.media.VoiceClipPlayer
import chat.papo.mobile.media.VoiceClipPlayerListener
import chat.papo.mobile.media.VoiceFormat
import chat.papo.mobile.media.VoiceRecorder
import chat.papo.mobile.media.VoiceRecorderException
import kotlinx.coroutines.CompletableDeferred

// Gravador, captura e player falsos da gravação de voz, que o teste da máquina de estados e o do ChatViewModel compartilham.

internal class FakeVoiceCapture : VoiceCapture {
    var bytes = byteArrayOf(1, 2, 3)
    var finishError: VoiceRecorderException? = null
    var onFinish: () -> Unit = {}
    var finishCalls = 0
    var cancelCalls = 0

    override fun finish(): ByteArray {
        finishCalls++
        onFinish()
        finishError?.let { throw it }
        return bytes
    }

    override fun cancel() {
        cancelCalls++
    }
}

internal class FakeVoiceRecorder(override val format: VoiceFormat) : VoiceRecorder {
    var access = MicrophoneAccess.Granted
    var accessGate: CompletableDeferred<Unit>? = null
    var accessCalls = 0
    var settingsOpened = 0
    var startError: VoiceRecorderException? = null
    var onStart: () -> Unit = {}
    val captures = mutableListOf<FakeVoiceCapture>()
    val maxDurations = mutableListOf<Long>()
    val errorCallbacks = mutableListOf<() -> Unit>()

    override suspend fun requestAccess(): MicrophoneAccess {
        accessCalls++
        accessGate?.await()
        return access
    }

    override fun openSettings() {
        settingsOpened++
    }

    override fun start(maxDurationMs: Long, onError: () -> Unit): VoiceCapture {
        onStart()
        startError?.let { throw it }
        maxDurations += maxDurationMs
        errorCallbacks += onError
        return FakeVoiceCapture().also { captures += it }
    }
}

internal class FakeVoicePlayer(val clip: VoiceClip, val listener: VoiceClipPlayerListener) : VoiceClipPlayer {
    val calls = mutableListOf<String>()
    var position = 0L
    var released = false

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

    override fun positionMs() = position

    override fun release() {
        released = true
    }
}
