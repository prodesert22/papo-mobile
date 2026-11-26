package chat.papo.mobile.ui.media

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleStartEffect
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.Message
import chat.papo.mobile.data.api.AttachmentDto
import chat.papo.mobile.data.api.OutgoingFile
import chat.papo.mobile.media.PREVIEW_MAX_BYTES
import chat.papo.mobile.media.PlaybackFailure
import chat.papo.mobile.media.PosterSource
import chat.papo.mobile.media.guardGone
import chat.papo.mobile.media.posterKey
import chat.papo.mobile.media.posterSource
import chat.papo.mobile.media.readAtMost
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.action_close
import chat.papo.mobile.resources.action_retry
import chat.papo.mobile.resources.msg_download
import chat.papo.mobile.resources.video_download_failed
import chat.papo.mobile.resources.video_play
import chat.papo.mobile.resources.viewer_counter
import chat.papo.mobile.resources.viewer_image_failed
import chat.papo.mobile.resources.viewer_save
import chat.papo.mobile.resources.viewer_save_failed
import chat.papo.mobile.resources.viewer_saved
import chat.papo.mobile.resources.viewer_share
import chat.papo.mobile.resources.viewer_share_failed
import chat.papo.mobile.ui.components.LocalPlatformUi
import chat.papo.mobile.ui.components.PlatformImage
import chat.papo.mobile.ui.components.rememberMediaImage
import chat.papo.mobile.ui.components.rememberedPainter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.engawapg.lib.zoomable.ScrollGesturePropagation
import net.engawapg.lib.zoomable.rememberZoomState
import net.engawapg.lib.zoomable.zoomable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Lado maior da imagem original decodificada: cobre o zoom de 5x de uma tela cheia sem estourar a memória com uma foto enorme. */
private const val VIEWER_IMAGE_PX = 4096

/** Lado maior da prévia de uma imagem pendente: menor que a do original, porque ela sai de um arquivo lido inteiro da memória. */
private const val PREVIEW_IMAGE_PX = 2048

/** O zoom vai de 1x até este valor. */
private const val MAX_ZOOM = 5f

/** Quanto tempo o aviso de salvar ou compartilhar fica na tela. */
private const val NOTICE_MS = 3_000L

/**
 * A mídia aberta no visualizador de uma conversa: a mensagem e o anexo em que ele abriu, mais a página em que está e, se
 * for de vídeo, o ponto em que toca. É o que sobrevive a girar o aparelho; a mídia sai das mensagens de novo.
 *
 * A [positionMs] não é estado observável de propósito (ver [PlayingVideo]): muda a cada segundo e só importa quando se
 * salva ou se recria o player. [playing] é, porque trocar a capa pelo player recompõe a página.
 */
internal class ViewingMedia(
    val messageId: String,
    /** A chave ([viewerKey]) em que o visualizador abriu: é a única página sensível que ele mostra (ver [viewerItems]). */
    val openedKey: String,
    currentKey: String = openedKey,
    playing: Boolean = false,
    var positionMs: Long = 0L,
) {
    /** A página que está na tela. */
    var currentKey: String = currentKey
        private set

    /** O vídeo da página [currentKey] está tocando, e não só mostrando a capa. */
    var playing: Boolean by mutableStateOf(playing)

    /** Passou para a página [key]: o player da anterior já saiu, e a nova começa sem tocar. */
    fun select(key: String) {
        if (key == currentKey) return
        currentKey = key
        playing = false
        positionMs = 0L
    }

    companion object {
        /** Abre na imagem (ou GIF) [attachment] da mensagem [messageId]. */
        fun image(messageId: String, attachment: AttachmentDto) = ViewingMedia(messageId, viewerKey(attachment.id))

        /**
         * Abre na página do vídeo anexado [mediaKey] (a `PlayableMedia.key`, `attachment:<id>`) da mensagem [messageId]. Com
         * [playFromMs] nulo mostra a capa com o ▶; com um ponto (0 é o começo) o vídeo já toca a partir dele, como quem
         * vem de um player que estava tocando no cartão.
         */
        fun video(messageId: String, mediaKey: String, playFromMs: Long? = null) =
            ViewingMedia(messageId, mediaKey, playing = playFromMs != null, positionMs = playFromMs ?: 0L)

        val Saver: Saver<ViewingMedia?, Any> = Saver(
            save = { viewing -> viewing?.let { listOf(it.messageId, it.openedKey, it.currentKey, it.playing, it.positionMs) } },
            restore = { saved ->
                val parts = saved as? List<*>
                val messageId = parts?.getOrNull(0) as? String
                val openedKey = parts?.getOrNull(1) as? String
                val currentKey = parts?.getOrNull(2) as? String
                val playing = parts?.getOrNull(3) as? Boolean
                val position = parts?.getOrNull(4) as? Long
                if (messageId == null || openedKey == null || currentKey == null || playing == null || position == null) {
                    null
                } else {
                    ViewingMedia(messageId, openedKey, currentKey, playing, position)
                }
            },
        )
    }
}

