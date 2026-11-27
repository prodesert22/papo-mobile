package chat.papo.mobile.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.papo.mobile.account.Account
import chat.papo.mobile.account.AccountColors
import org.jetbrains.compose.resources.decodeToImageBitmap

/** Cor estável para um identificador, tirada da paleta de contas. */
fun colorFor(id: String): Color = Color(AccountColors[(id.hashCode() and Int.MAX_VALUE) % AccountColors.size])

fun Account.accentColor(): Color = color?.let { Color(it) } ?: colorFor(userId)

fun initials(text: String): String {
    val words = text.trim().split(' ', '-', '_', '.').filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2)
        else -> "${words[0].first()}${words[1].first()}"
    }.uppercase()
}

fun Color.readableOn(): Color = if (luminance() > 0.5f) Color(0xFF12262B) else Color.White

@Composable
fun rememberImage(bytes: ByteArray?): ImageBitmap? = remember(bytes) {
    bytes?.let { runCatching { it.decodeToImageBitmap() }.getOrNull() }
}

/**
 * Ícone de uma conta: o servidor no centro, um aro na cor da conta e, no canto,
 * a inicial do usuário. É o aro e o selo que separam duas contas do mesmo servidor.
 */
@Composable
fun AccountAvatar(
    account: Account,
    selected: Boolean,
    ringBackground: Color,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    unread: Boolean = false,
    unreadColor: Color = Color.Unspecified,
    /** O número vermelho de menções da conta (`badgeText`); no lugar do ponto de não lida. */
    mentions: String? = null,
) {
    val accent = account.accentColor()
    val corner by animateDpAsState(if (selected) size * 0.3f else size / 2)
    val shape = RoundedCornerShape(corner)
    val icon = rememberImage(account.serverIcon)

    Box(modifier.size(size + 6.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(size + 6.dp)
                .border(2.dp, if (selected) accent else Color.Transparent, RoundedCornerShape(corner + 3.dp)),
        )
        Box(
            Modifier.size(size).clip(shape).background(accent.copy(alpha = 0.28f)),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Image(icon, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Text(
                    initials(account.title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 2.dp, y = 2.dp)
                .size(size * 0.42f)
                .background(ringBackground, CircleShape)
                .padding(2.dp)
                .background(accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                account.username.take(1).uppercase(),
                fontSize = (size.value * 0.2f).sp,
                lineHeight = (size.value * 0.2f).sp,
                fontWeight = FontWeight.Bold,
                color = accent.readableOn(),
            )
        }
        // Um ponto no canto: vermelho se a sessão caiu, na cor de destaque se há algo não lido.
        val dot = when {
            account.sessionExpired -> MaterialTheme.colorScheme.error
            mentions != null -> null
            unread -> unreadColor
            else -> null
        }
        if (mentions != null && !account.sessionExpired) {
            MentionBadge(
                mentions,
                Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-4).dp).background(ringBackground, RoundedCornerShape(10.dp)).padding(2.dp),
                minHeight = 18.dp,
            )
        }
        if (dot != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(14.dp)
                    .background(ringBackground, CircleShape)
                    .padding(2.dp)
                    .background(dot, CircleShape),
            )
        }
    }
}
