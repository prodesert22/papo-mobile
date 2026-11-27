package chat.papo.mobile.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.profile_edit_saving
import chat.papo.mobile.resources.profile_status_available
import chat.papo.mobile.resources.profile_status_away
import chat.papo.mobile.resources.profile_status_busy
import chat.papo.mobile.resources.profile_status_title
import chat.papo.mobile.ui.components.StatusDisc
import org.jetbrains.compose.resources.stringResource

/**
 * Disponível, Ausente ou Ocupado, três botões de escolha: o marcado é o status atual da conta. Enquanto um pedido
 * está saindo, a escolha aparece marcada com "Salvando…" e os botões esperam; se falha, volta à anterior e diz por quê.
 */
@Composable
fun OwnStatusPicker(viewModel: OwnStatusViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(modifier) {
        Text(stringResource(Res.string.profile_status_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        // Os três na mesma linha, de larguras iguais: em tela estreita um deles não cai para a linha de baixo.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OwnStatus.entries.forEach { status ->
                FilterChip(
                    selected = state.selected == status,
                    onClick = { viewModel.choose(status) },
                    enabled = !state.saving,
                    modifier = Modifier.weight(1f),
                    label = {
                        Text(
                            stringResource(status.label()),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                        )
                    },
                    leadingIcon = { StatusDisc(status.presence(), size = 10.dp) },
                )
            }
        }
        if (state.saving) {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(Res.string.profile_edit_saving), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.problem?.failureText()?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** O valor de presença que o disco colore: disponível é `online`. */
private fun OwnStatus.presence() = when (this) {
    OwnStatus.Available -> "online"
    OwnStatus.Away -> "away"
    OwnStatus.Busy -> "busy"
}

private fun OwnStatus.label() = when (this) {
    OwnStatus.Available -> Res.string.profile_status_available
    OwnStatus.Away -> Res.string.profile_status_away
    OwnStatus.Busy -> Res.string.profile_status_busy
}
