package chat.papo.mobile.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.AttachmentFilter
import chat.papo.mobile.chat.Channel
import chat.papo.mobile.chat.ChannelType
import chat.papo.mobile.chat.Dm
import chat.papo.mobile.chat.SearchFilters
import chat.papo.mobile.chat.SearchHit
import chat.papo.mobile.chat.SearchOrder
import chat.papo.mobile.chat.User
import chat.papo.mobile.chat.readableChannels
import chat.papo.mobile.chat.renderMentions
import chat.papo.mobile.chat.searchByName
import chat.papo.mobile.core.formatDate
import chat.papo.mobile.core.formatDateTime
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.action_apply
import chat.papo.mobile.resources.action_back
import chat.papo.mobile.resources.action_cancel
import chat.papo.mobile.resources.action_retry
import chat.papo.mobile.resources.chat_unknown_user
import chat.papo.mobile.resources.search_attachments_any
import chat.papo.mobile.resources.search_attachments_with
import chat.papo.mobile.resources.search_attachments_without
import chat.papo.mobile.resources.search_clear_filter
import chat.papo.mobile.resources.search_clear_filters
import chat.papo.mobile.resources.search_clear_text
import chat.papo.mobile.resources.search_date_end
import chat.papo.mobile.resources.search_date_from
import chat.papo.mobile.resources.search_date_help
import chat.papo.mobile.resources.search_date_range
import chat.papo.mobile.resources.search_date_start
import chat.papo.mobile.resources.search_date_title
import chat.papo.mobile.resources.search_date_until
import chat.papo.mobile.resources.search_dm_note
import chat.papo.mobile.resources.search_dm_with
import chat.papo.mobile.resources.search_empty
import chat.papo.mobile.resources.search_end
import chat.papo.mobile.resources.search_filter_attachments
import chat.papo.mobile.resources.search_filter_author
import chat.papo.mobile.resources.search_filter_author_value
import chat.papo.mobile.resources.search_filter_conversation
import chat.papo.mobile.resources.search_filter_date
import chat.papo.mobile.resources.search_filter_link
import chat.papo.mobile.resources.search_filter_mentions
import chat.papo.mobile.resources.search_filter_mentions_value
import chat.papo.mobile.resources.search_idle
import chat.papo.mobile.resources.search_load_more
import chat.papo.mobile.resources.search_no_text
import chat.papo.mobile.resources.search_order_oldest
import chat.papo.mobile.resources.search_pick_author
import chat.papo.mobile.resources.search_pick_conversation
import chat.papo.mobile.resources.search_pick_conversation_search
import chat.papo.mobile.resources.search_pick_mention
import chat.papo.mobile.resources.search_recent_filters
import chat.papo.mobile.resources.search_submit
import chat.papo.mobile.resources.search_title
import chat.papo.mobile.resources.search_truncated
import chat.papo.mobile.ui.chat.UserPickerDialog
import chat.papo.mobile.ui.components.AppBadge
import chat.papo.mobile.ui.components.AuthorAvatar
import chat.papo.mobile.ui.components.UserAvatar
import chat.papo.mobile.ui.components.text
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/** Nomes que a busca mostra, tirados do que a conta já tem guardado; [channels] são só os que a conta pode ler. */
private class Directory(val users: Map<String, User>, channels: List<Channel>, dms: List<Dm>) {
    val channels = channels.filter { it.type != ChannelType.Category }
    val dms = dms
    private val channelById = channels.associateBy { it.id }
    private val dmById = dms.associateBy { it.id }

    fun channel(id: String): Channel? = channelById[id]
    fun dmUser(id: String): User? = dmById[id]?.let { users[it.userId] }
    fun isDm(id: String): Boolean = id in dmById
}

private enum class Picker { Conversation, Author, Mention, Attachments, Date }

