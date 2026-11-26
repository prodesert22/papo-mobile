package chat.papo.mobile.ui.media

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.chat.Message
import chat.papo.mobile.data.api.AttachmentDto
import chat.papo.mobile.media.PlayableMedia
import chat.papo.mobile.media.PlaybackFailure
import chat.papo.mobile.media.playableAttachment
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.action_close
import chat.papo.mobile.resources.action_retry
import chat.papo.mobile.resources.msg_download
import chat.papo.mobile.resources.video_download_failed
import chat.papo.mobile.resources.video_failed_gone
import chat.papo.mobile.resources.video_failed_network
import chat.papo.mobile.resources.video_failed_not_found
import chat.papo.mobile.resources.video_failed_rate_limited
import chat.papo.mobile.resources.video_failed_unauthorized
import chat.papo.mobile.resources.video_failed_unavailable
import chat.papo.mobile.resources.video_failed_unsupported
import chat.papo.mobile.ui.components.LocalPlatformUi
import org.jetbrains.compose.resources.stringResource

/**
 * O vídeo aberto no visualizador de uma conversa: a chave da mídia e o ponto em que ele está.
 *
 * A [positionMs] não é estado observável de propósito: muda a cada segundo e só importa quando se salva (girar o
 * aparelho) ou se recria o player, então escrevê-la não recompõe a conversa. O [Saver] a lê na hora de salvar.
 */
internal class PlayingVideo(val key: String, var positionMs: Long = 0L) {
    companion object {
        val Saver: Saver<PlayingVideo?, Any> = Saver(
            save = { video -> video?.let { listOf(it.key, it.positionMs) } },
            restore = { saved ->
                val parts = saved as? List<*>
                val key = parts?.getOrNull(0) as? String
                val position = parts?.getOrNull(1) as? Long
                if (key == null || position == null) null else PlayingVideo(key, position)
            },
        )
    }
}

/**
 * O que o visualizador abre: a [media] e, quando ela é um anexo, o [attachment] que o **Baixar** salva
 * (null onde não há arquivo a baixar).
 */
internal data class OpenVideo(val media: PlayableMedia, val attachment: AttachmentDto?)

/**
 * Refaz o [OpenVideo] de [key] a partir das [messages], que são de quem dá o servidor: depois de girar o aparelho só a
 * chave sobrevive (a mídia não se salva). Null se nenhum anexo toca mais com essa chave, por exemplo porque a
 * mensagem foi apagada.
 */
internal fun findOpenVideo(server: ServerUrl, messages: List<Message>, key: String): OpenVideo? {
    for (message in messages) {
        for (attachment in message.attachments) {
            val media = playableAttachment(server, attachment) ?: continue
            if (media.key == key) return OpenVideo(media, attachment)
        }
    }
    return null
}

/** O que o painel de falha oferece além de fechar. */
internal data class FailureActions(val retry: Boolean, val download: Boolean)

/**
 * Repetir só faz sentido onde a falha pode passar sozinha ([PlaybackFailure.RateLimited], [PlaybackFailure.Unavailable]
 * e [PlaybackFailure.Network]); sem acesso, sem arquivo e formato sem suporte, o mesmo pedido falharia igual. Baixar
 * precisa de um arquivo no servidor ([canDownload]) e de um pedido que ainda possa dar certo, e é a saída do formato
 * que o aparelho não toca.
 */
internal fun failureActions(failure: PlaybackFailure, canDownload: Boolean): FailureActions = when (failure) {
    PlaybackFailure.Unauthorized, PlaybackFailure.NotFound, PlaybackFailure.Gone -> FailureActions(retry = false, download = false)
    PlaybackFailure.Unsupported -> FailureActions(retry = false, download = canDownload)
    PlaybackFailure.RateLimited, PlaybackFailure.Unavailable, PlaybackFailure.Network ->
        FailureActions(retry = true, download = canDownload)
}

/** Último ponto conhecido do vídeo: um campo comum, sem recompor, lido só quando o player é recriado. */
private class Resume(var positionMs: Long)

