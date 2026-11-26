package chat.papo.mobile

import chat.papo.mobile.data.api.OutgoingFile
import chat.papo.mobile.media.EXIF_SCAN_BYTES
import chat.papo.mobile.media.carriesLocation
import chat.papo.mobile.media.exifHasGps
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Monta, à mão, os bytes de um JPEG com o EXIF que cada teste pede. Nada aqui lê o que o código de produção lê. */
private object Jpeg {
    private const val LATITUDE = 2
    private const val LONGITUDE = 4

    private fun u16(value: Int, little: Boolean) =
        if (little) byteArrayOf(value.toByte(), (value shr 8).toByte()) else byteArrayOf((value shr 8).toByte(), value.toByte())

    private fun u32(value: Int, little: Boolean) =
        if (little) {
            byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())
        } else {
            byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())
        }

    private fun entry(tag: Int, value: Int, little: Boolean) = u16(tag, little) + u16(4, little) + u32(1, little) + u32(value, little)

    /** O bloco TIFF: IFD0 só com o fabricante e, se [gpsTags] não é null, o ponteiro para um IFD de GPS com essas etiquetas. */
    fun tiff(little: Boolean, gpsTags: List<Int>?, gpsOffset: Int? = null, magic: Int = 42): ByteArray {
        val header = (if (little) "II".encodeToByteArray() else "MM".encodeToByteArray()) + u16(magic, little) + u32(8, little)
        val ifd0Entries = if (gpsTags == null) 1 else 2
        val gpsAt = 8 + 2 + ifd0Entries * 12 + 4
        var ifd0 = u16(ifd0Entries, little) + entry(0x010F, 0, little)
        if (gpsTags != null) ifd0 += entry(0x8825, gpsOffset ?: gpsAt, little)
        ifd0 += u32(0, little)
        val gps = if (gpsTags == null) {
            ByteArray(0)
        } else {
            gpsTags.fold(u16(gpsTags.size, little)) { bytes, tag -> bytes + entry(tag, 0, little) } + u32(0, little)
        }
        return header + ifd0 + gps
    }

    fun segment(marker: Int, payload: ByteArray) = byteArrayOf(0xFF.toByte(), marker.toByte()) + u16(payload.size + 2, false) + payload

    fun exif(tiff: ByteArray) = segment(0xE1, "Exif".encodeToByteArray() + byteArrayOf(0, 0) + tiff)

    fun xmp() = segment(0xE1, "http://ns.adobe.com/xap/1.0/".encodeToByteArray() + byteArrayOf(0) + "<x/>".encodeToByteArray())

    fun jfif() = segment(0xE0, "JFIF".encodeToByteArray() + byteArrayOf(0, 1, 1, 0, 0, 1, 0, 1, 0, 0))

    /** SOS e o fim da imagem: depois de `SOS` não há mais cabeçalhos. */
    val scan = segment(0xDA, byteArrayOf(0, 1, 2, 3)) + byteArrayOf(0x12, 0x34, 0xFF.toByte(), 0xD9.toByte())

    val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())

    fun file(vararg parts: ByteArray) = parts.fold(soi) { all, part -> all + part } + scan

    val withLocation = listOf(1, LATITUDE, 3, LONGITUDE)
}

class ExifHasGpsTest {
    @Test
    fun jpegComLatitudeELongitudeTemLocalizacaoNosDoisSentidosDeByte() {
        for (little in listOf(true, false)) {
            val jpeg = Jpeg.file(Jpeg.exif(Jpeg.tiff(little, Jpeg.withLocation)))
            assertTrue(exifHasGps(jpeg), "little=$little")
        }
    }

    @Test
    fun jpegSemBlocoDeGpsNaoTem() {
        for (little in listOf(true, false)) assertFalse(exifHasGps(Jpeg.file(Jpeg.exif(Jpeg.tiff(little, null)))), "little=$little")
    }

    @Test
    fun blocoDeGpsSemLatitudeELongitudeJuntasNaoContaComoLocalizacao() {
        assertFalse(exifHasGps(Jpeg.file(Jpeg.exif(Jpeg.tiff(true, listOf(0)))))) // só a versão
        assertFalse(exifHasGps(Jpeg.file(Jpeg.exif(Jpeg.tiff(true, listOf(1, 2)))))) // só a latitude
        assertFalse(exifHasGps(Jpeg.file(Jpeg.exif(Jpeg.tiff(false, listOf(3, 4)))))) // só a longitude
        assertFalse(exifHasGps(Jpeg.file(Jpeg.exif(Jpeg.tiff(false, emptyList()))))) // diretório vazio
    }

    @Test
    fun oExifPodeVirDepoisDeOutrosSegmentos() {
        val exif = Jpeg.exif(Jpeg.tiff(true, Jpeg.withLocation))
        assertTrue(exifHasGps(Jpeg.file(Jpeg.jfif(), exif)))
        // O XMP também é um APP1, mas não é o EXIF.
        assertTrue(exifHasGps(Jpeg.file(Jpeg.xmp(), exif)))
        assertFalse(exifHasGps(Jpeg.file(Jpeg.jfif(), Jpeg.xmp())))
    }

    @Test
    fun bytesDePreenchimentoAntesDoMarcadorNaoAtrapalham() {
        val padded = byteArrayOf(0xFF.toByte(), 0xFF.toByte()) + Jpeg.exif(Jpeg.tiff(true, Jpeg.withLocation)).drop(1).toByteArray()
        assertTrue(exifHasGps(Jpeg.soi + padded + Jpeg.scan))
    }

