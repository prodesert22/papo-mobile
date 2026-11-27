package chat.papo.mobile.ui.settings.appearance

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.settings_appearance
import chat.papo.mobile.resources.theme_appearance_app_note
import chat.papo.mobile.resources.theme_summary
import chat.papo.mobile.ui.settings.SettingsDetailScaffold
import chat.papo.mobile.ui.settings.SettingsPadding
import chat.papo.mobile.ui.theme.AppThemeStore
import chat.papo.mobile.ui.theme.ThemePresets
import chat.papo.mobile.ui.theme.ThemeSpec
import org.jetbrains.compose.resources.stringResource

/** O tema global do app: modo, predefinição, cores e prévia. O que se escolhe aqui vale em todos os servidores sem tema próprio. */
@Composable
fun AppearanceScreen(store: AppThemeStore, onBack: () -> Unit) {
    val spec by store.spec.collectAsStateWithLifecycle()
    SettingsDetailScaffold(stringResource(Res.string.settings_appearance), onBack) {
        // A tela em volta pode estar com o tema do servidor da conta: o que se edita aqui é o do app.
        Text(
            stringResource(Res.string.theme_appearance_app_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = SettingsPadding, top = 12.dp, end = SettingsPadding),
        )
        ThemeEditor(spec, store::set)
    }
}

/** O resumo de uma linha de um tema, como "Escuro · Oceano", para as listas de configurações. */
@Composable
fun themeSummary(spec: ThemeSpec): String =
    stringResource(Res.string.theme_summary, stringResource(spec.mode.label()), stringResource(ThemePresets.find(spec.preset).name))
