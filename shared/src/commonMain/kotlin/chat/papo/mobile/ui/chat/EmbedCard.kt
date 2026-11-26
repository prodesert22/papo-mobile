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
import chat.papo.mobile.chat.embedMediaAspect
import chat.papo.mobile.data.api.EmbedDto
import chat.papo.mobile.media.PlayableMedia
import chat.papo.mobile.media.aspectOrNull
import chat.papo.mobile.media.relayVideo
import chat.papo.mobile.ui.components.rememberMediaImage
import kotlin.io.encoding.Base64

/**
 * Cartão de um embed de link. A imagem aparece inteira, na proporção dela ([embedMediaAspect]). Se o embed traz um vídeo que
 * o relay do servidor serve ([relayVideo]), a caixa da imagem leva o ▶ e tocar nela chama [onPlay]. Nada é pedido ao relay
 * antes do toque: nem autoplay nem pré-carga, porque cada vídeo aberto prende uma das poucas vagas do relay no servidor.
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
            if (video != null || hasImage) {
                // A proporção das medidas do servidor, ou a da imagem quando ela chega; 16:9 até lá.
                val aspect = embedMediaAspect(embed) ?: image?.let { aspectOrNull(it.width, it.height) } ?: PLACEHOLDER_ASPECT
                Spacer(Modifier.height(8.dp))
                EmbedMediaBox(image, aspect, video, onPlay)
            }
        }
    }
}

/**
 * A caixa da imagem do embed: com a proporção [aspect] (a lista não pula quando a imagem chega), na largura do cartão e
 * no máximo [MediaMaxHeight] de altura, e a imagem inteira dentro dela (`Fit`, sem cortar as laterais nem o topo: uma
 * imagem alta fica estreita). Com [video] o ▶ fica por cima e tocar em qualquer ponto chama [onPlay]; antes da imagem
 * chegar, e sem ela, o fundo do cartão.
 */
@Composable
private fun EmbedMediaBox(image: ImageBitmap?, aspect: Float, video: PlayableMedia?, onPlay: (PlayableMedia) -> Unit) {
    Box(
        Modifier
            .heightIn(max = MediaMaxHeight)
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (video != null) Modifier.clickable(role = Role.Button) { onPlay(video) } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        image?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        if (video != null) PlayBadge()
    }
}

/** Altura máxima da imagem: uma imagem alta fica estreita, nunca cortada (como o cartão do vídeo anexado). */
private val MediaMaxHeight = 320.dp

/** Proporção da caixa enquanto não se sabe a da imagem: nem o servidor deu as medidas, nem ela chegou. */
private const val PLACEHOLDER_ASPECT = 16f / 9f
