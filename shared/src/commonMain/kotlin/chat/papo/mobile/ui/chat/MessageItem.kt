package chat.papo.mobile.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.EmojiCatalog
import chat.papo.mobile.chat.EmojiPickerModel
import chat.papo.mobile.chat.EmojiPiece
import chat.papo.mobile.chat.EmojiText
import chat.papo.mobile.chat.Markdown
import chat.papo.mobile.chat.MarkdownBlock
import chat.papo.mobile.chat.Message
import chat.papo.mobile.chat.TextSpan
import chat.papo.mobile.chat.UnicodeEmojiCatalog
import chat.papo.mobile.chat.User
import chat.papo.mobile.chat.hidesDestination
import chat.papo.mobile.chat.renderMentions
import chat.papo.mobile.core.formatTime
import chat.papo.mobile.data.api.AttachmentDto
import chat.papo.mobile.data.api.ReactionSummaryDto
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.chat_edited
import chat.papo.mobile.resources.chat_reply_missing
import chat.papo.mobile.resources.chat_reply_open
import chat.papo.mobile.resources.chat_reply_unloaded
import chat.papo.mobile.resources.chat_unknown_user
import chat.papo.mobile.resources.msg_pinned
import chat.papo.mobile.resources.msg_quick_react
import chat.papo.mobile.resources.msg_reactors_label
import chat.papo.mobile.resources.msg_reply
import chat.papo.mobile.ui.components.UserAvatar
import chat.papo.mobile.ui.components.parseHexColor
import chat.papo.mobile.ui.components.rememberMediaPainter
import chat.papo.mobile.ui.components.rememberedPainter
import chat.papo.mobile.ui.media.VideoHost
import chat.papo.mobile.ui.theme.LocalPapoColors
import org.jetbrains.compose.resources.stringResource

/** Um pedaço do corpo da mensagem: um texto (parágrafos ou um item de lista) ou uma régua entre textos. */
sealed interface MessagePart {
    class Text(val text: AnnotatedString) : MessagePart

    data object Divider : MessagePart
}

/** Corpo já montado de uma mensagem e os emojis do servidor que ele pede como imagem. */
class MessageText(val parts: List<MessagePart>, val emojiIds: Set<String>) {
    /** Tem emoji como imagem? Então o `Text` precisa do `inlineContent` de [emojiContent]. */
    val hasImages: Boolean get() = emojiIds.isNotEmpty()
}

/**
 * Medidas das listas: [step] é o recuo de cada nível e [markerWidth] a largura do marcador com o
 * espaço que vem depois dele, onde o texto do item começa. Em `sp`, porque o recuo de um parágrafo
 * só aceita uma unidade por vez.
 */
class ListMetrics(val step: TextUnit, val markerWidth: (String) -> TextUnit)

private val DefaultListMetrics = ListMetrics(step = 24.sp, markerWidth = { 24.sp })

/** Espaço sem quebra entre o marcador e o texto do item: o marcador nunca fica sozinho no fim da linha. */
private const val MARKER_GAP = "\u00A0"

/**
 * Corpo da mensagem com Markdown, menções destacadas, links clicáveis e `:nome:` dos emojis
 * do servidor como imagem. [emojiIds] liga nome a id; dentro de código, de link e de menção
 * o `:nome:` continua texto. Cada item de lista é um texto com recuo pendente, e a régua separa dois textos.
 *
 * O `@everyone` só sai em destaque, como uma menção, com [highlightEveryone]: ele vale quando o autor pode
 * usá-lo (`authorCanEveryone`). Sem isso é texto comum, como qualquer outro.
 *
 * Um link cujo texto esconde o destino (`[banco](https://outro.example)`, ver [hidesDestination]) não abre sozinho com
 * [onConfirmLink]: o toque chama a função com o destino, para quem mostra pedir a confirmação. Sem ela, e para os links
 * que mostram o próprio destino, o toque abre o link direto.
 */
