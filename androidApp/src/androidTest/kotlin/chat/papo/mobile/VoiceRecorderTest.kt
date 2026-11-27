package chat.papo.mobile

import android.Manifest
import android.content.Context
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import chat.papo.mobile.media.MicrophoneAccess
import chat.papo.mobile.media.VoiceClip
import chat.papo.mobile.media.VoiceClipPlayerListener
import chat.papo.mobile.media.VoiceRecorderException
import chat.papo.mobile.platform.ActivityHolder
import chat.papo.mobile.platform.ActivityRequests
import chat.papo.mobile.platform.AndroidVoiceClipPlayer
import chat.papo.mobile.platform.AndroidVoiceRecorder
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * O gravador de voz no Android de verdade: grava um ou dois segundos pelo `AndroidVoiceRecorder` e confere que o resultado é um
 * WebM com Opus que o decodificador do sistema abre e que o player da revisão toca até o fim, e que nada da gravação passou
 * pelas pastas do app (regra 8): ela vive num arquivo anônimo da memória (`memfd`). O microfone do emulador pode entregar
 * silêncio: o teste confere o formato, não o conteúdo. O tempo de gravação é real (o gravador é do sistema, sem relógio virtual).
 */
@RunWith(AndroidJUnit4::class)
class VoiceRecorderTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val activities = ActivityHolder()
    private val recorder = AndroidVoiceRecorder(context, ActivityRequests(context, activities), activities)

    @Before
    fun grantMicrophone() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
    }

    private fun appFiles(): Map<String, Long> = listOfNotNull(context.filesDir, context.cacheDir, context.codeCacheDir, context.noBackupFilesDir, context.externalCacheDir)
        .flatMap { root -> root.walkTopDown().filter { it.isFile }.map { it.absolutePath to it.length() }.toList() }
        .toMap()

    /** Os arquivos anônimos em memória que o processo mantém abertos, como o `/proc` os mostra. */
    private fun openMemfds(): List<String> = File("/proc/self/fd").listFiles().orEmpty()
        .mapNotNull { runCatching { Os.readlink(it.absolutePath) }.getOrNull() }
        .filter { it.contains("memfd:papo-voice") }

    private fun record(millis: Long): ByteArray {
        val capture = recorder.start(maxDurationMs = 10_000L) {}
        SystemClock.sleep(millis)
        return capture.finish()
    }

    @Test
    fun comOMicrofoneConcedidoOAcessoVoltaConcedidoSemPerguntar() = runBlocking {
        assertEquals(MicrophoneAccess.Granted, recorder.requestAccess())
    }

    @Test
    fun gravaUmWebmComOpusQueODecodificadorDoSistemaAbre() {
        val bytes = record(1_500L)

        // O cabeçalho EBML de um WebM e o tipo de documento.
        assertContentEquals(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()), bytes.copyOf(4))
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains("webm"), "o DocType deveria ser webm")
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(ArrayDataSource(bytes))
            assertEquals(1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            assertEquals(MediaFormat.MIMETYPE_AUDIO_OPUS, format.getString(MediaFormat.KEY_MIME))
            assertEquals(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            assertTrue(durationUs in 1_000_000L..2_500_000L, "duração fora do esperado: $durationUs us")
        } finally {
            extractor.release()
        }
    }

    @Test
    fun aGravacaoNaoPassaPorNenhumaPastaDoAppEOArquivoEmMemoriaFechaAoTerminar() {
        val before = appFiles()
        assertTrue(openMemfds().isEmpty())

        val capture = recorder.start(maxDurationMs = 10_000L) {}
        val during = openMemfds()
        SystemClock.sleep(1_000L)
        capture.finish()

        assertEquals(1, during.size, "a gravação deveria ter um arquivo em memória aberto: $during")
        assertTrue(openMemfds().isEmpty(), "o arquivo em memória deveria ter sido fechado")
        assertEquals(before, appFiles(), "nada deveria ter sido escrito nas pastas do app")
    }

    @Test
    fun cancelarSoltaOArquivoEmMemoriaECancelarDeNovoNaoFazNada() {
        val capture = recorder.start(maxDurationMs = 10_000L) {}
        SystemClock.sleep(300L)

        capture.cancel()
        capture.cancel()

        assertTrue(openMemfds().isEmpty())
        assertFailsWith<VoiceRecorderException> { capture.finish() }
    }

    @Test
    fun pararCedoDemaisNaoEntregaGravacaoESoltaTudo() {
        val capture = recorder.start(maxDurationMs = 10_000L) {}
        val outcome = runCatching { capture.finish() }

        // Alguns aparelhos aceitam uma gravação de poucos milissegundos; os outros falham: nos dois casos nada fica aberto.
        outcome.exceptionOrNull()?.let { assertTrue(it is VoiceRecorderException, "erro inesperado: $it") }
        assertTrue(openMemfds().isEmpty())
    }

    @Test
    fun oPlayerDaRevisaoTocaAGravacaoDaMemoriaAteOFim() {
        val bytes = record(1_200L)
        val clip = VoiceClip("audio_teste.webm", "audio/webm", bytes, 1_200L)
        val startedPlaying = AtomicBoolean(false)
        val ended = CountDownLatch(1)
        val failed = AtomicBoolean(false)
        val listener = object : VoiceClipPlayerListener {
            override fun onPlayingChanged(playing: Boolean) {
                if (playing) startedPlaying.set(true)
            }

            override fun onEnded() = ended.countDown()

            override fun onFailure() {
                failed.set(true)
                ended.countDown()
            }
        }
        val main = Handler(Looper.getMainLooper())
        val created = CountDownLatch(1)
        var player: AndroidVoiceClipPlayer? = null
        main.post {
            player = AndroidVoiceClipPlayer(context, clip, listener).also { it.play() }
            created.countDown()
        }
        assertTrue(created.await(5, TimeUnit.SECONDS))
        try {
            assertTrue(ended.await(15, TimeUnit.SECONDS), "o player não chegou ao fim")
            assertFalse(failed.get(), "o player falhou ao tocar a gravação")
            assertTrue(startedPlaying.get())
        } finally {
            val closed = CountDownLatch(1)
            main.post {
                player?.release()
                closed.countDown()
            }
            closed.await(5, TimeUnit.SECONDS)
        }
    }

    /** Uma fonte de mídia sobre bytes em memória, para o `MediaExtractor` não precisar de arquivo. */
    private class ArrayDataSource(private val bytes: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(size, bytes.size - position.toInt())
            System.arraycopy(bytes, position.toInt(), buffer, offset, count)
            return count
        }

        override fun getSize(): Long = bytes.size.toLong()

        override fun close() = Unit
    }
}
