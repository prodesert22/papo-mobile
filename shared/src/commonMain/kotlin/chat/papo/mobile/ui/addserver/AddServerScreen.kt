package chat.papo.mobile.ui.addserver

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.papo.mobile.account.AddAccountResult
import chat.papo.mobile.account.ServerProbe
import chat.papo.mobile.account.iconBytes
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.action_back
import chat.papo.mobile.resources.action_continue
import chat.papo.mobile.resources.add_server_address
import chat.papo.mobile.resources.add_server_address_hint
import chat.papo.mobile.resources.add_server_change
import chat.papo.mobile.resources.add_server_find
import chat.papo.mobile.resources.add_server_insecure_accept
import chat.papo.mobile.resources.add_server_insecure_body
import chat.papo.mobile.resources.add_server_insecure_title
import chat.papo.mobile.resources.add_server_members
import chat.papo.mobile.resources.add_server_password_body
import chat.papo.mobile.resources.add_server_password_label
import chat.papo.mobile.resources.add_server_password_title
import chat.papo.mobile.resources.add_server_private
import chat.papo.mobile.resources.add_server_title
import chat.papo.mobile.resources.login_password
import chat.papo.mobile.resources.login_submit
import chat.papo.mobile.resources.login_tab
import chat.papo.mobile.resources.login_username
import chat.papo.mobile.resources.password_hide
import chat.papo.mobile.resources.password_show
import chat.papo.mobile.resources.register_password_rule
import chat.papo.mobile.resources.register_submit
import chat.papo.mobile.resources.register_tab
import chat.papo.mobile.resources.welcome_body
import chat.papo.mobile.resources.welcome_title
import chat.papo.mobile.ui.components.colorFor
import chat.papo.mobile.ui.components.initials
import chat.papo.mobile.ui.components.rememberImage
import chat.papo.mobile.ui.components.text
import org.jetbrains.compose.resources.stringResource

/**
 * Fluxo de entrada num servidor: endereço, aviso de http, senha do servidor
 * (quando fechado) e login ou criação de conta.
 *
 * [canLeave] é falso quando ainda não há nenhuma conta: aí esta é a tela inicial.
 */
