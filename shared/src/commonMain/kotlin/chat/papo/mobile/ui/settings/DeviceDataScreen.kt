package chat.papo.mobile.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.settings_storage
import chat.papo.mobile.resources.settings_storage_body
import org.jetbrains.compose.resources.stringResource

/** O que fica guardado neste aparelho e como. */
@Composable
fun DeviceDataScreen(onBack: () -> Unit) {
    SettingsDetailScaffold(stringResource(Res.string.settings_storage), onBack) {
        Text(
            stringResource(Res.string.settings_storage_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsPadding, vertical = 16.dp),
        )
    }
}
