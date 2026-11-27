package chat.papo.mobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.papo.mobile.media.VoiceProblem
import chat.papo.mobile.media.VoiceRecording
import chat.papo.mobile.media.VoiceRecordingState
import chat.papo.mobile.media.formatClock
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.audio_pause
import chat.papo.mobile.resources.audio_play
import chat.papo.mobile.resources.audio_time
import chat.papo.mobile.resources.voice_discard
import chat.papo.mobile.resources.voice_problem_blocked
import chat.papo.mobile.resources.voice_problem_denied
import chat.papo.mobile.resources.voice_problem_failed
import chat.papo.mobile.resources.voice_problem_in_call
import chat.papo.mobile.resources.voice_problem_playback
import chat.papo.mobile.resources.voice_recording
import chat.papo.mobile.resources.voice_review_position
import chat.papo.mobile.resources.voice_send
import chat.papo.mobile.resources.voice_stop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource

/** A gravação de mensagens de voz do app; null onde a plataforma não grava, e então o clipe não oferece gravar. */
val LocalVoiceRecording = staticCompositionLocalOf<VoiceRecording?> { null }

// Sem gravador na plataforma a tela lê estes valores fixos: nenhuma gravação, nenhum problema, nenhuma chamada.
internal val IDLE_VOICE: StateFlow<VoiceRecordingState> = MutableStateFlow(VoiceRecordingState.Idle)
internal val NO_VOICE_PROBLEM: StateFlow<VoiceProblem?> = MutableStateFlow(null)
internal val NO_CALL: StateFlow<Boolean> = MutableStateFlow(false)

/**
 * O lugar do campo de texto enquanto há uma gravação desta conversa: gravando, o indicador, o tempo contra o teto e o
 * ⏹; na revisão, ▶/⏸ com a barra de busca e o tempo, o descarte e o envio. [onSend] entrega a gravação à conversa.
 */
@Composable
internal fun VoiceRecorderBar(voice: VoiceRecording, onSend: () -> Unit) {
    val state by voice.state.collectAsStateWithLifecycle()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = voice::discard) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(Res.string.voice_discard), tint = MaterialTheme.colorScheme.error)
        }
        when (val current = state) {
            VoiceRecordingState.Idle -> Unit
            is VoiceRecordingState.Recording -> RecordingControls(current, onStop = voice::stop, modifier = Modifier.weight(1f))
            is VoiceRecordingState.Review -> ReviewControls(voice, current, onSend, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun RecordingControls(state: VoiceRecordingState.Recording, onStop: () -> Unit, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(8.dp))
        Spacer(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(Res.string.voice_recording), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            stringResource(Res.string.audio_time, formatClock(state.elapsedMs), formatClock(state.maxMs)),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        FilledIconButton(onClick = onStop) {
            Icon(Icons.Filled.Stop, contentDescription = stringResource(Res.string.voice_stop))
        }
    }
}

@Composable
private fun ReviewControls(voice: VoiceRecording, state: VoiceRecordingState.Review, onSend: () -> Unit, modifier: Modifier) {
    val duration = state.clip.durationMs
    // A barra só busca ao soltar, como a dos anexos: enquanto o dedo arrasta, a barra e o tempo seguem o dedo.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shownMs = dragging?.toLong() ?: state.positionMs
    val position = stringResource(Res.string.voice_review_position)
    val end = duration.coerceAtLeast(1L).toFloat()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = voice::togglePlay) {
            if (state.playing) {
                Icon(Icons.Filled.Pause, contentDescription = stringResource(Res.string.audio_pause), tint = MaterialTheme.colorScheme.primary)
            } else {
                Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(Res.string.audio_play), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Slider(
            value = shownMs.toFloat().coerceIn(0f, end),
            onValueChange = { dragging = it },
            modifier = Modifier.weight(1f).semantics { contentDescription = position },
            valueRange = 0f..end,
            onValueChangeFinished = {
                dragging?.let { voice.seekTo(it.toLong()) }
                dragging = null
            },
        )
        Text(
            stringResource(Res.string.audio_time, formatClock(shownMs), formatClock(duration)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        FilledIconButton(onClick = onSend) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(Res.string.voice_send))
        }
    }
}

/** O texto de cada problema da gravação, para o aviso da conversa. */
@Composable
internal fun VoiceProblem.text(): String = stringResource(
    when (this) {
        VoiceProblem.MicrophoneDenied -> Res.string.voice_problem_denied
        VoiceProblem.MicrophoneBlocked -> Res.string.voice_problem_blocked
        VoiceProblem.InCall -> Res.string.voice_problem_in_call
        VoiceProblem.RecorderFailed -> Res.string.voice_problem_failed
        VoiceProblem.PlaybackFailed -> Res.string.voice_problem_playback
    },
)
