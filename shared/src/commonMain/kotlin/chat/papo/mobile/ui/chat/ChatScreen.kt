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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.ChannelType
import chat.papo.mobile.chat.EmojiCompletion
import chat.papo.mobile.chat.EmojiPickerModel
import chat.papo.mobile.chat.EmojiRef
import chat.papo.mobile.chat.Message
import chat.papo.mobile.chat.Presence
import chat.papo.mobile.chat.ServerEmoji
import chat.papo.mobile.chat.ServerEmojis
import chat.papo.mobile.chat.UnicodeEmoji
import chat.papo.mobile.chat.UnicodeEmojiCatalog
import chat.papo.mobile.chat.UnicodeEmojis
import chat.papo.mobile.chat.User
import chat.papo.mobile.chat.authorsByRecency
import chat.papo.mobile.chat.continuesGroup
import chat.papo.mobile.chat.filterPinned
import chat.papo.mobile.chat.mentionQueryAt
import chat.papo.mobile.chat.renderMentions
import chat.papo.mobile.chat.suggestMentions
import chat.papo.mobile.core.dayKey
import chat.papo.mobile.core.formatDate
import chat.papo.mobile.data.api.AttachmentDto
import chat.papo.mobile.data.api.OutgoingFile
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.action_back
import chat.papo.mobile.resources.action_cancel
import chat.papo.mobile.resources.action_close
import chat.papo.mobile.resources.channels_join_call
import chat.papo.mobile.resources.chat_attach
import chat.papo.mobile.resources.chat_editing
import chat.papo.mobile.resources.chat_emoji
import chat.papo.mobile.resources.msg_react_more
import chat.papo.mobile.resources.chat_empty
import chat.papo.mobile.resources.chat_focus_missing
import chat.papo.mobile.resources.chat_go_to_latest
import chat.papo.mobile.resources.chat_history_start
import chat.papo.mobile.resources.chat_locating
import chat.papo.mobile.resources.chat_keyboard
import chat.papo.mobile.resources.chat_pinned
import chat.papo.mobile.resources.chat_pinned_empty
import chat.papo.mobile.resources.chat_pinned_filter
import chat.papo.mobile.resources.chat_pinned_no_match
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
import chat.papo.mobile.resources.chat_viewing_older
import chat.papo.mobile.resources.chat_yesterday
import chat.papo.mobile.resources.msg_copy
import chat.papo.mobile.resources.msg_delete
import chat.papo.mobile.resources.msg_delete_body
import chat.papo.mobile.resources.msg_delete_title
import chat.papo.mobile.resources.msg_edit
import chat.papo.mobile.resources.msg_pin
import chat.papo.mobile.resources.msg_reply
import chat.papo.mobile.resources.msg_select
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
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    session: AccountSession,
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    /** Entra na chamada do canal de voz; null onde não há chamada. Só é oferecido em canal de voz. */
    onJoinCall: (() -> Unit)?,
) {
    val chat = session.chat
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val users by chat.users.collectAsStateWithLifecycle()
    val channels by chat.channels.collectAsStateWithLifecycle()
    val dms by chat.dms.collectAsStateWithLifecycle()
    val presence by chat.presence.collectAsStateWithLifecycle()
    val typing by chat.typing.collectAsStateWithLifecycle()
    val pinnedIds by chat.pinned.collectAsStateWithLifecycle()
    val detachedChannels by chat.detached.collectAsStateWithLifecycle()
    // O trecho à vista é antigo e não chega à mensagem mais nova (aberto a partir de uma busca).
    val detached = viewModel.channelId in detachedChannels
    val serverEmojis by session.emojis.state.collectAsStateWithLifecycle()
    val unicodeEmojis by produceState<UnicodeEmojiCatalog?>(null) {
        value = try {
            UnicodeEmojis.get()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // sem o catálogo só não há tamanho grande para emoji Unicode
        }
    }
    val drawableEmojis = rememberDrawableEmojis()
    // Carrega os emojis do servidor uma vez por sessão (as páginas vêm espaçadas).
    LaunchedEffect(session) { session.emojis.start() }

    val channel = channels.firstOrNull { it.id == viewModel.channelId }
    val dmUser = dms.firstOrNull { it.id == viewModel.channelId }?.let { users[it.userId] }
    val title = channel?.name ?: dmUser?.displayName ?: ""

    val isVoice = channel?.type == ChannelType.Voice
    val draft = viewModel.draft
    // O rascunho vai para o banco quando a tela sai de cena: outra tela por cima, app em segundo plano ou fim da conversa.
    LifecycleResumeEffect(session) {
        onPauseOrDispose { session.drafts.flush() }
    }
    var actionsFor by remember { mutableStateOf<Message?>(null) }
    var reactingTo by remember { mutableStateOf<Message?>(null) }
    var selecting by remember { mutableStateOf<Message?>(null) }
    var columnPx by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf<Message?>(null) }
    var viewing by remember { mutableStateOf<AttachmentDto?>(null) }
    val listState = rememberLazyListState()
    val byId = remember(messages) { messages.associateBy { it.id } }
    // Quem falou há pouco no canal vem primeiro nas sugestões de menção.
    val recentAuthors = remember(messages) { authorsByRecency(messages) }
    // O que ainda está saindo vai num item só no índice 0 da lista invertida (o fim da conversa): `lead` é
    // quanto ele desloca os índices das mensagens quando a lista é lida por posição.
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val lead = if (pending.isEmpty()) 0 else 1
    // Ao mandar uma mensagem, a conversa desce até ela.
    var shownPending by remember { mutableIntStateOf(pending.size) }
    LaunchedEffect(pending.size) {
        if (pending.size > shownPending) listState.scrollToItem(0)
        shownPending = pending.size
    }

    // Carrega mais histórico quando a rolagem se aproxima da mensagem mais antiga.
    LaunchedEffect(listState, messages.size, lead) {
        snapshotFlow { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= messages.size + lead - 8 }
            .distinctUntilChanged()
            .collect { nearStart -> if (nearStart && messages.isNotEmpty()) viewModel.loadOlder() }
    }
    // O canal conta como lido quando a mensagem mais nova está na tela.
    val atBottom by remember(lead) { derivedStateOf { listState.firstVisibleItemIndex <= lead } }
    val newest = messages.firstOrNull()
    var previousNewest by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(newest?.id) {
        // Ao inserir um item, a lista mantém o antigo ancorado e o novo fica fora da tela.
        // Se o usuário estava no fim, acompanha a conversa. Mandar uma mensagem já desce a lista (ver `pending`):
        // a confirmação dela, segundos depois, não pode puxar de volta quem já subiu para ler.
        val shifted = messages.indexOfFirst { it.id == previousNewest }
        val wasAtBottom = previousNewest == null || (shifted >= 0 && listState.firstVisibleItemIndex <= shifted + lead)
        previousNewest = newest?.id
        // Num trecho antigo, a "mais nova" é só a última carregada: não há conversa a acompanhar.
        if (newest != null && !detached && wasAtBottom) listState.scrollToItem(0)
    }
    LaunchedEffect(newest?.id, atBottom, detached) {
        // O fim de um trecho antigo não é o fim da conversa: marcar ali faria a leitura voltar no tempo.
        if (newest != null && atBottom && !detached) viewModel.markRead(newest)
    }
    // Lendo um trecho antigo: perto do fim dele, carrega o que vem depois.
    val canLoadNewer = detached && state.focusId == null && !state.locating
    LaunchedEffect(listState, canLoadNewer, lead) {
        if (!canLoadNewer) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex <= 3 + lead }
            .distinctUntilChanged()
            .collect { nearEnd -> if (nearEnd) viewModel.loadNewer() }
    }
    // Voltou para as mensagens recentes: a lista troca de conteúdo logo depois do aviso,
    // então o fim da conversa fica fixado por um instante.
    var shownLatestTick by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(state.latestTick) {
        if (state.latestTick == shownLatestTick) return@LaunchedEffect
        shownLatestTick = state.latestTick
        withTimeoutOrNull(1500) {
            snapshotFlow { (viewModel.channelId in detachedChannels) to messages.firstOrNull()?.id }
                .collect { listState.scrollToItem(0) }
        }
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
            listState.scrollToItem(focusIndex + lead, -listState.layoutInfo.viewportSize.height / 3)
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
                            Icon(
                                if (isVoice) Icons.AutoMirrored.Filled.VolumeUp else Icons.Filled.Tag,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
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
                    if (isVoice && onJoinCall != null) {
                        IconButton(onClick = onJoinCall) {
                            Icon(Icons.Filled.Call, contentDescription = stringResource(Res.string.channels_join_call))
                        }
                    }
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
        // O espaço de baixo (teclado, gaveta de emoji ou barra de navegação) é do compositor.
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).onSizeChanged { columnPx = it.height }) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (messages.isEmpty() && pending.isEmpty() && state.loadedOnce) {
                    Text(
                        stringResource(Res.string.chat_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.BottomStart).padding(20.dp),
                    )
                }
                LazyColumn(Modifier.fillMaxSize(), state = listState, reverseLayout = true) {
                    if (pending.isNotEmpty()) {
                        item(key = "outbox") {
                            PendingMessages(
                                session = session,
                                pending = pending,
                                users = users,
                                emojiIds = serverEmojis.idByName,
                                unicodeEmojis = unicodeEmojis,
                                repliedTo = { byId[it] },
                                onRetry = viewModel::retry,
                                onWithdraw = viewModel::withdraw,
                            )
                        }
                    }
                    items(messages.size, key = { messages[it].id }) { index ->
                        val message = messages[index]
                        val older = messages.getOrNull(index + 1)
                        val newDay = older == null || older.createdAt?.dayKey() != message.createdAt?.dayKey()
                        val compact = older != null && continuesGroup(older, message)
                        Column {
                            if (newDay && (older != null || state.reachedStart)) DaySeparator(message)
                            MessageItem(
                                session = session,
                                message = message,
                                author = message.authorId?.let { users[it] },
                                users = users,
                                emojiIds = serverEmojis.idByName,
                                unicodeEmojis = unicodeEmojis,
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
                    if (state.targetMissing) {
                        NoticeBar(stringResource(Res.string.chat_focus_missing), viewModel::dismissTargetMissing)
                    }
                    state.error?.let { error -> NoticeBar(error.text(), viewModel::dismissError) }
                }
            }

            if (detached) OlderHistoryBar(busy = state.loadingNewer || state.returning, onLatest = viewModel::goToLatest)
            TypingLine(typing[viewModel.channelId].orEmpty().mapNotNull { users[it] })
            Composer(
                session = session,
                state = state,
                draft = draft,
                users = users,
                presence = presence,
                recentAuthors = recentAuthors,
                serverEmojis = serverEmojis,
                emojiCatalog = drawableEmojis,
                availableHeightPx = columnPx,
                placeholder = if (dmUser != null) {
                    stringResource(Res.string.chat_placeholder_dm, title)
                } else {
                    stringResource(Res.string.chat_placeholder, if (isVoice) title else "#$title")
                },
                onDraftChange = viewModel::onDraftChange,
                onPickMention = viewModel::pickMention,
                onAddFiles = viewModel::addFiles,
                onRemoveFile = viewModel::removeFile,
                onCancelContext = viewModel::cancelContext,
                onSubmit = viewModel::submit,
            )
        }
    }

    actionsFor?.let { message ->
        val isPinned = pinnedIds[viewModel.channelId]?.contains(message.id) == true
        MessageActionsSheet(
            session = session,
            message = message,
            users = users,
            mine = message.authorId == session.userId,
            canPin = channel != null,
            pinned = isPinned,
            onDismiss = { actionsFor = null },
            onReact = { emojiId, unicode -> viewModel.react(message, emojiId, unicode) },
            onMoreReactions = {
                actionsFor = null
                reactingTo = message
            },
            onReply = { viewModel.startReply(message) },
            onEdit = { viewModel.startEdit(message) },
            onDelete = { confirmDelete = message },
            onTogglePin = { viewModel.setPinned(message, !isPinned) },
            onSelectText = { selecting = message },
        )
    }
    selecting?.let { message ->
        SelectTextSheet(
            session = session,
            content = message.content,
            users = users,
            emojiIds = serverEmojis.idByName,
            unicodeEmojis = unicodeEmojis,
            onDismiss = { selecting = null },
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
    reactingTo?.let { message ->
        EmojiPickerSheet(
            session = session,
            onPick = { ref ->
                when (ref) {
                    is EmojiRef.Unicode -> viewModel.react(message, null, ref.char)
                    is EmojiRef.Custom -> viewModel.react(message, ref.id, null)
                }
                reactingTo = null
            },
            onDismiss = { reactingTo = null },
        )
    }
    viewing?.let { ImageViewer(session, it) { viewing = null } }
    state.pinnedList?.let { PinnedSheet(session, it, users, viewModel::hidePinned) }
}

/** Aviso de que a lista mostra um trecho antigo, com o caminho de volta para o fim da conversa. */
@Composable
private fun OlderHistoryBar(busy: Boolean, onLatest: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.chat_viewing_older),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            TextButton(onClick = onLatest, enabled = !busy) { Text(stringResource(Res.string.chat_go_to_latest)) }
        }
    }
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

