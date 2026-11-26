package chat.papo.mobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.action_back
import chat.papo.mobile.resources.action_retry
import chat.papo.mobile.resources.blocked_empty
import chat.papo.mobile.resources.blocked_help
import chat.papo.mobile.resources.blocked_title
import chat.papo.mobile.resources.blocked_uncertain
import chat.papo.mobile.resources.blocked_unblock
import chat.papo.mobile.ui.components.UserAvatar
import chat.papo.mobile.ui.components.failureText
import chat.papo.mobile.ui.components.text
import org.jetbrains.compose.resources.stringResource

/** Quem a conta bloqueou, com o botão de desbloquear. Tudo o que vem do servidor aparece como texto simples. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedUsersScreen(session: AccountSession, viewModel: BlockedUsersViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val blocked by session.blocks.users.collectAsStateWithLifecycle()
    val users by session.chat.users.collectAsStateWithLifecycle()
    val failure = state.failure?.failureText(uncertain = stringResource(Res.string.blocked_uncertain))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.blocked_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = 20.dp)) {
            item(key = "help") {
                Text(
                    stringResource(Res.string.blocked_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            state.loadError?.let { error ->
                item(key = "load-error") {
                    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Text(error.text(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = viewModel::reload) { Text(stringResource(Res.string.action_retry)) }
                    }
                }
            }
            failure?.let { message ->
                item(key = "failure") {
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
            when {
                blocked.isEmpty() && state.loading -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    }
                }
                blocked.isEmpty() && state.loadError == null -> item(key = "empty") {
                    Text(
                        stringResource(Res.string.blocked_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            items(blocked, key = { it.id }) { entry ->
                val known = users[entry.id]
                val name = known?.displayName ?: entry.displayName
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    UserAvatar(session, known, size = 40.dp, fallbackName = name)
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val username = known?.username ?: entry.username
                        if (username.isNotBlank()) {
                            Text(
                                "@$username",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    TextButton(onClick = { viewModel.unblock(entry.id) }, enabled = state.unblocking == null) {
                        Text(stringResource(Res.string.blocked_unblock))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}
