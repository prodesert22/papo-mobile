package chat.papo.mobile.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.media.PlayableMedia
import chat.papo.mobile.media.PlaybackFailure
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.video_open_fullscreen
import chat.papo.mobile.resources.video_play
import chat.papo.mobile.ui.components.LocalPlatformUi
import chat.papo.mobile.ui.components.VideoPlayerState
import chat.papo.mobile.ui.media.LoadingBadge
import chat.papo.mobile.ui.media.PlayingVideo
import chat.papo.mobile.ui.media.VideoHost
import chat.papo.mobile.ui.media.cardKey
import chat.papo.mobile.ui.media.failureMessage
import org.jetbrains.compose.resources.stringResource

/**
 * O que ocupa a caixa de um vídeo tocável na lista, anexo ou embed, que preenche a caixa que o cartão deu e mostra:
 *
 * - **na capa** ([cover]): o ▶ do meio é um botão e toca o vídeo ali mesmo ([VideoHost.playInline]); tocar em qualquer
 *   outro ponto abre a tela cheia ([VideoHost.openFullscreen]);
 * - **tocando no cartão**: o [player], no lugar da capa e na mesma caixa (a lista não pula). Os controles são do player: o de
 *   vídeo do servidor não tem nenhum e abre a tela cheia com um toque ([ServerVideoBox]); o do YouTube tem os do próprio YouTube;
 * - **com falha**: a capa com o motivo curto por cima; tocar abre a tela cheia, que tem o painel completo. Quem avisa é o
 *   [player], por `onFailure`; o do YouTube não avisa (o player dele mostra o próprio erro).
 *
 * O [player] existe só enquanto este cartão está na composição: rolar para fora da lista, trocar de conversa e travar o app
 * o liberam, e o vídeo volta à capa sem guardar o ponto. [embed] só escolhe o texto da falha (o servidor é quem busca o
 * vídeo de um embed). O cartão é o da mensagem [messageId] e da mídia [mediaKey]: o mesmo link em duas mensagens é a mesma
 * mídia e só o cartão tocado toca.
 */
@Composable
internal fun VideoBox(
    messageId: String,
    mediaKey: String,
    host: VideoHost,
    embed: Boolean,
    player: @Composable (video: PlayingVideo, onFailure: (PlaybackFailure) -> Unit) -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
    cover: @Composable BoxScope.() -> Unit,
) {
    val cardKey = cardKey(messageId, mediaKey)
    val playing = host.inline(cardKey)
    var failure by remember(playing) { mutableStateOf<PlaybackFailure?>(null) }
    // O dono é este cartão, não o player: com a falha o player já saiu, e o cartão continua dono do vídeo até sair de cena.
    DisposableEffect(playing) {
        onDispose { playing?.let(host::release) }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (playing != null && failure == null) {
            player(playing) { failure = it }
        } else {
            cover()
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.video_open_fullscreen)) { host.openFullscreen(mediaKey) },
                contentAlignment = Alignment.Center,
            ) {
                failure?.let { FailureHint(it, embed) }
            }
            if (failure == null) PlayBadge { host.playInline(cardKey) }
        }
    }
}

/**
 * A caixa de um vídeo do servidor ([media]: um anexo ou o relay de um embed): o player da plataforma, só com a imagem e sem
 * controle nenhum. Um toque em qualquer ponto do vídeo abre a tela cheia a partir do ponto em que ele está. Enquanto ele toca
 * as capas dos outros vídeos esperam, como na tela cheia. Ver [VideoBox].
 */
@Composable
internal fun ServerVideoBox(
    session: AccountSession,
    messageId: String,
    media: PlayableMedia,
    host: VideoHost,
    embed: Boolean,
    cover: @Composable BoxScope.() -> Unit,
) {
    VideoBox(
        messageId = messageId,
        mediaKey = media.key,
        host = host,
        embed = embed,
        player = { video, onFailure -> InlineVideoPlayer(session, media, video, host, onFailure, cover) },
        cover = cover,
    )
}

/**
 * O player no lugar da capa, da posição em que o vídeo está. Fica sem controles: o vídeo toca limpo e um toque em qualquer
 * ponto o leva para o visualizador, do ponto atual, tirando o player daqui. A capa continua por cima até o primeiro quadro (sem
 * ela o ▶ daria num vazio preto), com o indicador de carregamento do tema, que volta se o vídeo trava esperando dados.
 */
@Composable
private fun InlineVideoPlayer(
    session: AccountSession,
    media: PlayableMedia,
    video: PlayingVideo,
    host: VideoHost,
    onFailure: (PlaybackFailure) -> Unit,
    cover: @Composable BoxScope.() -> Unit,
) {
    // Enquanto ele toca as capas esperam: o player e elas disputariam o limite de pedidos do servidor.
    DisposableEffect(session) {
        session.videoPosters.playerOpened()
        onDispose { session.videoPosters.playerClosed() }
    }
    val player = remember { VideoPlayerState() }
    Box(Modifier.fillMaxSize()) {
        LocalPlatformUi.current.VideoPlayer(
            client = session.client,
            media = media,
            startAtMs = video.positionMs,
            modifier = Modifier.fillMaxSize(),
            state = player,
            onPosition = { video.positionMs = it },
            onFailure = onFailure,
        )
        AnimatedVisibility(visible = !player.hasPicture, modifier = Modifier.fillMaxSize(), enter = EnterTransition.None, exit = fadeOut()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center, content = cover)
        }
        if (player.loading) LoadingBadge(Modifier.align(Alignment.Center).size(56.dp))
        // Por cima de tudo, para que o toque seja daqui e não do player; o gesto de rolar a lista passa por ele.
        Box(
            Modifier
                .fillMaxSize()
                .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.video_open_fullscreen)) {
                    host.openFullscreen(media.key, player.resumePositionMs)
                },
        )
    }
}

/** O motivo curto da falha sobre a capa, legível sobre qualquer imagem nos dois temas. */
@Composable
private fun FailureHint(failure: PlaybackFailure, embed: Boolean) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).padding(12.dp), contentAlignment = Alignment.Center) {
        Text(
            stringResource(failureMessage(failure, embed)),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * O ▶ no meio da caixa: um disco escuro com o triângulo branco, legível sobre qualquer fundo nos dois temas. É um botão
 * de 52 dp (mais que os 48 dp do toque mínimo) que chama [onClick]; o resto da caixa abre a tela cheia.
 */
@Composable
internal fun PlayBadge(onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(Res.string.video_play), tint = Color.White, modifier = Modifier.size(32.dp))
    }
}