    @Test
    fun marcadorSemTamanhoNoMeioNaoQuebraAProcura() {
        val restart = byteArrayOf(0xFF.toByte(), 0xD0.toByte())
        assertTrue(exifHasGps(Jpeg.soi + restart + Jpeg.exif(Jpeg.tiff(true, Jpeg.withLocation)) + Jpeg.scan))
    }

    @Test
    fun oExifDepoisDosDadosDaImagemNaoValeEOFimDosDadosTambemPara() {
        val exif = Jpeg.exif(Jpeg.tiff(true, Jpeg.withLocation))
        assertFalse(exifHasGps(Jpeg.soi + Jpeg.scan + exif))
        assertFalse(exifHasGps(Jpeg.soi + byteArrayOf(0xFF.toByte(), 0xD9.toByte()) + exif))
    }

    @Test
    fun quemNaoEJpegOuNemTemTamanhoParaSerUmNaoTem() {
        assertFalse(exifHasGps(ByteArray(0)))
        assertFalse(exifHasGps(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))) // curto demais
        assertFalse(exifHasGps("GIF89a".encodeToByteArray() + ByteArray(40)))
        assertFalse(exifHasGps(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(40)))
        assertFalse(exifHasGps(Jpeg.soi + byteArrayOf(0, 0, 0, 0, 0))) // depois do SOI não vem um marcador
    }

    @Test
    fun segmentoComTamanhoImpossivelEDadoCortadoNaoTemLocalizacaoNemDaErro() {
        val whole = Jpeg.file(Jpeg.exif(Jpeg.tiff(true, Jpeg.withLocation)))
        assertTrue(exifHasGps(whole))
        // Cortar o arquivo em qualquer ponto antes do fim do bloco de GPS nunca lança.
        for (size in 0 until whole.size) exifHasGps(whole.copyOf(size))
        assertFalse(exifHasGps(whole.copyOf(40)))
        // Tamanho de segmento menor que os dois bytes do próprio tamanho.
        assertFalse(exifHasGps(Jpeg.soi + byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0, 1) + ByteArray(30)))
    }

    @Test
    fun cabecalhoTiffInvalidoOuPonteirosForaDoSegmentoNaoTemLocalizacao() {
        assertFalse(exifHasGps(Jpeg.file(Jpeg.exif(Jpeg.tiff(true, Jpeg.withLocation, magic = 43)))))
        assertFalse(exifHasGps(Jpeg.file(Jpeg.exif(Jpeg.tiff(true, Jpeg.withLocation, gpsOffset = 100_000)))))
        assertFalse(exifHasGps(Jpeg.file(Jpeg.exif(Jpeg.tiff(false, Jpeg.withLocation, gpsOffset = -1)))))
        val notTiff = Jpeg.exif("XX".encodeToByteArray() + ByteArray(30))
        assertFalse(exifHasGps(Jpeg.file(notTiff)))
    }

    @Test
    fun lixoDepoisDoCabecalhoDeJpegNuncaLanca() {
        val random = Random(7)
        repeat(300) {
            val noise = Jpeg.soi + byteArrayOf(0xFF.toByte(), 0xE1.toByte()) + random.nextBytes(random.nextInt(0, 200))
            exifHasGps(noise)
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CarriesLocationTest {
    private val located = Jpeg.file(Jpeg.exif(Jpeg.tiff(true, Jpeg.withLocation)))
    private val plain = Jpeg.file(Jpeg.exif(Jpeg.tiff(true, null)))

    @Test
    fun jpegComLocalizacaoAvisaESemElaNao() = runTest {
        assertTrue(bytesFile("a.jpg", "image/jpeg", located).carriesLocation())
        assertFalse(bytesFile("b.jpg", "image/JPEG", plain).carriesLocation())
    }

    @Test
    fun soJpegEConferidoEOArquivoNemEAbertoNosOutrosTipos() = runTest {
        var opened = 0
        val png = OutgoingFile("a.png", "image/png", 10, open = { opened++; ByteReadChannel(located) })
        assertFalse(png.carriesLocation())
        val gone = OutgoingFile("a.jpg", "image/jpeg", 10, open = { opened++; ByteReadChannel(located) }, unavailable = true)
        assertFalse(gone.carriesLocation())
        assertEquals(0, opened)
    }

    @Test
    fun arquivoQueNaoAbreOuFalhaNaLeituraNaoAvisaENaoLanca() = runTest {
        val fails = OutgoingFile("a.jpg", "image/jpeg", 10, open = { error("provedor fora do ar") })
        assertFalse(fails.carriesLocation())
        val broken = OutgoingFile("b.jpg", "image/jpeg", 10, open = { ByteChannel().also { it.cancel(IllegalStateException("cortou")) } })
        assertFalse(broken.carriesLocation())
    }

    @Test
    fun fechaOFluxoDepoisDeLerSemPrecisarDoRestoDoArquivo() = runTest {
        // Mais bytes do que a checagem lê: o fluxo só termina porque ela o cancela.
        val channel = ByteReadChannel(located + ByteArray(EXIF_SCAN_BYTES.toInt() + 10_000))
        assertTrue(OutgoingFile("a.jpg", "image/jpeg", null, open = { channel }).carriesLocation())
        assertTrue(channel.closedCause != null) // só `cancel` fecha um canal que ainda tem bytes
    }

    @Test
    fun cancelarAChecagemRelancaOCancelamentoEFechaOFluxo() = runTest {
        val silent = ByteChannel() // nunca recebe um byte: a leitura espera
        var cancelled = false
        var finished = false
        val job = launch {
            try {
                OutgoingFile("a.jpg", "image/jpeg", null, open = { silent }).carriesLocation()
                finished = true
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(cancelled)
        assertFalse(finished)
        assertTrue(silent.isClosedForRead)
    }
}
