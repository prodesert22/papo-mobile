package chat.papo.mobile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import chat.papo.mobile.media.CropRequest
import chat.papo.mobile.media.ImageKind
import chat.papo.mobile.media.ImageReduction
import chat.papo.mobile.media.ImageRefusal
import chat.papo.mobile.media.PROFILE_IMAGE_MAX_BYTES
import chat.papo.mobile.media.ProfileImageKind
import chat.papo.mobile.media.SourceImageInfo
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
import kotlin.test.assertNull
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

    private fun reduce(source: ByteArray, kind: ProfileImageKind, crop: CropRequest? = null): ImageReduction =
        runBlocking { reducer.reduce(source, kind, crop) }

    private fun ready(source: ByteArray, kind: ProfileImageKind, crop: CropRequest? = null) =
        assertIs<ImageReduction.Ready>(reduce(source, kind, crop)).image

    private fun inspect(source: ByteArray): SourceImageInfo? = runBlocking { reducer.inspect(source) }

    private fun decode(bytes: ByteArray): Bitmap = assertNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))

    /** Os quatro cantos de uma imagem quadrada: cima à esquerda vermelho, cima à direita verde, baixo à esquerda azul, baixo à direita amarelo. */
    private fun quadrants(side: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val half = side / 2f
        Canvas(bitmap).apply {
            drawRect(0f, 0f, half, half, Paint().apply { color = Color.RED })
            drawRect(half, 0f, side.toFloat(), half, Paint().apply { color = Color.GREEN })
            drawRect(0f, half, half, side.toFloat(), Paint().apply { color = Color.BLUE })
            drawRect(half, half, side.toFloat(), side.toFloat(), Paint().apply { color = Color.YELLOW })
        }
        return bitmap
    }

    /** O pixel de [bitmap] perto do canto ou do meio pedido, longe das bordas entre as cores. */
    private fun Bitmap.corner(right: Boolean, bottom: Boolean): Int =
        getPixel(if (right) width - 1 - width / 10 else width / 10, if (bottom) height - 1 - height / 10 else height / 10)

    private fun exifJpeg(bitmap: Bitmap, orientation: Int): ByteArray {
        val file = File.createTempFile("papo-teste-recorte", ".jpg").also { scratch += it }
        file.writeBytes(encode(bitmap, Bitmap.CompressFormat.JPEG, 100))
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return file.readBytes()
    }

    private fun assertNear(expected: Int, actual: Int, tolerance: Int = 8) =
        assertTrue(kotlin.math.abs(expected - actual) <= tolerance, "esperado perto de $expected, veio $actual")

    private fun refusal(source: ByteArray, kind: ProfileImageKind, crop: CropRequest? = null) =
        assertIs<ImageReduction.Refused>(reduce(source, kind, crop)).reason

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

    // Recorte manual

    @Test
    fun recorteEscolhidoDecodificaSoARegiaoPedida() {
        // Esquerda vermelha, meio verde, direita azul; o avatar do terço da direita é só azul.
        val source = Bitmap.createBitmap(300, 100, Bitmap.Config.ARGB_8888)
        Canvas(source).apply {
            drawColor(Color.GREEN)
            drawRect(0f, 0f, 100f, 100f, Paint().apply { color = Color.RED })
            drawRect(200f, 0f, 300f, 100f, Paint().apply { color = Color.BLUE })
        }
        val png = encode(source, Bitmap.CompressFormat.PNG)

        val left = decode(ready(png, ProfileImageKind.Avatar, CropRequest(0, 0f, 0f, 1f / 3f, 1f)).bytes)
        val right = decode(ready(png, ProfileImageKind.Avatar, CropRequest(0, 2f / 3f, 0f, 1f / 3f, 1f)).bytes)

        assertEquals(100 to 100, left.width to left.height)
        assertEquals(Color.RED, left.getPixel(50, 50))
        assertEquals(Color.RED, left.getPixel(5, 5))
        assertEquals(Color.BLUE, right.getPixel(50, 50))
        assertEquals(Color.BLUE, right.getPixel(95, 95))
    }

    @Test
    fun recorteGrandeDeUmaFotoViraAvatarDe512ComASaidaDeSempre() {
        val image = ready(encode(gradient(3000, 2000), Bitmap.CompressFormat.JPEG), ProfileImageKind.Avatar, CropRequest(0, 0.1f, 0f, 2f / 3f, 1f))

        assertEquals(512 to 512, image.width to image.height)
        assertEquals(512 to 512, decodeBounds(image.bytes))
        assertEquals("image/png", image.mimeType)
        assertFalse(image.animated)
    }

    @Test
    fun recorteDeBannerViraBannerDe1536Por512() {
        val image = ready(encode(gradient(3000, 2000), Bitmap.CompressFormat.JPEG), ProfileImageKind.Banner, CropRequest(0, 0f, 0.25f, 1f, 0.5f))

        assertEquals(1536 to 512, decodeBounds(image.bytes))
    }

    @Test
    fun cadaVoltaGiraOResultadoNoSentidoHorario() {
        val png = encode(quadrants(200), Bitmap.CompressFormat.PNG)
        val whole = { turns: Int -> decode(ready(png, ProfileImageKind.Avatar, CropRequest(turns, 0f, 0f, 1f, 1f)).bytes) }

        val none = whole(0)
        assertEquals(listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW), listOf(none.corner(false, false), none.corner(true, false), none.corner(false, true), none.corner(true, true)))
        val one = whole(1)
        assertEquals(listOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN), listOf(one.corner(false, false), one.corner(true, false), one.corner(false, true), one.corner(true, true)))
        val two = whole(2)
        assertEquals(listOf(Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED), listOf(two.corner(false, false), two.corner(true, false), two.corner(false, true), two.corner(true, true)))
        val three = whole(3)
        assertEquals(listOf(Color.GREEN, Color.YELLOW, Color.RED, Color.BLUE), listOf(three.corner(false, false), three.corner(true, false), three.corner(false, true), three.corner(true, true)))
    }

    @Test
    fun aRegiaoEDaImagemJaGiradaEnaoDaOriginal() {
        // 200 x 200 girada uma volta: em cima, azul e vermelho. A metade de cima da imagem girada é só azul e vermelho.
        val png = encode(quadrants(200), Bitmap.CompressFormat.PNG)

        val top = decode(ready(png, ProfileImageKind.Banner, CropRequest(1, 0f, 0f, 1f, 1f / 3f)).bytes)

        // Um banner da faixa de cima da imagem girada: 200 x 66, azul à esquerda e vermelho à direita.
        assertEquals(198 to 66, top.width to top.height)
        assertEquals(Color.BLUE, top.getPixel(10, 33))
        assertEquals(Color.RED, top.getPixel(top.width - 10, 33))
    }

    @Test
    fun umaVoltaDeUmBannerTrocaALarguraEAAltura() {
        // Uma foto em pé de 1000 x 3000 girada vira 3000 x 1000: o banner é a imagem toda, reduzida a 1536 x 512.
        val image = ready(encode(gradient(1000, 3000), Bitmap.CompressFormat.PNG), ProfileImageKind.Banner, CropRequest(1, 0f, 0f, 1f, 1f))

        assertEquals(1536 to 512, decodeBounds(image.bytes))
    }

    @Test
    fun orientacaoDoExifSeSomaAoRecorteEAoGiro() {
        // O EXIF manda girar 90 graus no sentido horário: a imagem de 300 x 200 é vista em pé, 200 x 300, vermelha em cima e azul embaixo.
        val source = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)
        Canvas(source).apply {
            drawColor(Color.BLUE)
            drawRect(0f, 0f, 150f, 200f, Paint().apply { color = Color.RED })
        }
        val jpeg = exifJpeg(source, ExifInterface.ORIENTATION_ROTATE_90)

        // O terço de baixo e o do meio da imagem orientada (quadrado de 200 pixels): vermelho em cima, azul embaixo.
        val lower = decode(ready(jpeg, ProfileImageKind.Avatar, CropRequest(0, 0f, 1f / 3f, 1f, 2f / 3f)).bytes)
        assertEquals(200 to 200, lower.width to lower.height)
        assertTrue(Color.red(lower.getPixel(100, 5)) > 200 && Color.blue(lower.getPixel(100, 5)) < 60)
        assertTrue(Color.blue(lower.getPixel(100, 195)) > 200 && Color.red(lower.getPixel(100, 195)) < 60)

        // Com mais uma volta (a imagem orientada gira para 300 x 200), o vermelho vai para a direita.
        val turned = decode(ready(jpeg, ProfileImageKind.Avatar, CropRequest(1, 0.1f, 0f, 2f / 3f, 1f)).bytes)
        assertEquals(200 to 200, turned.width to turned.height)
        assertTrue(Color.red(turned.getPixel(195, 100)) > 200 && Color.blue(turned.getPixel(195, 100)) < 60)
        assertTrue(Color.blue(turned.getPixel(5, 100)) > 200 && Color.red(turned.getPixel(5, 100)) < 60)
    }

    @Test
    fun comZoomAltoARegiaoPequenaNaoEAmpliadaAlemDoQueTem() {
        // 100 x 100 pixels no meio de uma foto de 2000 x 2000: sai com 100 x 100 e as cores do meio do degradê.
        val image = ready(encode(gradient(2000, 2000), Bitmap.CompressFormat.PNG), ProfileImageKind.Avatar, CropRequest(0, 0.475f, 0.475f, 0.05f, 0.05f))
        val result = decode(image.bytes)

        assertEquals(100 to 100, result.width to result.height)
        assertEquals(100 to 100, image.width to image.height)
        assertNear(121, Color.red(result.getPixel(0, 50)))
        assertNear(133, Color.red(result.getPixel(99, 50)))
        assertNear(121, Color.green(result.getPixel(50, 0)))
        assertNear(133, Color.green(result.getPixel(50, 99)))
    }

    @Test
    fun aLocalizacaoDoExifNaoSaiNemComRecorte() {
        val file = File.createTempFile("papo-teste-gps-recorte", ".jpg").also { scratch += it }
        file.writeBytes(encode(gradient(600, 600), Bitmap.CompressFormat.JPEG))
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "12/1,58/1,17/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "S")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "38/1,30/1,5/1")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W")
            saveAttributes()
        }
        val source = file.readBytes()
        assertTrue(exifHasGps(source), "a amostra precisa ter localização para o teste valer")

        val image = ready(source, ProfileImageKind.Avatar, CropRequest(1, 0.25f, 0.25f, 0.5f, 0.5f))

        assertFalse(exifHasGps(image.bytes))
    }

    @Test
    fun animacaoNaoERecortadaEVaiComoEsta() {
        val image = ready(ImageSamples.animatedGif, ProfileImageKind.Avatar, CropRequest(1, 0f, 0f, 0.5f, 0.5f))

        assertTrue(image.animated)
        assertContentEquals(ImageSamples.animatedGif, image.bytes)
    }

    @Test
    fun recorteForaDaImagemOuImagemPequenaDemaisSaoRecusados() {
        val png = encode(gradient(400, 400), Bitmap.CompressFormat.PNG)

        assertEquals(ImageRefusal.NotAnImage, refusal(png, ProfileImageKind.Avatar, CropRequest(0, 0.9f, 0f, 0.5f, 0.5f)))
        assertEquals(ImageRefusal.NotAnImage, refusal(png, ProfileImageKind.Avatar, CropRequest(0, 0f, 0f, Float.NaN, 0.5f)))
        assertEquals(ImageRefusal.NotAnImage, refusal(encode(gradient(2, 100), Bitmap.CompressFormat.PNG), ProfileImageKind.Banner, CropRequest(0, 0f, 0f, 1f, 1f)))
    }

    @Test
    fun inspecionarDizOTamanhoNaOrientacaoDoExifESeEAnimacao() {
        assertEquals(SourceImageInfo(3000, 2000, animated = false), inspect(encode(gradient(3000, 2000), Bitmap.CompressFormat.JPEG)))
        assertEquals(SourceImageInfo(200, 300, animated = false), inspect(exifJpeg(gradient(300, 200), ExifInterface.ORIENTATION_ROTATE_90)))
        assertEquals(SourceImageInfo(64, 64, animated = true), inspect(ImageSamples.animatedGif))
        assertEquals(true, inspect(ImageSamples.animatedWebp)?.animated)
        assertEquals(false, inspect(ImageSamples.singleFrameGif)?.animated)
    }

    @Test
    fun inspecionarUmArquivoQueNaoEImagemDaNulo() {
        assertNull(inspect(ByteArray(2000) { (it * 31).toByte() }))
        assertNull(inspect(ByteArray(0)))
        assertNull(inspect(ImageSamples.png.copyOf(20)))
    }
}
