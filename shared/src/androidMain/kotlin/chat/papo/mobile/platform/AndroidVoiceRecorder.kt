package chat.papo.mobile.platform

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.system.Os
import android.system.OsConstants
import androidx.core.app.ActivityCompat
import chat.papo.mobile.chat.MAX_ATTACHMENT_BYTES
import chat.papo.mobile.media.MicrophoneAccess
import chat.papo.mobile.media.VoiceCapture
import chat.papo.mobile.media.VoiceFormat
import chat.papo.mobile.media.VoiceRecorder
import chat.papo.mobile.media.VoiceRecorderException
import java.io.FileDescriptor
import java.io.IOException

/** Opus em WebM, como o web grava; 32 kbps mono é voz nítida em cerca de 4 KB por segundo. */
private const val VOICE_BITRATE = 32_000
private const val VOICE_SAMPLE_RATE = 48_000

/**
 * A folga do limite do `MediaRecorder` sobre o do app: quem para a gravação em condições normais é o [chat.papo.mobile.media.VoiceRecording],
 * e o limite do gravador só evita que uma tela travada grave sem fim.
 */
private const val RECORDER_SLACK_MS = 2_000L

/**
 * O gravador de voz do Android: `MediaRecorder` com Opus em WebM (o `audio_*.webm` do web), escrevendo num arquivo anônimo
 * da memória (`memfd_create`, API 30) em vez de `cacheDir` ou `filesDir`: o disco do aparelho nunca vê a gravação (regra 8).
 * O arquivo é buscável, que o WebM precisa para gravar a duração e o índice no fim, e some quando o descritor fecha.
 */
class AndroidVoiceRecorder(
    context: Context,
    private val requests: ActivityRequests,
    private val activities: ActivityHolder,
) : VoiceRecorder {
    private val app = context.applicationContext

    override val format = VoiceFormat(mimeType = "audio/webm", extension = "webm", bitrate = VOICE_BITRATE)

    override suspend fun requestAccess(): MicrophoneAccess {
        if (requests.isGranted(Manifest.permission.RECORD_AUDIO)) return MicrophoneAccess.Granted
        if (requests.request(listOf(Manifest.permission.RECORD_AUDIO))[Manifest.permission.RECORD_AUDIO] == true) {
            return MicrophoneAccess.Granted
        }
        // O Android não diz "não pergunto mais" direto: depois de recusar, só enquanto ele ainda explicaria o pedido de novo
        // a recusa é simples. Sem a atividade não houve pedido, e então também não há recusa definitiva.
        val activity = activities.current ?: return MicrophoneAccess.Denied
        val canAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
        return if (canAskAgain) MicrophoneAccess.Denied else MicrophoneAccess.Blocked
    }

    override fun openSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            app.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Um aparelho sem a tela de configurações do app: não há para onde levar.
        }
    }

    override fun start(maxDurationMs: Long, onError: () -> Unit): VoiceCapture {
        val file = try {
            Os.memfd_create("papo-voice", OsConstants.MFD_CLOEXEC)
        } catch (e: Exception) {
            throw VoiceRecorderException("sem arquivo em memória para a gravação", e)
        }
        // A atribuição do uso do microfone ao app só existe com o contexto (API 31); antes dela o construtor sem contexto.
        @Suppress("DEPRECATION")
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(app) else MediaRecorder()
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.WEBM)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            recorder.setAudioChannels(1)
            recorder.setAudioSamplingRate(VOICE_SAMPLE_RATE)
            recorder.setAudioEncodingBitRate(VOICE_BITRATE)
            recorder.setMaxDuration((maxDurationMs + RECORDER_SLACK_MS).toInt())
            recorder.setOutputFile(file)
            recorder.setOnErrorListener { _, _, _ -> onError() }
            recorder.prepare()
            recorder.start()
        } catch (e: Exception) {
            // `start` falha com RuntimeException quando o microfone não abre (em uso, sem permissão no instante).
            recorder.release()
            closeQuietly(file)
            throw VoiceRecorderException("não foi possível abrir o microfone", e)
        }
        return MemoryCapture(recorder, file)
    }

    /** A gravação em curso: dona do [MediaRecorder] e do arquivo em memória, que fecha ao terminar ou cancelar. */
    private class MemoryCapture(private val recorder: MediaRecorder, private val file: FileDescriptor) : VoiceCapture {
        private var open = true

        @Synchronized
        override fun finish(): ByteArray {
            if (!open) throw VoiceRecorderException("a gravação já terminou")
            open = false
            try {
                // `stop` lança RuntimeException quando parou cedo demais para haver áudio.
                recorder.stop()
                return readAll(file)
            } catch (e: VoiceRecorderException) {
                throw e
            } catch (e: Exception) {
                throw VoiceRecorderException("a gravação não é válida", e)
            } finally {
                recorder.release()
                closeQuietly(file)
            }
        }

        @Synchronized
        override fun cancel() {
            if (!open) return
            open = false
            try {
                recorder.release()
            } finally {
                closeQuietly(file)
            }
        }
    }

    private companion object {
        /** Lê o arquivo em memória do começo ao fim; o contêiner pode passar do que o servidor aceita, e então não é uma gravação que sirva. */
        fun readAll(file: FileDescriptor): ByteArray {
            val size = Os.fstat(file).st_size
            if (size <= 0L) throw VoiceRecorderException("gravação vazia")
            if (size > MAX_ATTACHMENT_BYTES) throw VoiceRecorderException("gravação grande demais")
            val bytes = ByteArray(size.toInt())
            Os.lseek(file, 0L, OsConstants.SEEK_SET)
            var read = 0
            while (read < bytes.size) {
                val count = Os.read(file, bytes, read, bytes.size - read)
                if (count <= 0) throw IOException("o arquivo em memória acabou antes do esperado")
                read += count
            }
            return bytes
        }

        fun closeQuietly(file: FileDescriptor) {
            try {
                Os.close(file)
            } catch (_: Exception) {
                // Já fechado: nada a soltar.
            }
        }
    }
}
