package chat.papo.mobile.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.papo.mobile.AppSession
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.account_blocked_users
import chat.papo.mobile.resources.account_change_password
import chat.papo.mobile.resources.account_devices
import chat.papo.mobile.resources.account_remove
import chat.papo.mobile.resources.account_remove_body
import chat.papo.mobile.resources.account_remove_confirm
import chat.papo.mobile.resources.account_remove_title
import chat.papo.mobile.resources.action_back
import chat.papo.mobile.resources.notif_account_open
import chat.papo.mobile.resources.notif_account_section
import chat.papo.mobile.resources.profile_edit_row_body
import chat.papo.mobile.resources.settings_about
import chat.papo.mobile.resources.settings_account_local
import chat.papo.mobile.resources.settings_account_local_summary
import chat.papo.mobile.resources.settings_android_notifications
import chat.papo.mobile.resources.settings_android_notifications_summary
import chat.papo.mobile.resources.settings_appearance
import chat.papo.mobile.resources.settings_blocked_summary
import chat.papo.mobile.resources.settings_devices_summary
import chat.papo.mobile.resources.settings_password_summary
import chat.papo.mobile.resources.settings_privacy
import chat.papo.mobile.resources.settings_privacy_summary
import chat.papo.mobile.resources.settings_profile
import chat.papo.mobile.resources.settings_remove_summary
import chat.papo.mobile.resources.settings_server_info
import chat.papo.mobile.resources.settings_server_theme
import chat.papo.mobile.resources.settings_server_theme_summary
import chat.papo.mobile.resources.settings_storage
import chat.papo.mobile.resources.settings_storage_summary
import chat.papo.mobile.resources.settings_tab_app
import chat.papo.mobile.resources.settings_tab_server
import chat.papo.mobile.resources.settings_title
import chat.papo.mobile.resources.settings_version
import chat.papo.mobile.ui.components.AccountAvatar
import chat.papo.mobile.ui.components.LocalNotificationPermission
import chat.papo.mobile.ui.settings.appearance.themeSummary
import chat.papo.mobile.ui.theme.ThemeSpec
import org.jetbrains.compose.resources.stringResource

/** As abas da tela de configurações: as do aparelho e as da conta (servidor). */
enum class SettingsTab { App, Server }

/**
 * Para onde a tela de configurações leva. As funções com conta recebem o id dela: é a conta que a aba Servidor mostra.
 */
class SettingsNavigation(
    val back: () -> Unit,
    val appearance: () -> Unit,
    val security: () -> Unit,
    val deviceData: () -> Unit,
    val about: () -> Unit,
    val editProfile: (accountId: String) -> Unit,
    val accountLocal: (accountId: String) -> Unit,
    val serverTheme: (accountId: String) -> Unit,
    val notifications: (accountId: String) -> Unit,
    val blocked: (accountId: String) -> Unit,
    val password: (accountId: String) -> Unit,
    val devices: (accountId: String) -> Unit,
    val serverInfo: (accountId: String) -> Unit,
)