@Composable
private fun Composer(
    session: AccountSession,
    state: ChatUiState,
    draft: TextFieldValue,
    users: Map<String, User>,
    presence: Map<String, Presence>,
    recentAuthors: List<String>,
    serverEmojis: ServerEmojis,
    emojiCatalog: UnicodeEmojiCatalog?,
    availableHeightPx: Int,
    placeholder: String,
    onDraftChange: (TextFieldValue) -> Unit,
    onPickMention: (username: String, userId: String) -> Unit,
    onAddFiles: (List<OutgoingFile>) -> Unit,
    onRemoveFile: (OutgoingFile) -> Unit,
    onCancelContext: () -> Unit,
    onSubmit: () -> Unit,
) {
    val pickFiles = LocalPlatformUi.current.rememberFilePicker(onAddFiles)
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val panelScope = rememberCoroutineScope()
    val panel = remember { EmojiPanelState(panelScope) }

    // Quanto do teclado está na tela acima da barra de navegação, e a altura em que ele parou da última vez.
    val imeInsets = WindowInsets.ime
    val navInsets = WindowInsets.navigationBars
    val imePx = imeInsets.getBottom(density)
    val navPx = navInsets.getBottom(density)
    val keyboardAboveNav = (imePx - navPx).coerceAtLeast(0)
    var keyboardPx by rememberSaveable { mutableIntStateOf(KeyboardMemory.lastPx) }
    LaunchedEffect(imeInsets, navInsets, density) {
        val minimum = with(density) { MIN_KEYBOARD.toPx() }
        snapshotFlow { (imeInsets.getBottom(density) - navInsets.getBottom(density)).coerceAtLeast(0) }
            .collectLatest { px ->
                delay(250) // só vale a altura em que o teclado parou, não a da animação
                if (px > minimum) {
                    keyboardPx = px
                    KeyboardMemory.lastPx = px
                }
            }
    }
    LaunchedEffect(imeInsets, density) {
        var previous = imeInsets.getBottom(density)
        snapshotFlow { imeInsets.getBottom(density) }.collect { now ->
            // O teclado começou a subir (tocou no campo): ele toma o lugar da gaveta.
            if (now > previous && panel.isOpen && !panel.searchFocused) panel.toKeyboard()
            // O teclado da busca fechou (voltar): a gaveta volta ao tamanho dela.
            if (now == 0 && previous > 0 && panel.searchFocused) focusManager.clearFocus()
            previous = now
        }
    }
    val collapsedPx = if (keyboardPx > 0) keyboardPx.toFloat() else with(density) { DEFAULT_PANEL.toPx() }
    LaunchedEffect(panel.mode, keyboardAboveNav) {
        if (panel.mode == EmojiPanelMode.ToKeyboard) {
            // Chegou na altura de sempre, ou não veio teclado nenhum (teclado físico, por exemplo).
            if (keyboardAboveNav >= collapsedPx - 2f) panel.keyboardArrived() else { delay(700); panel.keyboardArrived() }
        }
    }
    var headerPx by remember { mutableIntStateOf(0) }
    val maxPanelPx = (availableHeightPx - headerPx - max(imePx, navPx) - with(density) { 24.dp.toPx() })
        .coerceAtLeast(collapsedPx)
    val searchPanelPx = with(density) { SEARCH_PANEL.toPx() }
    SideEffect {
        panel.collapsed = collapsedPx
        panel.expandedExtra = maxPanelPx - collapsedPx
        panel.flingThreshold = with(density) { 600.dp.toPx() }
    }
    val panelVisiblePx = EmojiPanelMath.visible(
        panel.mode, collapsedPx, panel.extra, keyboardAboveNav.toFloat(), panel.searchFocused, searchPanelPx, maxPanelPx,
    )
    // O conteúdo fica com a altura de sempre durante a troca com o teclado e só é cortado.
    val panelContentPx = if (panel.searchFocused) panelVisiblePx else max(panelVisiblePx, collapsedPx)
    // Voltar fecha a gaveta antes de sair da conversa.
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = panel.mode != EmojiPanelMode.Hidden,
        onBackCompleted = { panel.hide() },
    )
    val toggleEmoji: () -> Unit = {
        if (panel.isOpen) {
            panel.toKeyboard()
            try {
                focus.requestFocus()
            } catch (e: IllegalStateException) {
                // campo fora da tela
            }
            keyboard?.show()
        } else {
            panel.open(keyboardVisible = keyboardAboveNav > 0)
            keyboard?.hide()
        }
    }
    val mention = if (draft.selection.collapsed) mentionQueryAt(draft.text, draft.selection.start) else null
    val emojiQuery = if (draft.selection.collapsed) EmojiCompletion.queryBefore(draft.text, draft.selection.start) else null
    val emojiSuggestions = remember(emojiQuery?.text, emojiCatalog, serverEmojis) {
        if (emojiQuery == null) emptyList() else emojiSuggestions(emojiQuery.text, emojiCatalog, serverEmojis)
    }
    // `:atalho:` completo vira o emoji ao digitar o segundo dois-pontos (nome de emoji do servidor fica como texto).
    val onChange: (TextFieldValue) -> Unit = { value ->
        val edit = if (emojiCatalog != null && value.composition == null && value.selection.collapsed) {
            EmojiCompletion.closeShortcode(draft.text, value.text, value.selection.start, emojiCatalog, serverEmojis.idByName.keys)
        } else {
            null
        }
        onDraftChange(if (edit != null) TextFieldValue(edit.text, TextRange(edit.cursor)) else value)
    }
    fun insertEmoji(ref: EmojiRef) {
        val text = when (ref) {
            is EmojiRef.Unicode -> ref.char
            is EmojiRef.Custom -> serverEmojis.byId[ref.id]?.let { ":${it.name}:" }
        } ?: return
        onDraftChange(insertAtCursor(draft, text))
    }
    val suggestions = remember(mention, users, presence, recentAuthors) {
        if (mention == null) {
            emptyList()
        } else {
            suggestMentions(mention.text, users.values, session.userId, recentAuthors, presence)
        }
    }
    // Com o teclado aberto sobra pouca conversa: a lista de sugestões nunca passa de 40% da altura da tela.
    val maxSuggestionsHeight = with(density) {
        minOf(SUGGESTION_ROW * MAX_SUGGESTION_ROWS, (availableHeightPx * 0.4f).toDp().coerceAtLeast(SUGGESTION_ROW * 2))
    }

    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column {
        Column(Modifier.onSizeChanged { headerPx = it.height }) {
            if (suggestions.isNotEmpty() && mention != null) {
                MentionSuggestions(session, suggestions, queryText = mention.text, maxHeight = maxSuggestionsHeight) { user ->
                    val text = draft.text.substring(0, mention.start) + "@${user.username} " + draft.text.substring(draft.selection.start)
                    onPickMention(user.username, user.id)
                    onDraftChange(TextFieldValue(text, TextRange(mention.start + user.username.length + 2)))
                }
            }

            if (emojiSuggestions.isNotEmpty() && emojiQuery != null) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    emojiSuggestions.forEach { suggestion ->
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable {
                                    session.recentEmojis.used(suggestion.ref)
                                    val edit = EmojiCompletion.replace(draft.text, draft.selection.start, emojiQuery, suggestion.insert)
                                    onDraftChange(TextFieldValue(edit.text, TextRange(edit.cursor)))
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            suggestion.unicode?.let { Text(it.char, fontSize = 22.sp) }
                            suggestion.server?.let { EmojiImage(session, it.id, Modifier.size(24.dp)) }
                            Spacer(Modifier.width(6.dp))
                            Text(suggestion.label, style = MaterialTheme.typography.labelLarge)
                        }
                    }
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
                            IconButton(onClick = { onRemoveFile(file) }) {
                                Icon(Icons.Filled.Close, stringResource(Res.string.chat_remove_attachment), Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = pickFiles, enabled = state.editing == null) {
                    Icon(Icons.Filled.AttachFile, contentDescription = stringResource(Res.string.chat_attach))
                }
                TextField(
                    value = draft,
                    onValueChange = onChange,
                    placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    enabled = !state.savingEdit,
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
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focus)
                        .onFocusChanged { if (it.isFocused && panel.isOpen) panel.toKeyboard() },
                )
                IconButton(onClick = toggleEmoji, enabled = !state.savingEdit) {
                    if (panel.isOpen) {
                        Icon(Icons.Filled.Keyboard, contentDescription = stringResource(Res.string.chat_keyboard))
                    } else {
                        Icon(Icons.Filled.EmojiEmotions, contentDescription = stringResource(Res.string.chat_emoji))
                    }
                }
                val canSend = !state.savingEdit && (draft.text.isNotBlank() || state.files.isNotEmpty())
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (state.savingEdit) {
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
            // A gaveta ocupa o lugar do teclado; a escolha entra no cursor e a gaveta fica aberta.
            if (panelVisiblePx > 0.5f) {
                EmojiPanel(session, panel, panelVisiblePx, panelContentPx, onPick = ::insertEmoji)
            }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.ime.union(WindowInsets.navigationBars)))
        }
    }
}

