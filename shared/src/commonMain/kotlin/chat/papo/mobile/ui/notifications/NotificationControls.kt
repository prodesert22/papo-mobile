package chat.papo.mobile.ui.notifications

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import chat.papo.mobile.chat.ActiveMute
import chat.papo.mobile.chat.MuteDuration
import chat.papo.mobile.chat.MuteSource
import chat.papo.mobile.chat.NotificationLevel
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.notif_level_all
import chat.papo.mobile.resources.notif_level_mentions
import chat.papo.mobile.resources.notif_level_nothing
import chat.papo.mobile.resources.notif_mute_15m
import chat.papo.mobile.resources.notif_mute_1h
import chat.papo.mobile.resources.notif_mute_24h
import chat.papo.mobile.resources.notif_mute_3h
import chat.papo.mobile.resources.notif_mute_8h
import chat.papo.mobile.resources.notif_mute_forever
import chat.papo.mobile.resources.notif_muted_by_server
import chat.papo.mobile.resources.notif_muted_forever
import chat.papo.mobile.resources.notif_muted_until
import chat.papo.mobile.resources.notif_section_collapsed
import chat.papo.mobile.resources.notif_section_expanded
import chat.papo.mobile.resources.notif_sync_note
import chat.papo.mobile.resources.notif_unmute
import chat.papo.mobile.resources.notif_use_server_default
import chat.papo.mobile.resources.notif_value_off
import chat.papo.mobile.resources.notif_value_on
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/** Os tamanhos e o recuo das linhas, iguais nas folhas, na tela da conta e no menu. */
private val RowMinHeight = 48.dp
internal val ContentPadding = 24.dp

@Composable
internal fun NotificationLevel.label(): String = stringResource(
    when (this) {
        NotificationLevel.All -> Res.string.notif_level_all
        NotificationLevel.OnlyMentions -> Res.string.notif_level_mentions
        NotificationLevel.Nothing -> Res.string.notif_level_nothing
    },
)

@Composable
internal fun MuteDuration.label(): String = stringResource(
    when (this) {
        MuteDuration.Minutes15 -> Res.string.notif_mute_15m
        MuteDuration.Hour1 -> Res.string.notif_mute_1h
        MuteDuration.Hours3 -> Res.string.notif_mute_3h
        MuteDuration.Hours8 -> Res.string.notif_mute_8h
        MuteDuration.Hours24 -> Res.string.notif_mute_24h
        MuteDuration.UntilReenabled -> Res.string.notif_mute_forever
    },
)

@Composable
internal fun onOffLabel(on: Boolean): String = stringResource(if (on) Res.string.notif_value_on else Res.string.notif_value_off)

/** "Usar padrão do servidor (valor)": a opção que segue o servidor, dizendo o que ele tem agora. */
@Composable
internal fun serverDefaultLabel(value: String): String = stringResource(Res.string.notif_use_server_default, value)

/**
 * O estado do silêncio em texto, ou null se não há. Um silêncio que vem do servidor, visto de um canal, é só
 * "Silenciado pelo servidor"; o fim dele se vê na tela do servidor.
 */
@Composable
internal fun muteStatusText(mute: ActiveMute?, inheritedFromServer: Boolean): String? = when {
    mute == null -> null
    inheritedFromServer && mute.source == MuteSource.Server -> stringResource(Res.string.notif_muted_by_server)
    mute.until == null -> stringResource(Res.string.notif_muted_forever)
    else -> stringResource(Res.string.notif_muted_until, mute.until.muteEndText(Clock.System.now()))
}

/** Linha que abre e fecha uma seção (o "▸"). [summary] mostra a escolha de agora com a seção fechada. */
@Composable
internal fun SectionHeader(title: String, summary: String?, expanded: Boolean, onToggle: () -> Unit) {
    val state = stringResource(if (expanded) Res.string.notif_section_expanded else Res.string.notif_section_collapsed)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = state }
            .padding(horizontal = ContentPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) {
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Icon(
            if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** As opções de uma escolha única, lidas pelo leitor de tela como um grupo de botões de rádio. */
@Composable
internal fun RadioGroup(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup()) { content() }
}

@Composable
internal fun RadioChoice(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = ContentPadding, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

/** Uma ação dentro de uma seção (um tempo de silêncio), sem marca de escolha. */
@Composable
internal fun ActionChoice(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = ContentPadding + 40.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun NoteText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(horizontal = ContentPadding, vertical = 8.dp),
    )
}

/** O aviso de que o servidor ainda não guarda estas opções; só aparece enquanto isso for verdade. */
@Composable
internal fun SyncNote(syncAvailable: Boolean) {
    if (!syncAvailable) NoteText(stringResource(Res.string.notif_sync_note))
}

/** O estado do silêncio, com o "Reativar" quando quem silenciou foi a própria conversa (ou o servidor, na tela dele). */
@Composable
internal fun MuteStatusRow(status: String, canUnmute: Boolean, onUnmute: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = ContentPadding, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(status, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (canUnmute) TextButton(onClick = onUnmute) { Text(stringResource(Res.string.notif_unmute)) }
    }
}

/** Uma opção ligada ou desligada, com o texto de apoio. A linha toda é o alvo do toque. */
@Composable
internal fun SwitchRow(title: String, body: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (body != null) {
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}
