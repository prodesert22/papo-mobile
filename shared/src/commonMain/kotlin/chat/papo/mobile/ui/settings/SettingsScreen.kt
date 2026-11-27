package chat.papo.mobile.ui.settings

import androidx.compose.runtime.Composable
import chat.papo.mobile.AppSession
import chat.papo.mobile.security.AppLock

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
 * Aplicativo.
 *
 * Provisória (onda 0 da 0.9.0): mostra as telas antigas até a trilha A escrever as listas.
 */
@Composable
fun SettingsScreen(
    session: AppSession,
    lock: AppLock,
    appVersion: String,
    accountId: String?,
    initialTab: SettingsTab,
    nav: SettingsNavigation,
) {
    if (initialTab == SettingsTab.Server && accountId != null) {
        AccountSettingsScreen(
            session,
            accountId,
            onBack = nav.back,
            onOpenBlocked = { nav.blocked(accountId) },
            onOpenPassword = { nav.password(accountId) },
            onEditProfile = { nav.editProfile(accountId) },
            onOpenNotifications = { nav.notifications(accountId) },
        )
    } else {
        AppSettingsScreen(lock, appVersion, onBack = nav.back)
    }
}
