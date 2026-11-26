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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.data.api.AttachmentDto
import chat.papo.mobile.media.PlayableMedia
import chat.papo.mobile.media.playableAttachment
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.chat_sensitive
import chat.papo.mobile.resources.msg_download
import chat.papo.mobile.ui.components.formatBytes
import chat.papo.mobile.ui.components.rememberMediaImage
import chat.papo.mobile.ui.media.rememberAttachmentDownload
import org.jetbrains.compose.resources.stringResource

/**
 * Um anexo da mensagem: imagem com miniatura, vídeo que o player abre (cartão com ▶) ou, para o resto, o cartão de
 * arquivo com **Baixar**. Os que o player recusa (bloqueado) ficam no cartão de arquivo.
 */
@Composable
internal fun MessageAttachment(
    session: AccountSession,
    attachment: AttachmentDto,
    onOpenImage: (AttachmentDto) -> Unit,
    onPlay: (PlayableMedia) -> Unit,
) {
    val video = playableAttachment(session.client.server, attachment)
    when {
        attachment.isImage && attachment.thumbnailId != null -> ImageAttachment(session, attachment, onOpen = { onOpenImage(attachment) })
        video != null -> VideoAttachment(session, attachment, video, onPlay)
        else -> FileAttachment(session, attachment)
    }
}

@Composable
internal fun ImageAttachment(session: AccountSession, attachment: AttachmentDto, onOpen: () -> Unit) {
    var revealed by remember(attachment.id) { mutableStateOf(!attachment.isSensitive) }
    val ratio = run {
        val w = attachment.thumbnailWidth ?: 0
        val h = attachment.thumbnailHeight ?: 0
        if (w > 0 && h > 0) (w.toFloat() / h).coerceIn(0.5f, 2.5f) else 4f / 3f
    }
    val image by rememberMediaImage(session, if (revealed) "att-thumb/${attachment.id}" else null) {
        session.api.attachmentThumbnail(attachment.id)
    }
    // A proporção vem dos metadados: a caixa já nasce do tamanho certo e a lista não pula ao carregar.
    Box(
        Modifier
            .widthIn(max = 280.dp)
            .heightIn(max = 320.dp)
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable { if (revealed) onOpen() else revealed = true },
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        when {
            !revealed -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(12.dp)) {
                Icon(Icons.Filled.VisibilityOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(Res.string.chat_sensitive),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            bitmap != null -> Image(bitmap, attachment.fileName, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

@Composable
internal fun FileAttachment(session: AccountSession, attachment: AttachmentDto) {
    val download = rememberAttachmentDownload(session, attachment)
    Row(
        Modifier
            .widthIn(max = 320.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = !download.downloading, onClickLabel = stringResource(Res.string.msg_download), onClick = download::start)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(attachment.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(
                formatBytes(attachment.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = if (download.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        if (download.downloading) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Filled.Download, contentDescription = stringResource(Res.string.msg_download), modifier = Modifier.size(20.dp))
        }
    }
}