/**
 * Tela cheia do vídeo, em fundo preto: o título, o botão de fechar e o player da plataforma, que fala com o servidor
 * pelo `PapoClient` da [session] dona da mensagem. Se o player desiste, o painel diz o motivo e oferece o que ainda
 * adianta (ver [failureActions]); **Tentar de novo** recria o player no último ponto conhecido.
 *
 * [startAtMs] é só o ponto de partida; [onPosition] recebe o ponto atual enquanto toca.
 */
@Composable
internal fun VideoViewer(
    session: AccountSession,
    video: OpenVideo,
    startAtMs: Long,
    onPosition: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val platform = LocalPlatformUi.current
    val download = video.attachment?.let { rememberAttachmentDownload(session, it) }
    var attempt by remember { mutableIntStateOf(0) }
    var failure by remember { mutableStateOf<PlaybackFailure?>(null) }
    // A barra de título acompanha os controles do player: some com eles e deixa o vídeo limpo.
    var controlsVisible by remember { mutableStateOf(true) }
    val resume = remember { Resume(startAtMs) }
    // Tela cheia de verdade: sem a largura padrão do diálogo e sem deixar a janela ao redor das barras do sistema (o
    // player as esconde). A barra de título abaixo já respeita os recuos, então nada importante fica sob elas.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val failed = failure
            if (failed == null) {
                // O ponto de partida é fixado por tentativa: mudá-lo durante a reprodução não pode reiniciar o player.
                val startAt = remember(attempt) { resume.positionMs }
                key(attempt) {
                    platform.VideoPlayer(
                        client = session.client,
                        media = video.media,
                        startAtMs = startAt,
                        modifier = Modifier.fillMaxSize(),
                        onPosition = {
                            resume.positionMs = it
                            onPosition(it)
                        },
                        onFailure = { failure = it },
                        onControlsVisible = { controlsVisible = it },
                    )
                }
            } else {
                FailurePanel(
                    failure = failed,
                    actions = failureActions(failed, canDownload = download != null),
                    download = download,
                    onRetry = {
                        failure = null
                        attempt++
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Sem o player (painel de falha) não há controles, e o título com o fechar fica sempre à vista.
            AnimatedVisibility(
                visible = controlsVisible || failure != null,
                modifier = Modifier.align(Alignment.TopStart),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.action_close), tint = Color.White)
                    }
                    Text(
                        video.media.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FailurePanel(
    failure: PlaybackFailure,
    actions: FailureActions,
    download: AttachmentDownload?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.padding(horizontal = 24.dp, vertical = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text(
            failureText(failure),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        if (actions.retry || (actions.download && download != null)) {
            Spacer(Modifier.height(20.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (actions.retry) {
                    Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) {
                        Text(stringResource(Res.string.action_retry))
                    }
                }
                if (actions.download && download != null) {
                    OutlinedButton(
                        onClick = download::start,
                        enabled = !download.downloading,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White, disabledContentColor = Color.White.copy(alpha = 0.6f)),
                    ) {
                        if (download.downloading) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        } else {
                            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.msg_download))
                    }
                }
            }
            if (download?.failed == true) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(Res.string.video_download_failed),
                    style = MaterialTheme.typography.labelLarge,
                    color = DownloadFailedColor,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** O vermelho de erro do tema escuro, legível sobre o preto nos dois temas do app. */
private val DownloadFailedColor = Color(0xFFFFB4AB)

@Composable
private fun failureText(failure: PlaybackFailure): String = stringResource(
    when (failure) {
        PlaybackFailure.Unauthorized -> Res.string.video_failed_unauthorized
        PlaybackFailure.NotFound -> Res.string.video_failed_not_found
        PlaybackFailure.Gone -> Res.string.video_failed_gone
        PlaybackFailure.RateLimited -> Res.string.video_failed_rate_limited
        PlaybackFailure.Unavailable -> Res.string.video_failed_unavailable
        PlaybackFailure.Unsupported -> Res.string.video_failed_unsupported
        PlaybackFailure.Network -> Res.string.video_failed_network
    },
)
