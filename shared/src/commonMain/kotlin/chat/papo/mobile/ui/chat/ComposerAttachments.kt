package chat.papo.mobile.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.papo.mobile.chat.AttachmentProblem
import chat.papo.mobile.chat.MAX_ATTACHMENTS
import chat.papo.mobile.chat.MAX_ATTACHMENTS_TOTAL_MB
import chat.papo.mobile.chat.MAX_ATTACHMENT_MB
import chat.papo.mobile.chat.isGif
import chat.papo.mobile.chat.isVisualMedia
import chat.papo.mobile.data.api.OutgoingFile
import chat.papo.mobile.media.carriesLocation
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.attach_badge_gif
import chat.papo.mobile.resources.attach_file
import chat.papo.mobile.resources.attach_has_location
import chat.papo.mobile.resources.attach_photo_video
import chat.papo.mobile.resources.attach_problem_file_too_large
import chat.papo.mobile.resources.attach_problem_too_many
import chat.papo.mobile.resources.attach_problem_total_too_large
import chat.papo.mobile.resources.attach_unavailable
import chat.papo.mobile.resources.chat_attach
import chat.papo.mobile.resources.chat_remove_attachment
import chat.papo.mobile.ui.components.LocalPlatformUi
import chat.papo.mobile.ui.components.PlatformImage
import chat.papo.mobile.ui.components.formatBytes
import chat.papo.mobile.ui.components.rememberedPainter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

private val PREVIEW_SIZE = 72.dp

/**
 * O clipe do compositor: abre um menu com a galeria de fotos e vídeos (que o sistema entrega sem pedir permissão
 * de armazenamento) e o seletor de arquivos. [freeSlots] são as vagas que restam na mensagem, o teto do seletor de
 * fotos; o que passar disso é recusado com aviso por quem recebe os arquivos.
 */
@Composable
internal fun AttachButton(enabled: Boolean, freeSlots: Int, onFiles: (List<OutgoingFile>) -> Unit) {
    val platform = LocalPlatformUi.current
    val pickMedia = platform.rememberMediaPicker(freeSlots, onFiles)
    val pickFile = platform.rememberFilePicker(onFiles)
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled) {
            Icon(Icons.Filled.AttachFile, contentDescription = stringResource(Res.string.chat_attach))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.attach_photo_video)) },
                leadingIcon = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null) },
                onClick = {
                    open = false
                    pickMedia()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.attach_file)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null) },
                onClick = {
                    open = false
                    pickFile()
                },
            )
        }
    }
}

/** Os arquivos escolhidos e ainda não enviados, numa fileira que rola: prévia de 72 dp para foto e vídeo, nome e tamanho para o resto. */
@Composable
internal fun AttachmentTray(files: List<OutgoingFile>, onRemove: (OutgoingFile) -> Unit) {
    if (files.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        files.forEach { file ->
            key(file) {
                if (file.isVisualMedia() && !file.unavailable) PictureCard(file, onRemove = { onRemove(file) }) else FileCard(file, onRemove = { onRemove(file) })
            }
        }
    }
}

/** Por que o último lote de arquivos escolhidos não entrou por inteiro, para o aviso da conversa. */
@Composable
internal fun AttachmentProblem.text(): String = when (this) {
    AttachmentProblem.TooMany -> stringResource(Res.string.attach_problem_too_many, MAX_ATTACHMENTS.toString())
    is AttachmentProblem.FileTooLarge -> stringResource(Res.string.attach_problem_file_too_large, name, MAX_ATTACHMENT_MB.toString())
    AttachmentProblem.TotalTooLarge -> stringResource(Res.string.attach_problem_total_too_large, MAX_ATTACHMENTS_TOTAL_MB.toString())
}

/** Foto ou vídeo: a prévia reduzida, com o selo GIF e o ✕ por cima, e, quando a foto traz a localização, o aviso ao lado. */
@Composable
private fun PictureCard(file: OutgoingFile, onRemove: () -> Unit) {
    val previewPx = with(LocalDensity.current) { PREVIEW_SIZE.roundToPx() }
    val image by rememberPreview(file, previewPx)
    val hasLocation by produceState(false, file) { value = withContext(Dispatchers.Default) { file.carriesLocation() } }
    Row(Modifier.height(PREVIEW_SIZE).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Box(Modifier.size(PREVIEW_SIZE)) {
            val picture = image
            if (picture != null) {
                Image(picture.rememberedPainter(), contentDescription = file.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(
                    if (file.mimeType.startsWith("video/", ignoreCase = true)) Icons.Filled.Videocam else Icons.Filled.Image,
                    contentDescription = file.name,
                    modifier = Modifier.align(Alignment.Center),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (file.isGif()) GifBadge(Modifier.align(Alignment.BottomStart).padding(4.dp))
            RemoveButton(onRemove, Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
        if (hasLocation) {
            Column(Modifier.width(112.dp).fillMaxHeight().padding(horizontal = 8.dp), verticalArrangement = Arrangement.Center) {
                Icon(Icons.Filled.LocationOn, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                Text(stringResource(Res.string.attach_has_location), style = MaterialTheme.typography.labelSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Qualquer outro arquivo, ou o que o provedor já perdeu: nome e tamanho (ou o aviso de que ficou indisponível) e o ✕. */
@Composable
private fun FileCard(file: OutgoingFile, onRemove: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(140.dp)) {
            Text(file.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            if (file.unavailable) {
                Text(stringResource(Res.string.attach_unavailable), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            } else {
                file.size?.let {
                    Text(formatBytes(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Close, stringResource(Res.string.chat_remove_attachment), Modifier.size(16.dp))
        }
    }
}

@Composable
private fun GifBadge(modifier: Modifier) {
    Text(
        stringResource(Res.string.attach_badge_gif),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        modifier = modifier.clip(RoundedCornerShape(4.dp)).background(SCRIM).padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/** O ✕ fica sobre a foto, que pode ter qualquer cor: fundo escuro e ícone branco valem nos dois temas. */
@Composable
private fun RemoveButton(onClick: () -> Unit, modifier: Modifier) {
    val label = stringResource(Res.string.chat_remove_attachment)
    Box(
        modifier.size(28.dp).clip(CircleShape).background(SCRIM).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Close, contentDescription = label, modifier = Modifier.size(16.dp), tint = Color.White)
    }
}

private val SCRIM = Color.Black.copy(alpha = 0.55f)

/** A prévia de [file] em [px]; esta função é a dona da imagem e a fecha ao trocar ou sair da composição. */
@Composable
private fun rememberPreview(file: OutgoingFile, px: Int): State<PlatformImage?> {
    val image = produceState<PlatformImage?>(null, file, px) {
        value = try {
            file.preview(px)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // sem prévia: o cartão mostra o ícone do tipo
        }
    }
    val current = image.value
    DisposableEffect(current) { onDispose { current?.close() } }
    return image
}
