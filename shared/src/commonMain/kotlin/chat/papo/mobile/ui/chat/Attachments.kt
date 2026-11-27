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
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.Message
import chat.papo.mobile.data.api.AttachmentDto
import chat.papo.mobile.media.ModerationView
import chat.papo.mobile.media.aspectOrNull
import chat.papo.mobile.media.attachmentThumbKey
import chat.papo.mobile.media.guardGone
import chat.papo.mobile.media.moderationView
import chat.papo.mobile.media.playableAttachment
import chat.papo.mobile.media.playableAudio
import chat.papo.mobile.media.sniffImage
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.attachment_blocked
import chat.papo.mobile.resources.attachment_gif_badge
import chat.papo.mobile.resources.chat_sensitive
import chat.papo.mobile.resources.msg_download
import chat.papo.mobile.ui.components.LocalAudioPlayback
import chat.papo.mobile.ui.components.formatBytes
import chat.papo.mobile.ui.components.rememberMediaPainter
import chat.papo.mobile.ui.components.rememberedPainter
import chat.papo.mobile.ui.media.VideoHost
import chat.papo.mobile.ui.media.rememberAttachmentDownload
import org.jetbrains.compose.resources.stringResource

/**
 * Um anexo da [message]: imagem com miniatura (um GIF anima), áudio que o player toca (cartão com ▶ e barra de busca), vídeo
 * que o player toca (cartão com ▶) ou, para o resto, o cartão de arquivo com **Baixar**. O áudio vem antes do vídeo, como no
 * web: uma gravação de voz que o servidor farejou como `video/webm` é um áudio. Um anexo que a moderação bloqueou
 * ([ModerationView.Blocked]) vira o aviso e nada é pedido ao servidor.
 */
@Composable
internal fun MessageAttachment(
    session: AccountSession,
    message: Message,
    attachment: AttachmentDto,
    onOpenImage: (AttachmentDto) -> Unit,
    videos: VideoHost,
) {
    if (moderationView(attachment.moderationStatus) == ModerationView.Blocked) {
        BlockedAttachment()
        return
    }
    // Sem motor de áudio na plataforma o áudio cai no cartão de arquivo.
    val audio = LocalAudioPlayback.current
    val audioMedia = audio?.let { playableAudio(session.client.server, attachment) }
    val video = playableAttachment(session.client.server, attachment)
    when {
        attachment.isImage && attachment.thumbnailId != null -> ImageAttachment(session, message.id, attachment, onOpen = { onOpenImage(attachment) })
        audio != null && audioMedia != null -> AudioAttachment(session, message, attachment, audioMedia, audio)
        video != null -> VideoAttachment(session, message.id, attachment, video, videos)
        else -> FileAttachment(session, attachment)
    }
}

/** O aviso no lugar de um anexo que a moderação removeu. */
@Composable
internal fun BlockedAttachment() {
    Row(
        Modifier
            .widthIn(max = 320.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Block, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(Res.string.attachment_blocked),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Lado maior da miniatura decodificada: a caixa da lista tem até 280 dp, e a miniatura do servidor, até 640 px. */
private const val THUMBNAIL_PX = 640

/**
 * A miniatura de uma imagem anexada. Um GIF anima em laço (a miniatura que o servidor gera de um GIF também é um GIF) e
 * leva o selo **GIF**, mesmo com as animações do sistema removidas, quando ele fica no primeiro quadro. Anexo sensível
 * fica escondido, sem pedir nada, até o toque; virar sensível depois de aberto esconde de novo. Se o servidor responde 410
 * à miniatura, o anexo foi bloqueado ([guardGone]) e o aviso o substitui.
 */
@Composable
internal fun ImageAttachment(session: AccountSession, messageId: String, attachment: AttachmentDto, onOpen: () -> Unit) {
    val sensitive = moderationView(attachment.moderationStatus) == ModerationView.Sensitive
    var revealed by remember(attachment.id, sensitive) { mutableStateOf(!sensitive) }
    val ratio = aspectOrNull(attachment.thumbnailWidth, attachment.thumbnailHeight) ?: (4f / 3f)
    val name = attachmentThumbKey(attachment.id)
    val image = if (revealed) {
        rememberMediaPainter(session, name, THUMBNAIL_PX, animate = true) {
            session.guardGone(messageId, attachment.id) { session.api.attachmentThumbnail(attachment.id) }
        }.value
    } else {
        null
    }
    // O formato vem dos bytes (não do MIME do servidor) e só se lê depois que a imagem chegou, já no cache.
    val isGif by produceState(false, image) {
        value = image != null && session.media.peek(name)?.let { sniffImage(it).mayAnimate } == true
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
            image != null -> Image(image.rememberedPainter(), attachment.fileName, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        if (isGif) GifBadge(Modifier.align(Alignment.BottomStart).padding(6.dp))
    }
}

/** O selo **GIF** no canto da miniatura: um disco escuro com texto branco, legível sobre qualquer imagem nos dois temas. */
@Composable
private fun GifBadge(modifier: Modifier = Modifier) {
    Text(
        stringResource(Res.string.attachment_gif_badge),
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
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
