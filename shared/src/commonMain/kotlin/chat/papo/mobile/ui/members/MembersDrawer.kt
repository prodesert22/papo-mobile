package chat.papo.mobile.ui.members

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.Member
import chat.papo.mobile.chat.MemberListing
import chat.papo.mobile.chat.MemberSection
import chat.papo.mobile.chat.MemberStatus
import chat.papo.mobile.chat.groupMembers
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.members_count
import chat.papo.mobile.resources.members_none
import chat.papo.mobile.resources.members_search
import chat.papo.mobile.resources.members_section_away
import chat.papo.mobile.resources.members_section_busy
import chat.papo.mobile.resources.members_section_offline
import chat.papo.mobile.resources.members_section_online
import chat.papo.mobile.resources.members_title
import chat.papo.mobile.resources.members_view_profile
import chat.papo.mobile.resources.members_you
import chat.papo.mobile.resources.presence_away
import chat.papo.mobile.resources.presence_busy
import chat.papo.mobile.resources.presence_offline
import chat.papo.mobile.resources.presence_online
import chat.papo.mobile.resources.search_clear_text
import chat.papo.mobile.ui.chat.PresenceDot
import chat.papo.mobile.ui.components.UserAvatar
import chat.papo.mobile.ui.components.parseHexColor
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

// A folha não cobre a tela inteira num celular estreito: o resto fica com o véu, que fecha a gaveta ao toque.
private const val SheetWidthFraction = 0.85f
private val SheetMaxWidth = 320.dp

/**
 * Lista de membros do servidor numa gaveta que entra pela borda direita, por cima de [content] (a tela de baixo).
 * Quem chama é dono do [state] e o abre (`state.open()`, de um botão); a gaveta fecha ao arrastar para a direita, ao
 * tocar no véu e com o gesto de voltar do sistema. Os gestos só valem com a gaveta aberta, para não disputar com o
 * deslize das mensagens nem com o voltar da borda. Fechada, a lista não é composta: nada é buscado, nem os avatares.
 * Tocar num membro chama [onOpenProfile]; fechar a gaveta antes ou depois fica por conta de quem chama.
 *
 * Use dentro da tela (dentro do destino da navegação), não por fora dela: o voltar vai para quem se registrou por último.
 */
@Composable
fun MembersDrawer(
    session: AccountSession,
    state: DrawerState,
    onOpenProfile: (userId: String) -> Unit,
    content: @Composable () -> Unit,
) {
    val direction = LocalLayoutDirection.current
    val title = stringResource(Res.string.members_title)
    // Aberta, abrindo ou fechando (inclusive no meio de um arrasto) a folha tem lista; só parada e fechada ela some.
    val showing = state.currentValue != DrawerValue.Closed || state.targetValue != DrawerValue.Closed || state.isAnimationRunning

    // O Material só ancora a gaveta no começo da tela. Com a direção invertida em volta dela, o começo é a borda
    // direita e o arrasto, o véu e as margens do sistema acompanham; a direção de verdade volta dentro da folha e da tela.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalNavigationDrawer(
            drawerContent = {
                // Esta folha é a que tem o voltar do sistema do Material: ele só vale com o estado da gaveta.
                ModalDrawerSheet(
                    drawerState = state,
                    modifier = Modifier.fillMaxWidth(SheetWidthFraction).widthIn(max = SheetMaxWidth).semantics { paneTitle = title },
                    drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    CompositionLocalProvider(LocalLayoutDirection provides direction) {
                        if (showing) MembersSheet(session, closing = state.targetValue == DrawerValue.Closed, onOpenProfile)
                    }
                }
            },
            drawerState = state,
            gesturesEnabled = state.currentValue == DrawerValue.Open,
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides direction) { content() }
        }
    }
}

