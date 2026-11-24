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
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.User
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap

/** Imagem vinda do cache cifrado da conta; null enquanto carrega ou se não existir. */
@Composable
fun rememberMediaImage(session: AccountSession, name: String?, fetch: suspend () -> ByteArray?): State<ImageBitmap?> =
    produceState<ImageBitmap?>(null, session.accountId, name) {
        value = null
        if (name == null) return@produceState
        value = try {
            val bytes = session.media.get(name, fetch)
            bytes?.let { withContext(Dispatchers.Default) { it.decodeToImageBitmap() } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
    }

@Composable
fun UserAvatar(session: AccountSession, user: User?, modifier: Modifier = Modifier, size: Dp = 40.dp, fallbackName: String = "?") {
    val image = rememberMediaImage(session, user?.let { "avatar/${it.id}/${it.avatarVersion}" }) {
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