/** Busca de mensagens no servidor da conta, com filtros e resultados paginados. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(session: AccountSession, viewModel: SearchViewModel, onBack: () -> Unit, onOpen: (SearchHit) -> Unit) {
    val chat = session.chat
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recent by viewModel.recentFilters.collectAsStateWithLifecycle()
    val users by chat.users.collectAsStateWithLifecycle()
    val channels by chat.channels.collectAsStateWithLifecycle()
    val permissions by chat.access.collectAsStateWithLifecycle()
    val dms by chat.dms.collectAsStateWithLifecycle()
    val directory = remember(users, channels, permissions, dms) {
        Directory(users, readableChannels(channels, permissions, session.userId), dms)
    }

    var text by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var focused by rememberSaveable { mutableStateOf(false) }
    // O teclado abre só na primeira vez: ao voltar de um resultado, a lista continua à vista.
    LaunchedEffect(Unit) {
        if (!focused) {
            focused = true
            focus.requestFocus()
        }
    }

    val canSubmit = text.isNotBlank() || state.filters.hasCriteria
    val submit = {
        if (canSubmit) {
            keyboard?.hide()
            viewModel.submit(text)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text(stringResource(Res.string.search_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit() }),
                        trailingIcon = {
                            if (text.isNotEmpty()) {
                                IconButton(onClick = { text = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.search_clear_text))
                                }
                            }
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = submit, enabled = canSubmit) {
                        Icon(Icons.Filled.Search, contentDescription = stringResource(Res.string.search_submit))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            FilterBar(session, state.filters, directory) { viewModel.setFilters(it, text) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val error = state.error
                when {
                    state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    error != null && !state.searched -> Notice {
                        Text(error.text(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = viewModel::retry) { Text(stringResource(Res.string.action_retry)) }
                    }
                    !state.searched -> Notice {
                        Hint(stringResource(Res.string.search_idle))
                        if (state.filters.channelId == null) Hint(stringResource(Res.string.search_dm_note))
                        if (recent.isNotEmpty()) RecentFilters(recent, directory) { viewModel.setFilters(it, text) }
                    }
                    state.results.isEmpty() -> Notice {
                        Text(
                            stringResource(Res.string.search_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(bottom = 10.dp),
                        )
                        if (state.filters.channelId == null) Hint(stringResource(Res.string.search_dm_note))
                    }
                    else -> Results(session, state, directory, onOpen, viewModel::loadMore)
                }
            }
        }
    }
}

@Composable
private fun Notice(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) { content() }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

@Composable
private fun conversationLabel(directory: Directory, channelId: String, fallback: String? = null): String {
    directory.channel(channelId)?.let { return "#${it.name}" }
    if (directory.isDm(channelId)) {
        return stringResource(Res.string.search_dm_with, directory.dmUser(channelId)?.displayName ?: stringResource(Res.string.chat_unknown_user))
    }
    return fallback?.takeIf { it.isNotBlank() }?.let { "#$it" } ?: stringResource(Res.string.search_filter_conversation)
}

@Composable
private fun userName(directory: Directory, userId: String): String =
    directory.users[userId]?.displayName ?: stringResource(Res.string.chat_unknown_user)

private fun LocalDate.format(): String = atStartOfDayIn(TimeZone.UTC).formatDate(TimeZone.UTC)

@Composable
private fun dateLabel(filters: SearchFilters): String? {
    val start = filters.dateStart
    val end = filters.dateEnd
    return when {
        start != null && end != null && start == end -> start.format()
        start != null && end != null -> stringResource(Res.string.search_date_range, start.format(), end.format())
        start != null -> stringResource(Res.string.search_date_from, start.format())
        end != null -> stringResource(Res.string.search_date_until, end.format())
        else -> null
    }
}

@Composable
private fun attachmentsLabel(filter: AttachmentFilter): String = stringResource(
    when (filter) {
        AttachmentFilter.Any -> Res.string.search_attachments_any
        AttachmentFilter.With -> Res.string.search_attachments_with
        AttachmentFilter.Without -> Res.string.search_attachments_without
    },
)

/** Os filtros em uma linha, como `Autor: Bia · #geral · Tem link`. */
@Composable
private fun filterSummary(filters: SearchFilters, directory: Directory): String = listOfNotNull(
    filters.channelId?.let { conversationLabel(directory, it) },
    filters.authorId?.let { stringResource(Res.string.search_filter_author_value, userName(directory, it)) },
    filters.mentionId?.let { stringResource(Res.string.search_filter_mentions_value, userName(directory, it)) },
    if (filters.hasLink) stringResource(Res.string.search_filter_link) else null,
    if (filters.attachments != AttachmentFilter.Any) attachmentsLabel(filters.attachments) else null,
    dateLabel(filters),
    if (filters.order == SearchOrder.Oldest) stringResource(Res.string.search_order_oldest) else null,
).joinToString(" · ")

