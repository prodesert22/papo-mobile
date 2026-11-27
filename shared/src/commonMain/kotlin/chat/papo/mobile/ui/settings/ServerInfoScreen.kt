package chat.papo.mobile.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.papo.mobile.AppSession
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.settings_server_info
import org.jetbrains.compose.resources.stringResource

/** Nome, ícone, dono e contagens do servidor da conta [accountId]. */
@Composable
fun ServerInfoScreen(session: AppSession, accountId: String, onBack: () -> Unit) {
    val accounts by session.accounts.accounts.collectAsStateWithLifecycle()
    val account = accounts.firstOrNull { it.id == accountId }
    SettingsDetailScaffold(stringResource(Res.string.settings_server_info), onBack) {
        if (account != null) {
            Column(Modifier.padding(horizontal = SettingsPadding, vertical = 16.dp)) { ServerSection(session.accounts, account) }
        }
    }
}
