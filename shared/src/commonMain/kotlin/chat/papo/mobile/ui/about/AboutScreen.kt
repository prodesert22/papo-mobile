package chat.papo.mobile.ui.about

import androidx.compose.runtime.Composable
import chat.papo.mobile.core.BuildInfo
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.about_title
import chat.papo.mobile.ui.settings.SettingsDetailScaffold
import org.jetbrains.compose.resources.stringResource

/** Provisória (0.9.0, item 14): a trilha A escreve o conteúdo na onda 2. */
@Composable
fun AboutScreen(build: BuildInfo, onLicenses: () -> Unit, onBack: () -> Unit) {
    SettingsDetailScaffold(stringResource(Res.string.about_title), onBack) {}
}
