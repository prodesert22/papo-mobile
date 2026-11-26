package chat.papo.mobile.media

/**
 * O formato de uma imagem, lido dos primeiros bytes. Não confia em `format` nem no tipo
 * MIME que o servidor informa: um APNG chega como `PNG`, e só os bytes distinguem os dois.
 */
enum class ImageKind(
    /** Pode ter mais de um quadro. Não promete que o aparelho a anima: quem decodifica confere. */
    val mayAnimate: Boolean = false,
) {
    Gif(mayAnimate = true),
    Png,
    Apng(mayAnimate = true),
    Webp,
    WebpAnimated(mayAnimate = true),
    Jpeg,
    Heif,
    Unknown,
}

/** Largura e altura em pixels. */
data class PixelSize(val width: Int, val height: Int)

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
private val JPEG_SIGNATURE = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

private const val PNG_CHUNK_HEADER_SIZE = 8 // comprimento (4) e tipo (4)
private const val PNG_CHUNK_TRAILER_SIZE = 4 // CRC
private const val WEBP_FIRST_CHUNK = 12
private const val WEBP_VP8X_FLAGS = 20
private const val WEBP_ANIMATION_FLAG = 0x02

private val HEIF_BRANDS = setOf("heic", "heix", "heim", "heis", "hevc", "hevx", "hevm", "hevs", "mif1", "msf1")

/** Descobre o formato de [bytes] só pelo cabeçalho; lê no máximo os chunks antes do primeiro `IDAT` de um PNG. */
fun sniffImage(bytes: ByteArray): ImageKind = when {
    bytes.startsWith("GIF87a") || bytes.startsWith("GIF89a") -> ImageKind.Gif
    bytes.startsWith(PNG_SIGNATURE) -> if (hasAnimationControl(bytes)) ImageKind.Apng else ImageKind.Png
    isWebp(bytes) -> if (isAnimatedWebp(bytes)) ImageKind.WebpAnimated else ImageKind.Webp
    bytes.startsWith(JPEG_SIGNATURE) -> ImageKind.Jpeg
    isHeif(bytes) -> ImageKind.Heif
    else -> ImageKind.Unknown
}

/**
 * O tamanho de uma imagem de [width] x [height] reduzida para que o lado maior caiba em [maxPx],
 * mantendo a proporção. Nunca amplia, e nenhum lado fica abaixo de 1 (nem com medidas ou limite inválidos).
 */
fun fitWithin(width: Int, height: Int, maxPx: Int): PixelSize {
    val w = width.coerceAtLeast(1)
    val h = height.coerceAtLeast(1)
    val limit = maxPx.coerceAtLeast(1)
    val longest = maxOf(w, h)
    if (longest <= limit) return PixelSize(w, h)
    // Em Long: lado * limite estoura Int com imagens de bilhões de pixels.
    fun scaled(side: Int) = ((side.toLong() * limit + longest / 2) / longest).toInt().coerceAtLeast(1)
    return PixelSize(scaled(w), scaled(h))
}

/**
 * Um PNG é animado (APNG) quando o chunk `acTL` vem antes do primeiro `IDAT`. Anda pelos chunks
 * pelo comprimento declarado; um arquivo cortado antes de `IDAT` conta como PNG comum.
 */
private fun hasAnimationControl(bytes: ByteArray): Boolean {
    var offset = PNG_SIGNATURE.size.toLong()
    while (offset + PNG_CHUNK_HEADER_SIZE <= bytes.size) {
        val at = offset.toInt()
        when (bytes.asciiAt(at + 4, 4)) {
            "acTL" -> return true
            "IDAT", "IEND" -> return false
        }
        offset += PNG_CHUNK_HEADER_SIZE + bytes.uint32At(at) + PNG_CHUNK_TRAILER_SIZE
    }
    return false
}

private fun isWebp(bytes: ByteArray): Boolean = bytes.startsWith("RIFF") && bytes.hasAscii(8, "WEBP")

private fun isHeif(bytes: ByteArray): Boolean = bytes.hasAscii(4, "ftyp") && bytes.asciiAt(8, 4) in HEIF_BRANDS

/** Um WebP animado começa por um chunk `VP8X` com o bit de animação ligado. */
private fun isAnimatedWebp(bytes: ByteArray): Boolean =
    bytes.hasAscii(WEBP_FIRST_CHUNK, "VP8X") && bytes.size > WEBP_VP8X_FLAGS &&
        bytes[WEBP_VP8X_FLAGS].toInt() and WEBP_ANIMATION_FLAG != 0

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

private fun ByteArray.startsWith(text: String): Boolean = hasAscii(0, text)

private fun ByteArray.hasAscii(offset: Int, text: String): Boolean =
    offset + text.length <= size && text.indices.all { this[offset + it].toInt() == text[it].code }

private fun ByteArray.asciiAt(offset: Int, length: Int): String =
    if (offset + length > size) "" else buildString(length) { repeat(length) { append(this@asciiAt[offset + it].toInt().toChar()) } }

/** Inteiro de 32 bits sem sinal, big-endian, como Long (o comprimento de um chunk PNG vai até 2^31 - 1). */
private fun ByteArray.uint32At(offset: Int): Long =
    (0 until 4).fold(0L) { acc, i -> (acc shl 8) or (this[offset + i].toLong() and 0xFF) }
