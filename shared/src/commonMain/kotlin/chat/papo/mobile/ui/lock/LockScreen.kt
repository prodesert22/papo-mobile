package chat.papo.mobile.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.action_cancel
import chat.papo.mobile.resources.lock_body
import chat.papo.mobile.resources.lock_prompt_subtitle
import chat.papo.mobile.resources.lock_prompt_title
import chat.papo.mobile.resources.lock_reset
import chat.papo.mobile.resources.lock_reset_body
import chat.papo.mobile.resources.lock_reset_confirm
import chat.papo.mobile.resources.lock_reset_title
import chat.papo.mobile.resources.lock_title
import chat.papo.mobile.resources.lock_unlock
import chat.papo.mobile.security.AppLock
import chat.papo.mobile.security.AuthPrompt
import chat.papo.mobile.security.LockState
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
fun LockScreen(lock: AppLock, state: LockState.Locked) {
    val scope = rememberCoroutineScope()
    val prompt = AuthPrompt(stringResource(Res.string.lock_prompt_title), stringResource(Res.string.lock_prompt_subtitle))
    var confirmReset by remember { mutableStateOf(false) }

    // Pede a digital assim que a tela aparece; se o usuário fechar o pedido, fica o botão.
    LaunchedEffect(Unit) {
        if (state.error == null) lock.unlock(prompt)
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 28.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Text("papo", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(20.dp))
            Text(stringResource(Res.string.lock_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                state.error ?: stringResource(Res.string.lock_body),
                style = MaterialTheme.typography.bodyLarge,
                color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = { scope.launch { lock.unlock(prompt) } },
                enabled = !state.authenticating,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(stringResource(Res.string.lock_unlock)) }
            if (state.error != null) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.lock_reset), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(Res.string.lock_reset_title)) },
            text = { Text(stringResource(Res.string.lock_reset_body)) },
            confirmButton = {
                TextButton(onClick = { confirmReset = false; scope.launch { lock.resetLocalData() } }) {
                    Text(stringResource(Res.string.lock_reset_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(Res.string.action_cancel)) }
            },
        )
    }
}
