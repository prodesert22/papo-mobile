package chat.papo.mobile.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.papo.mobile.AppSession
import chat.papo.mobile.account.Account
import chat.papo.mobile.account.AccountColors
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.account_color
import chat.papo.mobile.resources.account_color_none
import chat.papo.mobile.resources.account_label
import chat.papo.mobile.resources.account_label_help
import chat.papo.mobile.resources.account_label_hint
import chat.papo.mobile.resources.settings_account_local
import chat.papo.mobile.ui.components.readableOn
import org.jetbrains.compose.resources.stringResource

/** O apelido e a cor da conta [accountId] neste aparelho: só mudam como ela aparece aqui, nada vai ao servidor. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountLocalScreen(session: AppSession, accountId: String, onBack: () -> Unit) {
    val accounts by session.accounts.accounts.collectAsStateWithLifecycle()
    val account = accounts.firstOrNull { it.id == accountId }
    SettingsDetailScaffold(stringResource(Res.string.settings_account_local), onBack) {
        if (account == null) return@SettingsDetailScaffold
        Column(Modifier.padding(horizontal = SettingsPadding, vertical = 16.dp)) {
            LabelField(account) { label -> session.accounts.setLabel(account.id, label) }
            Spacer(Modifier.height(20.dp))
            Text(stringResource(Res.string.account_color), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ColorSwatch(null, selected = account.color == null, stringResource(Res.string.account_color_none)) {
                    session.accounts.setColor(account.id, null)
                }
                AccountColors.forEach { argb ->
                    ColorSwatch(Color(argb), selected = account.color == argb, label = null) {
                        session.accounts.setColor(account.id, argb)
                    }
                }
            }
        }
    }
}

@Composable
private fun LabelField(account: Account, onSave: (String) -> Unit) {
    var text by remember(account.id) { mutableStateOf(account.label.orEmpty()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it.take(24)
            onSave(text)
        },
        label = { Text(stringResource(Res.string.account_label)) },
        placeholder = { Text(stringResource(Res.string.account_label_hint)) },
        supportingText = { Text(stringResource(Res.string.account_label_help)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ColorSwatch(color: Color?, selected: Boolean, label: String?, onClick: () -> Unit) {
    val outline = MaterialTheme.colorScheme.outline
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(color ?: Color.Transparent)
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.onSurface else outline, CircleShape)
            .clickable(role = Role.RadioButton, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            color == null -> Icon(Icons.Filled.Block, contentDescription = label, tint = outline, modifier = Modifier.size(20.dp))
            selected -> Icon(Icons.Filled.Check, contentDescription = null, tint = color.readableOn(), modifier = Modifier.size(20.dp))
        }
    }
}
