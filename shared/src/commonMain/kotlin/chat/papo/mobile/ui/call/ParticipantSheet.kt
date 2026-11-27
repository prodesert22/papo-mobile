package chat.papo.mobile.ui.call

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.User
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.call_volume_distortion
import chat.papo.mobile.resources.call_volume_mute
import chat.papo.mobile.resources.call_volume_screen
import chat.papo.mobile.resources.call_volume_unmute
import chat.papo.mobile.resources.call_volume_voice
import chat.papo.mobile.ui.components.UserAvatar
import chat.papo.mobile.voice.DEFAULT_VOLUME_PERCENT
import chat.papo.mobile.voice.MAX_VOLUME_PERCENT
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * O volume de uma pessoa da chamada, só para quem ouve: a voz dela, o áudio da transmissão de tela dela (se ela tem) e
 * "Silenciar para mim". Os valores ficam no banco da conta e não vão ao servidor. O nome vem do servidor e é só texto.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParticipantSheet(session: AccountSession, userId: String, user: User?, hasScreenAudio: Boolean, onDismiss: () -> Unit) {
    val volumes = session.voiceVolumes
    val levels by volumes.levels.collectAsStateWithLifecycle()
    // O controle deslizante grava com atraso; fechar a folha (ou a chamada acabar) grava o que faltou.
    DisposableEffect(volumes) { onDispose(volumes::flush) }

    val voice = levels.voiceOf(userId)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                UserAvatar(session, user, size = 40.dp, fallbackName = user?.displayName ?: "?")
                Spacer(Modifier.width(12.dp))
                Text(user?.displayName ?: "?", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(4.dp))
            VolumeControl(stringResource(Res.string.call_volume_voice, voice), voice) { volumes.setVoice(userId, it) }
            if (hasScreenAudio) {
                val screen = levels.screenOf(userId)
                VolumeControl(stringResource(Res.string.call_volume_screen, screen), screen) { volumes.setScreen(userId, it) }
            }
            Text(
                stringResource(Res.string.call_volume_distortion),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = { volumes.setVoice(userId, if (voice == 0) DEFAULT_VOLUME_PERCENT else 0) }) {
                Text(stringResource(if (voice == 0) Res.string.call_volume_unmute else Res.string.call_volume_mute))
            }
        }
    }
}

@Composable
private fun VolumeControl(label: String, percent: Int, onChange: (Int) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Slider(
            value = percent.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = 0f..MAX_VOLUME_PERCENT.toFloat(),
        )
    }
}