@Composable
fun AddServerScreen(
    viewModel: AddServerViewModel,
    canLeave: Boolean,
    onLeave: () -> Unit,
    onDone: (AddAccountResult) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.done) { state.done?.let(onDone) }

    val goBack = { if (!viewModel.back() && canLeave) onLeave() }
    val showBack = canLeave || state.step != AddServerStep.Address

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Box(Modifier.height(56.dp).padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
                if (showBack) {
                    IconButton(onClick = goBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                }
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            ) {
                AnimatedContent(state.step, label = "passo") { step ->
                    Column {
                        when (step) {
                            AddServerStep.Address -> AddressStep(state, viewModel, firstRun = !canLeave)
                            AddServerStep.InsecureWarning -> InsecureStep(state, viewModel)
                            AddServerStep.ServerPassword -> ServerPasswordStep(state, viewModel)
                            AddServerStep.Credentials -> CredentialsStep(state, viewModel)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddressStep(state: AddServerState, vm: AddServerViewModel, firstRun: Boolean) {
    if (firstRun) {
        Spacer(Modifier.height(24.dp))
        Text("papo", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(28.dp))
    }
    Text(
        stringResource(if (firstRun) Res.string.welcome_title else Res.string.add_server_title),
        style = MaterialTheme.typography.headlineMedium,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(Res.string.welcome_body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(28.dp))
    OutlinedTextField(
        value = state.address,
        onValueChange = vm::setAddress,
        label = { Text(stringResource(Res.string.add_server_address)) },
        placeholder = { Text(stringResource(Res.string.add_server_address_hint)) },
        singleLine = true,
        enabled = !state.busy,
        isError = state.error != null,
        supportingText = state.error?.let { error -> { Text(error.text()) } },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
        ),
        keyboardActions = KeyboardActions(onGo = { vm.findServer() }),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(20.dp))
    PrimaryButton(stringResource(Res.string.add_server_find), state.busy, enabled = state.address.isNotBlank(), onClick = vm::findServer)
}

@Composable
private fun InsecureStep(state: AddServerState, vm: AddServerViewModel) {
    state.probe?.let { ServerCard(it) }
    Spacer(Modifier.height(24.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(Res.string.add_server_insecure_title), style = MaterialTheme.typography.headlineSmall)
    }
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(Res.string.add_server_insecure_body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(28.dp))
    PrimaryButton(stringResource(Res.string.add_server_insecure_accept), busy = false, onClick = vm::acceptInsecure)
    Spacer(Modifier.height(4.dp))
    TextButton(onClick = { vm.back() }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(Res.string.add_server_change))
    }
}

@Composable
private fun ServerPasswordStep(state: AddServerState, vm: AddServerViewModel) {
    state.probe?.let { ServerCard(it) }
    Spacer(Modifier.height(24.dp))
    Text(stringResource(Res.string.add_server_password_title), style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(Res.string.add_server_password_body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(24.dp))
    PasswordField(
        value = state.serverPassword,
        onValueChange = vm::setServerPassword,
        label = stringResource(Res.string.add_server_password_label),
        enabled = !state.busy,
        error = state.error?.text(),
        imeAction = ImeAction.Go,
        onImeAction = vm::submitServerPassword,
    )
    Spacer(Modifier.height(20.dp))
    PrimaryButton(
        stringResource(Res.string.action_continue),
        state.busy,
        enabled = state.serverPassword.isNotEmpty(),
        onClick = vm::submitServerPassword,
    )
}

@Composable
private fun CredentialsStep(state: AddServerState, vm: AddServerViewModel) {
    state.probe?.let { ServerCard(it) }
    Spacer(Modifier.height(24.dp))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        CredentialsMode.entries.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = state.mode == mode,
                onClick = { vm.setMode(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, CredentialsMode.entries.size),
                enabled = !state.busy,
            ) {
                Text(stringResource(if (mode == CredentialsMode.Login) Res.string.login_tab else Res.string.register_tab))
            }
        }
    }
    Spacer(Modifier.height(20.dp))
    OutlinedTextField(
        value = state.username,
        onValueChange = vm::setUsername,
        label = { Text(stringResource(Res.string.login_username)) },
        singleLine = true,
        enabled = !state.busy,
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Next,
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    val register = state.mode == CredentialsMode.Register
    PasswordField(
        value = state.password,
        onValueChange = vm::setPassword,
        label = stringResource(Res.string.login_password),
        enabled = !state.busy,
        error = state.error?.text(),
        hint = if (register) stringResource(Res.string.register_password_rule) else null,
        imeAction = ImeAction.Go,
        onImeAction = vm::submitCredentials,
    )
    Spacer(Modifier.height(20.dp))
    PrimaryButton(
        stringResource(if (register) Res.string.register_submit else Res.string.login_submit),
        state.busy,
        enabled = state.username.isNotBlank() && state.password.isNotEmpty(),
        onClick = vm::submitCredentials,
    )
}

@Composable
private fun ServerCard(probe: ServerProbe) {
    val icon = rememberImage(probe.info.iconBytes())
    val name = probe.info.name.ifBlank { probe.url.host }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(colorFor(probe.url.value).copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) {
                    Image(icon, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Text(initials(name), style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                Text(
                    probe.url.display,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!probe.info.public) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(Res.string.add_server_private),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            stringResource(Res.string.add_server_members, probe.info.memberCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    error: String? = null,
    hint: String? = null,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
) {
    var visible by remember { mutableStateOf(false) }
    val supporting = error ?: hint
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = error != null,
        supportingText = supporting?.let { text -> { Text(text) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onAny = { onImeAction() }),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(if (visible) Res.string.password_hide else Res.string.password_show),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun PrimaryButton(text: String, busy: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurface)
        } else {
            Text(text)
        }
    }
}
