package chat.papo.mobile.ui.about

import androidx.compose.runtime.Composable
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.licenses_title
import chat.papo.mobile.ui.settings.SettingsDetailScaffold
import org.jetbrains.compose.resources.stringResource

/** Provisória (0.9.0, item 14): a trilha D escreve o conteúdo na onda 2. */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    SettingsDetailScaffold(stringResource(Res.string.licenses_title), onBack) {}
}
