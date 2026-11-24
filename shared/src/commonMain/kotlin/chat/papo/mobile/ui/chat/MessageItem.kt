package chat.papo.mobile.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.Markdown
import chat.papo.mobile.chat.Message
import chat.papo.mobile.chat.User
import chat.papo.mobile.chat.renderMentions
import chat.papo.mobile.core.formatTime
import chat.papo.mobile.data.api.AttachmentDto
import chat.papo.mobile.data.api.EmbedDto
import chat.papo.mobile.data.api.ReactionSummaryDto
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.chat_edited
import chat.papo.mobile.resources.chat_reply_missing
import chat.papo.mobile.resources.chat_sensitive
import chat.papo.mobile.resources.chat_unknown_user
import chat.papo.mobile.resources.msg_download
import chat.papo.mobile.ui.components.LocalPlatformUi
import chat.papo.mobile.ui.components.UserAvatar
import chat.papo.mobile.ui.components.formatBytes
import chat.papo.mobile.ui.components.parseHexColor
import chat.papo.mobile.ui.components.rememberMediaImage
import chat.papo.mobile.ui.theme.LocalPapoColors
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.io.encoding.Base64

/** Texto da mensagem com Markdown, menções destacadas e links clicáveis. */
fun messageText(content: String, nameOf: (String) -> String?, accent: Color, codeBackground: Color, muted: Color): AnnotatedString =
    buildAnnotatedString {
        for (span in Markdown.parse(content)) {
            val style = SpanStyle(
                fontWeight = if (span.bold || span.mentionUserId != null) FontWeight.SemiBold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
                textDecoration = when {
                    span.strike -> TextDecoration.LineThrough
                    span.link != null -> TextDecoration.Underline
                    else -> null
                },
                fontFamily = if (span.code) FontFamily.Monospace else null,
                background = if (span.code) codeBackground else Color.Unspecified,
                color = when {
                    span.link != null || span.mentionUserId != null -> accent
                    span.quote -> muted
                    else -> Color.Unspecified
                },
            )
            val text = when {
                span.mentionUserId != null -> "@" + (nameOf(span.mentionUserId) ?: "usuário")
                span.quote -> "▎ " + span.text
                else -> span.text
            }
            if (span.link != null) {
                withLink(LinkAnnotation.Url(span.link, TextLinkStyles(style))) { append(text) }
            } else {
                withStyle(style) { append(text) }
            }
        }
    }

