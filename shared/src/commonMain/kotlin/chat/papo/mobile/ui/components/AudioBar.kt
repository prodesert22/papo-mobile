package chat.papo.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.papo.mobile.media.AudioPlayback
import chat.papo.mobile.media.AudioTrack
import chat.papo.mobile.media.barTrack
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.audio_bar_open
import chat.papo.mobile.resources.audio_bar_stop
import chat.papo.mobile.resources.audio_default_title
import chat.papo.mobile.resources.audio_pause
import chat.papo.mobile.resources.audio_play
import org.jetbrains.compose.resources.stringResource

/**
 * O player de áudio da sessão aberta, para os cartões de áudio das conversas e para a [AudioBar]; null onde a plataforma
 * não tem um motor de áudio (e então os áudios aparecem como arquivos).
 */
val LocalAudioPlayback = staticCompositionLocalOf<AudioPlayback?> { null }

/** O que a barra mostra: o áudio e se ele toca; o resto do estado (a posição, a cada instante) não recompõe a barra. */
private data class BarModel(val track: AudioTrack, val playing: Boolean)

/**
 * A barra no topo da lista de conversas e da conversa quando há um áudio aberto cujo cartão não está na tela: o título,
 * ▶/⏸ e o botão de parar. Tocar na barra chama [onOpen], que leva à mensagem do áudio. Some sozinha com o áudio (ao fim,
 * ao parar, ao trocar de conta) e enquanto o cartão dele está à vista, que já tem os controles. Fica abaixo da `CallBar`
 * quando as duas aparecem: cada uma tem a sua linha.
 */
@Composable
fun AudioBar(onOpen: (AudioTrack) -> Unit, modifier: Modifier = Modifier) {
    val audio = LocalAudioPlayback.current ?: return
    val state = audio.state.collectAsState()
    val onScreen = audio.cardsOnScreen.collectAsState()
    val model by remember(audio) {
        derivedStateOf { state.value.barTrack(onScreen.value)?.let { BarModel(it, state.value.playing) } }
    }
    val current = model ?: return
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClickLabel = stringResource(Res.string.audio_bar_open)) { onOpen(current.track) }
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = audio::togglePause) {
            if (current.playing) {
                Icon(Icons.Filled.Pause, contentDescription = stringResource(Res.string.audio_pause), tint = MaterialTheme.colorScheme.primary)
            } else {
                Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(Res.string.audio_play), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.width(2.dp))
        Text(
            current.track.media.title.ifBlank { stringResource(Res.string.audio_default_title) },
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f).padding(vertical = 14.dp),
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
        IconButton(onClick = audio::stop) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.audio_bar_stop))
        }
    }
}