@Composable
private fun FilterBar(session: AccountSession, filters: SearchFilters, directory: Directory, onChange: (SearchFilters) -> Unit) {
    var picking by remember { mutableStateOf<Picker?>(null) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = filters.channelId != null,
            onClick = { picking = Picker.Conversation },
            label = { Text(filters.channelId?.let { conversationLabel(directory, it) } ?: stringResource(Res.string.search_filter_conversation)) },
        )
        FilterChip(
            selected = filters.authorId != null,
            onClick = { picking = Picker.Author },
            label = {
                Text(
                    filters.authorId?.let { stringResource(Res.string.search_filter_author_value, userName(directory, it)) }
                        ?: stringResource(Res.string.search_filter_author),
                )
            },
        )
        FilterChip(
            selected = filters.mentionId != null,
            onClick = { picking = Picker.Mention },
            label = {
                Text(
                    filters.mentionId?.let { stringResource(Res.string.search_filter_mentions_value, userName(directory, it)) }
                        ?: stringResource(Res.string.search_filter_mentions),
                )
            },
        )
        FilterChip(
            selected = filters.hasLink,
            onClick = { onChange(filters.copy(hasLink = !filters.hasLink)) },
            label = { Text(stringResource(Res.string.search_filter_link)) },
        )
        Box {
            FilterChip(
                selected = filters.attachments != AttachmentFilter.Any,
                onClick = { picking = Picker.Attachments },
                label = {
                    Text(
                        if (filters.attachments == AttachmentFilter.Any) {
                            stringResource(Res.string.search_filter_attachments)
                        } else {
                            attachmentsLabel(filters.attachments)
                        },
                    )
                },
            )
            DropdownMenu(expanded = picking == Picker.Attachments, onDismissRequest = { picking = null }) {
                AttachmentFilter.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(attachmentsLabel(option)) },
                        onClick = {
                            picking = null
                            onChange(filters.copy(attachments = option))
                        },
                    )
                }
            }
        }
        FilterChip(
            selected = filters.dateStart != null || filters.dateEnd != null,
            onClick = { picking = Picker.Date },
            label = { Text(dateLabel(filters) ?: stringResource(Res.string.search_filter_date)) },
        )
        FilterChip(
            selected = filters.order == SearchOrder.Oldest,
            onClick = {
                onChange(filters.copy(order = if (filters.order == SearchOrder.Oldest) SearchOrder.Newest else SearchOrder.Oldest))
            },
            label = { Text(stringResource(Res.string.search_order_oldest)) },
        )
        if (filters != SearchFilters()) {
            TextButton(onClick = { onChange(SearchFilters()) }) { Text(stringResource(Res.string.search_clear_filters)) }
        }
    }

    val everyone = remember(directory) { directory.users.values.sortedBy { it.displayName.lowercase() } }
    when (picking) {
        Picker.Conversation -> ConversationPickerDialog(
            session = session,
            directory = directory,
            onDismiss = { picking = null },
            onPick = {
                picking = null
                onChange(filters.copy(channelId = it))
            },
            onClear = filters.channelId?.let {
                {
                    picking = null
                    onChange(filters.copy(channelId = null))
                }
            },
        )
        Picker.Author -> UserPickerDialog(
            session = session,
            title = stringResource(Res.string.search_pick_author),
            users = everyone,
            onDismiss = { picking = null },
            onPick = {
                picking = null
                onChange(filters.copy(authorId = it.id))
            },
            onClear = filters.authorId?.let {
                {
                    picking = null
                    onChange(filters.copy(authorId = null))
                }
            },
        )
        Picker.Mention -> UserPickerDialog(
            session = session,
            title = stringResource(Res.string.search_pick_mention),
            users = everyone,
            onDismiss = { picking = null },
            onPick = {
                picking = null
                onChange(filters.copy(mentionId = it.id))
            },
            onClear = filters.mentionId?.let {
                {
                    picking = null
                    onChange(filters.copy(mentionId = null))
                }
            },
        )
        Picker.Date -> DateRangeDialog(
            start = filters.dateStart,
            end = filters.dateEnd,
            onDismiss = { picking = null },
            onApply = { start, end ->
                picking = null
                onChange(filters.copy(dateStart = start, dateEnd = end))
            },
        )
        Picker.Attachments, null -> Unit
    }
}

private class Conversation(val id: String, val name: String, val type: ChannelType?, val user: User?)