/**
 * Visualizador de mídia da mensagem [message], em tela cheia: as imagens, GIFs e vídeos dela numa fileira que se desliza,
 * com o contador "2/5". A imagem dá zoom de 1x a 5x (pinça e toque duplo), e a página só vira enquanto não há zoom; o GIF
 * anima. O vídeo mostra a capa e o ▶, que abre o player na própria página: só a página que está na tela tem player, e virar
 * a página o libera. **Salvar** e **Compartilhar** valem para a imagem da página. Se o servidor responde 410 (a moderação
 * bloqueou o anexo), ele é gravado como tal e o visualizador se fecha.
 */
@Composable
internal fun MediaViewer(session: AccountSession, message: Message, viewing: ViewingMedia, onDismiss: () -> Unit) {
    val shown = remember(message, viewing.openedKey) { viewerItems(session.client.server, message, viewing.openedKey) }
    val items = shown.items
    // Nada a mostrar (o anexo foi bloqueado, ou a mensagem perdeu os anexos): o visualizador se fecha.
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val platform = LocalPlatformUi.current
    val scope = rememberCoroutineScope()
    val latestItems by rememberUpdatedState(items)
    val pager = rememberPagerState(initialPage = items.indexOfFirst { it.key == viewing.currentKey }.takeIf { it >= 0 } ?: shown.start) { items.size }
    // A barra de cima acompanha os controles do player: some com eles e deixa o vídeo limpo.
    var controlsVisible by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf<StringResource?>(null) }
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { page ->
            latestItems.getOrNull(page)?.let { viewing.select(it.key) }
            controlsVisible = true
        }
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(NOTICE_MS)
            notice = null
        }
    }
    val page = pager.currentPage.coerceIn(0, items.lastIndex)
    val current = items[page]
    val saveAction = remember(session, message.id, current.key) {
        ViewerAction(scope, onDone = { ok -> notice = if (ok) Res.string.viewer_saved else Res.string.viewer_save_failed }) {
            val attachment = current.attachment
            val bytes = originalBytes(session, message.id, attachment.id)
            bytes != null && platform.saveToDownloads(attachment.fileName, attachment.mimeType, bytes)
        }
    }
    val shareAction = remember(session, message.id, current.key) {
        ViewerAction(scope, onDone = { ok -> if (!ok) notice = Res.string.viewer_share_failed }) {
            val attachment = current.attachment
            val bytes = originalBytes(session, message.id, attachment.id)
            bytes != null && platform.shareMedia(attachment.fileName, attachment.mimeType, bytes)
        }
    }
    ViewerWindow(onDismiss) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { items[it].key }) { index ->
            when (val item = items[index]) {
                is ViewerItem.Image -> MessageImagePage(session, message.id, item.attachment, onGone = onDismiss)
                is ViewerItem.Video -> VideoPage(
                    session = session,
                    messageId = message.id,
                    item = item,
                    onScreen = index == pager.currentPage,
                    viewing = viewing,
                    // Só a página atual manda na barra: o player da página que saiu ainda avisa que os controles sumiram
                    // (ao ser solto) depois da troca, e esconderia a barra de uma imagem, que não tem como trazê-la de volta.
                    onControlsVisible = { visible -> if (index == pager.currentPage) controlsVisible = visible },
                    onDismiss = onDismiss,
                )
            }
        }
        AnimatedVisibility(visible = controlsVisible, modifier = Modifier.align(Alignment.TopStart), enter = fadeIn(), exit = fadeOut()) {
            ViewerTopBar(
                title = current.attachment.fileName,
                counter = if (items.size > 1) stringResource(Res.string.viewer_counter, page + 1, items.size) else null,
                onDismiss = onDismiss,
            ) {
                if (current is ViewerItem.Image) {
                    ActionButton(saveAction, Icons.Filled.Download, Res.string.viewer_save)
                    ActionButton(shareAction, Icons.Filled.Share, Res.string.viewer_share)
                }
            }
        }
        notice?.let { Notice(it, Modifier.align(Alignment.BottomCenter)) }
    }
}

