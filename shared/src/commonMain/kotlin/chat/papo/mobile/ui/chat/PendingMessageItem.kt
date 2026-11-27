package chat.papo.mobile.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.Message
import chat.papo.mobile.chat.PendingMessage
import chat.papo.mobile.chat.PendingStatus
import chat.papo.mobile.chat.SendFailure
import chat.papo.mobile.chat.UnicodeEmojiCatalog
import chat.papo.mobile.chat.User
import chat.papo.mobile.chat.pendingContinuesGroup
import chat.papo.mobile.chat.renderMentions
import chat.papo.mobile.data.api.OutgoingFile
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.chat_unknown_user
import chat.papo.mobile.resources.outbox_cancel_send
import chat.papo.mobile.resources.outbox_copy
import chat.papo.mobile.resources.outbox_discard
import chat.papo.mobile.resources.outbox_failed_file_unavailable
import chat.papo.mobile.resources.outbox_failed_not_sent
import chat.papo.mobile.resources.outbox_failed_rate_limited
import chat.papo.mobile.resources.outbox_failed_rejected
import chat.papo.mobile.resources.outbox_failed_rejected_detail
import chat.papo.mobile.resources.outbox_failed_too_large
import chat.papo.mobile.resources.outbox_failed_unauthorized
import chat.papo.mobile.resources.outbox_queued
import chat.papo.mobile.resources.outbox_resend
import chat.papo.mobile.resources.outbox_resend_body
import chat.papo.mobile.resources.outbox_resend_title
import chat.papo.mobile.resources.outbox_sending
import chat.papo.mobile.resources.outbox_sending_progress
import chat.papo.mobile.resources.outbox_uncertain
import chat.papo.mobile.ui.components.UserAvatar
import chat.papo.mobile.ui.components.formatBytes
import chat.papo.mobile.ui.components.parseHexColor
import chat.papo.mobile.ui.settings.ConfirmDialog
import org.jetbrains.compose.resources.stringResource

/**
 * As mensagens que o usuário mandou nesta conversa e o servidor ainda não confirmou, no fim da lista.
 * Também é dono da folha de ações e da pergunta que antecede reenviar uma mensagem sem confirmação.
 */
@Composable
fun PendingMessages(
    session: AccountSession,
    pending: List<PendingMessage>,
    users: Map<String, User>,
    emojiIds: Map<String, String>,
    unicodeEmojis: UnicodeEmojiCatalog?,
    repliedTo: (String) -> Message?,
    /** A mensagem confirmada mais nova da conversa, logo acima da primeira em saída; nula sem nenhuma. */
    newest: Message?,
    onRetry: (localId: String, confirmedDuplicate: Boolean) -> Unit,
    onWithdraw: (localId: String) -> Unit,
) {
    var actionsFor by remember { mutableStateOf<String?>(null) }
    var confirmResend by remember { mutableStateOf<String?>(null) }
    val me = users[session.userId]

    Column {
        pending.forEachIndexed { index, message ->
            PendingMessageItem(
                session = session,
                pending = message,
                author = me,
                users = users,
                emojiIds = emojiIds,
                unicodeEmojis = unicodeEmojis,
                repliedTo = message.replyToId?.let(repliedTo),
                // A primeira continua o grupo da última mensagem confirmada quando ela é do mesmo autor, como ficará
                // depois de enviada; as seguintes são sempre a sequência da primeira.
                compact = index > 0 || pendingContinuesGroup(newest, message, session.userId),
                onOpenActions = { actionsFor = message.localId },
            )
        }
    }

    // A folha segue a mensagem: se ela foi confirmada ou descartada enquanto estava aberta, fecha.
    val selected = pending.firstOrNull { it.localId == actionsFor }
    if (selected != null) {
        PendingActionsSheet(
            pending = selected,
            users = users,
            onResend = {
                if (selected.status == PendingStatus.Uncertain) confirmResend = selected.localId else onRetry(selected.localId, false)
            },
            onDiscard = { onWithdraw(selected.localId) },
            onDismiss = { actionsFor = null },
        )
    }
    val toResend = pending.firstOrNull { it.localId == confirmResend }
    if (toResend != null) {
        ConfirmDialog(
            title = stringResource(Res.string.outbox_resend_title),
            body = stringResource(Res.string.outbox_resend_body),
            confirm = stringResource(Res.string.outbox_resend),
            onDismiss = { confirmResend = null },
            onConfirm = {
                confirmResend = null
                onRetry(toResend.localId, true)
            },
        )
    }
}

