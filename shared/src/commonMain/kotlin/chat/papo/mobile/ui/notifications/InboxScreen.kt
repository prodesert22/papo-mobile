package chat.papo.mobile.ui.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.NotificationItem
import chat.papo.mobile.chat.TriggerKind
import chat.papo.mobile.chat.classify
import chat.papo.mobile.chat.renderMentions
import chat.papo.mobile.core.formatDateTime
import chat.papo.mobile.core.parseInstant
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.action_back
import chat.papo.mobile.resources.action_close
import chat.papo.mobile.resources.action_retry
import chat.papo.mobile.resources.inbox_capped
import chat.papo.mobile.resources.inbox_conversation_unknown
import chat.papo.mobile.resources.inbox_empty
import chat.papo.mobile.resources.inbox_load_failed
import chat.papo.mobile.resources.inbox_load_more
import chat.papo.mobile.resources.inbox_mark_all
import chat.papo.mobile.resources.inbox_mark_all_limit
import chat.papo.mobile.resources.inbox_mark_uncertain
import chat.papo.mobile.resources.inbox_reason_dm
import chat.papo.mobile.resources.inbox_reason_everyone
import chat.papo.mobile.resources.inbox_reason_mention
import chat.papo.mobile.resources.inbox_reason_reply
import chat.papo.mobile.resources.inbox_row_unread
import chat.papo.mobile.resources.inbox_settings
import chat.papo.mobile.resources.inbox_title
import chat.papo.mobile.resources.inbox_unavailable_body
import chat.papo.mobile.resources.inbox_unavailable_title
import chat.papo.mobile.ui.chat.MessageTarget
import chat.papo.mobile.ui.components.AuthorAvatar
import chat.papo.mobile.ui.components.failureText
import chat.papo.mobile.ui.components.text
import chat.papo.mobile.ui.components.toUiError
import chat.papo.mobile.ui.theme.LocalPapoColors
import org.jetbrains.compose.resources.stringResource

/**
 * A caixa de notificações da conta: menções, respostas, `@everyone` e mensagens diretas, da mais nova à mais antiga.
 * Tocar numa linha abre a conversa na mensagem. Tudo o que vem do servidor aparece como texto simples (regra 6).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    session: AccountSession,
    viewModel: InboxViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpen: (channelId: String, target: MessageTarget) -> Unit,
) {
    val inbox by session.inbox.state.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val users by session.chat.users.collectAsStateWithLifecycle()
    val channels by session.chat.channels.collectAsStateWithLifecycle()
    val dms by session.chat.dms.collectAsStateWithLifecycle()
    val hasUnread = inbox.items.any { !it.read }
    val failure = inbox.error?.toUiError()?.text()
    val markFailure = state.markFailure?.failureText(uncertain = stringResource(Res.string.inbox_mark_uncertain))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.inbox_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::markAllRead, enabled = hasUnread && !state.markingAll) {
                        Icon(Icons.Filled.DoneAll, contentDescription = stringResource(Res.string.inbox_mark_all))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(Res.string.inbox_settings))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (failure != null && inbox.items.isNotEmpty()) {
                item(key = "error") { ErrorLine(stringResource(Res.string.inbox_load_failed) + "\n" + failure, viewModel::retry) }
            }
            markFailure?.let { message ->
                item(key = "mark-failure") {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            when {
                inbox.items.isEmpty() && !inbox.loaded && failure == null -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    }
                }
                inbox.items.isEmpty() && failure != null -> item(key = "error-empty") {
                    ErrorLine(stringResource(Res.string.inbox_load_failed) + "\n" + failure, viewModel::retry)
                }
                inbox.items.isEmpty() -> item(key = "empty") {
                    Text(
                        stringResource(Res.string.inbox_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            items(inbox.items, key = { it.id }) { item ->
                val dm = dms.firstOrNull { it.id == item.channelId }
                val author = item.authorId?.let { users[it] }
                val name = item.externalAuthor?.name?.takeIf { it.isNotBlank() } ?: author?.displayName ?: "?"
                val where = when {
                    dm != null -> users[dm.userId]?.displayName
                    else -> channels.firstOrNull { it.id == item.channelId }?.let { "#" + it.name }
                } ?: stringResource(Res.string.inbox_conversation_unknown)
                val reason = when (classify(item, session.userId, isDm = dm != null)) {
                    TriggerKind.Mention -> stringResource(Res.string.inbox_reason_mention)
                    TriggerKind.Reply -> stringResource(Res.string.inbox_reason_reply)
                    TriggerKind.Everyone -> stringResource(Res.string.inbox_reason_everyone)
                    TriggerKind.Dm -> stringResource(Res.string.inbox_reason_dm)
                }
                val preview = renderMentions(item.content) { users[it]?.displayName }
                InboxRow(
                    session = session,
                    item = item,
                    author = author,
                    name = name,
                    where = if (dm != null) reason else "$where · $reason",
                    preview = preview,
                    enabled = state.opening == null,
                    opening = state.opening == item.id,
                    onClick = { viewModel.open(item, onOpen) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (inbox.items.isNotEmpty()) {
                item(key = "footer") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (inbox.capped) {
                            Text(
                                stringResource(Res.string.inbox_capped),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (inbox.hasMore) {
                            TextButton(onClick = viewModel::loadMore, enabled = !inbox.loading) {
                                if (inbox.loading) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(stringResource(Res.string.inbox_load_more))
                            }
                            Text(
                                stringResource(Res.string.inbox_mark_all_limit),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (state.unavailable) {
        AlertDialog(
            onDismissRequest = viewModel::dismissUnavailable,
            title = { Text(stringResource(Res.string.inbox_unavailable_title)) },
            text = { Text(stringResource(Res.string.inbox_unavailable_body)) },
            confirmButton = { TextButton(onClick = viewModel::dismissUnavailable) { Text(stringResource(Res.string.action_close)) } },
        )
    }
    state.openError?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::dismissOpenError,
            text = { Text(error.text()) },
            confirmButton = { TextButton(onClick = viewModel::dismissOpenError) { Text(stringResource(Res.string.action_close)) } },
        )
    }
}

@Composable
private fun ErrorLine(text: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onRetry) { Text(stringResource(Res.string.action_retry)) }
    }
}

@Composable
private fun InboxRow(
    session: AccountSession,
    item: NotificationItem,
    author: chat.papo.mobile.chat.User?,
    name: String,
    where: String,
    preview: String,
    enabled: Boolean,
    opening: Boolean,
    onClick: () -> Unit,
) {
    val unreadLabel = stringResource(Res.string.inbox_row_unread)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { if (!item.read) contentDescription = "$name, $where$unreadLabel" },
        verticalAlignment = Alignment.Top,
    ) {
        AuthorAvatar(session, author, item.webhookId, item.externalAuthor?.avatarUrl, name, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (item.read) FontWeight.Normal else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    parseInstant(item.createdAt)?.formatDateTime().orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                where,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (preview.isNotBlank()) {
                Text(
                    preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (item.read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when {
                opening -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                !item.read -> Box(Modifier.size(9.dp).background(LocalPapoColors.current.accent, CircleShape))
            }
        }
    }
}
