package chat.papo.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Log
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import chat.papo.mobile.platform.AndroidPlatformUi
import chat.papo.mobile.ui.components.PlatformImage
import com.google.accompanist.drawablepainter.DrawablePainter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A decodificação de imagens no Android de verdade: o `ImageDecoder` do sistema não roda nos testes da JVM.
 * Usa as imagens de [ImageSamples] e nunca toca no app instalado.
 */
@RunWith(AndroidJUnit4::class)
class ImageDecodingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val ui = AndroidPlatformUi(context)
    private val opened = mutableListOf<PlatformImage>()

    // A escala de animação do sistema como estava antes do teste (os emuladores costumam deixá-la em 0).
    private var savedScale: String? = null
    private var scaleChanged = false

    /** Cada teste começa com as animações ligadas, seja qual for a configuração do aparelho. */
    @Before
    fun animationsOn() = setAnimationScale("1")

    @After
    fun cleanUp() {
        opened.forEach(PlatformImage::close)
        if (scaleChanged) {
            shell(savedScale?.let { "settings put global animator_duration_scale $it" } ?: "settings delete global animator_duration_scale")
        }
    }

    private fun decode(bytes: ByteArray, maxPx: Int = 128, animate: Boolean = true): PlatformImage? = runBlocking {
        ui.decodeImage(bytes, maxPx, animate)
    }?.also { opened += it }

    private fun decoded(bytes: ByteArray, maxPx: Int = 128, animate: Boolean = true): PlatformImage =
        assertNotNull(decode(bytes, maxPx, animate), "a imagem deveria decodificar")

    private fun shell(command: String) {
        val output = instrumentation.uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() } // espera o comando terminar
    }

    private fun setAnimationScale(value: String) {
        if (!scaleChanged) {
            savedScale = Settings.Global.getString(context.contentResolver, "animator_duration_scale")
            scaleChanged = true
        }
        shell("settings put global animator_duration_scale $value")
    }

    private fun isRunning(image: PlatformImage): Boolean =
        ((image.painter as DrawablePainter).drawable as AnimatedImageDrawable).isRunning

    /** Pinta o [PlatformImage.painter] como o Compose faria, num bitmap de [px] x [px]. */
    private fun render(image: PlatformImage, px: Int = 64): Bitmap {
        val target = ImageBitmap(px, px)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(target), Size(px.toFloat(), px.toFloat())) {
            with(image.painter) { draw(size) }
        }
        return target.asAndroidBitmap()
    }

    /** O disco colorido das amostras cobre este ponto em todos os quadros (e em qualquer tamanho). */
    private fun discColor(bitmap: Bitmap): String {
        val pixel = bitmap.getPixel(bitmap.width * 30 / 64, bitmap.height * 30 / 64)
        val colors = mapOf("vermelho" to Color.rgb(255, 70, 70), "amarelo" to Color.rgb(255, 200, 40), "verde" to Color.rgb(60, 200, 120))
        if (Color.alpha(pixel) < 128) return "transparente"
        return colors.minBy { (_, c) ->
            listOf(Color.red(pixel) - Color.red(c), Color.green(pixel) - Color.green(c), Color.blue(pixel) - Color.blue(c)).sumOf { it * it }
        }.key
    }

    // O que anima e o que não anima

    @Test
    fun gifDeTresQuadrosAnimaNoTamanhoDaImagem() {
        val image = decoded(ImageSamples.animatedGif)
        assertTrue(image.animated)
        assertEquals(64 to 64, image.width to image.height)
    }

    @Test
    fun webpAnimadoAnima() {
        assertTrue(decoded(ImageSamples.animatedWebp).animated)
    }

    @Test
    fun gifDeUmQuadroSoVemParado() {
        val image = decoded(ImageSamples.singleFrameGif)
        assertFalse(image.animated)
        assertEquals("vermelho", discColor(render(image)))
    }

    @Test
    fun webpPngEJpegSemAnimacaoVemParados() {
        for (bytes in listOf(ImageSamples.stillWebp, ImageSamples.png, ImageSamples.jpeg)) {
            val image = decoded(bytes)
            assertFalse(image.animated)
            assertEquals(64 to 64, image.width to image.height)
            assertEquals("vermelho", discColor(render(image)))
        }
    }

    @Test
    fun apngRegistraOQueEsteAndroidFaz() {
        val image = decoded(ImageSamples.apng)
        val note = "api=${Build.VERSION.SDK_INT} animated=${image.animated}"
        Log.i("ImageDecodingTest", "APNG: $note")
        instrumentation.sendStatus(0, Bundle().apply { putString("apng", note) })
        // Anime ou não, o APNG tem de abrir e começar no primeiro quadro.
        assertEquals(64 to 64, image.width to image.height)
        if (!image.animated) assertEquals("vermelho", discColor(render(image)))
    }

    @Test
    fun comAnimateFalsoQualquerFormatoVemNoPrimeiroQuadro() {
        for (bytes in listOf(ImageSamples.animatedGif, ImageSamples.animatedWebp, ImageSamples.apng)) {
            val image = decoded(bytes, animate = false)
            assertFalse(image.animated)
            assertEquals("vermelho", discColor(render(image)))
            // Parado de verdade: nada a iniciar.
            image.start()
            assertEquals("vermelho", discColor(render(image)))
        }
    }

    // O que não é imagem

    @Test
    fun bytesQueNaoSaoImagemViramNull() {
        assertNull(decode(ByteArray(0)))
        assertNull(decode("isto não é uma imagem".encodeToByteArray()))
        assertNull(decode(ImageSamples.png.copyOf(24))) // só o começo do PNG
        assertNull(decode(ImageSamples.animatedGif.copyOf(10), animate = false))
    }

    // Tamanho

    @Test
    fun oLadoMaiorSeReduzAoLimiteSemAmpliarNuncaNosDoisCaminhos() {
        for (bytes in listOf(ImageSamples.animatedGif, ImageSamples.animatedWebp, ImageSamples.png, ImageSamples.stillWebp, ImageSamples.jpeg)) {
            val reduced = decoded(bytes, maxPx = 32)
            assertEquals(32 to 32, reduced.width to reduced.height)
            val kept = decoded(bytes, maxPx = 256)
            assertEquals(64 to 64, kept.width to kept.height)
        }
    }

    @Test
    fun oPainterTemOTamanhoPedido() {
        for (bytes in listOf(ImageSamples.animatedGif, ImageSamples.png)) {
            val image = decoded(bytes, maxPx = 32)
            assertEquals(Size(32f, 32f), image.painter.intrinsicSize)
        }
    }

    // A animação em si

    @Test
    fun aAnimacaoAvancaOsQuadrosDepoisDeStart() = runBlocking {
        val image = decoded(ImageSamples.animatedGif)
        assertEquals("vermelho", discColor(render(image)))
        image.start()
        val seen = mutableSetOf<String>()
        withTimeout(10_000) {
            while (seen.size < 3) {
                seen += discColor(render(image))
                delay(40)
            }
        }
        assertEquals(setOf("vermelho", "amarelo", "verde"), seen)
    }

    @Test
    fun startPararEFecharControlamAAnimacao() {
        val image = decoded(ImageSamples.animatedWebp)
        assertFalse(isRunning(image), "decodificada, ainda não começou")
        image.start()
        assertTrue(isRunning(image))
        image.stop()
        assertFalse(isRunning(image))
        image.start()
        assertTrue(isRunning(image), "start depois de stop retoma")
        image.close()
        assertFalse(isRunning(image), "fechar para")
        image.start()
        assertFalse(isRunning(image), "depois de fechada não volta a animar")
    }

    @Test
    fun startESopareEmImagemParadaNaoFazemNada() {
        val image = decoded(ImageSamples.png)
        image.start()
        image.stop()
        image.close()
        assertFalse(image.animated)
    }

    // "Remover animações" do sistema

    @Test
    fun comAsAnimacoesDoSistemaDesligadasTudoVemParado() {
        setAnimationScale("0")
        for (bytes in listOf(ImageSamples.animatedGif, ImageSamples.animatedWebp, ImageSamples.apng)) {
            val image = decoded(bytes)
            assertFalse(image.animated)
            assertEquals("vermelho", discColor(render(image)))
        }
        setAnimationScale("1")
        assertTrue(decoded(ImageSamples.animatedGif).animated, "ligadas de novo, volta a animar")
    }

    @Test
    fun desligarAsAnimacoesDepoisDeDecodificarImpedeOStart() {
        val image = decoded(ImageSamples.animatedGif)
        setAnimationScale("0")
        image.start()
        assertFalse(isRunning(image))
        setAnimationScale("1")
        image.start()
        assertTrue(isRunning(image))
    }

    // Quem chama

    @Test
    fun osBytesDeOrigemNaoMudam() {
        val bytes = ImageSamples.animatedGif
        val copy = bytes.copyOf()
        val image = decoded(bytes)
        image.start()
        repeat(5) { render(image) }
        image.close()
        assertContentEquals(copy, bytes)
    }

    @Test
    fun cancelarNaoEngoleOCancelamento() {
        val cancelled = Job().apply { cancel() }
        assertFailsWith<CancellationException> {
            runBlocking { withContext(cancelled + Dispatchers.Default) { ui.decodeImage(ImageSamples.animatedGif, 128, true) } }
        }
    }
}
