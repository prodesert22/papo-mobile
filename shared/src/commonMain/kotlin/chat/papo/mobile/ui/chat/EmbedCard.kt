package chat.papo.mobile.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.UnicodeEmojiCatalog
import chat.papo.mobile.chat.User
import chat.papo.mobile.chat.embedMediaAspect
import chat.papo.mobile.chat.fieldRows
import chat.papo.mobile.chat.hasViewableImage
import chat.papo.mobile.chat.isGifEmbed
import chat.papo.mobile.chat.safeLink
import chat.papo.mobile.chat.youTubeId
import chat.papo.mobile.data.api.EmbedDto
import chat.papo.mobile.data.api.EmbedFieldDto
import chat.papo.mobile.media.PlayableMedia
import chat.papo.mobile.media.aspectOrNull
import chat.papo.mobile.media.embedAuthorKey
import chat.papo.mobile.media.embedImageKey
import chat.papo.mobile.media.fetchEmbedAuthorIcon
import chat.papo.mobile.media.fetchEmbedImage
import chat.papo.mobile.media.hasAuthorIcon
import chat.papo.mobile.media.hasImage
import chat.papo.mobile.media.relayVideo
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.embed_image_open
import chat.papo.mobile.resources.embed_open_link
import chat.papo.mobile.resources.embed_show_less
import chat.papo.mobile.resources.embed_show_more
import chat.papo.mobile.ui.components.PlatformImage
import chat.papo.mobile.ui.components.parseHexColor
import chat.papo.mobile.ui.components.rememberMediaImage
import chat.papo.mobile.ui.components.rememberMediaPainter
import chat.papo.mobile.ui.components.rememberedPainter
import chat.papo.mobile.ui.media.VideoHost
import chat.papo.mobile.ui.media.openInBrowser
import org.jetbrains.compose.resources.stringResource

/** Largura máxima do cartão de link e do cartão de GIF. */
private val LinkCardMaxWidth = 340.dp
private val GifCardMaxWidth = 280.dp

/** Altura máxima da imagem: uma imagem alta fica estreita, nunca cortada (como o cartão do vídeo anexado). */
private val MediaMaxHeight = 320.dp

/** A barra de cor na lateral do cartão e o recuo do conteúdo em relação a ela. */
private val BarWidth = 4.dp
private val CardPadding = 10.dp

/** Linhas da descrição antes do **Mostrar mais**. */
private const val DESCRIPTION_LINES = 6

/**
 * Lado maior da imagem decodificada: o servidor entrega imagem de embed com até 1024 px (GIF, 512), então mais que isso
 * não ganha nitidez e só custaria memória.
 */
private const val EMBED_IMAGE_PX = 1024

/** Proporção da caixa enquanto não se sabe a da imagem: nem o servidor deu as medidas, nem ela chegou. */
private const val PLACEHOLDER_ASPECT = 16f / 9f

/**
 * Cartão de um embed. O de GIF ([isGifEmbed]) é só a imagem animada, o provedor e o título; os outros são o cartão
 * completo de [LinkEmbedCard]. Nada é pedido ao relay de vídeo antes do toque: nem autoplay nem pré-carga, porque cada
 * vídeo aberto prende uma das poucas vagas do relay no servidor. [videos] guarda qual vídeo toca.
 *
 * A imagem que o cartão mostra abre no visualizador de mídia por [onOpenImage] quando [hasViewableImage]; a de um embed com
 * vídeo (do relay ou do YouTube) segue com o gesto do vídeo.
 */
@Composable
internal fun EmbedCard(
    session: AccountSession,
    messageId: String,
    embed: EmbedDto,
    users: Map<String, User>,
    emojiIds: Map<String, String>,
    unicodeEmojis: UnicodeEmojiCatalog?,
    videos: VideoHost,
    onOpenImage: (EmbedDto) -> Unit,
) {
    if (isGifEmbed(embed)) {
        GifEmbedCard(session, embed, onOpenImage)
    } else {
        LinkEmbedCard(session, messageId, embed, users, emojiIds, unicodeEmojis, videos, onOpenImage)
    }
}

/**
 * Cartão de um embed de link ou customizado: barra lateral com a cor do embed (ou a de contorno), provedor, autor com o
 * ícone, título com link, descrição em Markdown, campos em grade, imagem e rodapé. Tudo é texto: o conteúdo é remoto, e
 * os links só abrem se forem `http` ou `https` ([safeLink]). Se o embed traz um vídeo que o relay do servidor serve
 * ([relayVideo]), a caixa da imagem leva o ▶, que o toca ali mesmo, e tocar fora dele abre a tela cheia ([VideoBox]). Um
 * embed do YouTube ([youTubeId]) tem a mídia própria de [YouTubeMedia], que não passa pelo relay.
 *
 * O servidor serve uma imagem por embed (a `thumbnail` ou, sem ela, a `image`) e não serve o ícone do rodapé. Sem vídeo, tocar
 * na imagem, depois que ela chegou, a abre no visualizador ([onOpenImage]).
 */
