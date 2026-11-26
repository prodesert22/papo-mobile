package chat.papo.mobile.platform

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import chat.papo.mobile.media.POSTER_EXTRACT_TIMEOUT_MS
import chat.papo.mobile.media.RangeReader
import chat.papo.mobile.media.PixelSize
import chat.papo.mobile.media.VideoPosterExtractor
import chat.papo.mobile.media.isNearlyBlack
import chat.papo.mobile.media.posterRetryTimeUs
import chat.papo.mobile.media.posterSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Qualidade do JPEG da capa: quadro de vídeo já tem ruído, e o arquivo cifrado em disco fica em dezenas de KB. */
private const val POSTER_JPEG_QUALITY = 80

/** Pontos por lado da grade com que se olha se o quadro é preto. */
private const val BLACK_CHECK_GRID = 12

/**
 * Tira a capa com o `MediaMetadataRetriever` do sistema, lendo o vídeo por um [RangeMediaDataSource] sobre o
 * [RangeReader]: o decodificador pede os trechos que precisa (inclusive o `moov` no fim do arquivo) e só eles são baixados.
 * Tudo fica em memória; o JPEG devolvido é o que o chamador guarda no cache cifrado.
 *
 * O quadro é o primeiro do vídeo (o do instante 0, que só pede o começo do arquivo e o `moov`); se ele é preto, como a
 * abertura de um vídeo que começa em fade, tenta mais uma vez um pouco adiante, com o que o retriever já leu.
 *
 * A leitura bloqueia a thread que a roda e o retriever não é interrompível, então o cancelamento aborta a fonte de dados
 * (a próxima leitura falha e o retriever desiste) e só volta depois de o retriever ter sido liberado. Além de
 * [timeoutMs] a extração é abandonada do mesmo jeito e devolve null.
 */
class AndroidVideoPosterExtractor(
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMs: Long = POSTER_EXTRACT_TIMEOUT_MS,
) : VideoPosterExtractor {
    override suspend fun extract(reader: RangeReader, maxPx: Int): ByteArray? = try {
        withTimeout(timeoutMs) { extractNow(reader, maxPx) }
    } catch (e: TimeoutCancellationException) {
        // Só o limite desta extração chega aqui como timeout: o cancelamento de quem chamou passa direto. Sem capa.
        null
    }

    private suspend fun extractNow(reader: RangeReader, maxPx: Int): ByteArray? = coroutineScope {
        val source = RangeMediaDataSource(reader)
        val work = async(io) { source.use { decodePoster(it, maxPx) } }
        try {
            work.await()
        } catch (e: CancellationException) {
            source.abort()
            throw e
        }
    }

    private fun decodePoster(source: RangeMediaDataSource, maxPx: Int): ByteArray? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            fun metadata(key: Int) = retriever.extractMetadata(key)?.toIntOrNull()
            // O sistema amplia o quadro até o tamanho pedido: pedir só o que cabe em maxPx, na proporção do vídeo já girado.
            val size = posterSize(
                metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
                metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
                metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION),
                maxPx,
            )
            val first = frameAt(retriever, 0, size) ?: return null
            val frame = if (isNearlyBlack(samplePixels(first))) laterFrameOr(first, retriever, durationMs, size) else first
            try {
                val jpeg = ByteArrayOutputStream()
                return if (frame.compress(Bitmap.CompressFormat.JPEG, POSTER_JPEG_QUALITY, jpeg)) jpeg.toByteArray() else null
            } finally {
                frame.recycle()
            }
        } catch (e: RuntimeException) {
            // Contêiner ou codec que o aparelho não lê, arquivo cortado, ou a leitura que falhou (conexão, orçamento).
            return null
        } finally {
            retriever.release()
        }
    }
}

private fun frameAt(retriever: MediaMetadataRetriever, timeUs: Long, size: PixelSize): Bitmap? =
    retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, size.width, size.height)

/** O quadro de [posterRetryTimeUs], se o retriever o dá; senão fica [first], que é preto mas é um quadro do vídeo. */
private fun laterFrameOr(first: Bitmap, retriever: MediaMetadataRetriever, durationMs: Long?, size: PixelSize): Bitmap {
    val retryUs = posterRetryTimeUs(durationMs)
    if (retryUs == 0L) return first
    val later = try {
        frameAt(retriever, retryUs, size)
    } catch (e: RuntimeException) {
        // Leitura que falhou (conexão, orçamento): a capa preta ainda é melhor que nenhuma.
        null
    } ?: return first
    first.recycle()
    return later
}

/** Pontos espalhados do quadro, em ARGB, para [isNearlyBlack]: olhar todos os pixels custaria mais que o ganho. */
private fun samplePixels(frame: Bitmap): IntArray = IntArray(BLACK_CHECK_GRID * BLACK_CHECK_GRID) { index ->
    frame.getPixel(
        (index % BLACK_CHECK_GRID) * (frame.width - 1) / (BLACK_CHECK_GRID - 1),
        (index / BLACK_CHECK_GRID) * (frame.height - 1) / (BLACK_CHECK_GRID - 1),
    )
}

/**
 * Fonte de dados do `MediaMetadataRetriever` sobre um [RangeReader]. O sistema chama [readAt] numa thread própria e
 * espera a resposta, então ela bloqueia (`runBlocking`) até o bloco chegar. Qualquer falha do leitor (rede, 401 sem
 * renovação, orçamento) vira [IOException], que o retriever trata como erro de leitura.
 *
 * [abort] e [close] cancelam o pedido em andamento e recusam as leituras seguintes: quem abriu a fonte a fecha.
 */
class RangeMediaDataSource(private val reader: RangeReader) : MediaDataSource() {
    private val job = Job()

    override fun getSize(): Long = reader.size

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (!job.isActive) throw IOException("Fonte de dados fechada")
        try {
            return runBlocking(job) { reader.readAt(position, buffer, offset, size) }
        } catch (e: Exception) {
            // Cancelamento incluído: o sistema só entende IOException.
            throw IOException("Leitura do vídeo falhou", e)
        }
    }

    /** Interrompe a leitura em andamento; as seguintes falham. */
    fun abort() = job.cancel()

    override fun close() = abort()
}