fun messageText(
    content: String,
    nameOf: (String) -> String?,
    accent: Color,
    codeBackground: Color,
    muted: Color,
    emojiIds: Map<String, String> = emptyMap(),
    lists: ListMetrics = DefaultListMetrics,
    highlightEveryone: Boolean = false,
    onConfirmLink: ((String) -> Unit)? = null,
): MessageText {
    val used = LinkedHashSet<String>()

    fun AnnotatedString.Builder.appendEmojis(text: String) {
        for (piece in EmojiText.split(text, emojiIds)) {
            when (piece) {
                is EmojiPiece.Plain -> append(piece.text)
                is EmojiPiece.Custom -> {
                    used += piece.id
                    appendInlineContent(emojiContentId(piece.id), ":${piece.name}:")
                }
            }
        }
    }

    fun AnnotatedString.Builder.appendSpans(spans: List<TextSpan>) {
        // Sem o destaque o `@everyone` volta a ser texto comum, fundido ao vizinho: o que sobra com `everyone` é menção.
        for (span in if (highlightEveryone) spans else Markdown.withoutEveryone(spans)) {
            val style = spanStyle(span, accent, codeBackground, muted)
            val plain = spanText(span, nameOf)
            when {
                span.link != null -> {
                    val styles = TextLinkStyles(style)
                    val annotation = if (onConfirmLink != null && span.hidesDestination()) {
                        LinkAnnotation.Clickable(span.link, styles) { onConfirmLink(span.link) }
                    } else {
                        LinkAnnotation.Url(span.link, styles)
                    }
                    withLink(annotation) { append(plain) }
                }
                span.code || span.mentionUserId != null || span.everyone -> withStyle(style) { append(plain) }
                else -> withStyle(style) { appendEmojis(plain) }
            }
        }
    }

    // Cada linha do item é um texto à parte: a tabulação de um parágrafo vale só para a primeira linha dele (a quebra `\n`
    // abriria outra "primeira linha"), e assim o texto selecionado e lido pelo leitor de tela também traz uma quebra entre elas.
    // As linhas de continuação já começam no recuo do texto.
    fun listItem(item: MarkdownBlock.ListItem): List<MessagePart> {
        val lead = lists.step * (item.level + 0.3f)
        val rest = (lead.value + lists.markerWidth(item.marker).value).sp
        return splitLines(item.spans).mapIndexed { index, spans ->
            val indent = if (index == 0) TextIndent(lead, rest) else TextIndent(rest, rest)
            MessagePart.Text(
                buildAnnotatedString {
                    withStyle(ParagraphStyle(textIndent = indent)) {
                        if (index == 0) append(item.marker + MARKER_GAP)
                        appendSpans(spans)
                    }
                },
            )
        }
    }

    val parts = Markdown.blocks(content).flatMap { block ->
        when (block) {
            // Uma linha em branco entre dois blocos precisa de algo para ocupar a altura dela.
            is MarkdownBlock.Paragraph -> listOf(MessagePart.Text(buildAnnotatedString { if (block.spans.isEmpty()) append(" ") else appendSpans(block.spans) }))
            is MarkdownBlock.ListItem -> listItem(block)
            MarkdownBlock.Rule -> listOf(MessagePart.Divider)
        }
    }
    return MessageText(parts, used)
}

private fun spanStyle(span: TextSpan, accent: Color, codeBackground: Color, muted: Color) = SpanStyle(
    fontWeight = if (span.bold || span.mentionUserId != null || span.everyone) FontWeight.SemiBold else null,
    fontStyle = if (span.italic) FontStyle.Italic else null,
    textDecoration = when {
        span.strike -> TextDecoration.LineThrough
        span.link != null -> TextDecoration.Underline
        else -> null
    },
    fontFamily = if (span.code) FontFamily.Monospace else null,
    background = if (span.code) codeBackground else Color.Unspecified,
    color = when {
        span.link != null || span.mentionUserId != null || span.everyone -> accent
        span.quote -> muted
        else -> Color.Unspecified
    },
)

