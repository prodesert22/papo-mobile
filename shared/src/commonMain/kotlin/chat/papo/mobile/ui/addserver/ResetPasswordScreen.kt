package chat.papo.mobile.ui.addserver

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import chat.papo.mobile.chat.PasswordCheck
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.password_new
import chat.papo.mobile.resources.password_repeat
import chat.papo.mobile.resources.password_rules
import chat.papo.mobile.resources.reset_body
import chat.papo.mobile.resources.reset_gone
import chat.papo.mobile.resources.reset_host_mismatch
import chat.papo.mobile.resources.reset_link
import chat.papo.mobile.resources.reset_link_invalid
import chat.papo.mobile.resources.reset_submit
import chat.papo.mobile.resources.reset_submit_anyway
import chat.papo.mobile.resources.reset_title
import chat.papo.mobile.resources.reset_uncertain
import chat.papo.mobile.ui.components.failureText
import chat.papo.mobile.ui.components.text
import org.jetbrains.compose.resources.stringResource

/**
 * Redefinir a senha com o link que o dono do servidor gerou. O link e as senhas ficam só no estado do modelo da tela
 * (nunca em `rememberSaveable`) e são apagados ao enviar. O campo do link não tem correção automática, e os de senha
 * são campos de senha. Um link de outro endereço mostra os dois e só envia no segundo toque.
 */
@Composable
internal fun ResetPasswordStep(state: AddServerState, vm: AddServerViewModel) {
    val outcome = state.resetOutcome
    Text(stringResource(Res.string.reset_title), style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(Res.string.reset_body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(24.dp))
    OutlinedTextField(
        value = state.resetLink,
        onValueChange = vm::setResetLink,
        label = { Text(stringResource(Res.string.reset_link)) },
        singleLine = true,
        enabled = !state.busy,
        isError = outcome == ResetOutcome.InvalidLink,
        supportingText = if (outcome == ResetOutcome.InvalidLink) ({ Text(stringResource(Res.string.reset_link_invalid)) }) else null,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    val check = (outcome as? ResetOutcome.Check)?.check
    PasswordField(
        value = state.newPassword,
        onValueChange = vm::setNewPassword,
        label = stringResource(Res.string.password_new),
        enabled = !state.busy,
        error = (check as? PasswordCheck.Weak)?.text(),
        hint = stringResource(Res.string.password_rules),
        imeAction = ImeAction.Next,
    )
    Spacer(Modifier.height(12.dp))
    PasswordField(
        value = state.newPasswordRepeat,
        onValueChange = vm::setNewPasswordRepeat,
        label = stringResource(Res.string.password_repeat),
        enabled = !state.busy,
        error = (check as? PasswordCheck.Mismatch)?.text(),
        imeAction = ImeAction.Go,
        onImeAction = vm::submitReset,
    )
    Spacer(Modifier.height(16.dp))
    val message = when (outcome) {
        is ResetOutcome.HostDiffers -> stringResource(Res.string.reset_host_mismatch, outcome.linkHost, outcome.serverHost)
        ResetOutcome.Gone -> stringResource(Res.string.reset_gone)
        is ResetOutcome.Failed -> outcome.result.failureText(uncertain = stringResource(Res.string.reset_uncertain))
        ResetOutcome.InvalidLink, is ResetOutcome.Check, null -> null
    }
    message?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
    }
    PrimaryButton(
        stringResource(if (outcome is ResetOutcome.HostDiffers) Res.string.reset_submit_anyway else Res.string.reset_submit),
        state.busy,
        enabled = state.resetLink.isNotBlank() && state.newPassword.isNotEmpty() && state.newPasswordRepeat.isNotEmpty(),
        onClick = vm::submitReset,
    )
}
