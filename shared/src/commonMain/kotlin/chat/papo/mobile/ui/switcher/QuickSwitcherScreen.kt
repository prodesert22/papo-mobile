package chat.papo.mobile.ui.switcher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.papo.mobile.account.Account
import chat.papo.mobile.chat.SwitchEntry
import chat.papo.mobile.chat.SwitchKind
import chat.papo.mobile.chat.filterSwitchEntries
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.action_back
import chat.papo.mobile.resources.search_clear_text
import chat.papo.mobile.resources.switcher_empty
import chat.papo.mobile.resources.switcher_hint
import chat.papo.mobile.resources.switcher_kind_dm
import chat.papo.mobile.resources.switcher_kind_text
import chat.papo.mobile.resources.switcher_kind_voice
import chat.papo.mobile.resources.switcher_mention
import chat.papo.mobile.resources.switcher_unread
import chat.papo.mobile.resources.switcher_unread_count
import chat.papo.mobile.ui.components.accentColor
import chat.papo.mobile.ui.theme.LocalPapoColors
import org.jetbrains.compose.resources.stringResource

/**
 * Troca rápida de conversa: um campo de busca já focado e, abaixo, as conversas de todas as contas abertas, com as
 * menções e as não lidas primeiro. [accounts] só dá a cor e a contagem das contas; as conversas vêm do [viewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickSwitcherScreen(
    viewModel: QuickSwitcherViewModel,
    accounts: List<Account>,
    onBack: () -> Unit,
    onOpen: (SwitchEntry) -> Unit,
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(entries, query) { entries?.let { filterSwitchEntries(it, query) } }
    // O rótulo da conta só ajuda quando há mais de uma; com uma só seria ruído em cada linha.
    val showAccount = accounts.size > 1
    val accountColors = remember(accounts) { accounts.associate { it.id to it.accentColor() } }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(stringResource(Res.string.switcher_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        // Enter abre a primeira da lista, como o Ctrl+K da versão de terminal.
                        keyboardActions = KeyboardActions(onGo = { shown?.firstOrNull()?.let(onOpen) }),
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
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
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
            when {
                // Antes da primeira leitura não se sabe se há conversas: nada de "nenhuma" piscando.
                shown == null -> Unit
                shown.isEmpty() -> Text(
                    stringResource(Res.string.switcher_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.TopStart).padding(20.dp),
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.key }) { entry ->
                        EntryRow(entry, accountColor = accountColors[entry.accountId].takeIf { showAccount }, onClick = { onOpen(entry) })
                    }
                }
            }
        }
    }
}

/** [accountColor] só vem quando a linha deve dizer de que conta é. */
@Composable
private fun EntryRow(entry: SwitchEntry, accountColor: Color?, onClick: () -> Unit) {
    val emphasis = if (entry.unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when (entry.kind) {
                SwitchKind.Text -> Icons.Filled.Tag
                SwitchKind.Voice -> Icons.AutoMirrored.Filled.VolumeUp
                SwitchKind.Dm -> Icons.Filled.Person
            },
            contentDescription = stringResource(
                when (entry.kind) {
                    SwitchKind.Text -> Res.string.switcher_kind_text
                    SwitchKind.Voice -> Res.string.switcher_kind_voice
                    SwitchKind.Dm -> Res.string.switcher_kind_dm
                },
            ),
            tint = emphasis,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (entry.unread) FontWeight.SemiBold else FontWeight.Normal,
                color = emphasis,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (accountColor != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(accountColor, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        entry.accountLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Marker(entry)
    }
}

/** Selo no fim da linha: o número de não lidas de uma conversa direta, "@" numa menção em canal, ou só um ponto. */
@Composable
private fun Marker(entry: SwitchEntry) {
    val colors = LocalPapoColors.current
    when {
        entry.mention -> {
            val description = if (entry.unreadCount > 0) {
                stringResource(Res.string.switcher_unread_count, entry.unreadCount)
            } else {
                stringResource(Res.string.switcher_mention)
            }
            Box(
                Modifier
                    .heightIn(min = 22.dp)
                    .background(colors.accent, RoundedCornerShape(11.dp))
                    .padding(horizontal = 7.dp)
                    .clearAndSetSemantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (entry.unreadCount > 99) "99+" else if (entry.unreadCount > 0) entry.unreadCount.toString() else "@",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.onAccent,
                )
            }
        }
        entry.unread -> {
            val description = stringResource(Res.string.switcher_unread)
            Box(Modifier.size(9.dp).background(colors.accent, CircleShape).clearAndSetSemantics { contentDescription = description })
        }
    }
}
