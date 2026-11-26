package chat.papo.mobile.ui.notifications

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.papo.mobile.chat.Channel
import chat.papo.mobile.chat.ChannelNotificationSettings
import chat.papo.mobile.chat.DmNotificationSettings
import chat.papo.mobile.chat.MuteDuration
import chat.papo.mobile.chat.NotificationLevel
import chat.papo.mobile.chat.NotificationPrefs
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.notif_allow
import chat.papo.mobile.resources.notif_channel_title
import chat.papo.mobile.resources.notif_dm_title
import chat.papo.mobile.resources.notif_level_note
import chat.papo.mobile.resources.notif_level_title
import chat.papo.mobile.resources.notif_mute_channel
import chat.papo.mobile.resources.notif_mute_conversation
import chat.papo.mobile.resources.notif_mute_note_channel
import chat.papo.mobile.resources.notif_mute_note_dm
import chat.papo.mobile.resources.notif_open
import chat.papo.mobile.resources.notif_suppress
import chat.papo.mobile.resources.notif_suppress_everyone
import chat.papo.mobile.resources.notif_suppress_everyone_note
import chat.papo.mobile.resources.notif_suppress_mentions
import chat.papo.mobile.resources.notif_suppress_mentions_note
import chat.papo.mobile.resources.notif_sync_title
import chat.papo.mobile.resources.notif_value_off
import chat.papo.mobile.resources.notif_value_on
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/** As seções que abrem e fecham nas folhas; só uma fica aberta por vez. */
private enum class Section { Mute, Level, SuppressEveryone, SuppressMentions, Sync }

/**
 * A folha de notificações de um canal: silêncio, nível, supressão de `@everyone` e de menções, e sincronização.
 * Cada opção começa em "Usar padrão do servidor". Para abrir de um toque longo no canal ou do menu da conversa.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelNotificationSheet(prefs: NotificationPrefs, channel: Channel, onDismiss: () -> Unit) {
    val server by prefs.server.collectAsStateWithLifecycle()
    val channels by prefs.channels.collectAsStateWithLifecycle()
    val edits = remember(prefs) { NotificationEdits(prefs) }
    val state = channelNotificationsState(server, channels[channel.id] ?: ChannelNotificationSettings(), Clock.System.now(), prefs.syncAvailable)
    var open by rememberSaveable { mutableStateOf(-1) }
    fun toggle(section: Section) {
        open = if (open == section.ordinal) -1 else section.ordinal
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            SheetTitle(stringResource(Res.string.notif_channel_title, channel.name))

            MuteSection(
                title = stringResource(Res.string.notif_mute_channel),
                status = muteStatusText(state.mute, inheritedFromServer = true),
                canUnmute = state.canUnmute,
                note = stringResource(Res.string.notif_mute_note_channel),
                expanded = open == Section.Mute.ordinal,
                onToggle = { toggle(Section.Mute) },
                onMute = {
                    edits.muteChannel(channel.id, it)
                    open = -1
                },
                onUnmute = { edits.unmuteChannel(channel.id) },
            )

            val levelLabels = listOf(NotificationLevel.All, NotificationLevel.OnlyMentions, NotificationLevel.Nothing)
            val defaultLevel = serverDefaultLabel(state.serverLevel.label())
            SectionHeader(
                stringResource(Res.string.notif_level_title),
                summary = state.level?.label() ?: defaultLevel,
                expanded = open == Section.Level.ordinal,
                onToggle = { toggle(Section.Level) },
            )
            if (open == Section.Level.ordinal) {
                RadioGroup {
                    RadioChoice(state.level == null, defaultLevel) { edits.setChannelLevel(channel.id, null) }
                    levelLabels.forEach { level ->
                        RadioChoice(state.level == level, level.label()) { edits.setChannelLevel(channel.id, level) }
                    }
                }
                NoteText(stringResource(Res.string.notif_level_note))
            }

            SuppressSection(
                title = stringResource(Res.string.notif_suppress_everyone),
                value = state.suppressEveryone,
                serverValue = state.serverSuppressEveryone,
                note = stringResource(Res.string.notif_suppress_everyone_note),
                expanded = open == Section.SuppressEveryone.ordinal,
                onToggle = { toggle(Section.SuppressEveryone) },
                onSelect = { edits.setChannelSuppressEveryone(channel.id, it) },
            )
            SuppressSection(
                title = stringResource(Res.string.notif_suppress_mentions),
                value = state.suppressMentions,
                serverValue = state.serverSuppressMentions,
                note = stringResource(Res.string.notif_suppress_mentions_note),
                expanded = open == Section.SuppressMentions.ordinal,
                onToggle = { toggle(Section.SuppressMentions) },
                onSelect = { edits.setChannelSuppressMentions(channel.id, it) },
            )

            SyncSection(
                value = state.sync,
                serverValue = state.serverSync,
                syncAvailable = state.syncAvailable,
                expanded = open == Section.Sync.ordinal,
                onToggle = { toggle(Section.Sync) },
                onSelect = { edits.setChannelSync(channel.id, it) },
            )
        }
    }
}

/** A folha de notificações de uma conversa direta: só silêncio e sincronização. [name] é o nome da outra pessoa. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DmNotificationSheet(prefs: NotificationPrefs, dmId: String, name: String, onDismiss: () -> Unit) {
    val server by prefs.server.collectAsStateWithLifecycle()
    val dms by prefs.dms.collectAsStateWithLifecycle()
    val edits = remember(prefs) { NotificationEdits(prefs) }
    val state = dmNotificationsState(server, dms[dmId] ?: DmNotificationSettings(), Clock.System.now(), prefs.syncAvailable)
    var open by rememberSaveable { mutableStateOf(-1) }
    fun toggle(section: Section) {
        open = if (open == section.ordinal) -1 else section.ordinal
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            SheetTitle(if (name.isBlank()) stringResource(Res.string.notif_open) else stringResource(Res.string.notif_dm_title, name))

            MuteSection(
                title = stringResource(Res.string.notif_mute_conversation),
                status = muteStatusText(state.mute, inheritedFromServer = false),
                canUnmute = state.canUnmute,
                note = stringResource(Res.string.notif_mute_note_dm),
                expanded = open == Section.Mute.ordinal,
                onToggle = { toggle(Section.Mute) },
                onMute = {
                    edits.muteDm(dmId, it)
                    open = -1
                },
                onUnmute = { edits.unmuteDm(dmId) },
            )
            SyncSection(
                value = state.sync,
                serverValue = state.serverSync,
                syncAvailable = state.syncAvailable,
                expanded = open == Section.Sync.ordinal,
                onToggle = { toggle(Section.Sync) },
                onSelect = { edits.setDmSync(dmId, it) },
            )
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(horizontal = ContentPadding, vertical = 8.dp).semantics { heading() },
    )
}

/** "Silenciar ▸": o estado atual com o "Reativar" e, aberta, os tempos de silêncio. */
@Composable
private fun MuteSection(
    title: String,
    status: String?,
    canUnmute: Boolean,
    note: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    onMute: (MuteDuration) -> Unit,
    onUnmute: () -> Unit,
) {
    SectionHeader(title, summary = null, expanded = expanded, onToggle = onToggle)
    if (status != null) MuteStatusRow(status, canUnmute, onUnmute)
    if (expanded) {
        MuteDuration.entries.forEach { duration -> ActionChoice(duration.label()) { onMute(duration) } }
        NoteText(note)
    }
}

