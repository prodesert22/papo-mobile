package chat.papo.mobile.ui.settings.appearance

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.settings_appearance
import chat.papo.mobile.resources.theme_summary
import chat.papo.mobile.ui.settings.SettingsDetailScaffold
import chat.papo.mobile.ui.theme.AppThemeStore
import chat.papo.mobile.ui.theme.ThemePresets
import chat.papo.mobile.ui.theme.ThemeSpec
import org.jetbrains.compose.resources.stringResource

/** O tema global do app: modo, predefinição e prévia. O que se escolhe aqui vale em todos os servidores sem tema próprio. */
@Composable
fun AppearanceScreen(store: AppThemeStore, onBack: () -> Unit) {
    val spec by store.spec.collectAsStateWithLifecycle()
    SettingsDetailScaffold(stringResource(Res.string.settings_appearance), onBack) {
        ThemeEditor(spec, store::set)
    }
}

/** O resumo de uma linha de um tema, como "Escuro · Oceano", para as listas de configurações. */
@Composable
fun themeSummary(spec: ThemeSpec): String =
    stringResource(Res.string.theme_summary, stringResource(spec.mode.label()), stringResource(ThemePresets.find(spec.preset).name))