/** O que o trecho mostra: a menção vira `@nome`, o `@everyone` fica como foi escrito, e a citação ganha a barra. */
private fun spanText(span: TextSpan, nameOf: (String) -> String?): String = when {
    span.mentionUserId != null -> "@" + (nameOf(span.mentionUserId) ?: "usuário")
    span.everyone -> span.text
    span.quote -> "▎ " + span.text
    else -> span.text
}

/** As linhas de um item de lista: o `\n` separa a linha do marcador das de continuação. */
private fun splitLines(spans: List<TextSpan>): List<List<TextSpan>> {
    val lines = arrayListOf(ArrayList<TextSpan>())
    for (span in spans) {
        span.text.split('\n').forEachIndexed { index, part ->
            if (index > 0) lines += ArrayList<TextSpan>()
            if (part.isNotEmpty()) lines.last() += span.copy(text = part)
        }
    }
    return lines
}

private fun emojiContentId(emojiId: String) = "emoji:$emojiId"

/** O `inlineContent` do `Text` para os emojis de [MessageText.emojiIds]; [maxPx] limita o lado da imagem decodificada. */
@Composable
fun emojiContent(session: AccountSession, ids: Set<String>, maxPx: Int): Map<String, InlineTextContent> = remember(session.accountId, ids, maxPx) {
    ids.associate { id ->
        emojiContentId(id) to InlineTextContent(
            Placeholder(1.25.em, 1.25.em, PlaceholderVerticalAlign.TextCenter),
        ) { EmojiImage(session, id, Modifier.fillMaxSize(), maxPx = maxPx) }
    }
}

// Lado maior da imagem decodificada de um emoji: cobre 1,25 em de texto (e uma reação) e, com a
// mensagem só de emoji, o corpo maior. Mais pixels só gastariam memória em cada animação.
private const val EMOJI_TEXT_PX = 96
private const val EMOJI_JUMBO_PX = 128

/**
 * A imagem de um emoji do servidor, do cache cifrado. Enquanto carrega fica vazia, ou
 * mostra um círculo que pulsa com [placeholder] (lido só no desenho, sem recompor). Um emoji
 * animado anima com [animate]; sem ele (as sugestões depois do `:`) mostra o primeiro quadro.
 */
@Composable
fun EmojiImage(
    session: AccountSession,
    emojiId: String,
    modifier: Modifier = Modifier,
    placeholder: State<Float>? = null,
    animate: Boolean = true,
    maxPx: Int = EMOJI_TEXT_PX,
) {
    val image by rememberMediaPainter(session, EmojiCatalog.mediaKey(emojiId), maxPx, animate) { session.emojis.imageBytes(emojiId) }
    val loaded = image
    when {
        loaded != null -> Image(loaded.rememberedPainter(), contentDescription = null, modifier = modifier)
        placeholder != null -> {
            val color = MaterialTheme.colorScheme.onSurface
            Box(modifier.drawBehind { drawCircle(color.copy(alpha = placeholder.value)) })
        }
    }
}

