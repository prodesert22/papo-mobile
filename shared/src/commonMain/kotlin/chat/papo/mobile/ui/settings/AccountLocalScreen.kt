package chat.papo.mobile.ui.settings

import androidx.compose.runtime.Composable
import chat.papo.mobile.AppSession
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.settings_account_local
import org.jetbrains.compose.resources.stringResource

/** Provisória (onda 0 da 0.9.0): a trilha dona escreve o conteúdo. */
@Composable
fun AccountLocalScreen(session: AppSession, accountId: String, onBack: () -> Unit) {
    SettingsDetailScaffold(stringResource(Res.string.settings_account_local), onBack) {}
}
