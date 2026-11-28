package chat.papo.mobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.Channel
import chat.papo.mobile.chat.ChannelNotificationSettings
import chat.papo.mobile.chat.ChannelType
import chat.papo.mobile.chat.DmNotificationSettings
import chat.papo.mobile.chat.Dm
import chat.papo.mobile.chat.Presence
import chat.papo.mobile.chat.User
import chat.papo.mobile.chat.RowPlace
import chat.papo.mobile.chat.RowStyle
import chat.papo.mobile.chat.canManageChannels
import chat.papo.mobile.chat.conversationAccess
import chat.papo.mobile.chat.effective
import chat.papo.mobile.chat.effectiveDm
import chat.papo.mobile.chat.groupChannels
import chat.papo.mobile.chat.renderMentions
import chat.papo.mobile.chat.rowPlace
import chat.papo.mobile.chat.rowStyle
import chat.papo.mobile.chat.searchByName
import chat.papo.mobile.chat.showsUnreadDot
import chat.papo.mobile.data.ws.WsState
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.action_cancel
import chat.papo.mobile.resources.action_close
import chat.papo.mobile.resources.action_retry
import chat.papo.mobile.resources.channels_direct
import chat.papo.mobile.resources.channels_direct_empty
import chat.papo.mobile.resources.channels_in_call_state
import chat.papo.mobile.resources.channels_in_voice
import chat.papo.mobile.resources.channels_new_dm
import chat.papo.mobile.resources.channels_new_dm_button
import chat.papo.mobile.resources.channels_none
import chat.papo.mobile.resources.channels_none_visible
import chat.papo.mobile.resources.channels_open_chat
import chat.papo.mobile.resources.channels_reconnecting
import chat.papo.mobile.resources.channels_sync_failed
import chat.papo.mobile.resources.channels_tab_channels
import chat.papo.mobile.resources.channels_tab_direct_short
import chat.papo.mobile.resources.channels_uncategorized
import chat.papo.mobile.resources.dm_pick_search
import chat.papo.mobile.resources.dm_pick_title
import chat.papo.mobile.resources.search_clear_filter
import chat.papo.mobile.ui.components.MentionBadge
import chat.papo.mobile.ui.components.UiError
import chat.papo.mobile.ui.components.UserAvatar
import chat.papo.mobile.ui.components.text
import chat.papo.mobile.ui.components.toUiError
import chat.papo.mobile.ui.notifications.ChannelNotificationSheet
import chat.papo.mobile.ui.notifications.DmNotificationSheet
import chat.papo.mobile.ui.theme.LocalPapoColors
import kotlinx.coroutines.launch
import kotlin.time.Clock
import org.jetbrains.compose.resources.stringResource

/**
 * A lista da conta ativa em duas abas: os canais (por categoria, a aba de abertura) e as conversas diretas. A aba
 * escolhida fica lembrada por conta, e o deslizar lateral não troca de aba: esse gesto é do painel da conversa.
 */