/**
 * Uma mensagem. [compact] é a continuação de quem acabou de falar: sem avatar
 * nem nome, só o texto alinhado. Se ela responde a outra, a prévia mostra [repliedTo]
 * quando está carregada; senão avisa que [replyMissing] (o servidor não a tem mais) ou convida a
 * procurá-la com [onOpenReply].
 *
 * Gestos: deslizar para o lado do fim da linha chama [onReply], o toque duplo chama [onQuickReact]
 * e o toque longo, [onLongPress]. Os dois primeiros também são ações de acessibilidade. Nulos (sem permissão
 * para enviar ou reagir), o gesto e a ação de acessibilidade correspondentes não existem.
 *
 * [canReact] e [canRemoveOwnReaction] dizem quais bolinhas de reação respondem ao toque: com [canReact] todas;
 * sem ele, só a minha, e só com [canRemoveOwnReaction] (ver [reactionToggleable]). O toque longo numa bolinha chama
 * [onShowReactors] em qualquer uma, com ou sem essas permissões: ver quem reagiu só pede ler o canal.
 *
 * [addressedToMe] marca a mensagem dirigida a quem usa a conta, que a menciona, responde a ela ou é um `@everyone`
 * de quem pode usá-lo (a própria também, como no web): fundo dourado e uma barra no começo da linha.
 * [highlightEveryone] vale quando o autor pode usar `@everyone` e põe esse trecho do texto em destaque.
 * [highlighted] é o destaque passageiro (a mensagem que se responde ou edita, ou a que se
 * acabou de abrir) e tem o fundo no lugar do dourado, que continua na barra. Toda mensagem [pinned] leva o
 * alfinete no fim da linha, também as agrupadas, que não têm cabeçalho.
 *
 * [videos] é de onde os cartões de vídeo da mensagem sabem qual toca na própria caixa e por onde abrem a tela cheia.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageItem(
    session: AccountSession,
    message: Message,
    author: User?,
    users: Map<String, User>,
    emojiIds: Map<String, String>,
    unicodeEmojis: UnicodeEmojiCatalog?,
    repliedTo: Message?,
    replyMissing: Boolean,
    compact: Boolean,
    pinned: Boolean,
    addressedToMe: Boolean,
    highlightEveryone: Boolean,
    highlighted: Boolean,
    canReact: Boolean,
    canRemoveOwnReaction: Boolean,
    onOpenReply: () -> Unit,
    onReply: (() -> Unit)?,
    onQuickReact: (() -> Unit)?,
    onLongPress: () -> Unit,
    onReact: (ReactionSummaryDto) -> Unit,
    onOpenImage: (AttachmentDto) -> Unit,
    onShowReactors: (ReactionSummaryDto) -> Unit,
    videos: VideoHost,
) {
    val authorName = message.externalAuthor?.name?.takeIf { it.isNotBlank() }
        ?: author?.displayName
        ?: stringResource(Res.string.chat_unknown_user)
    val replyLabel = stringResource(Res.string.msg_reply)
    val reactLabel = stringResource(Res.string.msg_quick_react, EmojiPickerModel.DEFAULT_REACTIONS.first())
    val papoColors = LocalPapoColors.current
    val background = when {
        highlighted -> MaterialTheme.colorScheme.surfaceContainerHigh
        addressedToMe -> papoColors.highlight
        else -> Color.Transparent
    }

    SwipeToReply(onReply = { onReply?.invoke() }, modifier = Modifier.fillMaxWidth(), enabled = onReply != null) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(background)
                .then(if (addressedToMe) Modifier.startBar(papoColors.highlightBar, HighlightBarWidth) else Modifier)
                .combinedClickable(onClick = {}, onLongClick = onLongPress, onDoubleClick = onQuickReact)
                .semantics {
                    customActions = listOfNotNull(
                        onReply?.let { reply -> CustomAccessibilityAction(replyLabel) { reply(); true } },
                        onQuickReact?.let { react -> CustomAccessibilityAction(reactLabel) { react(); true } },
                    )
                }
                .padding(start = 12.dp, end = 12.dp, top = if (compact) 1.dp else 8.dp, bottom = 1.dp),
        ) {
            Box(Modifier.width(40.dp)) {
                if (!compact) UserAvatar(session, author, size = 40.dp, fallbackName = authorName)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                if (message.replyTo != null) {
                    ReplyPreview(repliedTo, replyMissing, repliedTo?.authorId?.let { users[it] }, users, onOpenReply, onLongPress)
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
                    }
                }
                if (message.content.isNotEmpty()) {
                    MessageBodyText(session, message.content, users, emojiIds, unicodeEmojis, highlightEveryone)
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
                    MessageAttachment(session, message.id, attachment, onOpenImage, videos)
                }
                message.embeds.forEach { embed ->
                    Spacer(Modifier.height(6.dp))
                    EmbedCard(session, message.id, embed, users, emojiIds, unicodeEmojis, videos)
                }
                if (message.reactions.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    ReactionRow(session, message, canReact, canRemoveOwnReaction, onReact, onShowReactors)
                }
            }
            if (pinned) {
                Spacer(Modifier.width(8.dp))
                // Alinhado à primeira linha: a do nome (20 dp) nas mensagens com cabeçalho, a do texto (24 dp) nas agrupadas.
                Icon(
                    Icons.Filled.PushPin,
                    contentDescription = stringResource(Res.string.msg_pinned),
                    modifier = Modifier.padding(top = if (compact) 5.dp else 3.dp).size(14.dp),
                    tint = papoColors.accent,
                )
            }
        }
    }
}

/** Largura da barra de destaque no começo da linha. */
private val HighlightBarWidth = 3.dp

