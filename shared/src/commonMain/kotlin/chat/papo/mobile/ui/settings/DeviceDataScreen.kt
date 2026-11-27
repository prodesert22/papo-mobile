package chat.papo.mobile.ui.settings

import androidx.compose.runtime.Composable
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.settings_storage
import org.jetbrains.compose.resources.stringResource

/** Provisória (onda 0 da 0.9.0): a trilha dona escreve o conteúdo. */
@Composable
fun DeviceDataScreen(onBack: () -> Unit) {
    SettingsDetailScaffold(stringResource(Res.string.settings_storage), onBack) {}
}
