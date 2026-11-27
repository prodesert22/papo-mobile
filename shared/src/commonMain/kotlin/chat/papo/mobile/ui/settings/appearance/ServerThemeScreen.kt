package chat.papo.mobile.ui.settings.appearance

import androidx.compose.runtime.Composable
import chat.papo.mobile.AppSession
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.settings_server_theme
import chat.papo.mobile.ui.settings.SettingsDetailScaffold
import org.jetbrains.compose.resources.stringResource

/** Provisória (onda 0 da 0.9.0): a trilha dona escreve o conteúdo. */
@Composable
fun ServerThemeScreen(session: AppSession, accountId: String, onBack: () -> Unit) {
    SettingsDetailScaffold(stringResource(Res.string.settings_server_theme), onBack) {}
}
