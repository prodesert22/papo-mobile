package chat.papo.mobile.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.data.api.EmbedDto
import chat.papo.mobile.media.PlayableMedia
import chat.papo.mobile.media.relayVideo
import chat.papo.mobile.ui.components.rememberMediaImage
import kotlin.io.encoding.Base64

/**
 * Cartão de um embed de link. Se ele traz um vídeo que o relay do servidor serve ([relayVideo]), a imagem (ou uma caixa
 * 16:9 sem imagem) leva o ▶ e tocar chama [onPlay]. Nada é pedido ao relay antes do toque: nem autoplay nem pré-carga,
 * porque cada vídeo aberto prende uma das poucas vagas do relay no servidor.
 */
@Composable
internal fun EmbedCard(session: AccountSession, embed: EmbedDto, onPlay: (PlayableMedia) -> Unit) {
    val video = relayVideo(session.client.server, embed)
    val hasImage = embed.thumbnail != null || embed.image != null
    val image by rememberMediaImage(session, if (hasImage) "embed/${embed.id}" else null) {
        session.api.embedImageBase64(embed.id)?.let { Base64.decode(it) }
    }
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .widthIn(max = 340.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Box(Modifier.width(3.dp).heightIn(min = 48.dp).background(MaterialTheme.colorScheme.outline))
        Column(Modifier.padding(10.dp)) {
            (embed.siteName ?: embed.provider)?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            embed.title?.takeIf { it.isNotBlank() }?.let { title ->
                val text = if (embed.url != null) {
                    buildAnnotatedString {
                        withLink(LinkAnnotation.Url(embed.url, TextLinkStyles(SpanStyle(color = accent)))) { append(title) }
                    }
                } else {
                    AnnotatedString(title)
                }
                Text(text, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            embed.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            if (video != null) {
                Spacer(Modifier.height(8.dp))
                EmbedVideoBox(image, onClick = { onPlay(video) })
            } else {
                image?.let { bitmap ->
                    Spacer(Modifier.height(8.dp))
                    Image(
                        bitmap,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }
    }
}

/**
 * A caixa do vídeo do embed: sempre 16:9, com a imagem do embed por baixo assim que ela chega (antes disso e sem imagem, o
 * fundo do cartão), para a lista não pular quando a imagem carrega. O ▶ fica por cima.
 */
@Composable
private fun EmbedVideoBox(image: ImageBitmap?, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        image?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        PlayBadge()
    }
}
