package chat.papo.mobile.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.User
import chat.papo.mobile.chat.avatarMediaName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * Imagem vinda do cache cifrado da conta; null enquanto carrega ou se não existir. Com [holdFailures] uma
 * busca que falhou não é repetida por um tempo ([chat.papo.mobile.media.MediaCache.get]).
 */
@Composable
fun rememberMediaImage(
    session: AccountSession,
    name: String?,
    holdFailures: Boolean = false,
    fetch: suspend () -> ByteArray?,
): State<ImageBitmap?> =
    produceState<ImageBitmap?>(null, session.accountId, name) {
        value = null
        if (name == null) return@produceState
        value = try {
            val bytes = session.media.get(name, holdFailures, fetch)
            bytes?.let { withContext(Dispatchers.Default) { it.decodeToImageBitmap() } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
    }

/**
 * Como [rememberMediaImage], mas decodificada pela plataforma ([PlatformUi.decodeImage]), então uma
 * imagem animada anima. Esta função é a dona da imagem: fecha-a ao trocar ou sair da composição, e só
 * a deixa animar com a tela à vista (para em segundo plano, ou com outra tela por cima). Quem desenha usa
 * [rememberedPainter]. Com [animate] falso mostra o primeiro quadro, sem custo de animação.
 */
@Composable
fun rememberMediaPainter(
    session: AccountSession,
    name: String,
    maxPx: Int,
    animate: Boolean,
    fetch: suspend () -> ByteArray?,
): State<PlatformImage?> {
    val platform = LocalPlatformUi.current
    val image = produceState<PlatformImage?>(null, session.accountId, name, maxPx, animate) {
        value = null
        value = try {
            session.media.get(name, fetch = fetch)?.let { platform.decodeImage(it, maxPx, animate) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
    }
    val current = image.value
    DisposableEffect(current) { onDispose { current?.close() } }
    LifecycleStartEffect(current) {
        current?.start()
        onStopOrDispose { current?.stop() }
    }
    return image
}

@Composable
fun UserAvatar(session: AccountSession, user: User?, modifier: Modifier = Modifier, size: Dp = 40.dp, fallbackName: String = "?") {
    // Avatares aparecem às dezenas (conversa, lista de membros): a falha de um não é repetida a cada exibição.
    val image = rememberMediaImage(session, user?.let { avatarMediaName(it.id, it.avatarVersion) }, holdFailures = true) {
        user?.let { session.api.avatar(it.id) }
    }
    val color = colorFor(user?.id ?: fallbackName)
    Box(modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
        val bitmap = image.value
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(
                initials(user?.displayName ?: fallbackName),
                fontSize = (size.value * 0.38f).sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Cor `#RRGGBB` de um cargo; null se o texto não for uma cor. */
fun parseHexColor(hex: String?): Color? {
    val value = hex?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16) ?: return null
    return Color(0xFF000000 or value)
}

fun formatBytes(size: Long): String = when {
    size >= 1_048_576 -> "${(size * 10 / 1_048_576) / 10.0} MB"
    size >= 1024 -> "${size / 1024} KB"
    else -> "$size B"
}