@Composable
private fun MembersSheet(session: AccountSession, closing: Boolean, onOpenProfile: (String) -> Unit) {
    val users by session.chat.users.collectAsStateWithLifecycle()
    val presence by session.chat.presence.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val listing = remember(users, presence, query) { groupMembers(users.values, presence, query, session.userId) }

    // O teclado acompanha a gaveta: com o campo em foco, ele não pode ficar aberto depois de ela fechar.
    var searching by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    LaunchedEffect(closing) { if (closing && searching) focus.clearFocus() }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(Res.string.members_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                pluralStringResource(Res.plurals.members_count, listing.total, listing.total),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SearchField(query, onChange = { query = it }, onFocusChange = { searching = it })
        if (listing.isEmpty) {
            Text(
                stringResource(Res.string.members_none),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(20.dp),
            )
        } else {
            MemberList(session, listing, onOpenProfile, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, onFocusChange: (Boolean) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        label = { Text(stringResource(Res.string.members_search), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.search_clear_text))
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        // A lista já acompanha o que se digita; a ação do teclado só o recolhe para ver o resto da lista.
        keyboardActions = KeyboardActions(onSearch = {
            keyboard?.hide()
            focus.clearFocus()
        }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).onFocusChanged { onFocusChange(it.isFocused) },
    )
}

@Composable
private fun MemberList(session: AccountSession, listing: MemberListing, onOpenProfile: (String) -> Unit, modifier: Modifier) {
    // A mesma cor da folha: o cabeçalho fixo cobre as linhas que passam por baixo dele.
    val container = MaterialTheme.colorScheme.surfaceContainerLow
    LazyColumn(modifier.fillMaxWidth()) {
        when (listing) {
            is MemberListing.Grouped -> for (section in listing.sections) {
                stickyHeader(key = "section-${section.status.name}", contentType = "header") {
                    SectionHeader(section, Modifier.background(container))
                }
                items(section.members, key = { it.id }, contentType = { "member" }) { MemberRow(session, it, onOpenProfile) }
            }
            is MemberListing.Found -> items(listing.members, key = { it.id }, contentType = { "member" }) { MemberRow(session, it, onOpenProfile) }
        }
    }
}

@Composable
private fun SectionHeader(section: MemberSection, modifier: Modifier) {
    Text(
        sectionTitle(section),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).semantics { heading() },
    )
}

@Composable
private fun sectionTitle(section: MemberSection): String = when (section.status) {
    MemberStatus.Online -> stringResource(Res.string.members_section_online, section.count)
    MemberStatus.Away -> stringResource(Res.string.members_section_away, section.count)
    MemberStatus.Busy -> stringResource(Res.string.members_section_busy, section.count)
    MemberStatus.Offline -> stringResource(Res.string.members_section_offline, section.count)
}

@Composable
private fun presenceLabel(status: MemberStatus): String = when (status) {
    MemberStatus.Online -> stringResource(Res.string.presence_online)
    MemberStatus.Away -> stringResource(Res.string.presence_away)
    MemberStatus.Busy -> stringResource(Res.string.presence_busy)
    MemberStatus.Offline -> stringResource(Res.string.presence_offline)
}

/** Nomes, mensagens de status e cargos vêm do servidor: tudo vai como texto puro, de uma linha. */
@Composable
private fun MemberRow(session: AccountSession, member: Member, onOpenProfile: (String) -> Unit) {
    val user = member.user
    val status = presenceLabel(member.status)
    Row(
        // 56 dp de altura (avatar de 40 dp e 8 dp em cima e embaixo), acima dos 48 dp do alvo de toque.
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(Res.string.members_view_profile)) { onOpenProfile(user.id) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.semantics { contentDescription = status }) {
            UserAvatar(session, user, size = 40.dp)
            PresenceDot(member.presence, Modifier.align(Alignment.BottomEnd))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    user.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = parseHexColor(user.roleColor) ?: MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (member.isMe) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(Res.string.members_you),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Text(
                member.statusMessage ?: "@${user.username}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
