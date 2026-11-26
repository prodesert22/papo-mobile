package chat.papo.mobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.Message
import chat.papo.mobile.data.api.AttachmentDto
import chat.papo.mobile.media.AudioPlayback
import chat.papo.mobile.media.AudioSpeed
import chat.papo.mobile.media.AudioTrack
import chat.papo.mobile.media.ModerationView
import chat.papo.mobile.media.PlayableMedia
import chat.papo.mobile.media.PlaybackFailure
import chat.papo.mobile.media.formatClock
import chat.papo.mobile.media.moderationView
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.action_retry
import chat.papo.mobile.resources.audio_default_title
import chat.papo.mobile.resources.audio_failed_forbidden
import chat.papo.mobile.resources.audio_failed_gone
import chat.papo.mobile.resources.audio_failed_network
import chat.papo.mobile.resources.audio_failed_not_found
import chat.papo.mobile.resources.audio_failed_rate_limited
import chat.papo.mobile.resources.audio_failed_session_ended
import chat.papo.mobile.resources.audio_failed_unavailable
import chat.papo.mobile.resources.audio_failed_unsupported
import chat.papo.mobile.resources.audio_loading
import chat.papo.mobile.resources.audio_pause
import chat.papo.mobile.resources.audio_play
import chat.papo.mobile.resources.audio_position
import chat.papo.mobile.resources.audio_speed_change
import chat.papo.mobile.resources.audio_speed_fast
import chat.papo.mobile.resources.audio_speed_faster
import chat.papo.mobile.resources.audio_speed_normal
import chat.papo.mobile.resources.audio_time
import chat.papo.mobile.resources.chat_sensitive
import chat.papo.mobile.resources.msg_download
import chat.papo.mobile.resources.video_download_failed
import chat.papo.mobile.ui.components.formatBytes
import chat.papo.mobile.ui.media.failureActions
import chat.papo.mobile.ui.media.rememberAttachmentDownload
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Largura máxima do cartão de áudio na lista, a mesma do cartão de arquivo. */
private val MaxWidth = 320.dp

/**
 * Cartão de um áudio anexado: ▶/⏸, o nome e o tamanho, a barra de busca com o tempo, a velocidade e o **Baixar**. Só um áudio
 * toca por vez no app ([AudioPlayback]); este cartão mostra o estado só quando o áudio dele é o aberto. Áudio marcado como
 * sensível fica escondido, sem pedir nada, até o primeiro toque; virar sensível depois de aberto esconde de novo (o áudio
 * que já tocava segue na [chat.papo.mobile.ui.components.AudioBar]).
 */
