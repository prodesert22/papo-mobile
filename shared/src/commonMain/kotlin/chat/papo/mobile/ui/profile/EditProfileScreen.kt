package chat.papo.mobile.ui.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.User
import chat.papo.mobile.media.ProfileField
import chat.papo.mobile.media.ProfileImageKind
import chat.papo.mobile.media.ReducedImage
import chat.papo.mobile.media.codePoints
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.action_back
import chat.papo.mobile.resources.action_retry
import chat.papo.mobile.resources.profile_edit_avatar
import chat.papo.mobile.resources.profile_edit_avatar_help
import chat.papo.mobile.resources.profile_edit_banner
import chat.papo.mobile.resources.profile_edit_banner_help
import chat.papo.mobile.resources.profile_edit_banner_removal
import chat.papo.mobile.resources.profile_edit_counter
import chat.papo.mobile.resources.profile_edit_description
import chat.papo.mobile.resources.profile_edit_discard
import chat.papo.mobile.resources.profile_edit_nickname
import chat.papo.mobile.resources.profile_edit_outcome_saved
import chat.papo.mobile.resources.profile_edit_outcome_sending
import chat.papo.mobile.resources.profile_edit_outcome_skipped
import chat.papo.mobile.resources.profile_edit_pick_avatar
import chat.papo.mobile.resources.profile_edit_pick_banner
import chat.papo.mobile.resources.profile_edit_preparing
import chat.papo.mobile.resources.profile_edit_reload
import chat.papo.mobile.resources.profile_edit_remove_banner
import chat.papo.mobile.resources.profile_edit_save
import chat.papo.mobile.resources.profile_edit_saving
import chat.papo.mobile.resources.profile_edit_status_message
import chat.papo.mobile.resources.profile_edit_step_avatar
import chat.papo.mobile.resources.profile_edit_step_banner
import chat.papo.mobile.resources.profile_edit_step_text
import chat.papo.mobile.resources.profile_edit_title
import chat.papo.mobile.resources.profile_edit_typing
import chat.papo.mobile.resources.profile_edit_typing_help
import chat.papo.mobile.core.MutationResult
import chat.papo.mobile.ui.components.LocalPlatformUi
import chat.papo.mobile.ui.components.PlatformImage
import chat.papo.mobile.ui.components.UserAvatar
import chat.papo.mobile.ui.components.colorFor
import chat.papo.mobile.ui.components.rememberMediaImage
import chat.papo.mobile.ui.components.text
import org.jetbrains.compose.resources.stringResource

private val AvatarSize = 88.dp

