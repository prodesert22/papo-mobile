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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.UserProfile
import chat.papo.mobile.chat.profileHeader
import chat.papo.mobile.core.formatDate
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.action_back
import chat.papo.mobile.resources.action_retry
import chat.papo.mobile.resources.presence_away
import chat.papo.mobile.resources.presence_busy
import chat.papo.mobile.resources.presence_offline
import chat.papo.mobile.resources.presence_online
import chat.papo.mobile.resources.profile_about
import chat.papo.mobile.resources.profile_copy_username
import chat.papo.mobile.resources.profile_member_since
import chat.papo.mobile.resources.profile_message
import chat.papo.mobile.resources.profile_not_found
import chat.papo.mobile.resources.profile_roles
import chat.papo.mobile.ui.chat.PresenceDot
import chat.papo.mobile.ui.components.UserAvatar
import chat.papo.mobile.ui.components.colorFor
import chat.papo.mobile.ui.components.parseHexColor
import chat.papo.mobile.ui.components.rememberMediaImage
import chat.papo.mobile.ui.components.text
import org.jetbrains.compose.resources.stringResource

private val AvatarSize = 88.dp
private val AvatarRing = 4.dp

/**
 * O perfil de um usuário: banner, avatar, nome, cargos e a descrição. Tudo o que vem do servidor é texto simples:
 * nada aqui é link, nem a descrição, que só se seleciona e copia. Enquanto o perfil carrega, aparece o que o app
 * já sabe da pessoa. "Mensagem" abre a conversa direta, "Copiar @usuário" copia o nome de usuário; no próprio
 * perfil não há ações.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    session: AccountSession,
    viewModel: ProfileViewModel,
    onBack: () -> Unit,
    /** Abre a conversa direta, cujo id é o argumento. */
    onOpenChat: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val users by session.chat.users.collectAsStateWithLifecycle()
    val presence by session.chat.presence.collectAsStateWithLifecycle()
    val userId = viewModel.userId
    val known = users[userId]
    val profile = (state.load as? ProfileLoad.Loaded)?.profile
    val header = profileHeader(known, profile)
    val bannerSha = profile?.bannerSha
    val banner by rememberMediaImage(session, bannerSha?.let { "media/$it" }) { bannerSha?.let { session.api.media(it) } }
    val isSelf = userId == session.userId

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth()) {
                val image = banner
                Box(Modifier.fillMaxWidth().aspectRatio(3f).background(colorFor(userId).copy(alpha = 0.55f))) {
                    if (image != null) {
                        Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
                // O avatar desce pela metade sobre o conteúdo abaixo do banner, com um aro da cor do fundo.
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 16.dp)
                        .offset(y = (AvatarSize + AvatarRing * 2) / 2)
                        .background(MaterialTheme.colorScheme.background, CircleShape)
                        .padding(AvatarRing),
                ) {
                    UserAvatar(session, known, size = AvatarSize, fallbackName = header?.displayName ?: "?")
                    PresenceDot(presence[userId], Modifier.align(Alignment.BottomEnd))
                }
            }
            Spacer(Modifier.height((AvatarSize + AvatarRing * 2) / 2 + 8.dp))

            Column(Modifier.padding(horizontal = 20.dp)) {
                header?.let {
                    Text(it.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("@${it.username}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        presenceText(presence[userId]?.status),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    it.statusMessage?.let { message ->
                        Spacer(Modifier.height(8.dp))
                        Text(message, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Spacer(Modifier.height(16.dp))

                if (!isSelf && state.load != ProfileLoad.NotFound) {
                    Actions(header?.username, state, onMessage = { viewModel.openChat(onOpenChat) })
                    Spacer(Modifier.height(8.dp))
                    BlockAction(session, userId, header?.displayName ?: "?")
                    Spacer(Modifier.height(16.dp))
                }

                when (val load = state.load) {
                    ProfileLoad.Loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    }
                    ProfileLoad.NotFound -> Text(
                        stringResource(Res.string.profile_not_found),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    is ProfileLoad.Failed -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(load.error.text(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = viewModel::retry) { Text(stringResource(Res.string.action_retry)) }
                    }
                    is ProfileLoad.Loaded -> Details(load.profile)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** "Mensagem" e "Copiar @usuário"; um erro ao abrir a conversa aparece logo abaixo, e o botão espera a resposta. */
@Composable
private fun Actions(username: String?, state: ProfileUiState, onMessage: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onMessage, enabled = !state.opening, modifier = Modifier.weight(1f)) {
                if (state.opening) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(Res.string.profile_message), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(
                onClick = { username?.let { clipboard.setText(AnnotatedString("@$it")) } },
                enabled = username != null,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(Res.string.profile_copy_username), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        state.openError?.let {
            Spacer(Modifier.height(8.dp))
            Text(it.text(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Details(profile: UserProfile) {
    if (profile.roles.isNotEmpty()) {
        Section(stringResource(Res.string.profile_roles))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            profile.roles.forEach { role ->
                Row(
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(parseHexColor(role.color) ?: MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(role.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    profile.description?.let {
        Section(stringResource(Res.string.profile_about))
        // Texto simples e selecionável: a descrição vem de fora e nada nela abre nem navega por conta própria.
        SelectionContainer { Text(it, style = MaterialTheme.typography.bodyLarge) }
        Spacer(Modifier.height(16.dp))
    }
    profile.memberSince?.formatDate()?.let {
        Text(
            stringResource(Res.string.profile_member_since, it),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun presenceText(status: String?): String = stringResource(
    when (status) {
        "online" -> Res.string.presence_online
        "away" -> Res.string.presence_away
        "busy" -> Res.string.presence_busy
        else -> Res.string.presence_offline
    },
)