/** Uma barra de [width] no começo da linha, na altura toda: à esquerda, ou à direita em idiomas da direita para a esquerda. */
internal fun Modifier.startBar(color: Color, width: Dp): Modifier = drawBehind {
    val barWidth = width.toPx()
    val x = if (layoutDirection == LayoutDirection.Rtl) size.width - barWidth else 0f
    drawRect(color, Offset(x, 0f), Size(barWidth, size.height))
}

/**
 * Corpo de texto de uma mensagem: Markdown, listas, menções, links e `:nome:` dos emojis do
 * servidor como imagem. Mensagem só de emoji aparece maior. [highlightEveryone]: ver [messageText].
 */
@Composable
fun MessageBodyText(
    session: AccountSession,
    content: String,
    users: Map<String, User>,
    emojiIds: Map<String, String>,
    unicodeEmojis: UnicodeEmojiCatalog?,
    highlightEveryone: Boolean = false,
) {
    val accent = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.bodyLarge
    val lists = rememberListMetrics(style)
    // Só mensagem com `:` depende da lista de emojis do servidor: as outras não refazem
    // o Markdown cada vez que a lista muda (ela chega aos poucos na primeira leitura).
    val relevantIds = if (':' in content) emojiIds else emptyMap()
    // O link que o texto esconde só abre depois de o usuário ver o destino.
    var pendingLink by remember { mutableStateOf<String?>(null) }
    val text = remember(content, users, accent, codeBackground, muted, relevantIds, lists, highlightEveryone) {
        messageText(content, { users[it]?.displayName }, accent, codeBackground, muted, relevantIds, lists, highlightEveryone) { pendingLink = it }
    }
    pendingLink?.let { LinkConfirmDialog(it, onDismiss = { pendingLink = null }) }
    // Mensagem só de emoji aparece maior; as imagens acompanham o corpo do texto (em `em`).
    val jumbo = remember(content, relevantIds, unicodeEmojis) {
        EmojiText.isEmojiOnly(content, relevantIds, unicodeEmojis)
    }
    val bodyStyle = if (jumbo) style.copy(fontSize = 34.sp, lineHeight = 44.sp) else style
    val inlineContent = if (text.hasImages) emojiContent(session, text.emojiIds, if (jumbo) EMOJI_JUMBO_PX else EMOJI_TEXT_PX) else emptyMap()
    val single = text.parts.singleOrNull()
    if (single is MessagePart.Text) {
        Text(single.text, style = bodyStyle, inlineContent = inlineContent)
        return
    }
    Column {
        for (part in text.parts) {
            when (part) {
                is MessagePart.Text -> Text(part.text, style = bodyStyle, inlineContent = inlineContent)
                MessagePart.Divider -> HorizontalDivider(Modifier.padding(vertical = 6.dp))
            }
        }
    }
}

