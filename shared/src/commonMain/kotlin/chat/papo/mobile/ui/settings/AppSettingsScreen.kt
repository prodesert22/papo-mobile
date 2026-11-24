package chat.papo.mobile.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.action_back
import chat.papo.mobile.resources.lock_disable_prompt_title
import chat.papo.mobile.resources.lock_enable_prompt_subtitle
import chat.papo.mobile.resources.lock_enable_prompt_title
import chat.papo.mobile.resources.lock_prompt_subtitle
import chat.papo.mobile.resources.settings_lock
import chat.papo.mobile.resources.settings_lock_body
import chat.papo.mobile.resources.settings_lock_failed
import chat.papo.mobile.resources.settings_lock_not_enrolled
import chat.papo.mobile.resources.settings_lock_timeout
import chat.papo.mobile.resources.settings_lock_timeout_1m
import chat.papo.mobile.resources.settings_lock_timeout_5m
import chat.papo.mobile.resources.settings_lock_timeout_immediately
import chat.papo.mobile.resources.settings_lock_unsupported
import chat.papo.mobile.resources.settings_secure_screen
import chat.papo.mobile.resources.settings_secure_screen_body
import chat.papo.mobile.resources.settings_security
import chat.papo.mobile.resources.settings_storage
import chat.papo.mobile.resources.settings_storage_body
import chat.papo.mobile.resources.settings_title
import chat.papo.mobile.resources.settings_version
import chat.papo.mobile.security.AppLock
import chat.papo.mobile.security.AuthPrompt
import chat.papo.mobile.security.LockChange
import chat.papo.mobile.security.LockTimeout
import chat.papo.mobile.security.UserAuthAvailability
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(lock: AppLock, appVersion: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val lockEnabled by lock.lockEnabled.collectAsStateWithLifecycle()
    val timeout by lock.timeout.collectAsStateWithLifecycle()
    val secureScreen by lock.secureScreen.collectAsStateWithLifecycle()
    var availability by remember { mutableStateOf(lock.userAuthAvailability()) }
    var changing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    val enablePrompt = AuthPrompt(
        stringResource(Res.string.lock_enable_prompt_title),
        stringResource(Res.string.lock_enable_prompt_subtitle),
    )
    val disablePrompt = AuthPrompt(
        stringResource(Res.string.lock_disable_prompt_title),
        stringResource(Res.string.lock_prompt_subtitle),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text(stringResource(Res.string.settings_security), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))

            val canToggle = lockEnabled || availability == UserAuthAvailability.Available
            SwitchRow(
                title = stringResource(Res.string.settings_lock),
                body = when {
                    failed -> stringResource(Res.string.settings_lock_failed)
                    canToggle -> stringResource(Res.string.settings_lock_body)
                    availability == UserAuthAvailability.NotEnrolled -> stringResource(Res.string.settings_lock_not_enrolled)
                    else -> stringResource(Res.string.settings_lock_unsupported)
                },
                bodyIsError = failed,
                checked = lockEnabled,
                enabled = canToggle && !changing,
                onChange = { enable ->
                    changing = true
                    failed = false
                    scope.launch {
                        val result = lock.setLockEnabled(enable, if (enable) enablePrompt else disablePrompt)
                        failed = result == LockChange.Failed
                        availability = lock.userAuthAvailability()
                        changing = false
                    }
                },
            )

            if (lockEnabled) {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(Res.string.settings_lock_timeout), style = MaterialTheme.typography.titleMedium)
                LockTimeout.entries.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = timeout == option, role = Role.RadioButton, onClick = { lock.setTimeout(option) })
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = timeout == option, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(
                                when (option) {
                                    LockTimeout.Immediately -> Res.string.settings_lock_timeout_immediately
                                    LockTimeout.OneMinute -> Res.string.settings_lock_timeout_1m
                                    LockTimeout.FiveMinutes -> Res.string.settings_lock_timeout_5m
                                },
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                SwitchRow(
                    title = stringResource(Res.string.settings_secure_screen),
                    body = stringResource(Res.string.settings_secure_screen_body),
                    checked = secureScreen,
                    enabled = true,
                    onChange = lock::setSecureScreen,
                )
            }

            Spacer(Modifier.height(28.dp))
            Text(stringResource(Res.string.settings_storage), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(Res.string.settings_storage_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (appVersion.isNotEmpty()) {
                Spacer(Modifier.height(28.dp))
                Text(
                    stringResource(Res.string.settings_version, appVersion),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    body: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    bodyIsError: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = if (bodyIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
