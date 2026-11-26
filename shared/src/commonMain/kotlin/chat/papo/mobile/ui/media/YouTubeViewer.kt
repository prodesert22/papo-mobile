package chat.papo.mobile.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import chat.papo.mobile.chat.Message
import chat.papo.mobile.chat.youTubeId
import chat.papo.mobile.chat.youTubeKey
import chat.papo.mobile.chat.youTubeWatchUrl
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.action_close
import chat.papo.mobile.resources.embed_youtube_label
import chat.papo.mobile.resources.embed_youtube_open
import chat.papo.mobile.ui.components.LocalPlatformUi
import org.jetbrains.compose.resources.stringResource

/** O vídeo do YouTube aberto na tela cheia: o id validado e o título do embed (pode ser vazio). */
internal data class OpenYouTube(val videoId: String, val title: String)

/**
 * Refaz o [OpenYouTube] de [key] ([youTubeKey]) a partir das [messages], como [findOpenVideo] faz com os do servidor: depois
 * de girar o aparelho só a chave sobrevive. Null se nenhum embed das mensagens é o do YouTube dessa chave.
 */
internal fun findOpenYouTube(messages: List<Message>, key: String): OpenYouTube? {
    for (message in messages) {
        for (embed in message.embeds) {
            val id = youTubeId(embed) ?: continue
            if (youTubeKey(id) == key) return OpenYouTube(id, embed.title.orEmpty())
        }
    }
    return null
}

/**
 * Tela cheia do YouTube, em fundo preto: o título, o botão de fechar, **Abrir no YouTube** e o player, que carrega ao abrir
 * (quem abriu já tocou no ▶ ou fora dele, o consentimento). O player daqui não tem o botão de tela cheia do YouTube: já é a
 * tela cheia. O ponto em que o vídeo estava no cartão não passa para cá, porque o app não fala com o player.
 */
@Composable
internal fun YouTubeViewer(video: OpenYouTube, onDismiss: () -> Unit) {
    val platform = LocalPlatformUi.current
    val uriHandler = LocalUriHandler.current
    // Abrir fora usa a página montada do id validado, nunca o endereço do embed.
    val pageUrl = remember(video.videoId) { youTubeWatchUrl(video.videoId) }
    val openOutside: () -> Unit = { pageUrl?.let { openInBrowser(uriHandler, it) } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        platform.HideSystemBars()
        Column(Modifier.fillMaxSize().background(Color.Black)) {
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
                    video.title.ifBlank { stringResource(Res.string.embed_youtube_label) },
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                if (pageUrl != null) {
                    IconButton(onClick = openOutside) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = stringResource(Res.string.embed_youtube_open), tint = Color.White)
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))) {
                platform.YouTubePlayer(video.videoId, Modifier.fillMaxSize(), fullscreenButton = false, onOpenExternally = openOutside)
            }
        }
    }
}