/**
 * As medidas das listas para o [style] do corpo. A largura de um marcador vem do texto medido
 * (número, bolinha e caixa de tarefa não têm a mesma largura), na primeira vez que uma lista
 * pede, e o recuo das linhas seguintes do item fica alinhado ao texto dele.
 */
@Composable
private fun rememberListMetrics(style: TextStyle): ListMetrics {
    val resolver = LocalFontFamilyResolver.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    return remember(resolver, density, direction, style) {
        val measurer by lazy(LazyThreadSafetyMode.NONE) { TextMeasurer(resolver, density, direction) }
        val widths = HashMap<String, TextUnit>()
        ListMetrics(
            // O recuo acompanha o corpo do texto; a conta com `Unspecified` falharia, então há um tamanho de reserva.
            step = (if (style.fontSize.isSpecified) style.fontSize else 16.sp) * 1.4f,
            markerWidth = { marker ->
                widths.getOrPut(marker) {
                    val measured = measurer.measure(marker + MARKER_GAP, style, softWrap = false, maxLines = 1)
                    with(density) { measured.size.width.toSp() }
                }
            },
        )
    }
}

/**
 * Prévia da mensagem respondida, em três estados: carregada (o conteúdo, e tocar vai até ela), ainda
 * não carregada (convida a procurá-la; tocar procura) e sabidamente apagada ([missing]; sem toque).
 * O toque longo continua abrindo as ações da mensagem, como no resto da linha.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReplyPreview(
    replied: Message?,
    missing: Boolean,
    author: User?,
    users: Map<String, User>,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val gone = replied == null && missing
    // Ainda não carregada: o texto tem cor de ação, porque tocar nele procura a mensagem.
    val unloaded = replied == null && !missing
    val color = if (unloaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val openLabel = stringResource(Res.string.chat_reply_open)
    Row(
        Modifier
            .padding(bottom = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .then(
                if (gone) {
                    Modifier
                } else {
                    Modifier.combinedClickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpen, onLongClick = onLongPress)
                },
            )
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.Reply,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(4.dp))
        val text = when {
            replied != null -> {
                val name = replied.externalAuthor?.name ?: author?.displayName ?: stringResource(Res.string.chat_unknown_user)
                val body = renderMentions(replied.content) { users[it]?.displayName }.ifBlank { replied.attachments.firstOrNull()?.fileName.orEmpty() }
                "$name: $body"
            }
            gone -> stringResource(Res.string.chat_reply_missing)
            else -> stringResource(Res.string.chat_reply_unloaded)
        }
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontStyle = if (gone) FontStyle.Italic else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun ReactionRow(
    session: AccountSession,
    message: Message,
    canReact: Boolean,
    canRemoveOwnReaction: Boolean,
    onReact: (ReactionSummaryDto) -> Unit,
    onShowReactors: (ReactionSummaryDto) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val showReactorsLabel = stringResource(Res.string.msg_reactors_label)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        message.reactions.forEach { reaction ->
            val mine = message.reactedWith(reaction)
            // Sem toque a bolinha continua mostrando a contagem; só deixa de somar ou tirar a reação.
            val toggleable = reactionToggleable(canReact, canRemoveOwnReaction, mine)
            val scheme = MaterialTheme.colorScheme
            Row(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (mine) scheme.primaryContainer else scheme.surfaceContainerHigh)
                    .border(1.dp, if (mine) scheme.primary else Color.Transparent, RoundedCornerShape(14.dp))
                    .combinedClickable(
                        onClick = { if (toggleable) onReact(reaction) },
                        onLongClickLabel = showReactorsLabel,
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onShowReactors(reaction)
                        },
                    )
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
    Box(Modifier.size(20.dp)) { EmojiImage(session, emojiId, Modifier.fillMaxSize()) }
}