/**
 * Uma mensagem em saída, parecida com uma enviada: o texto como o servidor o mostrará, mais uma linha
 * com o estado do envio. Tocar abre a folha de ações. [compact] omite avatar e nome, como na sequência
 * de mensagens de um mesmo autor.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PendingMessageItem(
    session: AccountSession,
    pending: PendingMessage,
    author: User?,
    users: Map<String, User>,
    emojiIds: Map<String, String>,
    unicodeEmojis: UnicodeEmojiCatalog?,
    repliedTo: Message?,
    compact: Boolean,
    onOpenActions: () -> Unit,
) {
    val authorName = author?.displayName ?: stringResource(Res.string.chat_unknown_user)
    val settled = pending.status !is PendingStatus.Queued && pending.status !is PendingStatus.Sending

    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpenActions, onLongClick = onOpenActions)
            .padding(start = 12.dp, end = 12.dp, top = if (compact) 1.dp else 8.dp, bottom = 1.dp),
    ) {
        Box(Modifier.width(40.dp)) {
            if (!compact) UserAvatar(session, author, size = 40.dp, fallbackName = authorName)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (repliedTo != null) ReplyLine(repliedTo, users)
            if (!compact) {
                Text(
                    authorName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = parseHexColor(author?.roleColor) ?: MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Ainda não é uma mensagem do servidor: fica mais apagada até ser confirmada ou até falhar.
            Column(Modifier.alpha(if (settled) 1f else 0.6f)) {
                if (pending.content.isNotEmpty()) MessageBodyText(session, pending.content, users, emojiIds, unicodeEmojis)
                pending.files.forEach { file ->
                    Spacer(Modifier.height(6.dp))
                    FileChip(file)
                }
            }
            if (pending.status == PendingStatus.Sending && pending.files.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                val bar = Modifier.widthIn(max = 280.dp).fillMaxWidth()
                val progress = pending.progress
                // Sem progresso mesmo enviando arquivos: o tamanho de algum não é conhecido, e a barra não tem total.
                if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = bar) else LinearProgressIndicator(modifier = bar)
            }
            Spacer(Modifier.height(2.dp))
            StatusLine(pending)
        }
    }
}

@Composable
private fun StatusLine(pending: PendingMessage) {
    val failed = pending.status is PendingStatus.Failed || pending.status == PendingStatus.Uncertain
    val color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            failed -> Icon(Icons.Filled.ErrorOutline, contentDescription = null, modifier = Modifier.size(14.dp), tint = color)
            pending.status == PendingStatus.Queued -> Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.size(14.dp), tint = color)
            else -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = color)
        }
        Spacer(Modifier.width(6.dp))
        Text(statusText(pending), style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun statusText(pending: PendingMessage): String = when (val status = pending.status) {
    PendingStatus.Queued -> stringResource(Res.string.outbox_queued)
    PendingStatus.Sending -> pending.progress?.takeIf { pending.files.isNotEmpty() }
        ?.let { stringResource(Res.string.outbox_sending_progress, "${(it * 100).toInt()}%") }
        ?: stringResource(Res.string.outbox_sending)
    is PendingStatus.Failed -> when (status.reason) {
        SendFailure.RateLimited -> stringResource(Res.string.outbox_failed_rate_limited)
        SendFailure.Rejected -> status.detail?.takeIf { it.isNotBlank() }
            ?.let { stringResource(Res.string.outbox_failed_rejected_detail, it) }
            ?: stringResource(Res.string.outbox_failed_rejected)
        SendFailure.NotSent -> stringResource(Res.string.outbox_failed_not_sent)
        SendFailure.Unauthorized -> stringResource(Res.string.outbox_failed_unauthorized)
        SendFailure.TooLarge -> stringResource(Res.string.outbox_failed_too_large)
        SendFailure.FileUnavailable -> stringResource(Res.string.outbox_failed_file_unavailable, status.detail.orEmpty())
    }
    PendingStatus.Uncertain -> stringResource(Res.string.outbox_uncertain)
}

/** A mensagem que está sendo respondida, numa linha, para a que sai não parecer solta. */
@Composable
private fun ReplyLine(replied: Message, users: Map<String, User>) {
    val name = replied.externalAuthor?.name ?: replied.authorId?.let { users[it]?.displayName } ?: stringResource(Res.string.chat_unknown_user)
    val body = renderMentions(replied.content) { users[it]?.displayName }.ifBlank { replied.attachments.firstOrNull()?.fileName.orEmpty() }
    Row(Modifier.padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.AutoMirrored.Filled.Reply,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "$name: $body",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FileChip(file: OutgoingFile) {
    Row(
        Modifier
            .widthIn(max = 320.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            file.size?.let { Text(formatBytes(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Reenviar (só o que falhou ou não foi confirmado), Descartar (ou Cancelar envio, se está saindo) e Copiar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PendingActionsSheet(
    pending: PendingMessage,
    users: Map<String, User>,
    onResend: () -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val sending = pending.status == PendingStatus.Sending
    val resendable = pending.status is PendingStatus.Failed || pending.status == PendingStatus.Uncertain
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Text(
                statusText(pending),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            if (resendable) {
                PendingAction(Icons.Filled.Refresh, stringResource(Res.string.outbox_resend)) { onResend(); onDismiss() }
            }
            PendingAction(
                if (sending) Icons.Filled.Close else Icons.Filled.Delete,
                stringResource(if (sending) Res.string.outbox_cancel_send else Res.string.outbox_discard),
                destructive = true,
            ) { onDiscard(); onDismiss() }
            if (pending.content.isNotBlank()) {
                PendingAction(Icons.Filled.ContentCopy, stringResource(Res.string.outbox_copy)) {
                    // Sai como o usuário escreveu: a menção volta a ser @nome, em vez do formato do servidor.
                    clipboard.setText(AnnotatedString(renderMentions(pending.content) { users[it]?.username }))
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun PendingAction(icon: ImageVector, label: String, destructive: Boolean = false, onClick: () -> Unit) {
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
