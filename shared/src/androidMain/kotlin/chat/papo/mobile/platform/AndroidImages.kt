package chat.papo.mobile.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.provider.Settings
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import chat.papo.mobile.media.PixelSize
import chat.papo.mobile.media.fitWithin
import chat.papo.mobile.media.sniffImage
import chat.papo.mobile.ui.components.PlatformImage
import com.google.accompanist.drawablepainter.DrawablePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer

/** Decodifica imagens com o `ImageDecoder` do sistema, direto dos bytes em memória: nada vai para o disco. */
internal class AndroidImages(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    /** "Remover animações" (e a escala de animação em zero, nas opções do desenvolvedor) zera esta configuração. */
    private fun animationsEnabled(): Boolean =
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

    suspend fun decode(bytes: ByteArray, maxPx: Int, animate: Boolean): PlatformImage? = withContext(Dispatchers.Default) {
        try {
            decodeNow(bytes, maxPx, animate && animationsEnabled())
        } catch (e: IOException) {
            // `ImageDecoder.DecodeException`: não é imagem, está cortada ou o formato não é suportado.
            null
        }
    }

    private fun decodeNow(bytes: ByteArray, maxPx: Int, animate: Boolean): PlatformImage? {
        // O ByteBuffer só embrulha o array, sem copiar: o AnimatedImageDrawable segue lendo dele depois
        // do retorno, e o array é o do cache em memória, compartilhado. Ninguém escreve nele.
        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        var target = PixelSize(1, 1)
        val sizing = ImageDecoder.OnHeaderDecodedListener { decoder, info, _ ->
            target = fitWithin(info.size.width, info.size.height, maxPx)
            decoder.setTargetSize(target.width, target.height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        // Só gasta o caminho de animação quem pode animar: o formato vem dos bytes, não do `format` do servidor.
        if (!animate || !sniffImage(bytes).mayAnimate) return StillImage(ImageDecoder.decodeBitmap(source, sizing))
        return when (val drawable = ImageDecoder.decodeDrawable(source, sizing)) {
            is AnimatedImageDrawable -> {
                drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                AnimatedImage(drawable, target, ::animationsEnabled)
            }
            // Um GIF de um quadro só, ou um APNG que este Android não anima, chega como imagem comum.
            is BitmapDrawable -> StillImage(drawable.bitmap)
            // O `ImageDecoder` ainda devolve um NinePatchDrawable para um PNG com chunk `npTc`: não serve de emoji.
            else -> null
        }
    }
}

private class StillImage(bitmap: Bitmap) : PlatformImage {
    override val painter: Painter = BitmapPainter(bitmap.asImageBitmap())
    override val width: Int = bitmap.width
    override val height: Int = bitmap.height
    override val animated: Boolean = false

    override fun start() = Unit

    override fun stop() = Unit

    override fun close() = Unit
}

private class AnimatedImage(
    private val drawable: AnimatedImageDrawable,
    size: PixelSize,
    private val animationsEnabled: () -> Boolean,
) : PlatformImage {
    // O DrawablePainter começa e termina a animação conforme entra e sai da composição, e redesenha a cada quadro.
    override val painter: Painter = DrawablePainter(drawable)
    override val width: Int = size.width
    override val height: Int = size.height
    override val animated: Boolean = true

    private var closed = false

    override fun start() {
        // As animações podem ter sido desligadas depois da decodificação (o usuário voltou das configurações).
        if (!closed && animationsEnabled()) drawable.start()
    }

    override fun stop() = drawable.stop()

    override fun close() {
        closed = true
        drawable.stop()
    }
}
