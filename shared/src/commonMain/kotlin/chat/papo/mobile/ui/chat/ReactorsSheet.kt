package chat.papo.mobile.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.Message
import chat.papo.mobile.chat.ReactorList
import chat.papo.mobile.chat.ReactorsState
import chat.papo.mobile.chat.User
import chat.papo.mobile.chat.orderLike
import chat.papo.mobile.data.api.ReactionGroupDto
import chat.papo.mobile.data.api.ReactionSummaryDto
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.action_retry
import chat.papo.mobile.resources.chat_unknown_user
import chat.papo.mobile.resources.reactors_empty
import chat.papo.mobile.resources.reactors_load_more
import chat.papo.mobile.resources.reactors_title
import chat.papo.mobile.ui.components.UserAvatar
import chat.papo.mobile.ui.components.text
import chat.papo.mobile.ui.components.toUiError
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import org.jetbrains.compose.resources.stringResource

/**
 * Quem reagiu a [message], uma lista por emoji. As bolinhas vêm da própria mensagem, então a contagem acompanha os
 * eventos do servidor; quem reagiu é uma foto de quando a folha abriu, porque o servidor só avisa a contagem. Começa em
 * [first] (a bolinha em que se tocou). A leitura é paginada e só anda quando a pessoa pede: ao abrir e ao trocar de
 * emoji, até achar alguém com ele (ver [ReactorList.reveal]); depois, ao rolar até o fim ou no botão.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReactorsSheet(
    session: AccountSession,
    message: Message,
    users: Map<String, User>,
    first: ReactionSummaryDto,
    onDismiss: () -> Unit,
) {
    // O escopo da folha é o dono do pedido em voo: fechá-la o cancela.
    val scope = rememberCoroutineScope()
    val list = remember(session.accountId, message.channelId, message.id) {
        ReactorList(session.api, message.channelId, message.id, scope)
    }
    val state by list.state.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(first.emojiId to first.unicode) }
    val groups = remember(message.reactions, state.groups) { orderLike(message.reactions, state.groups) }
    // Se a bolinha escolhida deixou de existir (todos tiraram a reação), a primeira passa a valer.
    val current = groups.firstOrNull { it.emojiId to it.unicode == selected } ?: groups.firstOrNull()
    val currentKey = current?.let { it.emojiId to it.unicode }
    LaunchedEffect(currentKey) {
        current?.let { list.reveal(it.emojiId, it.unicode) }
    }

    // A lista chega depois de a folha abrir e a altura cresce: sem o meio-termo, a folha acompanha o conteúdo em vez de
    // ficar parada na posição que tinha quando só havia o título.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Text(
                stringResource(Res.string.reactors_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                groups.forEach { group ->
                    FilterChip(
                        selected = group.emojiId to group.unicode == currentKey,
                        onClick = { selected = group.emojiId to group.unicode },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ReactionGlyph(session, group.emojiId, group.unicode)
                                Spacer(Modifier.width(6.dp))
                                Text(group.count.toString())
                            }
                        },
                    )
                }
            }
            if (current == null) {
                Notice(stringResource(Res.string.reactors_empty))
            } else {
                // Uma lista por emoji: trocar de bolinha volta ao topo e recomeça a conta da rolagem.
                key(current.emojiId, current.unicode) {
                    ReactorUsers(session, current, users, state, onMore = list::loadMore)
                }
            }
        }
    }
}

@Composable
private fun ReactorUsers(
    session: AccountSession,
    group: ReactionGroupDto,
    users: Map<String, User>,
    state: ReactorsState,
    onMore: () -> Unit,
) {
    val listState = rememberLazyListState()
    // Rolar até o fim traz a página seguinte. Uma lista que cabe sem rolar nunca sai do topo e só tem o botão; e uma
    // leitura que falhou só recomeça pelo botão, nunca pela rolagem.
    val more by rememberUpdatedState(onMore)
    val scrollLoads by rememberUpdatedState(state.error == null)
    LaunchedEffect(listState) {
        snapshotFlow {
            val scrolled = listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
            scrollLoads && scrolled && !listState.canScrollForward
        }
            .distinctUntilChanged()
            .filter { it }
            .collect { more() }
    }
    val unknown = stringResource(Res.string.chat_unknown_user)
    val error = state.error
    LazyColumn(Modifier.heightIn(max = 420.dp), state = listState) {
        items(group.users, key = { it.id }) { reaction ->
            val user = users[reaction.userId]
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                UserAvatar(session, user, size = 32.dp, fallbackName = unknown)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(user?.displayName ?: unknown, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (user != null) {
                        Text(
                            "@${user.username}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        when {
            error != null -> item(key = "error") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(error.toUiError().text(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onMore) { Text(stringResource(Res.string.action_retry)) }
                }
            }
            state.loading -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
            state.hasMore -> item(key = "more") {
                TextButton(onClick = onMore, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(stringResource(Res.string.reactors_load_more))
                }
            }
            group.users.isEmpty() -> item(key = "empty") { Notice(stringResource(Res.string.reactors_empty)) }
        }
    }
}

@Composable
private fun Notice(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
    )
}
