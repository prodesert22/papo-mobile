package chat.papo.mobile.ui.channel

import androidx.compose.runtime.Composable
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.channel_settings
import chat.papo.mobile.ui.settings.SettingsDetailScaffold
import org.jetbrains.compose.resources.stringResource

/** Provisória (onda 0 da 0.9.0): a trilha dona escreve o conteúdo. */
@Composable
fun ChannelSettingsScreen(session: AccountSession, channelId: String, onBack: () -> Unit) {
    SettingsDetailScaffold(stringResource(Res.string.channel_settings), onBack) {}
}
