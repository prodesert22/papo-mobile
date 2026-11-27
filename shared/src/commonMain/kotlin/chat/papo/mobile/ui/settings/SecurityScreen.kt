package chat.papo.mobile.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.lock_disable_prompt_title
import chat.papo.mobile.resources.lock_enable_prompt_subtitle
import chat.papo.mobile.resources.lock_enable_prompt_title
import chat.papo.mobile.resources.lock_prompt_subtitle
import chat.papo.mobile.resources.settings_capture
import chat.papo.mobile.resources.settings_capture_allow
import chat.papo.mobile.resources.settings_capture_block
import chat.papo.mobile.resources.settings_capture_body
import chat.papo.mobile.resources.settings_lock
import chat.papo.mobile.resources.settings_lock_body
import chat.papo.mobile.resources.settings_lock_failed
import chat.papo.mobile.resources.settings_lock_not_enrolled
import chat.papo.mobile.resources.settings_lock_timeout
import chat.papo.mobile.resources.settings_lock_timeout_15m
import chat.papo.mobile.resources.settings_lock_timeout_1h
import chat.papo.mobile.resources.settings_lock_timeout_1m
import chat.papo.mobile.resources.settings_lock_timeout_5m
import chat.papo.mobile.resources.settings_lock_timeout_immediately
import chat.papo.mobile.resources.settings_lock_unsupported
import chat.papo.mobile.resources.settings_notif_content
import chat.papo.mobile.resources.settings_notif_content_always
import chat.papo.mobile.resources.settings_notif_content_lock_screen
import chat.papo.mobile.resources.settings_notif_content_note
import chat.papo.mobile.resources.settings_notif_content_show
import chat.papo.mobile.resources.settings_privacy
import chat.papo.mobile.resources.settings_section_lock
import chat.papo.mobile.security.AppLock
import chat.papo.mobile.security.AuthPrompt
import chat.papo.mobile.security.LockChange
import chat.papo.mobile.security.LockTimeout
import chat.papo.mobile.security.NotificationContent
import chat.papo.mobile.security.NotificationPrivacy
import chat.papo.mobile.security.UserAuthAvailability
import chat.papo.mobile.security.secureWindow
import chat.papo.mobile.ui.notifications.RadioChoice
import chat.papo.mobile.ui.notifications.RadioGroup
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Segurança e privacidade: o bloqueio do app, as capturas de tela e o que as notificações mostram. */
@Composable
fun SecurityScreen(lock: AppLock, notifications: NotificationPrivacy, onBack: () -> Unit) {
    SettingsDetailScaffold(stringResource(Res.string.settings_privacy), onBack) {
        LockSection(lock)
        CaptureSection(lock)
        NotificationContentSection(notifications)
    }
}

@Composable
private fun LockSection(lock: AppLock) {
    val scope = rememberCoroutineScope()
    val lockEnabled by lock.lockEnabled.collectAsStateWithLifecycle()
    val timeout by lock.timeout.collectAsStateWithLifecycle()
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

    SettingsSectionTitle(stringResource(Res.string.settings_section_lock))
    val canToggle = lockEnabled || availability == UserAuthAvailability.Available
    SettingsSwitchRow(
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
        Text(
            stringResource(Res.string.settings_lock_timeout),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = SettingsPadding, vertical = 8.dp),
        )
        RadioGroup {
            LockTimeout.entries.forEach { option ->
                RadioChoice(selected = timeout == option, label = option.label(), onClick = { lock.setTimeout(option) })
            }
        }
    }
}

@Composable
private fun LockTimeout.label(): String = stringResource(
    when (this) {
        LockTimeout.Immediately -> Res.string.settings_lock_timeout_immediately
        LockTimeout.OneMinute -> Res.string.settings_lock_timeout_1m
        LockTimeout.FiveMinutes -> Res.string.settings_lock_timeout_5m
        LockTimeout.FifteenMinutes -> Res.string.settings_lock_timeout_15m
        LockTimeout.OneHour -> Res.string.settings_lock_timeout_1h
    },
)

/** Vale com ou sem o bloqueio; a escolha mostrada é a que vale agora, inclusive quando ainda é o padrão. */
@Composable
private fun CaptureSection(lock: AppLock) {
    val lockEnabled by lock.lockEnabled.collectAsStateWithLifecycle()
    val pref by lock.secureScreen.collectAsStateWithLifecycle()
    val blocked = secureWindow(lockEnabled, pref)

    SettingsSectionTitle(stringResource(Res.string.settings_capture))
    Text(
        stringResource(Res.string.settings_capture_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsPadding, vertical = 4.dp),
    )
    RadioGroup {
        RadioChoice(selected = !blocked, label = stringResource(Res.string.settings_capture_allow), onClick = { lock.setSecureScreen(false) })
        RadioChoice(selected = blocked, label = stringResource(Res.string.settings_capture_block), onClick = { lock.setSecureScreen(true) })
    }
}

@Composable
private fun NotificationContentSection(privacy: NotificationPrivacy) {
    val content by privacy.content.collectAsStateWithLifecycle()

    SettingsSectionTitle(stringResource(Res.string.settings_notif_content))
    RadioGroup {
        NotificationContent.entries.forEach { option ->
            RadioChoice(selected = content == option, label = option.label(), onClick = { privacy.setContent(option) })
        }
    }
    Text(
        stringResource(Res.string.settings_notif_content_note),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsPadding, vertical = 8.dp),
    )
}

@Composable
private fun NotificationContent.label(): String = stringResource(
    when (this) {
        NotificationContent.Show -> Res.string.settings_notif_content_show
        NotificationContent.HideOnLockScreen -> Res.string.settings_notif_content_lock_screen
        NotificationContent.HideAlways -> Res.string.settings_notif_content_always
    },
)
