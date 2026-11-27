package chat.papo.mobile.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.data.api.AttachmentDto
import chat.papo.mobile.media.ModerationView
import chat.papo.mobile.media.PlayableMedia
import chat.papo.mobile.media.PosterSource
import chat.papo.mobile.media.attachmentThumbKey
import chat.papo.mobile.media.guardGone
import chat.papo.mobile.media.moderationView
import chat.papo.mobile.media.posterKey
import chat.papo.mobile.media.posterSource
import chat.papo.mobile.media.videoCardAspect
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.chat_sensitive
import chat.papo.mobile.resources.msg_download
import chat.papo.mobile.resources.video_download_failed
import chat.papo.mobile.resources.video_poster_loading
import chat.papo.mobile.ui.components.formatBytes
import chat.papo.mobile.ui.components.rememberMediaImage
import chat.papo.mobile.ui.media.VideoHost
import chat.papo.mobile.ui.media.cardKey
import chat.papo.mobile.ui.media.rememberAttachmentDownload
import org.jetbrains.compose.resources.stringResource

/** Medidas máximas do cartão do vídeo na lista: as mesmas da imagem. */
private val MaxWidth = 280.dp
private val MaxHeight = 320.dp

/**
 * Cartão de um vídeo anexado: uma caixa com a proporção do vídeo (a lista não pula; sem dimensões, a da miniatura do
 * servidor, e sem ela 16:9) e o ▶ no meio, e embaixo o nome, o tamanho e o **Baixar**. O ▶ toca o vídeo na própria caixa
 * e tocar fora dele abre a tela cheia ([VideoBox]); [videos] guarda qual vídeo toca.
 *
 * A capa é a miniatura do servidor, quando ele a gerou (só gera para imagem, então quase nunca), ou um quadro do próprio
 * vídeo tirado neste aparelho (`VideoPosters`), guardado no cache cifrado. Enquanto não chega a caixa fica como está e
 * a capa aparece com um fade, sob o ▶, e um anel de progresso em volta do ▶ mostra que ela está sendo feita (só enquanto
 * é extraída: a que já estava no cache aparece sem ele); sem capa (formato que o aparelho não lê, falha) o cartão fica
 * simples, sem o anel. Vídeo marcado como sensível fica escondido, sem pedir capa nenhuma, com nome, tamanho e Baixar,
 * até o primeiro toque, em qualquer ponto; depois vale o gesto do ▶ e do resto da caixa, como no vídeo comum.
 */
@Composable
internal fun VideoAttachment(
    session: AccountSession,
    messageId: String,
    attachment: AttachmentDto,
    media: PlayableMedia,
    videos: VideoHost,
) {
    // A chave inclui o estado: virar sensível depois de aberto esconde de novo.
    val sensitive = moderationView(attachment.moderationStatus) == ModerationView.Sensitive
    var revealed by remember(attachment.id, sensitive) { mutableStateOf(!sensitive) }
    val download = rememberAttachmentDownload(session, attachment)
    val aspect = videoCardAspect(media.width, media.height, attachment.thumbnailWidth, attachment.thumbnailHeight)
    val source = posterSource(attachment, revealed)
    // O nome decide a chave do cache; o `fetch` só roda se a capa ainda não está lá, e então só pelo caminho da fonte.
    val poster by rememberMediaImage(
        session,
        when (source) {
            PosterSource.Server -> attachmentThumbKey(attachment.id)
            PosterSource.Extract -> posterKey(attachment.id)
            PosterSource.None -> null
        },
    ) {
        when (source) {
            PosterSource.Server -> session.guardGone(messageId, attachment.id) { session.api.attachmentThumbnail(attachment.id) }
            PosterSource.Extract -> session.videoPosters.poster(attachment.id, media.url, attachment.sizeBytes)
            PosterSource.None -> null
        }
    }
    // Só a capa extraída demora o bastante para mostrar progresso; a do cache nunca chama o `fetch`, então nunca está "sendo feita".
    val making by remember(session, attachment.id) { session.videoPosters.making(attachment.id) }.collectAsState(false)
    // A largura sai da proporção para a caixa caber na altura máxima: um vídeo em retrato fica estreito, não esticado.
    Column(
        Modifier
            .width(minOf(MaxWidth, MaxHeight * aspect))
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(aspect), contentAlignment = Alignment.Center) {
            if (revealed) {
                ServerVideoBox(session, messageId, media, videos, embed = false) {
                    val bitmap = poster
                    // A capa entra com um fade em vez de aparecer de uma vez; o valor só é lido ao desenhar, sem recompor por quadro.
                    val alpha by animateFloatAsState(if (bitmap != null) 1f else 0f, tween(durationMillis = 250), label = "poster")
                    // Inteira, sem recorte: o quadro tem a proporção real do vídeo, que a caixa só segue de perto.
                    if (bitmap != null) {
                        Image(bitmap, contentDescription = null, modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }, contentScale = ContentScale.Fit)
                    }
                    // O anel entra e sai com um fade: some junto com o início do fade da capa, sem um vazio entre os dois. Com o
                    // vídeo tocando no cartão ele cede ao indicador de carregamento do player (a extração espera o player).
                    val playerOpen = videos.inline(cardKey(messageId, media.key)) != null
                    val progress by animateFloatAsState(if (making && bitmap == null && !playerOpen) 1f else 0f, tween(durationMillis = 250), label = "posterProgress")
                    if (progress > 0f) {
                        val loading = stringResource(Res.string.video_poster_loading)
                        CircularProgressIndicator(
                            modifier = Modifier.size(64.dp).graphicsLayer { this.alpha = progress }.semantics { contentDescription = loading },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            strokeWidth = 2.dp,
                        )
                    }
                }
            } else {
                Box(Modifier.fillMaxSize().clickable(role = Role.Button) { revealed = true }, contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(12.dp)) {
                        Icon(Icons.Filled.VisibilityOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(Res.string.chat_sensitive),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (revealed) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(attachment.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    Text(
                        if (download.failed) stringResource(Res.string.video_download_failed) else formatBytes(attachment.sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (download.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                TextButton(
                    onClick = download::start,
                    enabled = !download.downloading,
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) {
                    if (download.downloading) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(Res.string.msg_download), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