/**
 * A imagem de [file], ainda não enviada, em tela cheia e com zoom: lida da memória (ver [readAtMost]) e reduzida na
 * decodificação, nunca copiada para o disco. Sem **Salvar** nem **Compartilhar**, porque o arquivo é da pessoa e ainda não
 * foi enviado. Um arquivo grande demais para a prévia, que não abre ou que não é uma imagem que o aparelho decodifica
 * mostra o aviso no lugar dela.
 */
@Composable
internal fun PendingImageViewer(file: OutgoingFile, onDismiss: () -> Unit) {
    val platform = LocalPlatformUi.current
    val load = rememberViewerImage(file, null) {
        withContext(Dispatchers.Default) {
            file.readAtMost(PREVIEW_MAX_BYTES)?.let { platform.decodeImage(it, PREVIEW_IMAGE_PX, animate = true) }
        }
    }
    ViewerWindow(onDismiss) {
        ImagePage(load, file.name)
        ViewerTopBar(title = file.name, counter = null, onDismiss = onDismiss, modifier = Modifier.align(Alignment.TopStart))
    }
}

/** A janela do visualizador: tela cheia de verdade, em fundo preto e sem as barras do sistema (que voltam com o gesto da borda). */
@Composable
private fun ViewerWindow(onDismiss: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    val platform = LocalPlatformUi.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        platform.HideSystemBars()
        Box(Modifier.fillMaxSize().background(Color.Black), content = content)
    }
}

@Composable
private fun ViewerTopBar(
    title: String,
    counter: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.45f))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onDismiss) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.action_close), tint = Color.White)
        }
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            if (counter != null) Text(counter, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
        }
        actions()
    }
}

@Composable
private fun ActionButton(action: ViewerAction, icon: ImageVector, label: StringResource) {
    IconButton(onClick = action::start, enabled = !action.busy) {
        if (action.busy) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
        } else {
            Icon(icon, contentDescription = stringResource(label), tint = Color.White)
        }
    }
}

