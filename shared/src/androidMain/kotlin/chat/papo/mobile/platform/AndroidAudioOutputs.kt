package chat.papo.mobile.platform

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import chat.papo.mobile.voice.AudioOutput
import chat.papo.mobile.voice.AudioOutputKind
import chat.papo.mobile.voice.AudioOutputPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** O tipo de aparelho do sistema ([AudioDeviceInfo.getType]) no tipo do app; o que não tem tipo próprio é [AudioOutputKind.Other]. */
@SuppressLint("InlinedApi")
internal fun outputKindOf(type: Int): AudioOutputKind = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioOutputKind.Earpiece
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioOutputKind.Speaker
    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> AudioOutputKind.Wired
    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> AudioOutputKind.Usb
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
    -> AudioOutputKind.Bluetooth
    AudioDeviceInfo.TYPE_HEARING_AID -> AudioOutputKind.HearingAid
    else -> AudioOutputKind.Other
}

private fun AudioDeviceInfo.toOutput(): AudioOutput {
    val kind = outputKindOf(type)
    // Sem nome do aparelho o sistema devolve o modelo do celular (é o nome dos aparelhos do próprio celular também): a tela
    // usa então o texto da saída em vez dele.
    val builtIn = kind == AudioOutputKind.Earpiece || kind == AudioOutputKind.Speaker
    val label = if (builtIn) null else productName?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != Build.MODEL }
    return AudioOutput(id.toString(), kind, label)
}

/** Uma saída do sistema com o aparelho nativo, que só quem a lista sabe usar. */
private class Device(val output: AudioOutput, val info: AudioDeviceInfo?)

/** O que muda de um Android para outro: como listar as saídas, aplicar uma e saber que algo mudou. */
private interface Backend {
    fun available(): List<Device>

    fun current(): AudioOutput?

    /** Pede ao sistema para usar [device]; false se ele recusou. */
    fun apply(device: Device): Boolean

    /** [onDevices] roda quando um aparelho liga ou desliga; [onCurrent] quando o sistema troca a saída em uso. */
    fun listen(onDevices: () -> Unit, onCurrent: () -> Unit)

    /** Tira os ouvintes e devolve a saída ao sistema. */
    fun release()
}

/**
 * As saídas de áudio da chamada. O motor é o dono: [start] ao abrir a sessão de áudio e [stop] ao fechá-la, que tira os
 * ouvintes. Quem decide a saída é a [AudioOutputPolicy]; aqui só se conta o que o aparelho oferece e se aplica o resultado.
 *
 * No Android 12 ou mais usa a API de dispositivos de comunicação, que lista fone Bluetooth, com fio e USB sem pedir a
 * permissão `BLUETOOTH_CONNECT`. No Android 11 ela não existe: fica a alternância entre alto-falante e fone do aparelho, e
 * o fone com fio o sistema já usa quando o alto-falante está desligado.
 */
class AndroidAudioOutputs(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _outputs = MutableStateFlow<List<AudioOutput>>(emptyList())
    val outputs: StateFlow<List<AudioOutput>> = _outputs.asStateFlow()

    private val _current = MutableStateFlow<AudioOutput?>(null)
    val current: StateFlow<AudioOutput?> = _current.asStateFlow()

    private var backend: Backend? = null
    private var policy: AudioOutputPolicy? = null

    /** Começa a acompanhar os aparelhos e já aplica a saída inicial. Chamar de novo sem [stop] não faz nada. */
    @Synchronized
    fun start() {
        if (backend != null) return
        val created = if (Build.VERSION.SDK_INT >= 31) Modern() else Legacy()
        backend = created
        policy = AudioOutputPolicy()
        created.listen(onDevices = ::refresh, onCurrent = ::mirror)
        refresh()
    }

    /** Fixa a saída escolhida pela pessoa; um [id] que não está na lista é ignorado. */
    @Synchronized
    fun select(id: String) {
        val active = backend ?: return
        val devices = active.available()
        val picked = policy?.choose(id, devices.map { it.output }) ?: return
        val device = devices.first { it.output.id == picked.id }
        _current.value = if (active.apply(device)) picked else active.current()
    }

    @Synchronized
    fun stop() {
        backend?.release()
        backend = null
        policy = null
        _outputs.value = emptyList()
        _current.value = null
    }

    @Synchronized
    private fun refresh() {
        val active = backend ?: return
        val devices = active.available()
        val outputs = devices.map { it.output }
        _outputs.value = outputs
        val wanted = policy?.decide(outputs)
        val system = active.current()
        if (wanted == null || system?.id == wanted.id) {
            _current.value = system
            return
        }
        _current.value = if (active.apply(devices.first { it.output.id == wanted.id })) wanted else system
    }

    /** O sistema trocou a saída sozinho (ou terminou de ligar a que pedimos): só o que se mostra acompanha. */
    @Synchronized
    private fun mirror() {
        val active = backend ?: return
        _current.value = active.current()
    }

    @RequiresApi(31)
    private inner class Modern : Backend {
        private var communicationListener: AudioManager.OnCommunicationDeviceChangedListener? = null
        private var deviceCallback: AudioDeviceCallback? = null

        override fun available(): List<Device> =
            audioManager.availableCommunicationDevices.map { Device(it.toOutput(), it) }

        override fun current(): AudioOutput? = audioManager.communicationDevice?.toOutput()

        override fun apply(device: Device): Boolean = device.info?.let(audioManager::setCommunicationDevice) ?: false

        override fun listen(onDevices: () -> Unit, onCurrent: () -> Unit) {
            val listener = AudioManager.OnCommunicationDeviceChangedListener { onCurrent() }
            val callback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = onDevices()
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = onDevices()
            }
            communicationListener = listener
            deviceCallback = callback
            audioManager.addOnCommunicationDeviceChangedListener(mainExecutor, listener)
            audioManager.registerAudioDeviceCallback(callback, mainHandler)
        }

        override fun release() {
            communicationListener?.let { runCatching { audioManager.removeOnCommunicationDeviceChangedListener(it) } }
            deviceCallback?.let { runCatching { audioManager.unregisterAudioDeviceCallback(it) } }
            communicationListener = null
            deviceCallback = null
            runCatching { audioManager.clearCommunicationDevice() }
        }
    }

    private inner class Legacy : Backend {
        override fun available(): List<Device> {
            val kinds = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { outputKindOf(it.type) }
            return buildList {
                if (AudioOutputKind.Earpiece in kinds) add(Device(AudioOutput(EARPIECE_ID, AudioOutputKind.Earpiece), null))
                // O alto-falante sempre existe: sem ele a chamada não teria por onde tocar.
                add(Device(AudioOutput(SPEAKER_ID, AudioOutputKind.Speaker), null))
            }
        }

        @Suppress("DEPRECATION")
        override fun current(): AudioOutput = if (audioManager.isSpeakerphoneOn) {
            AudioOutput(SPEAKER_ID, AudioOutputKind.Speaker)
        } else {
            AudioOutput(EARPIECE_ID, AudioOutputKind.Earpiece)
        }

        @Suppress("DEPRECATION")
        override fun apply(device: Device): Boolean {
            audioManager.isSpeakerphoneOn = device.output.kind == AudioOutputKind.Speaker
            return true
        }

        // As duas saídas são fixas: não há o que acompanhar.
        override fun listen(onDevices: () -> Unit, onCurrent: () -> Unit) = Unit

        @Suppress("DEPRECATION")
        override fun release() {
            runCatching { audioManager.isSpeakerphoneOn = false }
        }
    }

    private companion object {
        const val EARPIECE_ID = "earpiece"
        const val SPEAKER_ID = "speaker"
    }
}
