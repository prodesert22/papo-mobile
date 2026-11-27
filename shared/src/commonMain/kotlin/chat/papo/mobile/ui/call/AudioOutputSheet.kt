package chat.papo.mobile.ui.call

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.call_output_bluetooth
import chat.papo.mobile.resources.call_output_button
import chat.papo.mobile.resources.call_output_current
import chat.papo.mobile.resources.call_output_earpiece
import chat.papo.mobile.resources.call_output_hearing_aid
import chat.papo.mobile.resources.call_output_other
import chat.papo.mobile.resources.call_output_speaker
import chat.papo.mobile.resources.call_output_title
import chat.papo.mobile.resources.call_output_usb
import chat.papo.mobile.resources.call_output_wired
import chat.papo.mobile.resources.call_speaker_off
import chat.papo.mobile.resources.call_speaker_on
import chat.papo.mobile.voice.AudioOutput
import chat.papo.mobile.voice.AudioOutputKind
import chat.papo.mobile.voice.CallController
import chat.papo.mobile.voice.CallState
import chat.papo.mobile.voice.OutputControl
import chat.papo.mobile.voice.outputControlFor
import org.jetbrains.compose.resources.stringResource

private fun iconOf(kind: AudioOutputKind?): ImageVector = when (kind) {
    AudioOutputKind.Earpiece -> Icons.Filled.PhoneInTalk
    AudioOutputKind.Wired -> Icons.Filled.Headset
    AudioOutputKind.Usb -> Icons.Filled.Usb
    AudioOutputKind.Bluetooth -> Icons.Filled.BluetoothAudio
    AudioOutputKind.HearingAid -> Icons.Filled.Hearing
    AudioOutputKind.Other -> Icons.Filled.Speaker
    AudioOutputKind.Speaker, null -> Icons.AutoMirrored.Filled.VolumeUp
}

@Composable
private fun labelOf(output: AudioOutput): String {
    // O nome vem do aparelho (texto remoto ao app): só aparece como texto simples.
    output.name?.let { return it }
    return stringResource(
        when (output.kind) {
            AudioOutputKind.Earpiece -> Res.string.call_output_earpiece
            AudioOutputKind.Speaker -> Res.string.call_output_speaker
            AudioOutputKind.Wired -> Res.string.call_output_wired
            AudioOutputKind.Usb -> Res.string.call_output_usb
            AudioOutputKind.Bluetooth -> Res.string.call_output_bluetooth
            AudioOutputKind.HearingAid -> Res.string.call_output_hearing_aid
            AudioOutputKind.Other -> Res.string.call_output_other
        },
    )
}

/**
 * O botão da saída de áudio da chamada. Com só o alto-falante e o fone do aparelho alterna entre os dois; com outra saída
 * (fone, Bluetooth) abre a folha para escolher; sem o que trocar, fica desligado.
 */
@Composable
internal fun OutputButton(controller: CallController, state: CallState) {
    val control = outputControlFor(state.outputs)
    var open by rememberSaveable { mutableStateOf(false) }
    val speaker = state.output?.kind == AudioOutputKind.Speaker
    ControlButton(
        icon = iconOf(state.output?.kind),
        label = stringResource(
            when {
                control == OutputControl.Picker -> Res.string.call_output_button
                speaker -> Res.string.call_speaker_off
                else -> Res.string.call_speaker_on
            },
        ),
        active = state.output != null && state.output?.kind != AudioOutputKind.Earpiece,
        enabled = control != OutputControl.None,
        onClick = if (control == OutputControl.Picker) ({ open = true }) else controller::toggleSpeaker,
    )
    // A lista pode encolher com a folha aberta (o fone foi tirado): sem escolha a fazer, ela se fecha.
    if (open && control == OutputControl.Picker) {
        AudioOutputSheet(state.outputs, state.output, onSelect = controller::selectOutput, onDismiss = { open = false })
    }
}

/** A lista das saídas de áudio da chamada, com a atual marcada. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioOutputSheet(outputs: List<AudioOutput>, current: AudioOutput?, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                stringResource(Res.string.call_output_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            for (output in outputs) {
                val selected = output.id == current?.id
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = {
                                onSelect(output.id)
                                onDismiss()
                            },
                        )
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(iconOf(output.kind), contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(
                        labelOf(output),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (selected) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = stringResource(Res.string.call_output_current),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}
