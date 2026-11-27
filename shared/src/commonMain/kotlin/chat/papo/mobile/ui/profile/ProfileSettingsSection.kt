package chat.papo.mobile.ui.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.profile_edit_row_body
import chat.papo.mobile.resources.profile_edit_row_title
import org.jetbrains.compose.resources.stringResource

/**
 * "Perfil e status" nas configurações da conta: leva à edição do perfil, onde também se escolhe Disponível, Ausente ou
 * Ocupado. Só para uma conta com sessão aberta.
 */
@Composable
fun ProfileSettingsSection(@Suppress("UNUSED_PARAMETER") session: AccountSession, onEdit: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.profile_edit_row_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(Res.string.profile_edit_row_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
