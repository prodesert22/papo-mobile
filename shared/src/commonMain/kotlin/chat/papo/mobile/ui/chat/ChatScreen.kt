package chat.papo.mobile.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.ChannelType
import chat.papo.mobile.chat.Message
import chat.papo.mobile.chat.User
import chat.papo.mobile.chat.renderMentions
import chat.papo.mobile.core.dayKey
import chat.papo.mobile.core.formatDate
import chat.papo.mobile.data.api.AttachmentDto
import chat.papo.mobile.data.api.OutgoingFile
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.action_back
import chat.papo.mobile.resources.action_cancel
import chat.papo.mobile.resources.action_close
import chat.papo.mobile.resources.chat_attach
import chat.papo.mobile.resources.chat_editing
import chat.papo.mobile.resources.chat_empty
import chat.papo.mobile.resources.chat_focus_missing
import chat.papo.mobile.resources.chat_focus_too_old
import chat.papo.mobile.resources.chat_history_start
import chat.papo.mobile.resources.chat_locating
import chat.papo.mobile.resources.chat_pinned
import chat.papo.mobile.resources.chat_pinned_empty
import chat.papo.mobile.resources.chat_placeholder
import chat.papo.mobile.resources.chat_placeholder_dm
import chat.papo.mobile.resources.chat_remove_attachment
import chat.papo.mobile.resources.chat_replying_to
import chat.papo.mobile.resources.chat_search
import chat.papo.mobile.resources.chat_send
import chat.papo.mobile.resources.chat_today
import chat.papo.mobile.resources.chat_typing_many
import chat.papo.mobile.resources.chat_typing_one
import chat.papo.mobile.resources.chat_unknown_user
import chat.papo.mobile.resources.chat_yesterday
import chat.papo.mobile.resources.msg_copy
import chat.papo.mobile.resources.msg_delete
import chat.papo.mobile.resources.msg_delete_body
import chat.papo.mobile.resources.msg_delete_title
import chat.papo.mobile.resources.msg_edit
import chat.papo.mobile.resources.msg_pin
import chat.papo.mobile.resources.msg_reply
import chat.papo.mobile.resources.msg_unpin
import chat.papo.mobile.resources.presence_away
import chat.papo.mobile.resources.presence_busy
import chat.papo.mobile.resources.presence_offline
import chat.papo.mobile.resources.presence_online
import chat.papo.mobile.ui.components.LocalPlatformUi
import chat.papo.mobile.ui.components.UserAvatar
import chat.papo.mobile.ui.components.formatBytes
import chat.papo.mobile.ui.components.rememberMediaImage
import chat.papo.mobile.ui.components.text
import chat.papo.mobile.ui.settings.ConfirmDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val quickReactions = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(session: AccountSession, viewModel: ChatViewModel, onBack: () -> Unit, onSearch: () -> Unit) {
    val chat = session.chat
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val users by chat.users.collectAsStateWithLifecycle()
    val channels by chat.channels.collectAsStateWithLifecycle()
    val dms by chat.dms.collectAsStateWithLifecycle()
    val presence by chat.presence.collectAsStateWithLifecycle()
    val typing by chat.typing.collectAsStateWithLifecycle()
    val pinnedIds by chat.pinned.collectAsStateWithLifecycle()

    val channel = channels.firstOrNull { it.id == viewModel.channelId }
    val dmUser = dms.firstOrNull { it.id == viewModel.channelId }?.let { users[it.userId] }
    val title = channel?.name ?: dmUser?.displayName ?: ""

    var draft by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var actionsFor by remember { mutableStateOf<Message?>(null) }
    var confirmDelete by remember { mutableStateOf<Message?>(null) }
    var viewing by remember { mutableStateOf<AttachmentDto?>(null) }
    val listState = rememberLazyListState()
    val byId = remember(messages) { messages.associateBy { it.id } }

    // Carrega mais histórico quando a rolagem se aproxima da mensagem mais antiga.
    LaunchedEffect(listState, messages.size) {
        snapshotFlow { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= messages.size - 8 }
            .distinctUntilChanged()
            .collect { nearStart -> if (nearStart && messages.isNotEmpty()) viewModel.loadOlder() }
    }
    // O canal conta como lido quando a mensagem mais nova está na tela.
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    val newest = messages.firstOrNull()
    var previousNewest by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(newest?.id) {
        // Ao inserir um item, a lista mantém o antigo ancorado e o novo fica fora da tela.
        // Se o usuário estava no fim (ou a mensagem é dele), acompanha a conversa.
        val shifted = messages.indexOfFirst { it.id == previousNewest }
        val wasAtBottom = previousNewest == null || (shifted >= 0 && listState.firstVisibleItemIndex <= shifted)
        previousNewest = newest?.id
        if (newest != null && (wasAtBottom || newest.authorId == session.userId)) listState.scrollToItem(0)
    }
    LaunchedEffect(newest?.id, atBottom) {
        if (newest != null && atBottom) viewModel.markRead(newest)
    }
    // Outra conversa pode ter sido aberta por cima desta (busca → mensagem): ao voltar, é esta que está na tela.
    LifecycleResumeEffect(viewModel) {
        viewModel.onVisible()
        onPauseOrDispose {}
    }
    // Mensagem pedida ao abrir: rola até ela quando entra na lista e a destaca por alguns segundos.
    val focusIndex = state.focusId?.let { id -> messages.indexOfFirst { it.id == id } } ?: -1
    LaunchedEffect(state.focusId, focusIndex >= 0) {
        if (focusIndex < 0) return@LaunchedEffect
        try {
            // A lista é invertida: o deslocamento negativo sobe a mensagem e deixa à vista o que veio depois dela.
            listState.scrollToItem(focusIndex, -listState.layoutInfo.viewportSize.height / 3)
            delay(4.seconds)
        } finally {
            // Também ao sair da tela antes do tempo: voltar a ela não deve rolar de novo.
            viewModel.clearFocus()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (dmUser != null) {
                            UserAvatar(session, dmUser, size = 32.dp)
                            Spacer(Modifier.width(10.dp))
                        } else {
                            Icon(Icons.Filled.Tag, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Column {
                            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val subtitle = if (dmUser != null) presenceLabel(presence[dmUser.id]?.status) else channel?.topic
                            subtitle?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Filled.Search, contentDescription = stringResource(Res.string.chat_search))
                    }
                    if (channel != null && channel.type != ChannelType.Category) {
                        IconButton(onClick = viewModel::showPinned) {
                            Icon(Icons.Filled.PushPin, contentDescription = stringResource(Res.string.chat_pinned))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (messages.isEmpty() && state.loadedOnce) {
                    Text(
                        stringResource(Res.string.chat_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.BottomStart).padding(20.dp),
                    )
                }
                LazyColumn(Modifier.fillMaxSize(), state = listState, reverseLayout = true) {
                    items(messages.size, key = { messages[it].id }) { index ->
                        val message = messages[index]
                        val older = messages.getOrNull(index + 1)
                        val newDay = older == null || older.createdAt?.dayKey() != message.createdAt?.dayKey()
                        val compact = !newDay && older != null && older.authorId == message.authorId &&
                            message.replyTo == null && older.externalAuthor?.name == message.externalAuthor?.name &&
                            closeInTime(older, message)
                        Column {
                            if (newDay && (older != null || state.reachedStart)) DaySeparator(message)
                            MessageItem(
                                session = session,
                                message = message,
                                author = message.authorId?.let { users[it] },
                                users = users,
                                repliedTo = message.replyTo?.let { byId[it] },
                                compact = compact,
                                pinned = pinnedIds[viewModel.channelId]?.contains(message.id) == true,
                                highlighted = state.replyTo?.id == message.id || state.editing?.id == message.id ||
                                    state.focusId == message.id,
                                onLongPress = { actionsFor = message },
                                onReact = { viewModel.react(message, it.emojiId, it.unicode) },
                                onOpenImage = { viewing = it },
                            )
                        }
                    }
                    if (state.loadingOlder) {
                        item(key = "loading") {
                            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        }
                    } else if (state.reachedStart && messages.isNotEmpty()) {
                        item(key = "start") {
                            Text(
                                stringResource(Res.string.chat_history_start),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                            )
                        }
                    }
                }
                if (state.locating) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shadowElevation = 2.dp,
                        modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(Res.string.chat_locating), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                Column(Modifier.align(Alignment.BottomCenter).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.focusProblem?.let { problem ->
                        val text = when (problem) {
                            FocusProblem.Missing -> Res.string.chat_focus_missing
                            FocusProblem.TooOld -> Res.string.chat_focus_too_old
                        }
                        NoticeBar(stringResource(text), viewModel::dismissFocusProblem)
                    }
                    state.error?.let { error -> NoticeBar(error.text(), viewModel::dismissError) }
                }
            }

            TypingLine(typing[viewModel.channelId].orEmpty().mapNotNull { users[it] })
            Composer(
                session = session,
                state = state,
                draft = draft,
                users = users,
                placeholder = if (dmUser != null) {
                    stringResource(Res.string.chat_placeholder_dm, title)
                } else {
                    stringResource(Res.string.chat_placeholder, "#$title")
                },
                onDraftChange = {
                    if (it.text != draft.text && it.text.isNotEmpty()) viewModel.onTyping()
                    draft = it
                },
                onPickMention = viewModel::pickMention,
                onAddFiles = viewModel::addFiles,
                onRemoveFile = viewModel::removeFile,
                onCancelContext = {
                    if (state.editing != null) draft = TextFieldValue("")
                    viewModel.cancelContext()
                },
                onSubmit = { viewModel.submit(draft.text) { draft = TextFieldValue("") } },
            )
        }
    }

    actionsFor?.let { message ->
        val isPinned = pinnedIds[viewModel.channelId]?.contains(message.id) == true
        MessageActionsSheet(
            session = session,
            message = message,
            mine = message.authorId == session.userId,
            canPin = channel != null,
            pinned = isPinned,
            onDismiss = { actionsFor = null },
            onReact = { emojiId, unicode -> viewModel.react(message, emojiId, unicode) },
            onReply = { viewModel.startReply(message) },
            onEdit = {
                viewModel.startEdit(message)
                val shown = renderForEditing(message.content, users)
                draft = TextFieldValue(shown, TextRange(shown.length))
            },
            onDelete = { confirmDelete = message },
            onTogglePin = { viewModel.setPinned(message, !isPinned) },
        )
    }
    confirmDelete?.let { message ->
        ConfirmDialog(
            title = stringResource(Res.string.msg_delete_title),
            body = stringResource(Res.string.msg_delete_body),
            confirm = stringResource(Res.string.msg_delete),
            onDismiss = { confirmDelete = null },
            onConfirm = {
                confirmDelete = null
                viewModel.delete(message)
            },
        )
    }
    viewing?.let { ImageViewer(session, it) { viewing = null } }
    state.pinnedList?.let { PinnedSheet(session, it, users, viewModel::hidePinned) }
}

@Composable
private fun NoticeBar(text: String, onClose: () -> Unit) {
    Snackbar(
        action = {
            // A cor padrão do botão quase some no fundo da faixa; a do texto dela é legível nos dois temas.
            TextButton(
                onClick = onClose,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.inverseOnSurface),
            ) { Text(stringResource(Res.string.action_close), fontWeight = FontWeight.SemiBold) }
        },
    ) { Text(text) }
}

/** Ao editar, as menções voltam a aparecer como `@nome`; o envio as recodifica se o nome for escolhido de novo. */
private fun renderForEditing(content: String, users: Map<String, User>): String =
    renderMentions(content) { users[it]?.username }

private fun closeInTime(older: Message, newer: Message): Boolean {
    val a = older.createdAt ?: return false
    val b = newer.createdAt ?: return false
    return b - a < 5.minutes
}

@Composable
private fun presenceLabel(status: String?): String = stringResource(
    when (status) {
        "online" -> Res.string.presence_online
        "away" -> Res.string.presence_away
        "busy" -> Res.string.presence_busy
        else -> Res.string.presence_offline
    },
)

@Composable
private fun DaySeparator(message: Message) {
    val instant = message.createdAt ?: return
    val today = Clock.System.now()
    val label = when (instant.dayKey()) {
        today.dayKey() -> stringResource(Res.string.chat_today)
        (today - 1.days).dayKey() -> stringResource(Res.string.chat_yesterday)
        else -> instant.formatDate()
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun TypingLine(users: List<User>) {
    val text = when (users.size) {
        0 -> return
        // A frase de digitação personalizada do usuário substitui o texto padrão.
        1 -> users[0].typingPhrase?.takeIf { it.isNotBlank() }?.let { "${users[0].displayName} $it" }
            ?: stringResource(Res.string.chat_typing_one, users[0].displayName)
        else -> stringResource(Res.string.chat_typing_many)
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

/** Trecho `@pref` imediatamente antes do cursor, se houver. */
private fun mentionQuery(value: TextFieldValue): String? {
    if (!value.selection.collapsed) return null
    val before = value.text.substring(0, value.selection.start)
    val at = before.lastIndexOf('@')
    if (at < 0 || (at > 0 && !before[at - 1].isWhitespace())) return null
    val query = before.substring(at + 1)
    return query.takeIf { it.length <= 32 && it.none(Char::isWhitespace) }
}

@Composable
private fun Composer(
    session: AccountSession,
    state: ChatUiState,
    draft: TextFieldValue,
    users: Map<String, User>,
    placeholder: String,
    onDraftChange: (TextFieldValue) -> Unit,
    onPickMention: (username: String, userId: String) -> Unit,
    onAddFiles: (List<OutgoingFile>) -> Unit,
    onRemoveFile: (OutgoingFile) -> Unit,
    onCancelContext: () -> Unit,
    onSubmit: () -> Unit,
) {
    val pickFiles = LocalPlatformUi.current.rememberFilePicker(onAddFiles)
    val query = mentionQuery(draft)
    val suggestions = remember(query, users) {
        if (query == null) {
            emptyList()
        } else {
            val q = query.lowercase()
            users.values.asSequence()
                .filter { it.id != session.userId && !it.banned }
                .filter { it.username.lowercase().startsWith(q) || it.displayName.lowercase().startsWith(q) }
                .sortedBy { it.username.lowercase() }
                .take(5)
                .toList()
        }
    }

    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding()) {
            suggestions.forEach { user ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            val cursor = draft.selection.start
                            val at = draft.text.substring(0, cursor).lastIndexOf('@')
                            val text = draft.text.substring(0, at) + "@${user.username} " + draft.text.substring(cursor)
                            onPickMention(user.username, user.id)
                            onDraftChange(TextFieldValue(text, TextRange(at + user.username.length + 2)))
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    UserAvatar(session, user, size = 28.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(user.displayName, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(8.dp))
                    Text("@${user.username}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            val context = state.editing ?: state.replyTo
            if (context != null) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val label = if (state.editing != null) {
                        stringResource(Res.string.chat_editing)
                    } else {
                        val name = context.externalAuthor?.name ?: context.authorId?.let { users[it]?.displayName }
                            ?: stringResource(Res.string.chat_unknown_user)
                        stringResource(Res.string.chat_replying_to, name)
                    }
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = onCancelContext) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.action_cancel), modifier = Modifier.size(18.dp))
                    }
                }
            }

            if (state.files.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.files.forEach { file ->
                        Row(
                            Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(start = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.width(140.dp)) {
                                Text(file.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                                Text(
                                    formatBytes(file.size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { onRemoveFile(file) }, enabled = !state.sending) {
                                Icon(Icons.Filled.Close, stringResource(Res.string.chat_remove_attachment), Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = pickFiles, enabled = !state.sending && state.editing == null) {
                    Icon(Icons.Filled.AttachFile, contentDescription = stringResource(Res.string.chat_attach))
                }
                TextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    enabled = !state.sending,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    shape = RoundedCornerShape(22.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    modifier = Modifier.weight(1f),
                )
                val canSend = !state.sending && (draft.text.isNotBlank() || state.files.isNotEmpty())
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (state.sending) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = onSubmit, enabled = canSend) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = stringResource(Res.string.chat_send),
                                tint = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionsSheet(
    session: AccountSession,
    message: Message,
    mine: Boolean,
    canPin: Boolean,
    pinned: Boolean,
    onDismiss: () -> Unit,
    onReact: (emojiId: String?, unicode: String?) -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var customEmojis by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) { customEmojis = runCatching { session.emojis.all().keys.take(24).toList() }.getOrDefault(emptyList()) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                quickReactions.forEach { emoji ->
                    ReactionChoice(onClick = { onReact(null, emoji); onDismiss() }) { ReactionGlyph(session, null, emoji) }
                }
                customEmojis.forEach { id ->
                    ReactionChoice(onClick = { onReact(id, null); onDismiss() }) { ReactionGlyph(session, id, null) }
                }
            }
            Spacer(Modifier.height(8.dp))
            SheetAction(Icons.AutoMirrored.Filled.Reply, stringResource(Res.string.msg_reply)) { onReply(); onDismiss() }
            if (message.content.isNotBlank()) {
                SheetAction(Icons.Filled.ContentCopy, stringResource(Res.string.msg_copy)) {
                    clipboard.setText(AnnotatedString(message.content))
                    onDismiss()
                }
            }
            if (canPin) {
                SheetAction(Icons.Filled.PushPin, stringResource(if (pinned) Res.string.msg_unpin else Res.string.msg_pin)) {
                    onTogglePin()
                    onDismiss()
                }
            }
            if (mine) {
                SheetAction(Icons.Filled.Edit, stringResource(Res.string.msg_edit)) { onEdit(); onDismiss() }
                SheetAction(Icons.Filled.Delete, stringResource(Res.string.msg_delete), destructive = true) { onDelete(); onDismiss() }
            }
        }
    }
}

@Composable
private fun ReactionChoice(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, destructive: Boolean = false, onClick: () -> Unit) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color)
        Spacer(Modifier.width(18.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinnedSheet(session: AccountSession, pinned: List<Message>, users: Map<String, User>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(stringResource(Res.string.chat_pinned), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            if (pinned.isEmpty()) {
                Text(
                    stringResource(Res.string.chat_pinned_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(pinned, key = { it.id }) { message ->
                    val author = message.authorId?.let { users[it] }
                    Row(Modifier.padding(vertical = 8.dp)) {
                        UserAvatar(session, author, size = 32.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                message.externalAuthor?.name ?: author?.displayName ?: stringResource(Res.string.chat_unknown_user),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                renderMentions(message.content) { users[it]?.displayName }
                                    .ifBlank { message.attachments.firstOrNull()?.fileName.orEmpty() },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Imagem original em tela cheia, com zoom por pinça. */
@Composable
private fun ImageViewer(session: AccountSession, attachment: AttachmentDto, onDismiss: () -> Unit) {
    val image by rememberMediaImage(session, "att/${attachment.id}") { session.api.attachment(attachment.id) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            val bitmap = image
            if (bitmap == null) {
                CircularProgressIndicator(color = Color.White)
            } else {
                Image(
                    bitmap,
                    contentDescription = attachment.fileName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                offset = if (scale == 1f) Offset.Zero else offset + pan
                            }
                        }
                        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.action_close), tint = Color.White)
            }
        }
    }
}
