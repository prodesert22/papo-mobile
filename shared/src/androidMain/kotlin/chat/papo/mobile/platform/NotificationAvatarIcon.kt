package chat.papo.mobile.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import chat.papo.mobile.media.fitWithin
import java.io.IOException
import java.nio.ByteBuffer

/**
 * A imagem de quem escreveu, pronta para a notificação: pequena e recortada em círculo. Decodifica direto dos bytes em
 * memória, já reduzindo (nada vai para o disco, regra 8), e devolve nulo se a imagem não for legível.
 */
internal object NotificationAvatarIcon {
    /** O maior lado do ícone; o das notificações do sistema é de dezenas de dp, então isto basta com folga. */
    const val SIZE_PX = 128

    fun circle(bytes: ByteArray): Bitmap? {
        val decoded = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                val target = fitWithin(info.size.width, info.size.height, SIZE_PX)
                decoder.setTargetSize(target.width, target.height)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (e: IOException) {
            // `ImageDecoder.DecodeException`: não é imagem, está cortada ou o formato não é suportado.
            return null
        }
        return try {
            crop(decoded)
        } finally {
            decoded.recycle()
        }
    }

    /** O quadrado central de [source], só o que cabe num círculo; o resto fica transparente. */
    private fun crop(source: Bitmap): Bitmap {
        val side = minOf(source.width, source.height)
        val result = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawCircle(side / 2f, side / 2f, side / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(source, -(source.width - side) / 2f, -(source.height - side) / 2f, paint)
        return result
    }
}
