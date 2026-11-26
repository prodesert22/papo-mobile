package chat.papo.mobile.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.papo.mobile.chat.PasswordCheck
import chat.papo.mobile.chat.checkNewPassword
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.action_back
import chat.papo.mobile.resources.action_cancel
import chat.papo.mobile.resources.password_change_body
import chat.papo.mobile.resources.password_change_confirm_body
import chat.papo.mobile.resources.password_change_confirm_title
import chat.papo.mobile.resources.password_change_queue
import chat.papo.mobile.resources.password_change_submit
import chat.papo.mobile.resources.password_change_title
import chat.papo.mobile.resources.password_change_uncertain
import chat.papo.mobile.resources.password_change_uncertain_prepare
import chat.papo.mobile.resources.password_new
import chat.papo.mobile.resources.password_repeat
import chat.papo.mobile.resources.password_rules
import chat.papo.mobile.ui.addserver.PasswordField
import chat.papo.mobile.ui.addserver.PrimaryButton
import chat.papo.mobile.ui.components.failureText
import chat.papo.mobile.ui.components.text
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * A troca da senha da própria conta. As duas senhas vivem só aqui, em estado comum (nunca em `rememberSaveable`, que
 * grava no pacote de estado do sistema) e são apagadas ao enviar. Com a senha trocada a sessão acaba e a tela volta
 * à principal ([onDone]), que oferece "Entrar de novo".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangePasswordScreen(viewModel: ChangePasswordViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    val check = checkNewPassword(password, repeat)
    val outcome = state.outcome

    LaunchedEffect(outcome) { if (outcome == ChangePasswordOutcome.Changed) onDone() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.password_change_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !state.busy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text(
                stringResource(Res.string.password_change_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            PasswordField(
                value = password,
                onValueChange = { password = it },
                label = stringResource(Res.string.password_new),
                enabled = !state.busy,
                error = (check as? PasswordCheck.Weak)?.takeIf { password.isNotEmpty() }?.text(),
                hint = stringResource(Res.string.password_rules),
            )
            Spacer(Modifier.height(12.dp))
            PasswordField(
                value = repeat,
                onValueChange = { repeat = it },
                label = stringResource(Res.string.password_repeat),
                enabled = !state.busy,
                error = (check as? PasswordCheck.Mismatch)?.takeIf { repeat.isNotEmpty() }?.text(),
            )
            Spacer(Modifier.height(16.dp))
            outcome?.let { Failure(it) }
            PrimaryButton(
                stringResource(Res.string.password_change_submit),
                busy = state.busy,
                enabled = check == PasswordCheck.Ok,
                onClick = { confirming = true },
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirming) {
        val queued = viewModel.queued
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(Res.string.password_change_confirm_title)) },
            text = {
                Column {
                    Text(stringResource(Res.string.password_change_confirm_body))
                    if (queued > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(pluralStringResource(Res.plurals.password_change_queue, queued, queued), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        viewModel.submit(password, repeat)
                        // As senhas não ficam na tela depois do envio, deu certo ou não.
                        password = ""
                        repeat = ""
                    },
                ) { Text(stringResource(Res.string.password_change_submit), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}

@Composable
private fun Failure(outcome: ChangePasswordOutcome) {
    val message = when (outcome) {
        ChangePasswordOutcome.Changed -> null
        is ChangePasswordOutcome.Invalid -> outcome.check.text()
        is ChangePasswordOutcome.Failed -> outcome.result.failureText(
            uncertain = stringResource(
                if (outcome.step == PasswordStep.Prepare) Res.string.password_change_uncertain_prepare else Res.string.password_change_uncertain,
            ),
        )
    } ?: return
    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 12.dp))
}