@Composable
private fun ConversationPickerDialog(
    session: AccountSession,
    directory: Directory,
    onDismiss: () -> Unit,
    onPick: (channelId: String) -> Unit,
    onClear: (() -> Unit)?,
) {
    var query by remember { mutableStateOf("") }
    val unknown = stringResource(Res.string.chat_unknown_user)
    val all = remember(directory, unknown) {
        directory.channels.map { Conversation(it.id, it.name, it.type, null) } +
            directory.dms.map { dm -> directory.users[dm.userId].let { Conversation(dm.id, it?.displayName ?: unknown, null, it) } }
    }
    val filtered = remember(all, query) { searchByName(query, all) { listOfNotNull(it.name, it.user?.username) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.search_pick_conversation)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(Res.string.search_pick_conversation_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(filtered, key = { it.id }) { conversation ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(conversation.id) }.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                                when (conversation.type) {
                                    null -> UserAvatar(session, conversation.user, size = 36.dp, fallbackName = conversation.name)
                                    ChannelType.Voice -> Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
                                    else -> Icon(Icons.Filled.Tag, contentDescription = null)
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(conversation.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        textContentColor = MaterialTheme.colorScheme.onSurface,
    )
}

// O seletor do Material trabalha com a meia-noite UTC de cada dia.
private fun LocalDate.toPickerMillis(): Long = atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

private fun Long.toPickedDate(): LocalDate = Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.UTC).date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangeDialog(start: LocalDate?, end: LocalDate?, onDismiss: () -> Unit, onApply: (LocalDate?, LocalDate?) -> Unit) {
    val picker = rememberDateRangePickerState(
        initialSelectedStartDateMillis = start?.toPickerMillis(),
        initialSelectedEndDateMillis = end?.takeIf { start != null }?.toPickerMillis(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onApply(picker.selectedStartDateMillis?.toPickedDate(), picker.selectedEndDateMillis?.toPickedDate()) }) {
                Text(stringResource(Res.string.action_apply))
            }
        },
        dismissButton = {
            Row {
                if (start != null || end != null) {
                    TextButton(onClick = { onApply(null, null) }) { Text(stringResource(Res.string.search_clear_filter)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
            }
        },
    ) {
        // O conteúdo do diálogo é empilhado numa Box: a coluna põe a ajuda abaixo do calendário.
        Column {
            DateRangePicker(
                state = picker,
                title = {
                    Text(stringResource(Res.string.search_date_title), modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp))
                },
                // O cabeçalho padrão segue o idioma do aparelho; este usa os textos do app.
                headline = {
                    val first = picker.selectedStartDateMillis?.toPickedDate()?.format() ?: stringResource(Res.string.search_date_start)
                    val last = picker.selectedEndDateMillis?.toPickedDate()?.format() ?: stringResource(Res.string.search_date_end)
                    Text(
                        "$first – $last",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 24.dp, end = 12.dp, bottom = 12.dp),
                    )
                },
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(Res.string.search_date_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp),
            )
        }
    }
}

@Composable
private fun RecentFilters(recent: List<SearchFilters>, directory: Directory, onPick: (SearchFilters) -> Unit) {
    Spacer(Modifier.height(6.dp))
    Text(
        stringResource(Res.string.search_recent_filters),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    recent.forEach { filters ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(filters) }.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(filterSummary(filters, directory), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Results(session: AccountSession, state: SearchUiState, directory: Directory, onOpen: (SearchHit) -> Unit, onLoadMore: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(state.results, key = { it.id }) { hit -> ResultRow(session, hit, directory) { onOpen(hit) } }
        item(key = "footer") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                state.error?.let {
                    Text(it.text(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                }
                when {
                    state.loadingMore -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    state.hasMore -> OutlinedButton(onClick = onLoadMore) { Text(stringResource(Res.string.search_load_more)) }
                    else -> Text(
                        if (state.truncated) {
                            stringResource(Res.string.search_truncated, state.results.size)
                        } else {
                            stringResource(Res.string.search_end)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultRow(session: AccountSession, hit: SearchHit, directory: Directory, onClick: () -> Unit) {
    val author = hit.authorId?.let { directory.users[it] }
    val authorName = hit.externalAuthor?.name?.takeIf { it.isNotBlank() }
        ?: author?.displayName
        ?: hit.authorUsername?.takeIf { it.isNotBlank() }
        ?: stringResource(Res.string.chat_unknown_user)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        AuthorAvatar(session, author, hit.webhookId, hit.externalAuthor?.avatarUrl, authorName, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    authorName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (hit.webhookId != null) {
                    Spacer(Modifier.width(6.dp))
                    AppBadge()
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    hit.createdAt?.formatDateTime().orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                conversationLabel(directory, hit.channelId, hit.channelName),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Conteúdo remoto: só texto, sem link clicável nem formatação que navegue.
            val content = renderMentions(hit.content) { directory.users[it]?.displayName }
            if (content.isBlank()) {
                Text(
                    stringResource(Res.string.search_no_text),
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(content, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