/**
 * Uma mensagem. [compact] é a continuação de quem acabou de falar: sem avatar
 * nem nome, só o texto alinhado.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageItem(
    session: AccountSession,
    message: Message,
    author: User?,
    users: Map<String, User>,
    repliedTo: Message?,
    compact: Boolean,
    pinned: Boolean,
    highlighted: Boolean,
    onLongPress: () -> Unit,
    onReact: (ReactionSummaryDto) -> Unit,
    onOpenImage: (AttachmentDto) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val authorName = message.externalAuthor?.name?.takeIf { it.isNotBlank() }
        ?: author?.displayName
        ?: stringResource(Res.string.chat_unknown_user)

    Row(
        Modifier
            .fillMaxWidth()
            .background(if (highlighted) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
            .combinedClickable(onClick = {}, onLongClick = onLongPress)
            .padding(start = 12.dp, end = 12.dp, top = if (compact) 1.dp else 8.dp, bottom = 1.dp),
    ) {
        Box(Modifier.width(40.dp)) {
            if (!compact) UserAvatar(session, author, size = 40.dp, fallbackName = authorName)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (message.replyTo != null) {
                ReplyPreview(repliedTo, repliedTo?.authorId?.let { users[it] }, users)
            }
            if (!compact) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        authorName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = parseHexColor(author?.roleColor) ?: MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        message.createdAt?.formatTime().orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (pinned) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Filled.PushPin, contentDescription = null, modifier = Modifier.size(13.dp), tint = LocalPapoColors.current.accent)
                    }
                }
            }
            if (message.content.isNotEmpty()) {
                val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
                val muted = MaterialTheme.colorScheme.onSurfaceVariant
                val text = remember(message.content, users, accent) {
                    messageText(message.content, { users[it]?.displayName }, accent, codeBackground, muted)
                }
                Text(text, style = MaterialTheme.typography.bodyLarge)
            }
            if (message.edited) {
                Text(
                    stringResource(Res.string.chat_edited),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            message.attachments.forEach { attachment ->
                Spacer(Modifier.height(6.dp))
                if (attachment.isImage && attachment.thumbnailId != null) {
                    ImageAttachment(session, attachment, onOpen = { onOpenImage(attachment) })
                } else {
                    FileAttachment(session, attachment)
                }
            }
            message.embeds.forEach { embed ->
                Spacer(Modifier.height(6.dp))
                EmbedCard(session, embed)
            }
            if (message.reactions.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                ReactionRow(session, message, onReact)
            }
        }
    }
}

@Composable
private fun ReplyPreview(replied: Message?, author: User?, users: Map<String, User>) {
    Row(Modifier.padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.AutoMirrored.Filled.Reply,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(4.dp))
        val text = if (replied == null) {
            stringResource(Res.string.chat_reply_missing)
        } else {
            val name = replied.externalAuthor?.name ?: author?.displayName ?: stringResource(Res.string.chat_unknown_user)
            val body = renderMentions(replied.content) { users[it]?.displayName }.ifBlank { replied.attachments.firstOrNull()?.fileName.orEmpty() }
            "$name: $body"
        }
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ImageAttachment(session: AccountSession, attachment: AttachmentDto, onOpen: () -> Unit) {
    var revealed by remember(attachment.id) { mutableStateOf(!attachment.isSensitive) }
    val ratio = run {
        val w = attachment.thumbnailWidth ?: 0
        val h = attachment.thumbnailHeight ?: 0
        if (w > 0 && h > 0) (w.toFloat() / h).coerceIn(0.5f, 2.5f) else 4f / 3f
    }
    val image by rememberMediaImage(session, if (revealed) "att-thumb/${attachment.id}" else null) {
        session.api.attachmentThumbnail(attachment.id)
    }
    // A proporção vem dos metadados: a caixa já nasce do tamanho certo e a lista não pula ao carregar.
    Box(
        Modifier
            .widthIn(max = 280.dp)
            .heightIn(max = 320.dp)
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable { if (revealed) onOpen() else revealed = true },
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        when {
            !revealed -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(12.dp)) {
                Icon(Icons.Filled.VisibilityOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(Res.string.chat_sensitive),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            bitmap != null -> Image(bitmap, attachment.fileName, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

@Composable
private fun FileAttachment(session: AccountSession, attachment: AttachmentDto) {
    val platform = LocalPlatformUi.current
    val scope = rememberCoroutineScope()
    var downloading by remember(attachment.id) { mutableStateOf(false) }
    var failed by remember(attachment.id) { mutableStateOf(false) }
    Row(
        Modifier
            .widthIn(max = 320.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = !downloading, onClickLabel = stringResource(Res.string.msg_download)) {
                downloading = true
                failed = false
                scope.launch {
                    val ok = runCatching {
                        platform.saveToDownloads(attachment.fileName, attachment.mimeType, session.api.attachment(attachment.id))
                    }.getOrDefault(false)
                    failed = !ok
                    downloading = false
                }
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(attachment.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(
                formatBytes(attachment.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        if (downloading) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Filled.Download, contentDescription = stringResource(Res.string.msg_download), modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun EmbedCard(session: AccountSession, embed: EmbedDto) {
    val hasImage = embed.thumbnail != null || embed.image != null
    val image by rememberMediaImage(session, if (hasImage) "embed/${embed.id}" else null) {
        session.api.embedImageBase64(embed.id)?.let { Base64.decode(it) }
    }
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .widthIn(max = 340.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Box(Modifier.width(3.dp).heightIn(min = 48.dp).background(MaterialTheme.colorScheme.outline))
        Column(Modifier.padding(10.dp)) {
            (embed.siteName ?: embed.provider)?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            embed.title?.takeIf { it.isNotBlank() }?.let { title ->
                val text = if (embed.url != null) {
                    buildAnnotatedString {
                        withLink(LinkAnnotation.Url(embed.url, TextLinkStyles(SpanStyle(color = accent)))) { append(title) }
                    }
                } else {
                    AnnotatedString(title)
                }
                Text(text, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            embed.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            image?.let { bitmap ->
                Spacer(Modifier.height(8.dp))
                Image(
                    bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReactionRow(session: AccountSession, message: Message, onReact: (ReactionSummaryDto) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        message.reactions.forEach { reaction ->
            val mine = message.reactedWith(reaction)
            val scheme = MaterialTheme.colorScheme
            Row(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (mine) scheme.primaryContainer else scheme.surfaceContainerHigh)
                    .border(1.dp, if (mine) scheme.primary else Color.Transparent, RoundedCornerShape(14.dp))
                    .clickable { onReact(reaction) }
                    .padding(horizontal = 9.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReactionGlyph(session, reaction.emojiId, reaction.unicode)
                Spacer(Modifier.width(5.dp))
                Text(
                    reaction.count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (mine) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Emoji unicode como texto, ou a imagem de um emoji próprio do servidor. */
@Composable
fun ReactionGlyph(session: AccountSession, emojiId: String?, unicode: String?) {
    if (emojiId == null) {
        Text(unicode.orEmpty(), style = MaterialTheme.typography.bodyLarge)
        return
    }
    val image by rememberMediaImage(session, "emoji/$emojiId") { session.emojis.imageBytes(emojiId) }
    Box(Modifier.size(20.dp)) {
        image?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
    }
}