/**
 * Quem pode ser mencionado, do melhor casamento ao pior. A lista rola quando há mais do que cabe, e volta ao
 * primeiro resultado quando a consulta ([queryText]) muda.
 */
@Composable
private fun MentionSuggestions(
    session: AccountSession,
    users: List<User>,
    queryText: String,
    maxHeight: Dp,
    onPick: (User) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(queryText) { listState.scrollToItem(0) }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxHeight), state = listState) {
        items(users, key = { it.id }) { user ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = SUGGESTION_ROW)
                    .clickable { onPick(user) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UserAvatar(session, user, size = 28.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    user.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "@${user.username}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Altura de uma sugestão de menção: a de um alvo de toque. */
private val SUGGESTION_ROW = 48.dp

/** Linhas visíveis da lista de menções: seis e meia, para a meia linha de baixo mostrar que a lista rola. */
private const val MAX_SUGGESTION_ROWS = 6.5f

/** Última altura do teclado vista neste processo, para a gaveta já abrir certa em outra conversa. Só em memória. */
private object KeyboardMemory {
    var lastPx = 0
}

/** Altura da gaveta de emoji antes de o teclado ter aparecido alguma vez. */
private val DEFAULT_PANEL = 300.dp

/** Menos que isso não é teclado de verdade (barra de sugestões de um teclado físico, por exemplo). */
private val MIN_KEYBOARD = 120.dp

/** Altura da gaveta enquanto a busca dela usa o teclado: busca e duas fileiras de resultado. */
private val SEARCH_PANEL = 190.dp

/** Uma sugestão do `:atalho`: o que mostrar e o que vai para o texto. */
private class EmojiSuggestion(
    val label: String,
    val insert: String,
    val ref: EmojiRef,
    val unicode: UnicodeEmoji? = null,
    val server: ServerEmoji? = null,
)

/** Até 6 sugestões para `:texto`: primeiro os emojis do servidor que começam assim, depois os Unicode. */
private fun emojiSuggestions(text: String, catalog: UnicodeEmojiCatalog?, server: ServerEmojis): List<EmojiSuggestion> {
    val fromServer = server.list.filter { it.name.startsWith(text, ignoreCase = true) }.take(3).map {
        EmojiSuggestion(":${it.name}:", ":${it.name}:", EmojiRef.Custom(it.id), server = it)
    }
    val fromUnicode = catalog?.suggest(text, 6 - fromServer.size).orEmpty().map {
        EmojiSuggestion(":${it.shortcodes.firstOrNull() ?: it.name}:", it.char, EmojiRef.Unicode(it.char), unicode = it)
    }
    return fromServer + fromUnicode
}

/** Põe [text] no cursor (ou no lugar da seleção) e deixa o cursor depois dele. */
private fun insertAtCursor(value: TextFieldValue, text: String): TextFieldValue {
    val start = value.selection.min
    val updated = value.text.replaceRange(start, value.selection.max, text)
    return TextFieldValue(updated, TextRange(start + text.length))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionsSheet(
    session: AccountSession,
    message: Message,
    users: Map<String, User>,
    mine: Boolean,
    canPin: Boolean,
    pinned: Boolean,
    onDismiss: () -> Unit,
    onReact: (emojiId: String?, unicode: String?) -> Unit,
    onMoreReactions: () -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
    onSelectText: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val serverEmojis by session.emojis.state.collectAsStateWithLifecycle()
    val recents by session.recentEmojis.items.collectAsStateWithLifecycle()
    val quick = remember(recents, serverEmojis) { EmojiPickerModel.quickReactions(recents, serverEmojis) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                quick.forEach { ref ->
                    ReactionChoice(
                        onClick = {
                            session.recentEmojis.used(ref)
                            when (ref) {
                                is EmojiRef.Unicode -> onReact(null, ref.char)
                                is EmojiRef.Custom -> onReact(ref.id, null)
                            }
                            onDismiss()
                        },
                    ) {
                        when (ref) {
                            is EmojiRef.Unicode -> ReactionGlyph(session, null, ref.char)
                            is EmojiRef.Custom -> ReactionGlyph(session, ref.id, null)
                        }
                    }
                }
                ReactionChoice(onClick = onMoreReactions) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.msg_react_more))
                }
            }
            Spacer(Modifier.height(8.dp))
            SheetAction(Icons.AutoMirrored.Filled.Reply, stringResource(Res.string.msg_reply)) { onReply(); onDismiss() }
            if (message.content.isNotBlank()) {
                SheetAction(Icons.Filled.ContentCopy, stringResource(Res.string.msg_copy)) {
                    clipboard.setText(AnnotatedString(copyableText(message.content, users)))
                    onDismiss()
                }
                SheetAction(Icons.Filled.SelectAll, stringResource(Res.string.msg_select)) {
                    onSelectText()
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
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(pinned, users, query) { filterPinned(pinned, users, query) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(stringResource(Res.string.chat_pinned), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            if (pinned.isNotEmpty()) {
                // O filtro é local: nada vai ao servidor enquanto se digita.
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(Res.string.chat_pinned_filter)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
            }
            if (shown.isEmpty()) {
                Text(
                    stringResource(if (pinned.isEmpty()) Res.string.chat_pinned_empty else Res.string.chat_pinned_no_match),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(shown, key = { it.id }) { message ->
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