@Composable
fun ChannelListPane(
    session: AccountSession,
    /** A conversa que o painel mostra nesta conta (aberta ou à espera fora da tela): a linha dela fica marcada. */
    openId: String?,
    /** O canal de voz em que esta conta está numa chamada: a linha dele ganha o verde da faixa de chamada. */
    callChannelId: String?,
    onOpen: (channelId: String) -> Unit,
    onJoinVoice: (channelId: String) -> Unit,
    /** Abre as configurações do canal (nome e descrição). Só se oferece a quem tem `manage_channels` ([canManageChannels]). */
    onOpenChannelSettings: (channelId: String) -> Unit = {},
    /** A conversa direta [dmId] foi fechada: quem guarda a conversa aberta ou estacionada a esquece. */
    onDmClosed: (dmId: String) -> Unit = {},
) {
    val chat = session.chat
    // Só os canais que a conta pode ver; `allChannels` diz se o servidor tem algum, para a mensagem de lista vazia.
    val allChannels by chat.channels.collectAsStateWithLifecycle()
    val channels by chat.visibleChannels.collectAsStateWithLifecycle()
    val permissions by chat.access.collectAsStateWithLifecycle()
    val dms by chat.dms.collectAsStateWithLifecycle()
    val users by chat.users.collectAsStateWithLifecycle()
    val presence by chat.presence.collectAsStateWithLifecycle()
    val voice by chat.voiceMembers.collectAsStateWithLifecycle()
    val connection by chat.connection.collectAsStateWithLifecycle()
    val syncError by chat.syncError.collectAsStateWithLifecycle()
    val syncing by chat.syncing.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val blocked by session.blocks.blocked.collectAsStateWithLifecycle()
    var pickingUser by remember { mutableStateOf(false) }
    var dmError by remember { mutableStateOf<UiError?>(null) }
    val groups = remember(channels) { groupChannels(channels) }
    // As configurações e o número de menções: mudar uma delas (ou um silêncio acabar) refaz as linhas.
    val prefs = session.notificationPrefs
    val serverSettings by prefs.server.collectAsStateWithLifecycle()
    val channelSettings by prefs.channels.collectAsStateWithLifecycle()
    val dmSettings by prefs.dms.collectAsStateWithLifecycle()
    val mentions by session.mentions.mentions.collectAsStateWithLifecycle()
    var sheetChannel by remember { mutableStateOf<Channel?>(null) }
    var sheetDm by remember { mutableStateOf<Dm?>(null) }
    var tabs by rememberSaveable(stateSaver = ListTabs.Saver) { mutableStateOf(ListTabs.Empty) }
    val tab = tabs[session.accountId]
    val badges = remember(mentions) { tabBadges(mentions) }

    Column(Modifier.fillMaxSize()) {
        ListTabRow(tab, badges) { tabs = tabs.selected(session.accountId, it) }
        // Os avisos de conexão valem para as duas abas e ficam à vista, fora da rolagem.
        if (connection != WsState.Connected && !syncing && allChannels.isNotEmpty()) {
            StatusLine(stringResource(Res.string.channels_reconnecting))
        }
        if (syncError != null) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.channels_sync_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { scope.launch { chat.runSync() } }) { Text(stringResource(Res.string.action_retry)) }
            }
        }

        when (tab) {
            ListTab.Channels -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (channels.isEmpty() && !syncing && syncError == null) {
                    val message = if (allChannels.isEmpty()) Res.string.channels_none else Res.string.channels_none_visible
                    item(key = "empty") { StatusLine(stringResource(message)) }
                }
                groups.forEach { group ->
                    item(key = "cat-" + (group.category?.id ?: "none")) {
                        SectionHeader(group.category?.name ?: stringResource(Res.string.channels_uncategorized))
                    }
                    items(group.channels, key = { "ch-" + it.id }) { channel ->
                        val access = if (channel.type == ChannelType.Voice) {
                            conversationAccess(permissions, session.userId, channel.id, isDm = false)
                        } else {
                            null
                        }
                        // Tocar no canal de voz entra na chamada, se a conta pode; senão abre o chat. O chat também abre pelo botão
                        // no fim da linha, que só existe quando o toque entra na chamada e a conta lê o canal.
                        val joinsCall = access?.joinVoice == true
                        val chatButton = joinsCall && access?.read == true
                        val muted = !showsUnreadDot(effective(serverSettings, channelSettings[channel.id] ?: ChannelNotificationSettings(), Clock.System.now()))
                        val style = rowStyle(channel.unread, mentions.perChannel[channel.id] ?: 0, mentions.capped, muted)
                        ChannelRow(
                            channel,
                            voice[channel.id].orEmpty().size,
                            style,
                            rowPlace(channel.id, openId, callChannelId),
                            onOpenChat = if (chatButton) ({ onOpen(channel.id) }) else null,
                            onLongClick = { sheetChannel = channel },
                        ) {
                            if (joinsCall) onJoinVoice(channel.id) else onOpen(channel.id)
                        }
                    }
                }
            }
            ListTab.Direct -> if (dms.isEmpty()) {
                // A mensagem de vazio só vale com a leitura feita e sem erro: antes disso, ou com o aviso de falha acima, não se sabe.
                if (!syncing && syncError == null) EmptyDirect { pickingUser = true }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item(key = "dm-new") {
                        Row(Modifier.fillMaxWidth().padding(end = 4.dp), horizontalArrangement = Arrangement.End) {
                            IconButton(onClick = { pickingUser = true }) {
                                Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.channels_new_dm))
                            }
                        }
                    }
                    items(dms, key = { "dm-" + it.id }) { dm ->
                        val muted = !showsUnreadDot(effectiveDm(serverSettings, dmSettings[dm.id] ?: DmNotificationSettings(), Clock.System.now()))
                        val style = rowStyle(dm.unreadCount > 0, mentions.perDm[dm.id] ?: 0, capped = false, muted = muted)
                        DmRow(
                            session, dm, users[dm.userId], presence[dm.userId], users, style,
                            place = rowPlace(dm.id, openId, callId = null),
                            onLongClick = { sheetDm = dm },
                        ) { onOpen(dm.id) }
                    }
                }
            }
        }
    }

    if (pickingUser) {
        UserPickerDialog(
            session = session,
            title = stringResource(Res.string.dm_pick_title),
            users = users.values.filter { it.id != session.userId && !it.banned && it.id !in blocked }.sortedBy { it.displayName.lowercase() },
            onDismiss = { pickingUser = false },
            onPick = { user ->
                pickingUser = false
                scope.launch {
                    val channelId = try {
                        chat.openDm(user.id)
                    } catch (e: Throwable) {
                        // Um bloqueio de quem foi escolhido, ou qualquer outra recusa, aparece em vez de sumir.
                        dmError = e.toUiError() // relança o cancelamento
                        return@launch
                    }
                    onOpen(channelId)
                }
            },
        )
    }
    sheetChannel?.let { channel ->
        ChannelNotificationSheet(
            prefs,
            channel,
            onDismiss = { sheetChannel = null },
            // Só a quem tem a permissão de certeza: o que é de administração não se oferece para o servidor recusar.
            onOpenSettings = if (canManageChannels(permissions, session.userId)) ({ onOpenChannelSettings(channel.id) }) else null,
        )
    }
    sheetDm?.let { dm ->
        DmNotificationSheet(
            prefs,
            dm.id,
            users[dm.userId]?.displayName ?: "",
            onDismiss = { sheetDm = null },
            onClose = {
                scope.launch {
                    try {
                        chat.closeDm(dm.id)
                    } catch (e: Throwable) {
                        // A falha aparece e nada é repetido sozinho (um 429 também não); a linha fica, porque o servidor não a fechou.
                        dmError = e.toUiError() // relança o cancelamento
                        return@launch
                    }
                    onDmClosed(dm.id)
                }
            },
        )
    }
    dmError?.let { error ->
        AlertDialog(
            onDismissRequest = { dmError = null },
            text = { Text(error.text()) },
            confirmButton = { TextButton(onClick = { dmError = null }) { Text(stringResource(Res.string.action_close)) } },
        )
    }
}

