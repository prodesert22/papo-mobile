package chat.papo.mobile.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.papo.mobile.AppSession
import chat.papo.mobile.core.formatDateTime
import chat.papo.mobile.core.parseInstant
import chat.papo.mobile.data.api.ConnectionDto
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.account_device_drop
import chat.papo.mobile.resources.account_device_since
import chat.papo.mobile.resources.account_devices
import chat.papo.mobile.resources.account_devices_drop_all
import chat.papo.mobile.resources.account_devices_drop_all_body
import chat.papo.mobile.resources.account_devices_empty
import chat.papo.mobile.ui.components.UiError
import chat.papo.mobile.ui.components.text
import chat.papo.mobile.ui.components.toUiError
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Os aparelhos com sessão aberta na conta [accountId], com o desconectar de cada um e de todos. */
@Composable
fun DevicesScreen(session: AppSession, accountId: String, onBack: () -> Unit) {
    val accounts by session.accounts.accounts.collectAsStateWithLifecycle()
    val account = accounts.firstOrNull { it.id == accountId }
    val scope = rememberCoroutineScope()
    var confirmDropAll by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf<List<ConnectionDto>?>(null) }
    var error by remember { mutableStateOf<UiError?>(null) }

    suspend fun loadDevices() {
        val api = session.accounts.session(accountId)?.auth ?: return
        try {
            devices = api.connectedDevices()
            error = null
        } catch (e: Throwable) {
            error = e.toUiError() // relança o cancelamento
        }
    }

    LaunchedEffect(accountId, account?.sessionExpired) {
        if (account != null && !account.sessionExpired) loadDevices()
    }

    SettingsDetailScaffold(stringResource(Res.string.account_devices), onBack) {
        Column(Modifier.padding(horizontal = SettingsPadding, vertical = 8.dp)) {
            error?.let { Text(it.text(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            val list = devices
            if (list != null && list.isEmpty()) {
                Text(
                    stringResource(Res.string.account_devices_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            list?.forEach { connection ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(Res.string.account_device_since, parseInstant(connection.createdAt)?.formatDateTime() ?: "?"),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            scope.launch {
                                runCatching { session.accounts.session(accountId)?.auth?.dropConnection(connection.id) }
                                    .onFailure { error = it.toUiError() }
                                loadDevices()
                            }
                        },
                    ) { Text(stringResource(Res.string.account_device_drop)) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (!list.isNullOrEmpty()) {
                TextButton(onClick = { confirmDropAll = true }) {
                    Text(stringResource(Res.string.account_devices_drop_all), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (confirmDropAll) {
        ConfirmDialog(
            title = stringResource(Res.string.account_devices_drop_all),
            body = stringResource(Res.string.account_devices_drop_all_body),
            confirm = stringResource(Res.string.account_devices_drop_all),
            onDismiss = { confirmDropAll = false },
            onConfirm = {
                confirmDropAll = false
                scope.launch {
                    runCatching { session.accounts.session(accountId)?.auth?.dropConnection("ALL") }
                        .onFailure { error = it.toUiError() }
                    // A própria sessão foi revogada: a próxima chamada confirma e marca a conta como expirada.
                    loadDevices()
                }
            },
        )
    }
}