@Composable
private fun LinkEmbedCard(
    session: AccountSession,
    messageId: String,
    embed: EmbedDto,
    users: Map<String, User>,
    emojiIds: Map<String, String>,
    unicodeEmojis: UnicodeEmojiCatalog?,
    videos: VideoHost,
    onOpenImage: (EmbedDto) -> Unit,
) {
    val video = relayVideo(session.client.server, embed)
    val youtubeId = youTubeId(embed)
    val image = if (embed.hasImage()) rememberEmbedImage(session, embed) else null
    val accent = MaterialTheme.colorScheme.primary
    val barColor = parseHexColor(embed.color) ?: MaterialTheme.colorScheme.outline
    Column(
        Modifier
            .widthIn(max = LinkCardMaxWidth)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .startBar(barColor, BarWidth)
            .padding(start = BarWidth + CardPadding, top = CardPadding, end = CardPadding, bottom = CardPadding),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        (embed.siteName ?: embed.provider)?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        EmbedAuthor(session, embed, accent)
        embed.title?.takeIf { it.isNotBlank() }?.let { title ->
            Text(
                linkedText(title, safeLink(embed.url), accent),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        embed.description?.takeIf { it.isNotBlank() }?.let { EmbedDescription(session, it, users, emojiIds, unicodeEmojis) }
        if (embed.fields.isNotEmpty()) EmbedFields(embed.fields)
        if (youtubeId != null) {
            YouTubeMedia(messageId, youtubeId, image, videos, Modifier.padding(top = 4.dp))
        } else if (video != null || embed.hasImage()) {
            val aspect = embedMediaAspect(embed) ?: image?.let { aspectOrNull(it.width, it.height) } ?: PLACEHOLDER_ASPECT
            val onOpen = { onOpenImage(embed) }.takeIf { hasViewableImage(session.client.server, embed) }
            EmbedMediaBox(session, messageId, image, aspect, video, videos, onOpen, Modifier.padding(top = 4.dp))
        }
        embed.footer?.text?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Cartão de um embed de GIF (Tenor, GIPHY, KLIPY): a imagem animada em laço e sem som, do tamanho que o servidor deu
 * (até [GifCardMaxWidth]), com o provedor e o título embaixo. Tocar na imagem, depois que ela chegou, a abre no visualizador
 * ([onOpenImage]); o provedor e o título, na cor de link, abrem a página do GIF no navegador. Com as animações do sistema
 * removidas a imagem fica no primeiro quadro.
 */
@Composable
private fun GifEmbedCard(session: AccountSession, embed: EmbedDto, onOpenImage: (EmbedDto) -> Unit) {
    val image = rememberEmbedImage(session, embed)
    val uriHandler = LocalUriHandler.current
    val link = safeLink(embed.url)
    val aspect = embedMediaAspect(embed) ?: image?.let { aspectOrNull(it.width, it.height) } ?: PLACEHOLDER_ASPECT
    val openImageLabel = stringResource(Res.string.embed_image_open)
    val openLinkLabel = stringResource(Res.string.embed_open_link)
    val provider = (embed.provider ?: embed.siteName)?.takeIf { it.isNotBlank() }
    val title = embed.title?.takeIf { it.isNotBlank() }
    Column(
        // A largura sai da proporção para a caixa caber na altura máxima: um GIF alto fica estreito, não esticado.
        Modifier
            .width(minOf(GifCardMaxWidth, MediaMaxHeight * aspect))
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .then(if (image != null) Modifier.clickable(onClickLabel = openImageLabel, role = Role.Button) { onOpenImage(embed) } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            image?.let { Image(it.rememberedPainter(), title, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        }
        if (provider != null || title != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(if (link != null) Modifier.clickable(onClickLabel = openLinkLabel, role = Role.Button) { openInBrowser(uriHandler, link) } else Modifier)
                    .padding(horizontal = CardPadding, vertical = 6.dp),
            ) {
                provider?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                title?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (link != null) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * A imagem do embed, decodificada pela plataforma (então um GIF ou WebP animado anima), do cache cifrado da conta. É
 * `null` enquanto carrega, ou se o servidor não a tem. Só o pedido dela, que também traz o ícone do autor.
 */
@Composable
private fun rememberEmbedImage(session: AccountSession, embed: EmbedDto): PlatformImage? =
    rememberMediaPainter(session, embedImageKey(embed.id), EMBED_IMAGE_PX, animate = true) { session.fetchEmbedImage(embed.id) }.value

/** O autor do embed: o ícone de 20 dp (se o servidor o serve) e o nome, que é um link se o autor traz uma URL web. */
@Composable
private fun EmbedAuthor(session: AccountSession, embed: EmbedDto, linkColor: Color) {
    val author = embed.author ?: return
    val name = author.name?.takeIf { it.isNotBlank() } ?: return
    val icon = if (embed.hasAuthorIcon()) {
        rememberMediaImage(session, embedAuthorKey(embed.id)) { session.fetchEmbedAuthorIcon(embed) }.value
    } else {
        null
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        icon?.let {
            Image(it, contentDescription = null, modifier = Modifier.size(20.dp).clip(CircleShape), contentScale = ContentScale.Crop)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            linkedText(name, safeLink(author.url), linkColor),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A descrição do embed pelo mesmo Markdown do corpo da mensagem ([MessageBodyText]), cortada em [DESCRIPTION_LINES] linhas com
 * **Mostrar mais**. O corte é pela altura (o corpo pode ter listas e divisores, que um limite de linhas por texto não
 * soma): o conteúdo é medido inteiro, sem limite, e a caixa mostra só as primeiras linhas.
 */
@Composable
private fun EmbedDescription(
    session: AccountSession,
    description: String,
    users: Map<String, User>,
    emojiIds: Map<String, String>,
    unicodeEmojis: UnicodeEmojiCatalog?,
) {
    var expanded by remember(description) { mutableStateOf(false) }
    var fullHeightPx by remember(description) { mutableIntStateOf(0) }
    val density = LocalDensity.current
    // O corpo usa bodyLarge; a altura de uma linha vem do mesmo estilo.
    val style = MaterialTheme.typography.bodyLarge
    val lineHeightPx = with(density) { (if (style.lineHeight.isSpecified) style.lineHeight else style.fontSize * 1.5f).toPx() }
    val limitPx = lineHeightPx * DESCRIPTION_LINES
    // Meia linha de folga: um texto de exatamente seis linhas não pode virar "mais" por um pixel de arredondamento.
    val overflowing = fullHeightPx > limitPx + lineHeightPx / 2
    Column {
        Box(
            if (expanded) Modifier else Modifier.heightIn(max = with(density) { limitPx.toDp() }).clipToBounds(),
        ) {
            Box(Modifier.wrapContentHeight(align = Alignment.Top, unbounded = true).onSizeChanged { fullHeightPx = it.height }) {
                MessageBodyText(session, description, users, emojiIds, unicodeEmojis)
            }
        }
        if (overflowing) {
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(if (expanded) Res.string.embed_show_less else Res.string.embed_show_more))
            }
        }
    }
}

/** A grade dos campos: os `inline` de dois em dois lado a lado, os outros na largura toda ([fieldRows]). Só texto. */
@Composable
private fun EmbedFields(fields: List<EmbedFieldDto>) {
    val rows = remember(fields) { fieldRows(fields) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { field ->
                    Column(Modifier.weight(1f)) {
                        if (field.name.isNotBlank()) {
                            Text(field.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        }
                        if (field.value.isNotBlank()) {
                            Text(field.value, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

/**
 * A caixa da imagem do embed: com a proporção [aspect] (a lista não pula quando a imagem chega), na largura do cartão e
 * no máximo [MediaMaxHeight] de altura, e a imagem inteira dentro dela (`Fit`, sem cortar as laterais nem o topo). Com
 * [video] a caixa é a de um vídeo tocável ([VideoBox]): o ▶ o toca ali mesmo e o resto da caixa abre a tela cheia; antes da
 * imagem chegar, e sem ela, o fundo do cartão. Sem [video], [onOpen] (se não é nulo) abre a imagem inteira, mas só depois que
 * ela chegou: a caixa vazia não promete nada, e o visualizador a lê do cache em que o cartão a pôs.
 */
@Composable
private fun EmbedMediaBox(
    session: AccountSession,
    messageId: String,
    image: PlatformImage?,
    aspect: Float,
    video: PlayableMedia?,
    videos: VideoHost,
    onOpen: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val openLabel = stringResource(Res.string.embed_image_open)
    val open = onOpen.takeIf { video == null && image != null }
    Box(
        modifier
            .heightIn(max = MediaMaxHeight)
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (open != null) Modifier.clickable(onClickLabel = openLabel, role = Role.Button, onClick = open) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val cover: @Composable BoxScope.() -> Unit = {
            image?.let { Image(it.rememberedPainter(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        }
        if (video != null) ServerVideoBox(session, messageId, video, videos, embed = true, cover = cover) else cover()
    }
}

/** [text] com o link [url] (já filtrado por [safeLink]) na cor [color]; sem URL, texto puro. */
private fun linkedText(text: String, url: String?, color: Color): AnnotatedString =
    if (url == null) {
        AnnotatedString(text)
    } else {
        buildAnnotatedString { withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = color)))) { append(text) } }
    }