/** "Suprimir …": usar o padrão do servidor, suprimir ou permitir. */
@Composable
private fun SuppressSection(
    title: String,
    value: Boolean?,
    serverValue: Boolean,
    note: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (Boolean?) -> Unit,
) {
    val defaultLabel = serverDefaultLabel(onOffLabel(serverValue))
    val suppress = stringResource(Res.string.notif_suppress)
    val allow = stringResource(Res.string.notif_allow)
    SectionHeader(title, summary = triStateSummary(value, defaultLabel, suppress, allow), expanded = expanded, onToggle = onToggle)
    if (expanded) {
        TriStateChoices(value, defaultLabel, suppress, allow, onSelect)
        NoteText(note)
    }
}

/** "Sincronizar com o servidor": usar o padrão do servidor, ligado ou desligado, com o aviso enquanto o servidor não guarda. */
@Composable
private fun SyncSection(
    value: Boolean?,
    serverValue: Boolean,
    syncAvailable: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (Boolean?) -> Unit,
) {
    val defaultLabel = serverDefaultLabel(onOffLabel(serverValue))
    val on = stringResource(Res.string.notif_value_on)
    val off = stringResource(Res.string.notif_value_off)
    SectionHeader(stringResource(Res.string.notif_sync_title), summary = triStateSummary(value, defaultLabel, on, off), expanded = expanded, onToggle = onToggle)
    if (expanded) {
        TriStateChoices(value, defaultLabel, on, off, onSelect)
        SyncNote(syncAvailable)
    }
}

private fun triStateSummary(value: Boolean?, defaultLabel: String, trueLabel: String, falseLabel: String): String = when (value) {
    null -> defaultLabel
    true -> trueLabel
    false -> falseLabel
}

@Composable
private fun TriStateChoices(value: Boolean?, defaultLabel: String, trueLabel: String, falseLabel: String, onSelect: (Boolean?) -> Unit) {
    RadioGroup {
        RadioChoice(value == null, defaultLabel) { onSelect(null) }
        RadioChoice(value == true, trueLabel) { onSelect(true) }
        RadioChoice(value == false, falseLabel) { onSelect(false) }
    }
}