/**
 * Configurações em duas abas, Aplicativo e Servidor, cada uma uma lista de itens com resumo. Sem [accountId] só há a aba
 * Aplicativo, e a barra de abas some.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    session: AppSession,
    appVersion: String,
    /** O tema do app, para o resumo do item Aparência. */
    appTheme: ThemeSpec,
    accountId: String?,
    initialTab: SettingsTab,
    nav: SettingsNavigation,
) {
    val tabs = settingsTabs(accountId)
    var selected by rememberSaveable { mutableIntStateOf(initialTab.ordinal) }
    val tab = visibleTab(SettingsTab.entries[selected], accountId)

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(Res.string.settings_title)) },
                    navigationIcon = {
                        IconButton(onClick = nav.back) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                        }
                    },
                )
                if (tabs.size > 1) {
                    PrimaryTabRow(selectedTabIndex = tabs.indexOf(tab)) {
                        tabs.forEach { item ->
                            Tab(
                                selected = item == tab,
                                onClick = { selected = item.ordinal },
                                text = {
                                    Text(
                                        stringResource(
                                            if (item == SettingsTab.App) Res.string.settings_tab_app else Res.string.settings_tab_server,
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                tab == SettingsTab.Server && accountId != null -> ServerTab(session, accountId, nav)
                else -> AppTab(appVersion, appTheme, nav)
            }
        }
    }
}

@Composable
private fun AppTab(appVersion: String, appTheme: ThemeSpec, nav: SettingsNavigation) {
    val permission = LocalNotificationPermission.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        appEntries(systemSettings = permission != null).forEach { entry ->
            when (entry) {
                AppEntry.Appearance -> SettingsItem(
                    Icons.Filled.Palette,
                    stringResource(Res.string.settings_appearance),
                    themeSummary(appTheme),
                    nav.appearance,
                )
                AppEntry.Security -> SettingsItem(
                    Icons.Filled.Lock,
                    stringResource(Res.string.settings_privacy),
                    stringResource(Res.string.settings_privacy_summary),
                    nav.security,
                )
                AppEntry.AndroidNotifications -> SettingsItem(
                    Icons.Filled.Notifications,
                    stringResource(Res.string.settings_android_notifications),
                    stringResource(Res.string.settings_android_notifications_summary),
                    onClick = { permission?.openSettings() },
                )
                AppEntry.DeviceData -> SettingsItem(
                    Icons.Filled.Storage,
                    stringResource(Res.string.settings_storage),
                    stringResource(Res.string.settings_storage_summary),
                    nav.deviceData,
                )
                AppEntry.About -> SettingsItem(
                    Icons.Filled.Info,
                    stringResource(Res.string.settings_about),
                    appVersion.takeIf { it.isNotEmpty() }?.let { stringResource(Res.string.settings_version, it) },
                    nav.about,
                )
            }
        }
    }
}

@Composable
private fun ServerTab(session: AppSession, accountId: String, nav: SettingsNavigation) {
    val accounts by session.accounts.accounts.collectAsStateWithLifecycle()
    val sessions by session.accounts.sessionMap.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf(false) }
    val account = accounts.firstOrNull { it.id == accountId } ?: return

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // De qual servidor e usuário são estas configurações: a aba vale para a conta pedida, não para a ativa.
        Row(Modifier.padding(horizontal = SettingsPadding, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            AccountAvatar(account, selected = true, ringBackground = MaterialTheme.colorScheme.background, size = 56.dp)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(account.title, style = MaterialTheme.typography.titleLarge)
                Text(account.handle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        serverEntries(hasSession = sessions[accountId] != null, expired = account.sessionExpired).forEach { entry ->
            when (entry) {
                ServerEntry.Profile -> SettingsItem(
                    Icons.Filled.Person,
                    stringResource(Res.string.settings_profile),
                    stringResource(Res.string.profile_edit_row_body),
                    { nav.editProfile(accountId) },
                )
                ServerEntry.AccountLocal -> SettingsItem(
                    Icons.Filled.PhoneAndroid,
                    stringResource(Res.string.settings_account_local),
                    stringResource(Res.string.settings_account_local_summary),
                    { nav.accountLocal(accountId) },
                )
                ServerEntry.ServerTheme -> SettingsItem(
                    Icons.Filled.Brush,
                    stringResource(Res.string.settings_server_theme),
                    stringResource(Res.string.settings_server_theme_summary),
                    { nav.serverTheme(accountId) },
                )
                ServerEntry.Notifications -> SettingsItem(
                    Icons.Filled.Notifications,
                    stringResource(Res.string.notif_account_section),
                    stringResource(Res.string.notif_account_open),
                    { nav.notifications(accountId) },
                )
                ServerEntry.Blocked -> SettingsItem(
                    Icons.Filled.Block,
                    stringResource(Res.string.account_blocked_users),
                    stringResource(Res.string.settings_blocked_summary),
                    { nav.blocked(accountId) },
                )
                ServerEntry.Password -> SettingsItem(
                    Icons.Filled.Key,
                    stringResource(Res.string.account_change_password),
                    stringResource(Res.string.settings_password_summary),
                    { nav.password(accountId) },
                )
                ServerEntry.Devices -> SettingsItem(
                    Icons.Filled.Devices,
                    stringResource(Res.string.account_devices),
                    stringResource(Res.string.settings_devices_summary),
                    { nav.devices(accountId) },
                )
                ServerEntry.ServerInfo -> SettingsItem(
                    Icons.Filled.Dns,
                    stringResource(Res.string.settings_server_info),
                    account.server.display,
                    { nav.serverInfo(accountId) },
                )
                ServerEntry.SignOut -> SettingsItem(
                    Icons.AutoMirrored.Filled.Logout,
                    stringResource(Res.string.account_remove),
                    stringResource(Res.string.settings_remove_summary),
                    { confirmRemove = true },
                    danger = true,
                )
            }
        }
    }

    if (confirmRemove) {
        ConfirmDialog(
            title = stringResource(Res.string.account_remove_title, account.handle),
            body = stringResource(Res.string.account_remove_body),
            confirm = stringResource(Res.string.account_remove_confirm),
            onDismiss = { confirmRemove = false },
            onConfirm = {
                confirmRemove = false
                nav.back()
                session.accounts.removeInBackground(account.id)
            },
        )
    }
}