/**
 * Edita o próprio perfil: apelido, frase de status, descrição, frase de digitação, avatar e banner. A imagem escolhida é
 * recortada e reduzida aqui, antes de qualquer envio; "Salvar" manda só o que mudou e mostra o resultado de cada pedido.
 * Enquanto salva, nada muda e o voltar espera. Tudo o que o servidor devolve é texto simples.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreen(session: AccountSession, viewModel: EditProfileViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val users by session.chat.users.collectAsStateWithLifecycle()
    val pickAvatar = LocalPlatformUi.current.rememberImagePicker { viewModel.pick(ProfileImageKind.Avatar, it) }
    val pickBanner = LocalPlatformUi.current.rememberImagePicker { viewModel.pick(ProfileImageKind.Banner, it) }

    // Sair no meio de "Salvar" cancelaria o pedido e esconderia o resultado: o voltar espera.
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = state.saving,
        onBackCompleted = {},
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.profile_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !state.saving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            when (val load = state.load) {
                EditLoad.Loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                }
                is EditLoad.Failed -> Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(load.error.text(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = viewModel::reload) { Text(stringResource(Res.string.action_retry)) }
                }
                is EditLoad.Ready -> {
                    BannerSection(session, load.profile.id, load.profile.bannerSha, state, viewModel, pickBanner)
                    Spacer(Modifier.height(20.dp))
                    AvatarSection(session, users[session.userId], load.profile.displayName, state, viewModel, pickAvatar)
                    Spacer(Modifier.height(20.dp))
                    TextFields(state, viewModel)
                    Spacer(Modifier.height(20.dp))
                    SaveArea(state, viewModel)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun BannerSection(
    session: AccountSession,
    userId: String,
    bannerSha: String?,
    state: EditProfileState,
    viewModel: EditProfileViewModel,
    onPick: () -> Unit,
) {
    val current by rememberMediaImage(session, bannerSha?.let { "media/$it" }) { bannerSha?.let { session.api.media(it) } }
    val change = state.banner
    SectionTitle(stringResource(Res.string.profile_edit_banner))
    Box(
        Modifier.fillMaxWidth().aspectRatio(3f).clip(MaterialTheme.shapes.medium).background(colorFor(userId).copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            change is BannerChange.Replace -> PickedImageView(change.picked.image, Modifier.fillMaxSize())
            change == BannerChange.Remove -> Text(
                stringResource(Res.string.profile_edit_banner_removal),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp),
            )
            else -> current?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
    }
    Spacer(Modifier.height(8.dp))
    ImageActions(
        busy = state.reducing == ProfileImageKind.Banner,
        pickLabel = stringResource(Res.string.profile_edit_pick_banner),
        enabled = !state.saving,
        onPick = onPick,
    ) {
        if (change != BannerChange.Keep) {
            TextButton(onClick = viewModel::discardBanner, enabled = !state.saving) { Text(stringResource(Res.string.profile_edit_discard)) }
        } else if (state.hasBanner) {
            TextButton(onClick = viewModel::removeBanner, enabled = !state.saving) { Text(stringResource(Res.string.profile_edit_remove_banner)) }
        }
    }
    ImageNotes(stringResource(Res.string.profile_edit_banner_help), state.refusals[ProfileImageKind.Banner]?.text())
}

@Composable
private fun AvatarSection(
    session: AccountSession,
    user: User?,
    displayName: String,
    state: EditProfileState,
    viewModel: EditProfileViewModel,
    onPick: () -> Unit,
) {
    SectionTitle(stringResource(Res.string.profile_edit_avatar))
    val picked = state.avatar
    Box(Modifier.size(AvatarSize).clip(CircleShape)) {
        if (picked != null) {
            PickedImageView(picked.image, Modifier.fillMaxSize())
        } else {
            UserAvatar(session, user, size = AvatarSize, fallbackName = displayName)
        }
    }
    Spacer(Modifier.height(8.dp))
    ImageActions(
        busy = state.reducing == ProfileImageKind.Avatar,
        pickLabel = stringResource(Res.string.profile_edit_pick_avatar),
        enabled = !state.saving,
        onPick = onPick,
    ) {
        if (picked != null) {
            TextButton(onClick = viewModel::discardAvatar, enabled = !state.saving) { Text(stringResource(Res.string.profile_edit_discard)) }
        }
    }
    ImageNotes(stringResource(Res.string.profile_edit_avatar_help), state.refusals[ProfileImageKind.Avatar]?.text())
}

/** "Escolher …" mais o que mais couber ao lado; com a imagem sendo preparada, o botão espera e um aviso aparece. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImageActions(busy: Boolean, pickLabel: String, enabled: Boolean, onPick: () -> Unit, more: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
        OutlinedButton(onClick = onPick, enabled = enabled) { Text(pickLabel) }
        more()
    }
    if (busy) {
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.profile_edit_preparing), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ImageNotes(help: String, refusal: String?) {
    Spacer(Modifier.height(4.dp))
    Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (refusal != null) {
        Spacer(Modifier.height(4.dp))
        Text(refusal, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun TextFields(state: EditProfileState, viewModel: EditProfileViewModel) {
    Field(ProfileField.Nickname, stringResource(Res.string.profile_edit_nickname), state, viewModel)
    Spacer(Modifier.height(12.dp))
    Field(ProfileField.StatusMessage, stringResource(Res.string.profile_edit_status_message), state, viewModel)
    Spacer(Modifier.height(12.dp))
    Field(ProfileField.Description, stringResource(Res.string.profile_edit_description), state, viewModel, singleLine = false)
    Spacer(Modifier.height(12.dp))
    Field(ProfileField.Typing, stringResource(Res.string.profile_edit_typing), state, viewModel, help = stringResource(Res.string.profile_edit_typing_help))
}

/** Um campo com o contador em code points, que fica vermelho quando passa do limite do servidor. */
@Composable
private fun Field(
    field: ProfileField,
    label: String,
    state: EditProfileState,
    viewModel: EditProfileViewModel,
    singleLine: Boolean = true,
    help: String? = null,
) {
    val text = state.draft[field]
    val over = field in state.overLimit
    OutlinedTextField(
        value = text,
        onValueChange = { viewModel.edit(field, it) },
        label = { Text(label) },
        enabled = !state.saving,
        isError = over,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        maxLines = if (singleLine) 1 else 8,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        supportingText = {
            Row(Modifier.fillMaxWidth()) {
                Text(help.orEmpty(), Modifier.weight(1f))
                Text(stringResource(Res.string.profile_edit_counter, codePoints(text.trim()), field.limit))
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** "Salvar", o resultado de cada pedido e, depois de uma resposta incerta, "Recarregar perfil" (um GET, nunca um novo envio). */
@Composable
private fun SaveArea(state: EditProfileState, viewModel: EditProfileViewModel) {
    Button(onClick = viewModel::save, enabled = state.canSave, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        if (state.saving) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.profile_edit_saving))
        } else {
            Text(stringResource(Res.string.profile_edit_save))
        }
    }
    SaveStep.entries.forEach { step ->
        val outcome = state.outcomes[step] ?: return@forEach
        Spacer(Modifier.height(10.dp))
        Outcome(step, outcome)
    }
    if (state.outcomes.values.any { it == StepOutcome.Failed(MutationResult.Uncertain) }) {
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = viewModel::reload, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.profile_edit_reload))
        }
    }
}

@Composable
private fun Outcome(step: SaveStep, outcome: StepOutcome) {
    val name = stringResource(
        when (step) {
            SaveStep.Text -> Res.string.profile_edit_step_text
            SaveStep.Avatar -> Res.string.profile_edit_step_avatar
            SaveStep.Banner -> Res.string.profile_edit_step_banner
        },
    )
    val failure = (outcome as? StepOutcome.Failed)?.result?.failureText()
    val message = when (outcome) {
        StepOutcome.Sending -> stringResource(Res.string.profile_edit_outcome_sending)
        StepOutcome.Saved -> stringResource(Res.string.profile_edit_outcome_saved)
        StepOutcome.Skipped -> stringResource(Res.string.profile_edit_outcome_skipped)
        is StepOutcome.Failed -> failure.orEmpty()
    }
    Column {
        Text(name, style = MaterialTheme.typography.labelLarge)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = if (outcome is StepOutcome.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
}

/** A prévia de uma imagem já reduzida, parada (uma animação aparece no primeiro quadro). A imagem decodificada é fechada ao sair. */
@Composable
private fun PickedImageView(image: ReducedImage, modifier: Modifier) {
    val platform = LocalPlatformUi.current
    val decoded = produceState<PlatformImage?>(null, image) {
        value = null
        value = platform.decodeImage(image.bytes, maxOf(image.width, image.height), animate = false)
    }.value
    DisposableEffect(decoded) { onDispose { decoded?.close() } }
    decoded?.let { Image(it.painter, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop) }
}