/**
 * As duas abas logo abaixo do usuário, cada uma com o selo de menções dela. O nome da segunda cai para a forma curta
 * quando a versão inteira não cabe numa linha (tela estreita, letra grande, selo largo).
 */
@Composable
private fun ListTabRow(selected: ListTab, badges: TabBadges, onSelect: (ListTab) -> Unit) {
    PrimaryTabRow(selectedTabIndex = selected.ordinal, containerColor = Color.Transparent) {
        ListTab.entries.forEach { tab ->
            Tab(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                text = {
                    when (tab) {
                        ListTab.Channels -> TabLabel(stringResource(Res.string.channels_tab_channels), null, badges.channels)
                        ListTab.Direct -> TabLabel(
                            stringResource(Res.string.channels_direct),
                            stringResource(Res.string.channels_tab_direct_short),
                            badges.direct,
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun TabLabel(title: String, shortTitle: String?, badge: String?) {
    // Medido na própria linha: se o nome inteiro transborda, passa para a forma curta; um selo novo mede de novo.
    var short by remember(badge, shortTitle) { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (short && shortTitle != null) shortTitle else title,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
            onTextLayout = { if (it.hasVisualOverflow && shortTitle != null) short = true },
        )
        badge?.let {
            Spacer(Modifier.width(6.dp))
            MentionBadge(it)
        }
    }
}

/** A aba de conversas diretas sem nenhuma: o convite para começar uma, no centro. */
@Composable
private fun EmptyDirect(onNew: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(
            stringResource(Res.string.channels_direct_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onNew) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.channels_new_dm_button))
        }
    }
}

@Composable
private fun StatusLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SectionHeader(title: String, action: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 16.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        action()
    }
}

/**
 * A cor do nome e do ícone de uma linha: destaque, comum ou esmaecida, como diz [RowStyle]. Onde a pessoa está
 * ([RowPlace]) o nome sai na cor de texto mais forte, sem o negrito do destaque de mensagem nova.
 */
@Composable
private fun rowColor(style: RowStyle, place: RowPlace) = when {
    style.emphasized -> LocalPapoColors.current.unreadName
    place != RowPlace.None -> MaterialTheme.colorScheme.onSurface
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** O peso do nome: negrito com mensagem nova, médio onde a pessoa está, comum no resto. */
private fun rowWeight(style: RowStyle, place: RowPlace) = when {
    style.emphasized -> FontWeight.SemiBold
    place != RowPlace.None -> FontWeight.Medium
    else -> FontWeight.Normal
}

/** A opacidade do que esmaece numa conversa silenciada. */
private const val DIMMED_ALPHA = 0.45f

/** O fundo da conversa aberta: a cor do texto bem clara, que aparece sobre qualquer fundo de tema. */
private const val OPEN_ALPHA = 0.1f

/** O fundo do canal da chamada: o mesmo verde translúcido da faixa de chamada no topo da lista ([chat.papo.mobile.ui.call.CallBar]). */
private const val IN_CALL_ALPHA = 0.18f

/** A forma do fundo de uma linha, recuada das bordas, que também limita a ondulação do toque. */
private val RowShape = RoundedCornerShape(10.dp)

/** O recuo da linha até a borda da lista; somado ao de dentro dá os 16 dp das outras linhas da tela. */
private val RowInset = 8.dp

/**
 * A moldura de uma linha da lista: recuada das bordas, com o fundo de onde a pessoa está ([RowPlace]) e o toque
 * ([onClick], [onLongClick]) dentro dela; [padding] é o recuo do conteúdo. O leitor de tela ouve "selecionado" na
 * conversa aberta e que a pessoa está na chamada no canal dela.
 */
@Composable
private fun ListRow(
    place: RowPlace,
    padding: PaddingValues,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val inCall = stringResource(Res.string.channels_in_call_state)
    val ground = when (place) {
        RowPlace.None -> Color.Transparent
        RowPlace.Open -> MaterialTheme.colorScheme.onSurface.copy(alpha = OPEN_ALPHA)
        RowPlace.InCall -> LocalPapoColors.current.online.copy(alpha = IN_CALL_ALPHA)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = RowInset, vertical = 1.dp)
            .clip(RowShape)
            .background(ground)
            .semantics {
                selected = place == RowPlace.Open
                if (place == RowPlace.InCall) stateDescription = inCall
            }
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
            .padding(padding),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun ChannelRow(
    channel: Channel,
    inVoice: Int,
    style: RowStyle,
    place: RowPlace,
    onOpenChat: (() -> Unit)?,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    val accent = LocalPapoColors.current.accent
    val emphasis = rowColor(style, place)
    // Com o botão de chat no fim a linha ganha a altura dele, então o recuo de cima e de baixo sai.
    val hasChatButton = onOpenChat != null
    val vertical = if (hasChatButton) 0.dp else 9.dp
    ListRow(
        place,
        PaddingValues(start = 16.dp - RowInset, end = if (hasChatButton) 0.dp else 16.dp - RowInset, top = vertical, bottom = vertical),
        onClick,
        onLongClick,
    ) {
        Row(Modifier.weight(1f).alpha(if (style.dimmed) DIMMED_ALPHA else 1f), verticalAlignment = Alignment.CenterVertically) {
            // Na chamada o alto-falante vira o telefone da faixa de chamada, na cor do nome como lá: o verde fica no fundo,
            // e a forma diz o mesmo a quem não distingue a cor. Verde sobre verde quase some no tema claro.
            Icon(
                when {
                    place == RowPlace.InCall -> Icons.Filled.PhoneInTalk
                    channel.type == ChannelType.Voice -> Icons.AutoMirrored.Filled.VolumeUp
                    else -> Icons.Filled.Tag
                },
                contentDescription = null,
                tint = emphasis,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    channel.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = rowWeight(style, place),
                    color = emphasis,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (channel.type == ChannelType.Voice && inVoice > 0) {
                    Text(
                        stringResource(Res.string.channels_in_voice, inVoice),
                        style = MaterialTheme.typography.labelMedium,
                        color = LocalPapoColors.current.online,
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        style.badge?.let { MentionBadge(it) }
        if (style.showDot) Box(Modifier.size(9.dp).background(accent, CircleShape))
        if (onOpenChat != null) {
            IconButton(onClick = onOpenChat) {
                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = stringResource(Res.string.channels_open_chat))
            }
        }
    }
}

@Composable
private fun DmRow(
    session: AccountSession,
    dm: Dm,
    user: User?,
    presence: Presence?,
    users: Map<String, User>,
    style: RowStyle,
    place: RowPlace,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    ListRow(place, PaddingValues(horizontal = 16.dp - RowInset, vertical = 8.dp), onClick, onLongClick) {
        Row(Modifier.weight(1f).alpha(if (style.dimmed) DIMMED_ALPHA else 1f), verticalAlignment = Alignment.CenterVertically) {
            Box {
                UserAvatar(session, user, size = 40.dp)
                PresenceDot(presence, Modifier.align(Alignment.BottomEnd))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    user?.displayName ?: "?",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = rowWeight(style, place),
                    color = rowColor(style, place),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                dm.lastMessageText?.takeIf { it.isNotBlank() }?.let { text ->
                    Text(
                        renderMentions(text) { users[it]?.displayName },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        // Na conversa direta o número é o das mensagens não lidas, e o círculo de mensagem nova não tem vez: ele já é o número.
        style.badge?.let { MentionBadge(it) }
    }
}

@Composable
fun PresenceDot(presence: Presence?, modifier: Modifier = Modifier) {
    val color = when (presence?.status) {
        "online" -> LocalPapoColors.current.online
        "away" -> LocalPapoColors.current.accent
        "busy" -> MaterialTheme.colorScheme.error
        else -> return
    }
    Box(
        modifier.size(13.dp).background(MaterialTheme.colorScheme.background, CircleShape).padding(2.dp).background(color, CircleShape),
    )
}

/**
 * Escolha de uma pessoa, com busca por nome ou `@usuário` sem diferenciar acento
 * nem maiúsculas. [onClear], quando dado, oferece desfazer a escolha atual.
 */
@Composable
internal fun UserPickerDialog(
    session: AccountSession,
    title: String,
    users: List<User>,
    onDismiss: () -> Unit,
    onPick: (User) -> Unit,
    onClear: (() -> Unit)? = null,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(users, query) { searchByName(query, users) { listOf(it.username, it.displayName) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(Res.string.dm_pick_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(filtered, key = { it.id }) { user ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(user) }.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            UserAvatar(session, user, size = 36.dp)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(user.displayName, style = MaterialTheme.typography.bodyLarge)
                                if (user.displayName != user.username) {
                                    Text(
                                        "@${user.username}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (onClear != null) TextButton(onClick = onClear) { Text(stringResource(Res.string.search_clear_filter)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        // Sem cor explícita os nomes saem pretos, ilegíveis no tema escuro.
        textContentColor = MaterialTheme.colorScheme.onSurface,
    )
}
