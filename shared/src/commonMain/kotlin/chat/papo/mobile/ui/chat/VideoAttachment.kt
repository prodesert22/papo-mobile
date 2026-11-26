package chat.papo.mobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.data.api.AttachmentDto
import chat.papo.mobile.media.PlayableMedia
import chat.papo.mobile.media.videoAspect
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.chat_sensitive
import chat.papo.mobile.resources.msg_download
import chat.papo.mobile.resources.video_download_failed
import chat.papo.mobile.resources.video_play
import chat.papo.mobile.ui.components.formatBytes
import chat.papo.mobile.ui.media.rememberAttachmentDownload
import org.jetbrains.compose.resources.stringResource

/** Medidas máximas do cartão do vídeo na lista: as mesmas da imagem. */
private val MaxWidth = 280.dp
private val MaxHeight = 320.dp

/**
 * Cartão de um vídeo anexado: uma caixa com a proporção do vídeo (a lista não pula; sem dimensões, 16:9) e o ▶ no meio,
 * e embaixo o nome, o tamanho e o **Baixar**. Tocar a caixa chama [onPlay]. O servidor não gera capa de vídeo, então a
 * caixa não tem imagem. Vídeo marcado como sensível fica escondido, com nome, tamanho e Baixar, até o primeiro toque
 * (o segundo toca), como a imagem.
 */
@Composable
internal fun VideoAttachment(session: AccountSession, attachment: AttachmentDto, media: PlayableMedia, onPlay: (PlayableMedia) -> Unit) {
    var revealed by remember(attachment.id) { mutableStateOf(!attachment.isSensitive) }
    val download = rememberAttachmentDownload(session, attachment)
    val aspect = videoAspect(media.width, media.height)
    // A largura sai da proporção para a caixa caber na altura máxima: um vídeo em retrato fica estreito, não esticado.
    Column(
        Modifier
            .width(minOf(MaxWidth, MaxHeight * aspect))
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .clickable(role = Role.Button) { if (revealed) onPlay(media) else revealed = true },
            contentAlignment = Alignment.Center,
        ) {
            if (revealed) {
                PlayBadge()
            } else {
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

/** O ▶ no meio da caixa: um disco escuro com o triângulo branco, legível sobre qualquer fundo nos dois temas. */
@Composable
internal fun PlayBadge() {
    Box(Modifier.size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(Res.string.video_play), tint = Color.White, modifier = Modifier.size(32.dp))
    }
}
