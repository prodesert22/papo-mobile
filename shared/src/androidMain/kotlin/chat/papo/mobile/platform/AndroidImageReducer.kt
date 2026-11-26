package chat.papo.mobile.platform

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.drawable.AnimatedImageDrawable
import chat.papo.mobile.media.CropPlan
import chat.papo.mobile.media.CropRequest
import chat.papo.mobile.media.ImageReducer
import chat.papo.mobile.media.ImageReduction
import chat.papo.mobile.media.ImageRefusal
import chat.papo.mobile.media.PROFILE_IMAGE_MAX_BYTES
import chat.papo.mobile.media.PixelSize
import chat.papo.mobile.media.ProfileImageKind
import chat.papo.mobile.media.ReducedImage
import chat.papo.mobile.media.SourceImageInfo
import chat.papo.mobile.media.fitsLimits
import chat.papo.mobile.media.mimeTypeOf
import chat.papo.mobile.media.planCrop
import chat.papo.mobile.media.planManualCrop
import chat.papo.mobile.media.scaleForDecoder
import chat.papo.mobile.media.sniffImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Reduz imagens de perfil com o `ImageDecoder` e o `Bitmap.compress` do sistema, só em memória. O decodificador já
 * reduz e recorta durante a leitura (a foto inteira nunca existe no tamanho original) e aplica a rotação do EXIF; o
 * codificador não grava metadado nenhum, então a localização da foto não sai do aparelho. Com um [CropRequest] decodifica
 * só a região escolhida, já no tamanho final, e gira o resultado (pequeno) pelas voltas pedidas.
 */
internal class AndroidImageReducer : ImageReducer {
    override suspend fun inspect(source: ByteArray): SourceImageInfo? = withContext(Dispatchers.Default) {
        try {
            val sniffed = sniffImage(source)
            // Só quem pode ser animação precisa do teste do tipo do drawable; os outros são sempre imagens paradas.
            val probe = probe(source)
            SourceImageInfo(probe.size.width, probe.size.height, animated = sniffed.mayAnimate && probe.animated)
        } catch (e: IOException) {
            // `ImageDecoder.DecodeException`: não é imagem, está cortada ou o formato não é suportado.
            null
        }
    }

    override suspend fun reduce(source: ByteArray, kind: ProfileImageKind, crop: CropRequest?): ImageReduction = withContext(Dispatchers.Default) {
        try {
            reduceNow(source, kind, crop)
        } catch (e: IOException) {
            // `ImageDecoder.DecodeException`: não é imagem, está cortada ou o formato não é suportado.
            ImageReduction.Refused(ImageRefusal.NotAnImage)
        }
    }

    private suspend fun reduceNow(source: ByteArray, kind: ProfileImageKind, crop: CropRequest?): ImageReduction {
        val sniffed = sniffImage(source)
        val animatedMime = mimeTypeOf(sniffed)?.takeIf { sniffed.mayAnimate }
        if (animatedMime != null) {
            // Um GIF de um quadro só, ou um APNG que este Android não anima, é uma imagem comum e segue o caminho dela.
            animatedSize(source)?.let { return keepAnimation(source, animatedMime, it, kind) }
        }
        return recode(source, kind, crop)
    }

    /** O tamanho (com a orientação do EXIF) e o tipo da imagem, decodificada em 1 x 1: só quer o cabeçalho. */
    private class Probe(val size: PixelSize, val animated: Boolean)

    private fun probe(source: ByteArray): Probe {
        var size: PixelSize? = null
        val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(source))) { decoder, info, _ ->
            size = PixelSize(info.size.width, info.size.height)
            decoder.setTargetSize(1, 1)
        }
        return Probe(checkNotNull(size) { "o decodificador não informou o tamanho" }, drawable is AnimatedImageDrawable)
    }

    /** O tamanho de uma animação de verdade; null para uma imagem de um quadro. Decodifica em 1 x 1: só quer o cabeçalho. */
    private fun animatedSize(source: ByteArray): PixelSize? = probe(source).takeIf { it.animated }?.size

    /** A animação vai como veio se cabe nos limites do servidor; recodificá-la perderia os quadros, então fora deles é recusada. */
    private fun keepAnimation(source: ByteArray, mimeType: String, size: PixelSize, kind: ProfileImageKind): ImageReduction =
        if (fitsLimits(source.size, size.width, size.height, kind)) {
            ImageReduction.Ready(ReducedImage(source, mimeType, size.width, size.height, animated = true))
        } else {
            ImageReduction.Refused(ImageRefusal.AnimationTooLarge)
        }

    /**
     * Recorta ([crop], ou o centro sem ele), reduz e grava em PNG; se passa de 2 MiB, em WEBP com qualidade cada vez menor.
     * O decodificador só produz o recorte, já no tamanho final (nunca ampliado); as voltas de [crop] giram esse resultado.
     */
    private suspend fun recode(source: ByteArray, kind: ProfileImageKind, crop: CropRequest?): ImageReduction {
        val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(source))) { decoder, info, _ ->
            // O tamanho já vem na orientação certa (a do EXIF aplicada), que é a do recorte.
            val width = info.size.width
            val height = info.size.height
            val plan: CropPlan = (if (crop == null) planCrop(width, height, kind) else planManualCrop(width, height, kind, crop))
                ?: throw IOException("imagem pequena demais ou recorte inválido")
            val scaled = scaleForDecoder(plan, width, height)
            decoder.setTargetSize(scaled.targetWidth, scaled.targetHeight)
            decoder.setCrop(Rect(scaled.left, scaled.top, scaled.left + plan.outWidth, scaled.top + plan.outHeight))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val bitmap = rotated(decoded, crop?.quarterTurns ?: 0)
        try {
            encode(bitmap, Bitmap.CompressFormat.PNG, 100, "image/png")?.let { return ImageReduction.Ready(it) }
            for (quality in WEBP_QUALITIES) {
                currentCoroutineContext().ensureActive()
                encode(bitmap, Bitmap.CompressFormat.WEBP_LOSSY, quality, "image/webp")?.let { return ImageReduction.Ready(it) }
            }
            return ImageReduction.Refused(ImageRefusal.StillTooHeavy)
        } finally {
            bitmap.recycle()
        }
    }

    /** [bitmap] girado [quarterTurns] voltas de 90 graus no sentido horário; o original é solto se foi trocado. */
    private fun rotated(bitmap: Bitmap, quarterTurns: Int): Bitmap {
        val turns = quarterTurns.mod(4)
        if (turns == 0) return bitmap
        val matrix = Matrix().apply { postRotate(90f * turns) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false).also { if (it !== bitmap) bitmap.recycle() }
    }

    /** A imagem codificada, ou null se passa de 2 MiB (ou o codificador falhou). */
    private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int, mimeType: String): ReducedImage? {
        val out = ByteArrayOutputStream()
        if (!bitmap.compress(format, quality, out) || out.size() > PROFILE_IMAGE_MAX_BYTES) return null
        return ReducedImage(out.toByteArray(), mimeType, bitmap.width, bitmap.height, animated = false)
    }

    private companion object {
        /** Qualidades do WEBP com perda, da melhor para a pior: poucos passos, para o tempo de redução ter teto. */
        val WEBP_QUALITIES = intArrayOf(90, 75, 60, 45, 30)
    }
}
