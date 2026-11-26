package chat.papo.mobile.ui.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.action_cancel
import chat.papo.mobile.resources.blocked_uncertain
import chat.papo.mobile.resources.profile_block
import chat.papo.mobile.resources.profile_block_body
import chat.papo.mobile.resources.profile_block_title
import chat.papo.mobile.resources.profile_unblock
import chat.papo.mobile.ui.components.failureText
import org.jetbrains.compose.resources.stringResource

/**
 * "Bloquear" ou "Desbloquear" no perfil de outra pessoa. Bloquear pede confirmação, porque corta as conversas diretas
 * nos dois sentidos; desbloquear não, porque não reabre nada. [name] é o nome que a confirmação mostra.
 */
@Composable
internal fun BlockAction(session: AccountSession, userId: String, name: String) {
    // A sessão entra na chave: o estado de um bloqueio feito numa conta nunca aparece em outra.
    val viewModel = viewModel(key = "block/$userId/${session.hashCode()}") { BlockActionViewModel(session.blocks, userId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val blocked by session.blocks.blocked.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf(false) }
    val isBlocked = userId in blocked

    Column(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { if (isBlocked) viewModel.unblock() else confirming = true },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val color = if (isBlocked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
            Text(stringResource(if (isBlocked) Res.string.profile_unblock else Res.string.profile_block), color = color)
        }
        state.failure?.failureText(uncertain = stringResource(Res.string.blocked_uncertain))?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(Res.string.profile_block_title, name)) },
            text = { Text(stringResource(Res.string.profile_block_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        viewModel.block()
                    },
                ) { Text(stringResource(Res.string.profile_block), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}
