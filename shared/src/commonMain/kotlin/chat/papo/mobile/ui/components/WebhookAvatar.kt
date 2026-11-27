package chat.papo.mobile.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.User
import chat.papo.mobile.chat.webhookAvatarId
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.chat_app_badge
import chat.papo.mobile.resources.chat_app_badge_description
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.stringResource

/**
 * O avatar de quem escreveu uma mensagem: o do webhook, se ela veio de um ([webhookId] não nulo), senão o do usuário
 * [author]. [name] é o nome mostrado ao lado, de onde saem as iniciais de quem não tem imagem.
 */
@Composable
fun AuthorAvatar(
    session: AccountSession,
    author: User?,
    webhookId: String?,
    avatarUrl: String?,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    if (webhookId != null) {
        WebhookAvatar(session, webhookId, avatarUrl, name, modifier, size)
    } else {
        UserAvatar(session, author, modifier, size, fallbackName = name)
    }
}

/**
 * Avatar do autor de uma mensagem de webhook. A imagem só vem do servidor da conta e só quando [avatarUrl] é o
 * caminho do próprio webhook (ver [webhookAvatarId]); com qualquer outra coisa, ou sem imagem, ficam as iniciais de
 * [name] sobre a cor dele, como o avatar de quem não tem foto, para que autores de pontes continuem distinguíveis.
 */
@Composable
fun WebhookAvatar(
    session: AccountSession,
    webhookId: String?,
    avatarUrl: String?,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val avatarId = remember(webhookId, avatarUrl) { webhookAvatarId(webhookId, avatarUrl) }
    // O cache de mídia não serve aqui: o caminho não tem versão, e uma imagem já guardada nunca chamaria o servidor.
    val image = produceState<ImageBitmap?>(null, session, avatarId) {
        value = null
        if (avatarId == null) return@produceState
        // Primeiro o que já está guardado, sem esperar a rede; depois a resposta do servidor desta sessão.
        value = decodeAvatar { session.webhookAvatars.cached(avatarId) }
        value = decodeAvatar { session.webhookAvatars.get(avatarId) }
    }
    val bitmap = image.value
    // A cor de fundo é só das iniciais: uma imagem com transparência mostra o que está atrás dela.
    val circle = modifier.size(size).clip(CircleShape)
    Box(if (bitmap == null) circle.background(colorFor(name).copy(alpha = 0.35f)) else circle, contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(
                initials(name),
                fontSize = (size.value * 0.38f).sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private suspend fun decodeAvatar(load: suspend () -> ByteArray?): ImageBitmap? = try {
    load()?.let { bytes -> withContext(Dispatchers.Default) { bytes.decodeToImageBitmap() } }
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    null // imagem ilegível: ficam as iniciais
}

/** O selo "APP" que acompanha o nome de quem escreve por um webhook, e que o leitor de tela lê como "Aplicativo". */
@Composable
fun AppBadge(modifier: Modifier = Modifier) {
    val description = stringResource(Res.string.chat_app_badge_description)
    Text(
        stringResource(Res.string.chat_app_badge),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 5.dp, vertical = 1.dp)
            .clearAndSetSemantics { contentDescription = description },
    )
}
