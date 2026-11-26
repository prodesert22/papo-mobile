package chat.papo.mobile.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import chat.papo.mobile.chat.youTubeKey
import chat.papo.mobile.chat.youTubeWatchUrl
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.embed_youtube_label
import chat.papo.mobile.resources.embed_youtube_open
import chat.papo.mobile.ui.components.LocalPlatformUi
import chat.papo.mobile.ui.components.PlatformImage
import chat.papo.mobile.ui.components.rememberedPainter
import chat.papo.mobile.ui.media.VideoHost
import chat.papo.mobile.ui.media.openInBrowser
import org.jetbrains.compose.resources.stringResource

/** O player do YouTube é 16:9, e a caixa também: a imagem do embed (quase sempre 4:3 com tarjas) é cortada nessa proporção. */
private const val YOUTUBE_ASPECT = 16f / 9f

/**
 * A mídia de um embed do YouTube ([videoId] já validado por `youTubeId`): a imagem do embed com o ▶ e o rótulo "YouTube", e
 * embaixo **Abrir no YouTube**. **Nada é pedido ao YouTube antes do toque**: a capa é a imagem que o servidor já guardou, e
 * o player só é criado quando o ▶ é tocado, que é o consentimento (como no web). Depois do toque o player toca no próprio
 * cartão, e tocar fora do ▶ abre a tela cheia ([VideoBox]). Abrir fora do app usa a página que se monta do id validado
 * ([youTubeWatchUrl]), nunca o endereço do embed.
 */
@Composable
internal fun YouTubeMedia(
    messageId: String,
    videoId: String,
    image: PlatformImage?,
    videos: VideoHost,
    modifier: Modifier = Modifier,
) {
    val platform = LocalPlatformUi.current
    val uriHandler = LocalUriHandler.current
    val watchUrl = remember(videoId) { youTubeWatchUrl(videoId) }
    val openOutside: () -> Unit = { watchUrl?.let { openInBrowser(uriHandler, it) } }
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(YOUTUBE_ASPECT)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            VideoBox(
                messageId = messageId,
                mediaKey = youTubeKey(videoId),
                host = videos,
                embed = true,
                player = { _, _ ->
                    platform.YouTubePlayer(videoId, Modifier.fillMaxSize(), fullscreenButton = true, onOpenExternally = openOutside)
                },
            ) {
                image?.let { Image(it.rememberedPainter(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                YouTubeLabel(Modifier.align(Alignment.BottomStart).padding(8.dp))
            }
        }
        if (watchUrl != null) {
            TextButton(onClick = openOutside, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(Res.string.embed_youtube_open))
            }
        }
    }
}

/** O rótulo da capa: diz de onde o vídeo vem antes do toque, legível sobre qualquer imagem nos dois temas. */
@Composable
private fun YouTubeLabel(modifier: Modifier = Modifier) {
    Text(
        stringResource(Res.string.embed_youtube_label),
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
