package chat.papo.mobile.ui.channel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.papo.mobile.chat.ChannelEditResult
import chat.papo.mobile.chat.ChannelNameProblem
import chat.papo.mobile.chat.MAX_CHANNEL_NAME
import chat.papo.mobile.chat.MAX_CHANNEL_TOPIC
import chat.papo.mobile.chat.codePointLength
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.action_save
import chat.papo.mobile.resources.channel_edit_counter
import chat.papo.mobile.resources.channel_edit_forbidden
import chat.papo.mobile.resources.channel_edit_gone
import chat.papo.mobile.resources.channel_edit_name
import chat.papo.mobile.resources.channel_edit_name_empty
import chat.papo.mobile.resources.channel_edit_name_taken
import chat.papo.mobile.resources.channel_edit_no_permission
import chat.papo.mobile.resources.channel_edit_topic
import chat.papo.mobile.resources.channel_edit_uncertain
import chat.papo.mobile.resources.channel_settings
import chat.papo.mobile.ui.components.failureText
import chat.papo.mobile.ui.settings.SettingsDetailScaffold
import chat.papo.mobile.ui.settings.SettingsPadding
import org.jetbrains.compose.resources.stringResource

/**
 * Editar nome e descrição de um canal. O conteúdo é texto digitado pela pessoa e nunca é interpretado. Quem não tem
 * `manage_channels` não chega aqui pelo menu, e se perder a permissão com a tela aberta os campos ficam desligados.
 */
@Composable
fun ChannelSettingsScreen(viewModel: ChannelSettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) { if (state.saved) onBack() }

    SettingsDetailScaffold(stringResource(Res.string.channel_settings), onBack, scroll = false) { padding ->
        // O teclado encolhe a área rolável: o campo em foco fica à vista. O que o Scaffold já descontou (barras do
        // sistema) não é descontado de novo.
        Column(
            Modifier.fillMaxSize().consumeWindowInsets(padding).imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = SettingsPadding, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.channel == null && !state.gone) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }
            val notice = when {
                state.gone || state.failure == ChannelEditResult.NotFound -> stringResource(Res.string.channel_edit_gone)
                !state.canManage -> stringResource(Res.string.channel_edit_no_permission)
                else -> null
            }
            notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            NameField(state, viewModel::setName)
            if (state.hasTopic) TopicField(state, viewModel::setTopic)
            val failure = state.failure?.takeIf { it != ChannelEditResult.NotFound }?.text()
            failure?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(12.dp))
                }
                Button(onClick = viewModel::save, enabled = state.canSave) { Text(stringResource(Res.string.action_save)) }
            }
        }
    }
}

@Composable
private fun NameField(state: ChannelSettingsState, onChange: (String) -> Unit) {
    val problem = state.nameProblem
    OutlinedTextField(
        value = state.name,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(Res.string.channel_edit_name)) },
        enabled = state.editable,
        singleLine = true,
        isError = problem == ChannelNameProblem.TooLong || (problem == ChannelNameProblem.Empty && state.name.isNotEmpty()),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
        supportingText = {
            Row(Modifier.fillMaxWidth()) {
                Text(if (problem == ChannelNameProblem.Empty) stringResource(Res.string.channel_edit_name_empty) else "", Modifier.weight(1f))
                Text(stringResource(Res.string.channel_edit_counter, codePointLength(state.name.trim()), MAX_CHANNEL_NAME))
            }
        },
    )
}

@Composable
private fun TopicField(state: ChannelSettingsState, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = state.topic,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(Res.string.channel_edit_topic)) },
        enabled = state.editable,
        minLines = 3,
        maxLines = 8,
        isError = !state.topicFits,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        supportingText = {
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                Text(stringResource(Res.string.channel_edit_counter, codePointLength(state.topic.trim()), MAX_CHANNEL_TOPIC))
            }
        },
    )
}

@Composable
private fun ChannelEditResult.text(): String? = when (this) {
    ChannelEditResult.Saved -> null
    ChannelEditResult.NameTaken -> stringResource(Res.string.channel_edit_name_taken)
    ChannelEditResult.Forbidden -> stringResource(Res.string.channel_edit_forbidden)
    ChannelEditResult.NotFound -> stringResource(Res.string.channel_edit_gone)
    is ChannelEditResult.Failed -> result.failureText(uncertain = stringResource(Res.string.channel_edit_uncertain))
}