@Composable
internal fun AudioAttachment(
    session: AccountSession,
    message: Message,
    attachment: AttachmentDto,
    media: PlayableMedia,
    audio: AudioPlayback,
) {
    // A chave inclui o estado: virar sensível depois de aberto esconde de novo.
    val sensitive = moderationView(attachment.moderationStatus) == ModerationView.Sensitive
    var revealed by remember(attachment.id, sensitive) { mutableStateOf(!sensitive) }
    if (revealed) {
        AudioPlayerCard(session, message, attachment, media, audio)
    } else {
        Row(
            Modifier
                .widthIn(max = MaxWidth)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable { revealed = true }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.VisibilityOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(Res.string.chat_sensitive),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AudioPlayerCard(
    session: AccountSession,
    message: Message,
    attachment: AttachmentDto,
    media: PlayableMedia,
    audio: AudioPlayback,
) {
    val track = remember(session.accountId, message.id, message.channelId, message.createdAtIso, media) {
        AudioTrack(session.accountId, media, message.channelId, message.id, message.createdAtIso)
    }
    // Com o cartão à vista, os controles são os dele; a barra de áudio só aparece quando ele sai da tela.
    DisposableEffect(audio, track.key) {
        audio.cardShown(track.key)
        onDispose { audio.cardHidden(track.key) }
    }
    // Só este áudio recompõe o cartão a cada posição: o dos outros cartões fica nulo e igual a si mesmo.
    val mine by produceState(audio.state.value.takeIf { it.track?.key == track.key }, audio, track.key) {
        audio.stateOf(track.key).collect { value = it }
    }
    val speed by audio.speed.collectAsState()
    val download = rememberAttachmentDownload(session, attachment)
    val failure = mine?.failure
    // 410: o anexo foi bloqueado pela moderação. Gravá-lo troca o cartão pelo aviso.
    LaunchedEffect(failure) {
        if (failure == PlaybackFailure.Gone) session.chat.markBlocked(message.id, attachment.id)
    }

    val playing = mine?.playing == true
    val durationMs = mine?.durationMs
    // A barra só busca ao soltar: cada busca em trecho não carregado é um pedido `Range`, e o servidor limita os pedidos por
    // endereço. Enquanto o dedo arrasta, a barra e o tempo seguem o dedo, sem pedir nada ao motor.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shownMs = dragging?.toLong() ?: mine?.positionMs ?: 0L

    Column(
        Modifier
            .fillMaxWidth()
            .widthIn(max = MaxWidth)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayButton(
                playing = playing,
                buffering = playing && mine?.buffering == true,
                failure = failure,
                onClick = { audio.toggle(track, session.client) },
            )
            Column(Modifier.weight(1f)) {
                Text(
                    attachment.fileName.ifBlank { stringResource(Res.string.audio_default_title) },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                val problem = failure?.let { audioFailureMessage(it) } ?: Res.string.video_download_failed.takeIf { download.failed }
                Text(
                    if (problem != null) stringResource(problem) else formatBytes(attachment.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            IconButton(onClick = download::start, enabled = !download.downloading) {
                if (download.downloading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Download, contentDescription = stringResource(Res.string.msg_download), modifier = Modifier.size(20.dp))
                }
            }
        }
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val position = stringResource(Res.string.audio_position)
            val end = (durationMs ?: 0L).coerceAtLeast(1L).toFloat()
            Slider(
                value = shownMs.toFloat().coerceIn(0f, end),
                onValueChange = { dragging = it },
                modifier = Modifier.weight(1f).semantics { contentDescription = position },
                // Sem duração (o áudio ainda não abriu) não há para onde buscar.
                enabled = mine != null && failure == null && durationMs != null && durationMs > 0L,
                valueRange = 0f..end,
                onValueChangeFinished = {
                    dragging?.let { audio.seekTo(it.toLong()) }
                    dragging = null
                },
            )
            Text(
                if (durationMs != null) {
                    stringResource(Res.string.audio_time, formatClock(shownMs), formatClock(durationMs))
                } else {
                    formatClock(shownMs)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val speedLabel = stringResource(speedText(speed))
            val speedDescription = stringResource(Res.string.audio_speed_change, speedLabel)
            TextButton(
                onClick = audio::cycleSpeed,
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.semantics { contentDescription = speedDescription },
            ) {
                Text(speedLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** ▶, ⏸ ou, depois de uma falha, de novo; com um anel enquanto o áudio carrega. */
@Composable
private fun PlayButton(playing: Boolean, buffering: Boolean, failure: PlaybackFailure?, onClick: () -> Unit) {
    // Onde o mesmo pedido falharia igual (sem sessão, sem acesso, sem arquivo, formato sem suporte), tocar de novo não resolve.
    val enabled = failure == null || failureActions(failure, canDownload = true).retry
    Box(contentAlignment = Alignment.Center) {
        IconButton(onClick = onClick, enabled = enabled) {
            when {
                failure != null -> Icon(Icons.Filled.Refresh, contentDescription = stringResource(Res.string.action_retry))
                playing -> Icon(Icons.Filled.Pause, contentDescription = stringResource(Res.string.audio_pause))
                else -> Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(Res.string.audio_play))
            }
        }
        if (buffering) {
            val loading = stringResource(Res.string.audio_loading)
            CircularProgressIndicator(Modifier.size(40.dp).semantics { contentDescription = loading }, strokeWidth = 2.dp)
        }
    }
}

/** O texto da velocidade no botão. */
internal fun speedText(speed: AudioSpeed): StringResource = when (speed) {
    AudioSpeed.Normal -> Res.string.audio_speed_normal
    AudioSpeed.Fast -> Res.string.audio_speed_fast
    AudioSpeed.Faster -> Res.string.audio_speed_faster
}

/** O texto de cada falha do áudio. */
internal fun audioFailureMessage(failure: PlaybackFailure): StringResource = when (failure) {
    PlaybackFailure.SessionEnded -> Res.string.audio_failed_session_ended
    PlaybackFailure.Forbidden -> Res.string.audio_failed_forbidden
    PlaybackFailure.NotFound -> Res.string.audio_failed_not_found
    PlaybackFailure.Gone -> Res.string.audio_failed_gone
    PlaybackFailure.RateLimited -> Res.string.audio_failed_rate_limited
    PlaybackFailure.Unavailable -> Res.string.audio_failed_unavailable
    PlaybackFailure.Unsupported -> Res.string.audio_failed_unsupported
    PlaybackFailure.Network -> Res.string.audio_failed_network
}
