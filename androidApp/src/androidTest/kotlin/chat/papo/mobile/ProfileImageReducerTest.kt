package chat.papo.mobile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import chat.papo.mobile.media.ImageKind
import chat.papo.mobile.media.ImageReduction
import chat.papo.mobile.media.ImageRefusal
import chat.papo.mobile.media.PROFILE_IMAGE_MAX_BYTES
import chat.papo.mobile.media.ProfileImageKind
import chat.papo.mobile.media.exifHasGps
import chat.papo.mobile.media.sniffImage
import chat.papo.mobile.platform.AndroidPlatformUi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A redução das imagens do perfil no Android de verdade: o `ImageDecoder` e o codificador do sistema não rodam nos testes
 * da JVM. Usa só imagens geradas aqui e a pasta de cache do APK de teste; nunca toca no app instalado.
 */
@RunWith(AndroidJUnit4::class)
class ProfileImageReducerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val reducer = AndroidPlatformUi(instrumentation.targetContext).imageReducer
    private val scratch = mutableListOf<File>()

    @After
    fun cleanUp() = scratch.forEach(File::delete)

    private fun reduce(source: ByteArray, kind: ProfileImageKind): ImageReduction = runBlocking { reducer.reduce(source, kind) }

    private fun ready(source: ByteArray, kind: ProfileImageKind) = assertIs<ImageReduction.Ready>(reduce(source, kind)).image

    private fun refusal(source: ByteArray, kind: ProfileImageKind) = assertIs<ImageReduction.Refused>(reduce(source, kind)).reason

    private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int = 95): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(format, quality, it) }.toByteArray()

    /** Uma imagem de [width] x [height] com um degradê, para a redução ter o que reduzir. */
    private fun gradient(width: Int, height: Int): Bitmap {
        val pixels = IntArray(width * height) { i -> Color.rgb((i % width) * 255 / width, (i / width) * 255 / height, 128) }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun noise(width: Int, height: Int): Bitmap {
        val random = Random(7)
        val pixels = IntArray(width * height) { Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun decodeBounds(bytes: ByteArray): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return options.outWidth to options.outHeight
    }

    @Test
    fun fotoGrandeViraAvatarDe512PorFiguraAbaixoDe2MiB() {
        val image = ready(encode(gradient(3000, 2000), Bitmap.CompressFormat.JPEG), ProfileImageKind.Avatar)

        assertEquals(512 to 512, image.width to image.height)
        assertEquals(512 to 512, decodeBounds(image.bytes))
        assertTrue(image.bytes.size <= PROFILE_IMAGE_MAX_BYTES)
        assertFalse(image.animated)
        assertEquals("image/png", image.mimeType)
        assertEquals(ImageKind.Png, sniffImage(image.bytes))
    }

    @Test
    fun fotoGrandeViraBannerDe1536Por512() {
        val image = ready(encode(gradient(3000, 2000), Bitmap.CompressFormat.JPEG), ProfileImageKind.Banner)

        assertEquals(1536 to 512, image.width to image.height)
        assertEquals(1536 to 512, decodeBounds(image.bytes))
    }

    @Test
    fun imagemPequenaNaoEAmpliadaEOAvatarFicaQuadrado() {
        val image = ready(encode(gradient(100, 80), Bitmap.CompressFormat.PNG), ProfileImageKind.Avatar)

        assertEquals(80 to 80, decodeBounds(image.bytes))
    }

    @Test
    fun oRecorteEOCentroDaImagem() {
        // Esquerda vermelha, meio verde, direita azul: o avatar quadrado de uma imagem larga mostra só o meio.
        val source = Bitmap.createBitmap(300, 100, Bitmap.Config.ARGB_8888)
        Canvas(source).apply {
            drawColor(Color.GREEN)
            drawRect(0f, 0f, 100f, 100f, Paint().apply { color = Color.RED })
            drawRect(200f, 0f, 300f, 100f, Paint().apply { color = Color.BLUE })
        }

        val image = ready(encode(source, Bitmap.CompressFormat.PNG), ProfileImageKind.Avatar)
        val result = BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)

        assertEquals(100 to 100, result.width to result.height)
        assertEquals(Color.GREEN, result.getPixel(2, 50))
        assertEquals(Color.GREEN, result.getPixel(97, 50))
    }

    @Test
    fun aRotacaoDoExifEAplicadaAntesDoRecorte() {
        // Paisagem 300x200 com a metade esquerda vermelha e a direita azul; o EXIF manda girar 90 graus no sentido horário,
        // então o que se vê é uma imagem em pé, vermelha em cima e azul embaixo.
        val source = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)
        Canvas(source).apply {
            drawColor(Color.BLUE)
            drawRect(0f, 0f, 150f, 200f, Paint().apply { color = Color.RED })
        }
        val file = File.createTempFile("papo-teste-rotacao", ".jpg").also { scratch += it }
        file.writeBytes(encode(source, Bitmap.CompressFormat.JPEG, 100))
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }

        val image = ready(file.readBytes(), ProfileImageKind.Avatar)
        val result = BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)

        assertEquals(200 to 200, result.width to result.height)
        assertTrue(Color.red(result.getPixel(100, 10)) > 200 && Color.blue(result.getPixel(100, 10)) < 60)
        assertTrue(Color.blue(result.getPixel(100, 190)) > 200 && Color.red(result.getPixel(100, 190)) < 60)
    }

    @Test
    fun alocalizacaoDoExifNaoSai() {
        val file = File.createTempFile("papo-teste-gps", ".jpg").also { scratch += it }
        file.writeBytes(encode(gradient(400, 400), Bitmap.CompressFormat.JPEG))
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "12/1,58/1,17/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "S")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "38/1,30/1,5/1")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W")
            saveAttributes()
        }
        val source = file.readBytes()
        assertTrue(exifHasGps(source), "a amostra precisa ter localização para o teste valer")

        val image = ready(source, ProfileImageKind.Avatar)

        assertFalse(exifHasGps(image.bytes))
        assertFalse(String(image.bytes, Charsets.ISO_8859_1).contains("GPS"))
        assertFalse(String(image.bytes, Charsets.ISO_8859_1).contains("Exif"))
    }

    @Test
    fun pngQuePassaDe2MiBViraWebpComPerdaDentroDoLimite() {
        val source = encode(noise(1536, 512), Bitmap.CompressFormat.PNG)
        assertTrue(source.size > PROFILE_IMAGE_MAX_BYTES, "o ruído precisa dar um PNG grande para o teste valer")

        val image = ready(source, ProfileImageKind.Banner)

        assertEquals("image/webp", image.mimeType)
        assertEquals(ImageKind.Webp, sniffImage(image.bytes))
        assertTrue(image.bytes.size <= PROFILE_IMAGE_MAX_BYTES)
        assertEquals(1536 to 512, decodeBounds(image.bytes))
    }

    @Test
    fun animacaoDentroDosLimitesVaiComoEsta() {
        val image = ready(ImageSamples.animatedGif, ProfileImageKind.Avatar)

        assertTrue(image.animated)
        assertEquals("image/gif", image.mimeType)
        assertEquals(64 to 64, image.width to image.height)
        assertContentEquals(ImageSamples.animatedGif, image.bytes)
    }

    @Test
    fun webpAnimadoDentroDosLimitesTambemVaiComoEsta() {
        val webp = ready(ImageSamples.animatedWebp, ProfileImageKind.Avatar)

        assertTrue(webp.animated)
        assertEquals("image/webp", webp.mimeType)
        assertContentEquals(ImageSamples.animatedWebp, webp.bytes)
    }

    @Test
    fun animacaoMaiorQueOLimiteDoTipoERecusada() {
        // O mesmo GIF de 3 quadros com a tela lógica declarada em 600 x 600 (bytes 6 a 9 do cabeçalho).
        val big = ImageSamples.animatedGif.copyOf().also {
            it[6] = (600 and 0xFF).toByte()
            it[7] = (600 shr 8).toByte()
            it[8] = (600 and 0xFF).toByte()
            it[9] = (600 shr 8).toByte()
        }

        assertEquals(ImageRefusal.AnimationTooLarge, refusal(big, ProfileImageKind.Avatar))
        // No banner o limite é 2048: a mesma animação cabe.
        assertTrue(ready(big, ProfileImageKind.Banner).animated)
    }

    @Test
    fun gifDeUmQuadroSoSeguePeloCaminhoDasImagensParadas() {
        val image = ready(ImageSamples.singleFrameGif, ProfileImageKind.Avatar)

        assertFalse(image.animated)
        assertEquals("image/png", image.mimeType)
        assertNotNull(BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size))
    }

    @Test
    fun arquivoQueNaoEImagemEImagemPequenaDemaisSaoRecusados() {
        assertEquals(ImageRefusal.NotAnImage, refusal(ByteArray(2000) { (it * 31).toByte() }, ProfileImageKind.Avatar))
        assertEquals(ImageRefusal.NotAnImage, refusal(ByteArray(0), ProfileImageKind.Avatar))
        // Cortado no meio do cabeçalho do PNG.
        assertEquals(ImageRefusal.NotAnImage, refusal(ImageSamples.png.copyOf(20), ProfileImageKind.Avatar))
        // Dois pixels de largura não dão um banner 3:1.
        assertEquals(ImageRefusal.NotAnImage, refusal(encode(gradient(2, 100), Bitmap.CompressFormat.PNG), ProfileImageKind.Banner))
    }
}