/** O aviso curto do resultado de salvar ou compartilhar, embaixo, sobre o fundo preto. */
@Composable
private fun Notice(text: StringResource, modifier: Modifier = Modifier) {
    Surface(
        modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)).padding(16.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color.White.copy(alpha = 0.9f),
        contentColor = Color.Black,
    ) {
        Text(stringResource(text), Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Uma ação do visualizador que roda uma vez por toque ([busy] enquanto roda) e avisa o resultado a [onDone]; qualquer
 * falha conta como não ter dado certo, inclusive o estouro de memória de um arquivo grande (hoje ele é lido inteiro).
 * Roda em [scope], da composição do visualizador: fechá-lo cancela a ação.
 */
@Stable
private class ViewerAction(
    private val scope: CoroutineScope,
    private val onDone: (Boolean) -> Unit,
    private val run: suspend () -> Boolean,
) {
    var busy by mutableStateOf(false)
        private set

    fun start() {
        if (busy) return
        busy = true
        scope.launch {
            try {
                onDone(done())
            } finally {
                busy = false
            }
        }
    }

    private suspend fun done(): Boolean = try {
        run()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        false
    }
}

/** O original de [attachmentId], do cache cifrado da conta (que o visualizador já encheu) ou do servidor; um 410 bloqueia o anexo. */
private suspend fun originalBytes(session: AccountSession, messageId: String, attachmentId: String): ByteArray? =
    session.media.get(originalName(attachmentId)) {
        session.guardGone(messageId, attachmentId) { session.api.attachment(attachmentId) }
    }

private fun originalName(attachmentId: String) = "att/$attachmentId"

/** Como vai o carregamento de uma imagem do visualizador. */
private sealed interface ImageLoad {
    data object Loading : ImageLoad

    /** Não chegou, ou chegou e não é uma imagem que o aparelho decodifica. */
    data object Failed : ImageLoad

    class Ready(val image: PlatformImage) : ImageLoad
}

/**
 * Carrega com [load] a imagem que muda com [key1] e [key2]. Esta função é a dona da imagem: fecha-a ao trocar ou sair da
 * composição, e só a deixa animar com a tela à vista (para em segundo plano). Quem desenha usa [rememberedPainter].
 */
@Composable
private fun rememberViewerImage(key1: Any?, key2: Any?, load: suspend () -> PlatformImage?): ImageLoad {
    val state = produceState<ImageLoad>(ImageLoad.Loading, key1, key2) {
        value = ImageLoad.Loading
        value = try {
            load()?.let { ImageLoad.Ready(it) } ?: ImageLoad.Failed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ImageLoad.Failed
        }
    }
    val image = (state.value as? ImageLoad.Ready)?.image
    DisposableEffect(image) { onDispose { image?.close() } }
    LifecycleStartEffect(image) {
        image?.start()
        onStopOrDispose { image?.stop() }
    }
    return state.value
}

/** A página de uma imagem da mensagem: o original, até [VIEWER_IMAGE_PX], animado se for um GIF. [onGone] avisa o 410. */
@Composable
private fun MessageImagePage(session: AccountSession, messageId: String, attachment: AttachmentDto, onGone: () -> Unit) {
    val platform = LocalPlatformUi.current
    val load = rememberViewerImage(session.accountId, attachment.id) {
        session.media.get(originalName(attachment.id)) {
            session.guardGone(messageId, attachment.id, onGone = onGone) { session.api.attachment(attachment.id) }
        }?.let { platform.decodeImage(it, VIEWER_IMAGE_PX, animate = true) }
    }
    ImagePage(load, attachment.fileName)
}

@Composable
private fun ImagePage(load: ImageLoad, description: String) {
    when (load) {
        ImageLoad.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
        ImageLoad.Failed -> PageNotice(Icons.Filled.BrokenImage, stringResource(Res.string.viewer_image_failed))
        is ImageLoad.Ready -> ZoomableImage(load.image, description)
    }
}

/**
 * A imagem inteira na página, com pinça de 1x a [MAX_ZOOM]x, toque duplo (alterna 1x e 2,5x no ponto tocado) e arrasto
 * dentro das bordas. Sem zoom o gesto é da fileira de páginas; com zoom é todo da imagem, e a página não vira.
 */
@Composable
private fun ZoomableImage(image: PlatformImage, description: String) {
    val zoom = rememberZoomState(maxScale = MAX_ZOOM, contentSize = Size(image.width.toFloat(), image.height.toFloat()))
    Image(
        image.rememberedPainter(),
        contentDescription = description,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize().zoomable(zoom, scrollGesturePropagation = ScrollGesturePropagation.NotZoomed),
    )
}

/**
 * A página de um vídeo anexado: a capa com o ▶ ou, depois de tocar nele, o player. Só a página que está na tela
 * ([onScreen]) e que a pessoa mandou tocar ([ViewingMedia.playing]) tem player; sair dela o libera.
 */
@Composable
private fun VideoPage(
    session: AccountSession,
    messageId: String,
    item: ViewerItem.Video,
    onScreen: Boolean,
    viewing: ViewingMedia,
    onControlsVisible: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    if (onScreen && viewing.playing && viewing.currentKey == item.key) {
        VideoPlayback(session, messageId, item, viewing, onControlsVisible, onDismiss)
    } else {
        VideoCover(session, messageId, item.attachment, item.media.url, onPlay = { viewing.playing = true })
    }
}

/** A capa do vídeo, inteira, com o ▶ no meio: a miniatura do servidor, ou o quadro que o aparelho já extraiu para o cartão. */
@Composable
private fun VideoCover(session: AccountSession, messageId: String, attachment: AttachmentDto, url: String, onPlay: () -> Unit) {
    val source = posterSource(attachment, revealed = true)
    val poster by rememberMediaImage(
        session,
        when (source) {
            PosterSource.Server -> "att-thumb/${attachment.id}"
            PosterSource.Extract -> posterKey(session.accountId, attachment.id)
            PosterSource.None -> null
        },
    ) {
        when (source) {
            PosterSource.Server -> session.guardGone(messageId, attachment.id) { session.api.attachmentThumbnail(attachment.id) }
            PosterSource.Extract -> session.videoPosters.poster(attachment.id, url, attachment.sizeBytes)
            PosterSource.None -> null
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        poster?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        val label = stringResource(Res.string.video_play)
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).clickable(role = Role.Button, onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = label, tint = Color.White, modifier = Modifier.size(40.dp))
        }
    }
}

/**
 * O player do vídeo da página, que fala com o servidor pelo `PapoClient` da [session] dona da mensagem. Se o player desiste,
 * o painel diz o motivo e oferece o que ainda adianta (ver [failureActions]); **Tentar de novo** recria o player no último
 * ponto conhecido. Um 410 bloqueia o anexo e fecha o visualizador.
 */
@Composable
private fun VideoPlayback(
    session: AccountSession,
    messageId: String,
    item: ViewerItem.Video,
    viewing: ViewingMedia,
    onControlsVisible: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val platform = LocalPlatformUi.current
    val attachment = item.attachment
    val download = rememberAttachmentDownload(session, attachment)
    var attempt by remember { mutableIntStateOf(0) }
    var failure by remember { mutableStateOf<PlaybackFailure?>(null) }
    LaunchedEffect(failure) {
        if (failure == PlaybackFailure.Gone) {
            session.chat.markBlocked(messageId, attachment.id)
            onDismiss()
        }
    }
    // Enquanto o vídeo está aberto as capas dos cartões esperam: o player e elas disputariam o limite de pedidos.
    DisposableEffect(session) {
        session.videoPosters.playerOpened()
        onDispose { session.videoPosters.playerClosed() }
    }
    val failed = failure
    if (failed == null) {
        // O ponto de partida é fixado por tentativa: mudá-lo durante a reprodução não pode reiniciar o player.
        val startAt = remember(attempt) { viewing.positionMs }
        key(attempt) {
            platform.VideoPlayer(
                client = session.client,
                media = item.media,
                startAtMs = startAt,
                modifier = Modifier.fillMaxSize(),
                onPosition = { viewing.positionMs = it },
                onFailure = {
                    failure = it
                    onControlsVisible(true)
                },
                onControlsVisible = onControlsVisible,
            )
        }
    } else {
        FailurePage(
            message = failureMessage(failed, embed = false),
            actions = failureActions(failed, canDownload = true),
            download = download,
            onRetry = {
                failure = null
                attempt++
            },
        )
    }
}

/** O motivo de o vídeo não tocar e o que ainda adianta: tentar de novo e baixar (o vídeo anexado sempre tem arquivo). */
@Composable
private fun FailurePage(message: StringResource, actions: FailureActions, download: AttachmentDownload, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(message), style = MaterialTheme.typography.bodyLarge, color = Color.White, textAlign = TextAlign.Center)
        if (actions.retry || actions.download) {
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (actions.retry) {
                    Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) {
                        Text(stringResource(Res.string.action_retry))
                    }
                }
                if (actions.download) {
                    OutlinedButton(
                        onClick = download::start,
                        enabled = !download.downloading,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White, disabledContentColor = Color.White.copy(alpha = 0.6f)),
                    ) {
                        if (download.downloading) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        } else {
                            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.msg_download))
                    }
                }
            }
            if (download.failed) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(Res.string.video_download_failed),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFFFFB4AB),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Um aviso no meio da página (a imagem não abriu), com o ícone e o texto em branco sobre o fundo preto. */
@Composable
private fun PageNotice(icon: ImageVector, text: String) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Color.White, textAlign = TextAlign.Center)
    }
}
