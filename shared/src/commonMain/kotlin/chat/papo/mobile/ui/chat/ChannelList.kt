package chat.papo.mobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
import chat.papo.mobile.chat.RowStyle
import chat.papo.mobile.chat.conversationAccess
import chat.papo.mobile.chat.effective
import chat.papo.mobile.chat.effectiveDm
import chat.papo.mobile.chat.groupChannels
import chat.papo.mobile.chat.renderMentions
import chat.papo.mobile.chat.rowStyle
import chat.papo.mobile.chat.searchByName
import chat.papo.mobile.chat.showsUnreadDot
import chat.papo.mobile.data.ws.WsState
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.action_cancel
import chat.papo.mobile.resources.action_close
import chat.papo.mobile.resources.action_retry
import chat.papo.mobile.resources.channels_direct
import chat.papo.mobile.resources.channels_in_voice
import chat.papo.mobile.resources.channels_new_dm
import chat.papo.mobile.resources.channels_none
import chat.papo.mobile.resources.channels_none_visible
import chat.papo.mobile.resources.channels_open_chat
import chat.papo.mobile.resources.channels_reconnecting
import chat.papo.mobile.resources.channels_sync_failed
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

/** Lista de conversas diretas e canais (por categoria) da conta ativa. */
@Composable
fun ChannelListPane(
    session: AccountSession,
    onOpen: (channelId: String) -> Unit,
    onJoinVoice: (channelId: String) -> Unit,
    /** Abre as configurações do canal (nome e descrição). Só se oferece a quem tem `manage_channels` ([canManageChannels]). */
    onOpenChannelSettings: (channelId: String) -> Unit = {},
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

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (connection != WsState.Connected && !syncing && allChannels.isNotEmpty()) {
            item(key = "offline") { StatusLine(stringResource(Res.string.channels_reconnecting)) }
        }
        if (syncError != null) {
            item(key = "sync-error") {
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
        }

        item(key = "dm-header") {
            SectionHeader(stringResource(Res.string.channels_direct)) {
                IconButton(onClick = { pickingUser = true }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.channels_new_dm))
                }
            }
        }
        items(dms, key = { "dm-" + it.id }) { dm ->
            val muted = !showsUnreadDot(effectiveDm(serverSettings, dmSettings[dm.id] ?: DmNotificationSettings(), Clock.System.now()))
            val style = rowStyle(dm.unreadCount > 0, mentions.perDm[dm.id] ?: 0, capped = false, muted = muted)
            DmRow(session, dm, users[dm.userId], presence[dm.userId], users, style, onLongClick = { sheetDm = dm }) { onOpen(dm.id) }
        }

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
                    onOpenChat = if (chatButton) ({ onOpen(channel.id) }) else null,
                    onLongClick = { sheetChannel = channel },
                ) {
                    if (joinsCall) onJoinVoice(channel.id) else onOpen(channel.id)
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
    sheetChannel?.let { channel -> ChannelNotificationSheet(prefs, channel, onDismiss = { sheetChannel = null }) }
    sheetDm?.let { dm ->
        DmNotificationSheet(prefs, dm.id, users[dm.userId]?.displayName ?: "?", onDismiss = { sheetDm = null })
    }
    dmError?.let { error ->
        AlertDialog(
            onDismissRequest = { dmError = null },
            text = { Text(error.text()) },
            confirmButton = { TextButton(onClick = { dmError = null }) { Text(stringResource(Res.string.action_close)) } },
        )
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

/** A cor do nome e do ícone de uma linha: destaque, comum ou esmaecida, como diz [RowStyle]. */
@Composable
private fun rowColor(style: RowStyle) = when {
    style.emphasized -> LocalPapoColors.current.unreadName
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** A opacidade do que esmaece numa conversa silenciada. */
private const val DIMMED_ALPHA = 0.45f

@Composable
private fun ChannelRow(
    channel: Channel,
    inVoice: Int,
    style: RowStyle,
    onOpenChat: (() -> Unit)?,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    val accent = LocalPapoColors.current.accent
    val haptics = LocalHapticFeedback.current
    val emphasis = rowColor(style)
    // Com o botão de chat no fim a linha ganha a altura dele, então o recuo de cima e de baixo sai.
    val hasChatButton = onOpenChat != null
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
            .padding(start = 16.dp, end = if (hasChatButton) 4.dp else 16.dp)
            .padding(vertical = if (hasChatButton) 0.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).alpha(if (style.dimmed) DIMMED_ALPHA else 1f), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (channel.type == ChannelType.Voice) Icons.AutoMirrored.Filled.VolumeUp else Icons.Filled.Tag,
                contentDescription = null,
                tint = emphasis,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    channel.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (style.emphasized) FontWeight.SemiBold else FontWeight.Normal,
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
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
                    fontWeight = if (style.emphasized) FontWeight.SemiBold else FontWeight.Normal,
                    color = rowColor(style),
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
