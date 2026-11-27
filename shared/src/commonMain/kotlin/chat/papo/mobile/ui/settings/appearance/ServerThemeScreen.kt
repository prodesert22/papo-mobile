package chat.papo.mobile.ui.settings.appearance

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.papo.mobile.AppSession
import chat.papo.mobile.account.ServerTheme
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.settings_server_theme
import chat.papo.mobile.resources.theme_server_own
import chat.papo.mobile.resources.theme_server_own_hint
import chat.papo.mobile.resources.theme_server_use_app
import chat.papo.mobile.resources.theme_server_use_app_hint
import chat.papo.mobile.ui.settings.SettingsDetailScaffold
import chat.papo.mobile.ui.settings.SettingsPadding
import chat.papo.mobile.ui.theme.AppThemeStore
import org.jetbrains.compose.resources.stringResource

/**
 * O tema só do servidor da conta [accountId]: seguir o do app (padrão) ou ter um próprio, com os mesmos controles do tema
 * global. Fica gravado no banco cifrado da conta, só neste aparelho.
 */
@Composable
fun ServerThemeScreen(session: AppSession, accountId: String, appTheme: AppThemeStore, onBack: () -> Unit) {
    val sessions by session.accounts.sessionMap.collectAsStateWithLifecycle()
    val store = sessions[accountId]?.theme
    if (store == null) {
        // A conta foi removida enquanto a tela estava aberta.
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val chosen by store.state.collectAsStateWithLifecycle()
    val global by appTheme.spec.collectAsStateWithLifecycle()

    SettingsDetailScaffold(stringResource(Res.string.settings_server_theme), onBack) {
        val current = chosen ?: return@SettingsDetailScaffold // ainda lendo o banco
        val own = current as? ServerTheme.Own
        ChoiceRow(
            selected = own == null,
            title = stringResource(Res.string.theme_server_use_app),
            hint = stringResource(Res.string.theme_server_use_app_hint),
            onClick = { store.set(ServerTheme.UseApp) },
        )
        ChoiceRow(
            selected = own != null,
            title = stringResource(Res.string.theme_server_own),
            hint = stringResource(Res.string.theme_server_own_hint),
            // Começa do tema do app, para o primeiro ajuste partir do que a pessoa já vê.
            onClick = { if (own == null) store.set(ServerTheme.Own(global)) },
        )
        if (own != null) {
            ThemeEditor(own.spec, onChange = { store.set(ServerTheme.Own(it)) })
        } else {
            // Sem tema próprio mostra o do app, que é o que este servidor usa, sem controles.
            Column(Modifier.padding(horizontal = SettingsPadding, vertical = 12.dp)) {
                ThemePreview(global, systemDark = isSystemInDarkTheme())
            }
        }
    }
}

@Composable
private fun ChoiceRow(selected: Boolean, title: String, hint: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = SettingsPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
