package chat.papo.mobile.ui.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import chat.papo.mobile.chat.UserProfile
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.filled.Edit
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.clickable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
import chat.papo.mobile.resources.profile_edit_avatar_help
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
private val AvatarRing = 4.dp
private val PencilSize = 36.dp

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
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())) {
            when (val load = state.load) {
                EditLoad.Loading -> Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                }
                is EditLoad.Failed -> Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(load.error.text(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = viewModel::reload) { Text(stringResource(Res.string.action_retry)) }
                }
                is EditLoad.Ready -> {
                    ProfileHeader(session, users[session.userId], load.profile, state, viewModel, pickAvatar, pickBanner)
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        ImageNotes(stringResource(Res.string.profile_edit_banner_help), state.refusals[ProfileImageKind.Banner]?.text())
                        ImageNotes(stringResource(Res.string.profile_edit_avatar_help), state.refusals[ProfileImageKind.Avatar]?.text())
                        Spacer(Modifier.height(20.dp))
                        // A sessão entra na chave: o estado de uma conta nunca aparece na tela de outra.
                        val statusModel = viewModel<OwnStatusViewModel>(key = "own-status/${session.accountId}/${session.hashCode()}") { OwnStatusViewModel(session) }
                        OwnStatusPicker(statusModel)
                        Spacer(Modifier.height(20.dp))
                        TextFields(state, viewModel)
                        Spacer(Modifier.height(20.dp))
                        SaveArea(state, viewModel)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * O alto da tela como no Discord: o banner de ponta a ponta, o avatar sobreposto à sua borda de baixo, cada um com um lápis
 * que escolhe a imagem (e, quando cabe, remove o banner ou desfaz a escolha), e embaixo o nome com o `@usuário`.
 * O nome acompanha o apelido que está sendo digitado.
 */
@Composable
private fun ProfileHeader(
    session: AccountSession,
    user: User?,
    profile: UserProfile,
    state: EditProfileState,
    viewModel: EditProfileViewModel,
    onPickAvatar: () -> Unit,
    onPickBanner: () -> Unit,
) {
    val bannerSha = profile.bannerSha
    val current by rememberMediaImage(session, bannerSha?.let { "media/$it" }) { bannerSha?.let { session.api.media(it) } }
    val change = state.banner
    val picked = state.avatar
    val name = state.draft[ProfileField.Nickname].trim().ifEmpty { profile.username }
    val ring = AvatarSize + AvatarRing * 2
    Box(Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(3f).background(colorFor(profile.id).copy(alpha = 0.55f)),
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
            if (state.reducing == ProfileImageKind.Banner) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        }
        PencilButton(
            Modifier.align(Alignment.TopEnd).padding(12.dp),
            pickLabel = stringResource(Res.string.profile_edit_pick_banner),
            enabled = !state.saving && state.reducing == null,
            onPick = onPickBanner,
            extras = when {
                change != BannerChange.Keep -> listOf(stringResource(Res.string.profile_edit_discard) to viewModel::discardBanner)
                state.hasBanner -> listOf(stringResource(Res.string.profile_edit_remove_banner) to viewModel::removeBanner)
                else -> emptyList()
            },
        )
        Box(Modifier.align(Alignment.BottomStart).padding(start = 16.dp).offset(y = ring / 2)) {
            Box(Modifier.size(ring).background(MaterialTheme.colorScheme.background, CircleShape).padding(AvatarRing).clip(CircleShape)) {
                if (picked != null) {
                    PickedImageView(picked.image, Modifier.fillMaxSize())
                } else {
                    UserAvatar(session, user, size = AvatarSize, fallbackName = name)
                }
                if (state.reducing == ProfileImageKind.Avatar) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp), strokeWidth = 3.dp)
                }
            }
            PencilButton(
                Modifier.align(Alignment.TopEnd),
                pickLabel = stringResource(Res.string.profile_edit_pick_avatar),
                enabled = !state.saving && state.reducing == null,
                onPick = onPickAvatar,
                extras = if (picked != null) listOf(stringResource(Res.string.profile_edit_discard) to viewModel::discardAvatar) else emptyList(),
            )
        }
    }
    Spacer(Modifier.height(ring / 2 + 8.dp))
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text(name, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("@${profile.username}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * O lápis redondo sobre uma imagem. Sem [extras], um toque escolhe a imagem; com eles abre um menu com "escolher" e o que
 * mais couber (remover, desfazer).
 */
@Composable
private fun PencilButton(modifier: Modifier, pickLabel: String, enabled: Boolean, onPick: () -> Unit, extras: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Box(
            Modifier
                .size(PencilSize)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = if (enabled) 0.65f else 0.35f))
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = pickLabel) { if (extras.isEmpty()) onPick() else open = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Edit, contentDescription = pickLabel, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(pickLabel) }, onClick = { open = false; onPick() })
            extras.forEach { (label, action) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() }) }
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
