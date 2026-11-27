package chat.papo.mobile.ui.notifications

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.papo.mobile.chat.NotificationLevel
import chat.papo.mobile.chat.NotificationPrefs
import chat.papo.mobile.chat.MuteDuration
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.action_back
import chat.papo.mobile.resources.notif_account_title
import chat.papo.mobile.resources.notif_level_note
import chat.papo.mobile.resources.notif_level_account_title
import chat.papo.mobile.resources.notif_local_only_note
import chat.papo.mobile.resources.notif_sync_note_account
import chat.papo.mobile.resources.notif_mute_note_server
import chat.papo.mobile.resources.notif_mute_server
import chat.papo.mobile.resources.notif_suppress_everyone
import chat.papo.mobile.resources.notif_suppress_everyone_note
import chat.papo.mobile.resources.notif_suppress_mentions
import chat.papo.mobile.resources.notif_suppress_mentions_note
import chat.papo.mobile.resources.notif_sync_title
import chat.papo.mobile.resources.notif_unmute
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/**
 * As notificações da conta inteira: o que vale em todo canal que não escolheu o próprio valor. Os canais mudam o que
 * quiserem na folha deles. Ligada pelas configurações da conta.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountNotificationsScreen(prefs: NotificationPrefs, onBack: () -> Unit) {
    val server by prefs.server.collectAsStateWithLifecycle()
    val edits = remember(prefs) { NotificationEdits(prefs) }
    val state = serverNotificationsState(server, Clock.System.now())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.notif_account_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            ScreenHeading(stringResource(Res.string.notif_level_account_title))
            RadioGroup {
                listOf(NotificationLevel.All, NotificationLevel.OnlyMentions, NotificationLevel.Nothing).forEach { level ->
                    RadioChoice(state.level == level, level.label()) { edits.setServerLevel(level) }
                }
            }
            NoteText(stringResource(Res.string.notif_level_note))

            Spacer(Modifier.height(12.dp))
            Column(Modifier.padding(horizontal = ContentPadding)) {
                SwitchRow(
                    title = stringResource(Res.string.notif_suppress_everyone),
                    body = stringResource(Res.string.notif_suppress_everyone_note),
                    checked = state.suppressEveryone,
                    onChange = edits::setServerSuppressEveryone,
                )
                SwitchRow(
                    title = stringResource(Res.string.notif_suppress_mentions),
                    body = stringResource(Res.string.notif_suppress_mentions_note),
                    checked = state.suppressMentions,
                    onChange = edits::setServerSuppressMentions,
                )
            }

            Spacer(Modifier.height(12.dp))
            ScreenHeading(stringResource(Res.string.notif_mute_server))
            muteStatusText(state.mute, inheritedFromServer = false)?.let { MuteStatusRow(it, state.canUnmute, edits::unmuteServer) }
            MuteDuration.entries.forEach { duration -> ActionChoice(duration.label()) { edits.muteServer(duration) } }
            NoteText(stringResource(Res.string.notif_mute_note_server))

            Spacer(Modifier.height(12.dp))
            Column(Modifier.padding(horizontal = ContentPadding)) {
                SwitchRow(
                    title = stringResource(Res.string.notif_sync_title),
                    body = null,
                    checked = state.sync,
                    onChange = edits::setServerSync,
                )
            }
            NoteText(stringResource(Res.string.notif_sync_note_account))
            NoteText(stringResource(Res.string.notif_local_only_note))
        }
    }
}

@Composable
private fun ScreenHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = ContentPadding, vertical = 8.dp).semantics { heading() },
    )
}

/**
 * O menu de silêncio do servidor: os tempos e, se ele está silenciado, o estado com o "Reativar". Para abrir de um toque
 * longo no avatar da conta no trilho; quem o usa põe o menu junto do avatar (num `Box`) e controla [expanded].
 */
@Composable
fun ServerMuteMenu(prefs: NotificationPrefs, expanded: Boolean, onDismiss: () -> Unit) {
    val server by prefs.server.collectAsStateWithLifecycle()
    val edits = remember(prefs) { NotificationEdits(prefs) }
    val state = serverNotificationsState(server, Clock.System.now())

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Text(
            stringResource(Res.string.notif_mute_server),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).semantics { heading() },
        )
        muteStatusText(state.mute, inheritedFromServer = false)?.let { status ->
            DropdownMenuItem(text = { Text(status) }, enabled = false, onClick = {})
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.notif_unmute), color = MaterialTheme.colorScheme.primary) },
                onClick = {
                    edits.unmuteServer()
                    onDismiss()
                },
            )
        }
        MuteDuration.entries.forEach { duration ->
            DropdownMenuItem(
                text = { Text(duration.label()) },
                onClick = {
                    edits.muteServer(duration)
                    onDismiss()
                },
            )
        }
    }
}
