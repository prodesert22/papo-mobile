package chat.papo.mobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter

/**
 * Imagem decodificada pela plataforma, parada ou animada. Quem a pediu a fecha com [close];
 * [rememberMediaPainter] é o dono das que a interface mostra.
 */
interface PlatformImage {
    /** Quem desenha. Numa imagem animada o [painter] precisa passar por [rememberedPainter]. */
    val painter: Painter
    val width: Int
    val height: Int

    /** Tem mais de um quadro e a plataforma sabe animá-lo. */
    val animated: Boolean

    /** Anima. Sem efeito numa imagem parada, e a plataforma pode recusar (por exemplo, com as animações do sistema desligadas). */
    fun start()

    /** Para no quadro atual. */
    fun stop()

    /** Solta a imagem; não use o [painter] depois. */
    fun close()
}

/**
 * O [PlatformImage.painter] preso à composição. Uma imagem animada acompanha a composição
 * (começa quando o painter entra, termina quando sai) e só recebe esses avisos de dentro do `remember`.
 */
@Composable
fun PlatformImage.rememberedPainter(): Painter = remember(this) { painter }
