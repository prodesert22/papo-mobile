package chat.papo.mobile.media

import chat.papo.mobile.data.api.OutgoingFile
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray

/** Quantos bytes do começo de um JPEG bastam para achar o EXIF: ele fica num segmento de até 64 KiB, antes dos dados da imagem. */
const val EXIF_SCAN_BYTES = 256L * 1024

private const val GPS_INFO_POINTER = 0x8825
private const val GPS_LATITUDE = 2
private const val GPS_LONGITUDE = 4
private const val TIFF_HEADER = 8
private const val TIFF_MAGIC = 42
private const val IFD_ENTRY = 12L
private val EXIF_HEADER = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)

/**
 * O JPEG em [bytes] (o começo dele basta, ver [EXIF_SCAN_BYTES]) guarda a posição de quem o tirou? Procura, no
 * EXIF, um bloco de GPS com latitude e longitude; um bloco só com a versão, como alguns aparelhos deixam com a
 * localização desligada, não conta. Só olha JPEG: HEIC, PNG e WebP seguem sem a checagem. Dado ilegível ou
 * cortado dá false, nunca erro.
 */
fun exifHasGps(bytes: ByteArray): Boolean {
    val tiff = findExif(bytes) ?: return false
    return tiffHasGps(bytes, tiff.base, tiff.end)
}

/**
 * Este arquivo é um JPEG com a localização de quem o tirou? Lê só o começo dele, fecha o fluxo e devolve false se não
 * consegue ler: o aviso é um auxílio, e um arquivo que nem abre é problema do envio, não da prévia. Faz leitura
 * bloqueante do provedor: chame fora da thread principal.
 */
suspend fun OutgoingFile.carriesLocation(): Boolean {
    if (unavailable || !mimeType.equals("image/jpeg", ignoreCase = true)) return false
    val channel = try {
        open()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return false
    }
    return try {
        exifHasGps(channel.readBuffer(EXIF_SCAN_BYTES).readByteArray())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    } finally {
        channel.cancel()
    }
}

private fun ByteArray.u8(index: Int) = this[index].toInt() and 0xFF

/** Onde está o bloco TIFF do EXIF dentro do arquivo: de [base] até [end] (exclusivo). */
private class TiffSpan(val base: Int, val end: Int)

/** Um segmento de cabeçalho do JPEG: o marcador, onde acaba o que cabe nos bytes lidos e onde começa o segmento seguinte. */
private class Segment(val marker: Int, val dataStart: Int, val end: Int, val next: Int)

private fun hasJpegSignature(bytes: ByteArray) = bytes.size >= 4 && bytes.u8(0) == 0xFF && bytes.u8(1) == 0xD8

/** Marcadores que não têm tamanho nem dados: reinício, TEM e o próprio início da imagem. */
private fun isStandalone(marker: Int) = marker == 0x01 || marker in 0xD0..0xD8

/** Começam os dados da imagem (SOS) ou ela acaba (EOI): o EXIF, se houvesse, teria vindo antes. */
private fun endsHeaders(marker: Int) = marker == 0xDA || marker == 0xD9

/** O primeiro APP1 que começa com `Exif`; o XMP, que também mora num APP1, não conta. Null se o JPEG não o tem ou está ilegível. */
private fun findExif(bytes: ByteArray): TiffSpan? {
    if (!hasJpegSignature(bytes)) return null
    var at = 2
    while (true) {
        val segment = segmentAt(bytes, at) ?: return null
        if (segment.marker == 0xE1 && startsWithExif(bytes, segment.dataStart, segment.end)) {
            return TiffSpan(segment.dataStart + EXIF_HEADER.size, segment.end)
        }
        at = segment.next
    }
}

/** O segmento que começa em [at]; null quando os cabeçalhos acabam, o tamanho é impossível ou os bytes lidos terminam. */
private fun segmentAt(bytes: ByteArray, at: Int): Segment? {
    if (at + 4 > bytes.size || bytes.u8(at) != 0xFF) return null
    val marker = bytes.u8(at + 1)
    return when {
        marker == 0xFF -> Segment(marker, at + 1, at + 1, at + 1) // byte de preenchimento antes do marcador
        isStandalone(marker) -> Segment(marker, at + 2, at + 2, at + 2)
        endsHeaders(marker) -> null
        else -> {
            val length = (bytes.u8(at + 2) shl 8) or bytes.u8(at + 3) // inclui os dois bytes do próprio tamanho
            if (length < 2) null else Segment(marker, at + 4, minOf(at + 2 + length, bytes.size), at + 2 + length)
        }
    }
}

private fun startsWithExif(bytes: ByteArray, from: Int, end: Int): Boolean =
    end - from >= EXIF_HEADER.size && EXIF_HEADER.indices.all { bytes[from + it] == EXIF_HEADER[it] }

private fun tiffHasGps(bytes: ByteArray, base: Int, end: Int): Boolean {
    if (end - base < TIFF_HEADER) return false
    val little = when {
        bytes[base] == 'I'.code.toByte() && bytes[base + 1] == 'I'.code.toByte() -> true
        bytes[base] == 'M'.code.toByte() && bytes[base + 1] == 'M'.code.toByte() -> false
        else -> return false
    }
    val tiff = Tiff(bytes, base, end, little)
    if (tiff.u16(2) != TIFF_MAGIC) return false
    val gpsIfd = tiff.tags(tiff.u32(4))[GPS_INFO_POINTER] ?: return false
    val gps = tiff.tags(gpsIfd)
    return GPS_LATITUDE in gps && GPS_LONGITUDE in gps
}

/** Leitor do bloco TIFF do EXIF, que mede cada leitura contra o fim do segmento. Fora dele, as leituras dão -1. */
private class Tiff(private val bytes: ByteArray, private val base: Int, private val end: Int, private val little: Boolean) {
    fun u16(offset: Long): Int {
        val at = at(offset, 2) ?: return -1
        val first = bytes.u8(at)
        val second = bytes.u8(at + 1)
        return if (little) (second shl 8) or first else (first shl 8) or second
    }

    fun u32(offset: Long): Long {
        val at = at(offset, 4) ?: return -1
        var value = 0L
        for (i in 0 until 4) {
            val byte = bytes.u8(if (little) at + 3 - i else at + i).toLong()
            value = (value shl 8) or byte
        }
        return value
    }

    /** As etiquetas de um diretório (IFD) com o campo de valor de cada uma; vazio se o diretório não cabe no segmento. */
    fun tags(ifd: Long): Map<Int, Long> {
        val count = u16(ifd)
        val found = HashMap<Int, Long>()
        for (index in 0 until maxOf(count, 0)) {
            val entry = ifd + 2 + index * IFD_ENTRY
            val tag = u16(entry)
            val value = u32(entry + 8)
            if (tag < 0 || value < 0) break
            found[tag] = value
        }
        return found
    }

    private fun at(offset: Long, size: Int): Int? =
        if (offset < 0 || base + offset + size > end) null else (base + offset).toInt()
}
