package chat.papo.mobile

import chat.papo.mobile.media.ImageKind
import chat.papo.mobile.media.PixelSize
import chat.papo.mobile.media.fitWithin
import chat.papo.mobile.media.sniffImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageFormatTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun ascii(text: String) = text.encodeToByteArray()

    private fun u32(value: Long) = bytes((value shr 24).toInt() and 0xFF, (value shr 16).toInt() and 0xFF, (value shr 8).toInt() and 0xFF, value.toInt() and 0xFF)

    private fun u32le(value: Int) = bytes(value and 0xFF, (value shr 8) and 0xFF, (value shr 16) and 0xFF, (value shr 24) and 0xFF)

    private val pngSignature = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** Um chunk PNG: o CRC fica zerado, porque ler o formato não o confere. */
    private fun chunk(type: String, size: Int = 0) = u32(size.toLong()) + ascii(type) + ByteArray(size) + ByteArray(4)

    private fun png(vararg chunks: ByteArray) = chunks.fold(pngSignature) { acc, chunk -> acc + chunk }

    /** Um WebP: RIFF, o tamanho, WEBP e os chunks dados (o primeiro decide se é simples ou estendido). */
    private fun webp(vararg chunks: ByteArray): ByteArray {
        val body = chunks.fold(ascii("WEBP")) { acc, chunk -> acc + chunk }
        return ascii("RIFF") + u32le(body.size) + body
    }

    private fun vp8x(flags: Int) = ascii("VP8X") + u32le(10) + bytes(flags, 0, 0, 0) + ByteArray(6)

    // sniffImage

    @Test
    fun reconheceGifNasDuasVersoes() {
        assertEquals(ImageKind.Gif, sniffImage(ascii("GIF89a") + ByteArray(10)))
        assertEquals(ImageKind.Gif, sniffImage(ascii("GIF87a") + ByteArray(10)))
    }

    @Test
    fun gifIncompletoOuDeOutraVersaoNaoEGif() {
        assertEquals(ImageKind.Unknown, sniffImage(ascii("GIF89")))
        assertEquals(ImageKind.Unknown, sniffImage(ascii("GIF88a")))
    }

    @Test
    fun pngSemChunkDeAnimacaoEPng() {
        assertEquals(ImageKind.Png, sniffImage(png(chunk("IHDR", 13), chunk("IDAT", 20), chunk("IEND"))))
    }

    @Test
    fun pngComAcTLAntesDoIdatEApng() {
        assertEquals(ImageKind.Apng, sniffImage(png(chunk("IHDR", 13), chunk("acTL", 8), chunk("fcTL", 26), chunk("IDAT", 20), chunk("IEND"))))
    }

    @Test
    fun acTLDepoisDoIdatNaoContaPorqueAEspecificacaoManda() {
        assertEquals(ImageKind.Png, sniffImage(png(chunk("IHDR", 13), chunk("IDAT", 20), chunk("acTL", 8), chunk("IEND"))))
    }

    @Test
    fun chunksAntesDeAcTLNaoImpedemAReconhecerOApng() {
        val apng = png(chunk("IHDR", 13), chunk("pHYs", 9), chunk("tEXt", 30), chunk("acTL", 8), chunk("IDAT", 4))
        assertEquals(ImageKind.Apng, sniffImage(apng))
    }

    @Test
    fun pngSoComAAssinaturaOuCortadoAntesDoIdatEPng() {
        assertEquals(ImageKind.Png, sniffImage(pngSignature))
        assertEquals(ImageKind.Png, sniffImage(png(chunk("IHDR", 13)).copyOf(pngSignature.size + 12)))
    }

    @Test
    fun pngSemIdatEComIendEPng() {
        assertEquals(ImageKind.Png, sniffImage(png(chunk("IHDR", 13), chunk("IEND"))))
    }

    @Test
    fun comprimentoDeChunkAlemDoArquivoNaoTravaNemEstoura() {
        val huge = u32(0xFFFFFFFFL) + ascii("tEXt") + ByteArray(4)
        assertEquals(ImageKind.Png, sniffImage(pngSignature + huge))
        assertEquals(ImageKind.Png, sniffImage(pngSignature + chunk("IHDR", 13) + huge + chunk("acTL", 8)))
    }

    @Test
    fun webpSimplesNaoAnima() {
        assertEquals(ImageKind.Webp, sniffImage(webp(ascii("VP8 ") + u32le(4) + ByteArray(4))))
        assertEquals(ImageKind.Webp, sniffImage(webp(ascii("VP8L") + u32le(4) + ByteArray(4))))
    }

    @Test
    fun webpEstendidoSemOBitDeAnimacaoNaoAnima() {
        // Só o bit de alfa (0x10), mais EXIF e XMP: nenhum deles é a animação (0x02).
        assertEquals(ImageKind.Webp, sniffImage(webp(vp8x(0x10 or 0x08 or 0x04))))
    }

    @Test
    fun webpEstendidoComOBitDeAnimacaoAnima() {
        assertEquals(ImageKind.WebpAnimated, sniffImage(webp(vp8x(0x02))))
        assertEquals(ImageKind.WebpAnimated, sniffImage(webp(vp8x(0x12))))
    }

    @Test
    fun webpEstendidoCortadoAntesDasFlagsNaoAnima() {
        assertEquals(ImageKind.Webp, sniffImage(webp(vp8x(0x02)).copyOf(20)))
    }

    @Test
    fun riffQueNaoEWebpNaoEImagem() {
        assertEquals(ImageKind.Unknown, sniffImage(ascii("RIFF") + u32le(4) + ascii("WAVE")))
        assertEquals(ImageKind.Unknown, sniffImage(ascii("RIFF") + u32le(4)))
    }

    @Test
    fun reconheceJpeg() {
        assertEquals(ImageKind.Jpeg, sniffImage(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10)))
        assertEquals(ImageKind.Unknown, sniffImage(bytes(0xFF, 0xD8)))
    }

    @Test
    fun reconheceHeifPelaMarcaEDeixaAvifDeFora() {
        fun ftyp(brand: String) = u32(24) + ascii("ftyp") + ascii(brand) + ByteArray(12)
        assertEquals(ImageKind.Heif, sniffImage(ftyp("heic")))
        assertEquals(ImageKind.Heif, sniffImage(ftyp("mif1")))
        assertEquals(ImageKind.Unknown, sniffImage(ftyp("avif")))
        assertEquals(ImageKind.Unknown, sniffImage(ftyp("mp42")))
    }

    @Test
    fun ftypCortadoNaoTemMarcaENaoEImagem() {
        assertEquals(ImageKind.Unknown, sniffImage(u32(24) + ascii("ftyp")))
        assertEquals(ImageKind.Unknown, sniffImage(u32(24) + ascii("ftyphe")))
    }

    @Test
    fun vazioELixoNaoSaoImagem() {
        assertEquals(ImageKind.Unknown, sniffImage(ByteArray(0)))
        assertEquals(ImageKind.Unknown, sniffImage(ascii("<svg xmlns=\"http://www.w3.org/2000/svg\"/>")))
        assertEquals(ImageKind.Unknown, sniffImage(ByteArray(64) { it.toByte() }))
    }

    @Test
    fun soGifApngEWebpAnimadoPodemAnimar() {
        val animating = ImageKind.entries.filter { it.mayAnimate }
        assertEquals(listOf(ImageKind.Gif, ImageKind.Apng, ImageKind.WebpAnimated), animating)
        assertFalse(ImageKind.Png.mayAnimate)
        assertTrue(ImageKind.Gif.mayAnimate)
    }

    // fitWithin

    @Test
    fun imagemMenorQueOAlvoNaoEAmpliada() {
        assertEquals(PixelSize(32, 24), fitWithin(32, 24, 128))
        assertEquals(PixelSize(128, 128), fitWithin(128, 128, 128))
    }

    @Test
    fun paisagemReduzOLadoMaiorAteOLimite() {
        assertEquals(PixelSize(128, 64), fitWithin(512, 256, 128))
    }

    @Test
    fun retratoReduzOLadoMaiorAteOLimite() {
        assertEquals(PixelSize(64, 128), fitWithin(256, 512, 128))
    }

    @Test
    fun proporcaoQueNaoDivideArredondaAoMaisProximo() {
        assertEquals(PixelSize(96, 58), fitWithin(500, 300, 96)) // 57,6
        assertEquals(PixelSize(96, 55), fitWithin(500, 287, 96)) // 55,1
    }

    @Test
    fun nenhumLadoFicaAbaixoDeUm() {
        assertEquals(PixelSize(100, 1), fitWithin(1000, 2, 100))
        assertEquals(PixelSize(1, 100), fitWithin(2, 1000, 100))
    }

    @Test
    fun medidasEmBrancoOuNegativasViramUm() {
        assertEquals(PixelSize(1, 1), fitWithin(0, 0, 128))
        assertEquals(PixelSize(1, 50), fitWithin(0, 50, 128))
        assertEquals(PixelSize(1, 1), fitWithin(-5, -5, 128))
    }

    @Test
    fun limiteInvalidoViraUm() {
        assertEquals(PixelSize(1, 1), fitWithin(512, 256, 0))
        assertEquals(PixelSize(1, 1), fitWithin(512, 512, -3))
    }

    @Test
    fun medidasGigantesNaoEstouram() {
        assertEquals(PixelSize(128, 128), fitWithin(Int.MAX_VALUE, Int.MAX_VALUE, 128))
    }
}
