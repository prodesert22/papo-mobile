package chat.papo.mobile.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.papo.mobile.AppSession
import chat.papo.mobile.account.Account
import chat.papo.mobile.account.AccountColors
import chat.papo.mobile.core.formatDateTime
import chat.papo.mobile.core.parseInstant
import chat.papo.mobile.data.api.ConnectionDto
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.account_blocked_users
import chat.papo.mobile.resources.account_color
import chat.papo.mobile.resources.account_color_none
import chat.papo.mobile.resources.account_device_drop
import chat.papo.mobile.resources.account_device_since
import chat.papo.mobile.resources.account_devices
import chat.papo.mobile.resources.account_devices_drop_all
import chat.papo.mobile.resources.account_devices_drop_all_body
import chat.papo.mobile.resources.account_devices_empty
import chat.papo.mobile.resources.account_label
import chat.papo.mobile.resources.account_label_help
import chat.papo.mobile.resources.account_label_hint
import chat.papo.mobile.resources.account_remove
import chat.papo.mobile.resources.account_remove_body
import chat.papo.mobile.resources.account_remove_confirm
import chat.papo.mobile.resources.account_remove_title
import chat.papo.mobile.resources.account_security
import chat.papo.mobile.resources.account_settings
import chat.papo.mobile.resources.action_back
import chat.papo.mobile.resources.action_cancel
import chat.papo.mobile.ui.components.AccountAvatar
import chat.papo.mobile.ui.components.UiError
import chat.papo.mobile.ui.components.readableOn
import chat.papo.mobile.ui.components.text
import chat.papo.mobile.ui.components.toUiError
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AccountSettingsScreen(session: AppSession, accountId: String, onBack: () -> Unit, onOpenBlocked: () -> Unit) {
    val accounts by session.accounts.accounts.collectAsStateWithLifecycle()
    val account = accounts.firstOrNull { it.id == accountId }
    val scope = rememberCoroutineScope()
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmDropAll by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf<List<ConnectionDto>?>(null) }
    var error by remember { mutableStateOf<UiError?>(null) }

    suspend fun loadDevices() {
        val api = session.accounts.session(accountId)?.auth ?: return
        try {
            devices = api.connectedDevices()
            error = null
        } catch (e: Throwable) {
            error = e.toUiError()
        }
    }

    LaunchedEffect(accountId, account?.sessionExpired) {
        if (account != null && !account.sessionExpired) loadDevices()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.account_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        if (account == null) return@Scaffold
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
                AccountAvatar(account, selected = true, ringBackground = MaterialTheme.colorScheme.background, size = 56.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(account.title, style = MaterialTheme.typography.titleLarge)
                    Text(account.handle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            LabelField(account) { label -> session.accounts.setLabel(account.id, label) }
            Spacer(Modifier.height(20.dp))
            Text(stringResource(Res.string.account_color), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ColorSwatch(null, selected = account.color == null, stringResource(Res.string.account_color_none)) {
                    session.accounts.setColor(account.id, null)
                }
                AccountColors.forEach { argb ->
                    ColorSwatch(Color(argb), selected = account.color == argb, label = null) {
                        session.accounts.setColor(account.id, argb)
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
            ServerSection(session.accounts, account)

            if (!account.sessionExpired) {
                Spacer(Modifier.height(28.dp))
                Text(stringResource(Res.string.account_security), style = MaterialTheme.typography.titleMedium)
                SettingsLink(stringResource(Res.string.account_blocked_users), onOpenBlocked)

                Spacer(Modifier.height(28.dp))
                Text(stringResource(Res.string.account_devices), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
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
                            stringResource(
                                Res.string.account_device_since,
                                parseInstant(connection.createdAt)?.formatDateTime() ?: "?",
                            ),
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

            Spacer(Modifier.height(28.dp))
            OutlinedButton(onClick = { confirmRemove = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(stringResource(Res.string.account_remove), color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmRemove && account != null) {
        ConfirmDialog(
            title = stringResource(Res.string.account_remove_title, account.handle),
            body = stringResource(Res.string.account_remove_body),
            confirm = stringResource(Res.string.account_remove_confirm),
            onDismiss = { confirmRemove = false },
            onConfirm = {
                confirmRemove = false
                onBack()
                session.accounts.removeInBackground(account.id)
            },
        )
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

/** Uma linha que leva a outra tela. */
@Composable
private fun SettingsLink(title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LabelField(account: Account, onSave: (String) -> Unit) {
    var text by remember(account.id) { mutableStateOf(account.label.orEmpty()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it.take(24)
            onSave(text)
        },
        label = { Text(stringResource(Res.string.account_label)) },
        placeholder = { Text(stringResource(Res.string.account_label_hint)) },
        supportingText = { Text(stringResource(Res.string.account_label_help)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ColorSwatch(color: Color?, selected: Boolean, label: String?, onClick: () -> Unit) {
    val outline = MaterialTheme.colorScheme.outline
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(color ?: Color.Transparent)
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.onSurface else outline, CircleShape)
            .clickable(role = Role.RadioButton, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            color == null -> Icon(Icons.Filled.Block, contentDescription = label, tint = outline, modifier = Modifier.size(20.dp))
            selected -> Icon(Icons.Filled.Check, contentDescription = null, tint = color.readableOn(), modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun ConfirmDialog(title: String, body: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}
