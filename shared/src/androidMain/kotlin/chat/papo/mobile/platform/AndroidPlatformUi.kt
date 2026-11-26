package chat.papo.mobile.platform

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Size
import android.view.Gravity
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import chat.papo.mobile.chat.nameFor
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.media.PlayableMedia
import chat.papo.mobile.media.PlaybackFailure
import chat.papo.mobile.voice.VideoTrackHandle
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import chat.papo.mobile.data.api.OutgoingFile
import chat.papo.mobile.ui.components.PlatformImage
import chat.papo.mobile.ui.components.PlatformUi
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import kotlin.time.Duration.Companion.seconds

class AndroidPlatformUi(context: Context) : PlatformUi {
    private val app = context.applicationContext

    // Quem abre a folha de compartilhamento: com a atividade ela entra na pilha do app, sem a atividade precisa de uma pilha nova.
    private val host = context
    private val images = AndroidImages(app)
    private val receivedMedia = ReceivedMedia(app, images)

    // Paint não é seguro entre threads; as consultas são curtas, então um cadeado basta.
    private val glyphPaint = Paint()

    override fun supportsEmoji(emoji: String): Boolean = synchronized(glyphPaint) { glyphPaint.hasGlyph(emoji) }

    @Composable
    override fun rememberFilePicker(onPicked: (List<OutgoingFile>) -> Unit): () -> Unit {
        val describeAll = rememberDescribeAll(onPicked)
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents(), describeAll)
        return { launcher.launch("*/*") }
    }

    @Composable
    override fun rememberMediaPicker(maxItems: Int, onPicked: (List<OutgoingFile>) -> Unit): () -> Unit {
        val describeAll = rememberDescribeAll(onPicked)
        // O contrato só aceita mais de um item; com uma vaga só o seletor deixa escolher dois e a tela recusa o que sobra.
        val contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems.coerceAtLeast(2))
        val launcher = rememberLauncherForActivityResult(contract, describeAll)
        return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
    }

    /** Descreve o que o seletor devolveu fora da thread principal (o provedor pode ser lento) e entrega a lista a [onPicked]. */
    @Composable
    private fun rememberDescribeAll(onPicked: (List<OutgoingFile>) -> Unit): (List<Uri>) -> Unit {
        val scope = rememberCoroutineScope()
        val latest by rememberUpdatedState(onPicked)
        return { uris ->
            if (uris.isNotEmpty()) scope.launch { latest(withContext(Dispatchers.IO) { uris.map(::describe) }) }
        }
    }

    @Composable
    override fun VideoView(track: VideoTrackHandle, modifier: Modifier, fill: Boolean) {
        val video = track.native as? VideoTrack ?: return
        // A chave recria a superfície quando a faixa muda: um renderer não troca de fonte com segurança.
        key(video) {
            AndroidView(
                modifier = modifier,
                factory = { context ->
                    val renderer = SurfaceViewRenderer(context).apply {
                        init(WebRtcRuntime.egl.eglBaseContext, null)
                        setEnableHardwareScaler(true)
                        setScalingType(if (fill) RendererCommon.ScalingType.SCALE_ASPECT_FILL else RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                        setMirror(track.mirrored)
                        runCatching { video.addSink(this) }
                    }
                    // Com tamanho fixo o renderer sempre recorta o quadro. Para mostrar o vídeo
                    // inteiro ele precisa poder se medir pela proporção do vídeo, dentro de um contêiner.
                    val size = if (fill) FrameLayout.LayoutParams.MATCH_PARENT else FrameLayout.LayoutParams.WRAP_CONTENT
                    FrameLayout(context).apply { addView(renderer, FrameLayout.LayoutParams(size, size, Gravity.CENTER)) }
                },
                update = { container -> (container.getChildAt(0) as SurfaceViewRenderer).setMirror(track.mirrored) },
                onRelease = { container ->
                    val renderer = container.getChildAt(0) as SurfaceViewRenderer
                    // A faixa pode já ter sido liberada pelo motor quando a tela sai.
                    runCatching { video.removeSink(renderer) }
                    renderer.release()
                },
            )
        }
    }

    @Composable
    override fun VideoPlayer(
        client: PapoClient,
        media: PlayableMedia,
        startAtMs: Long,
        modifier: Modifier,
        onPosition: (Long) -> Unit,
        onFailure: (PlaybackFailure) -> Unit,
        onControlsVisible: (Boolean) -> Unit,
        onFullscreen: (() -> Unit)?,
    ) = AndroidVideoPlayer(client, media, startAtMs, modifier, onPosition, onFailure, onControlsVisible, onFullscreen)

    @Composable
    override fun HideSystemBars() = HideSystemBarsInDialog()

    @Composable
    override fun YouTubePlayer(videoId: String, modifier: Modifier, fullscreenButton: Boolean, onOpenExternally: () -> Unit) =
        AndroidYouTubePlayer(videoId, modifier, fullscreenButton, onOpenExternally)

    /**
     * Descreve o arquivo sem ler o conteúdo: nome, tipo e tamanho vêm do provedor. Abrir o arquivo uma vez já aqui
     * mostra o que sumiu ou o acesso que o sistema não deu (vira [OutgoingFile.unavailable]) e dá o tamanho exato.
     * O conteúdo só é lido no envio, direto do provedor, sem cópia em disco.
     */
    private fun describe(uri: Uri): OutgoingFile {
        val resolver = app.contentResolver
        var displayName: String? = null
        var size: Long? = null
        var mime: String? = null
        var unavailable = false
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) displayName = cursor.getString(0)
            }
            mime = resolver.getType(uri)
            val descriptor = resolver.openAssetFileDescriptor(uri, "r")
            if (descriptor == null) unavailable = true
            // Tamanho desconhecido (-1) ou zero: o provedor não sabe, e declarar o que não é lido estragaria o corpo.
            descriptor?.use { size = it.length.takeIf { length -> length > 0 } }
        } catch (e: Exception) {
            // Sem permissão (SecurityException), arquivo que sumiu (FileNotFoundException) ou provedor que se recusa: o
            // cartão avisa e o usuário decide, em vez de o envio descobrir depois.
            unavailable = true
        }
        val type = mime ?: "application/octet-stream"
        val name = nameFor(displayName, type)
        return OutgoingFile(
            name = name,
            mimeType = type,
            size = size,
            open = { openStream(uri, name) },
            preview = { maxPx -> thumbnail(uri, maxPx) },
            unavailable = unavailable,
        )
    }

    private fun openStream(uri: Uri, name: String): ByteReadChannel {
        val stream = app.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("o provedor não abriu o arquivo")
        return GuardedFileStream(stream, name).toByteReadChannel()
    }

    /**
     * A miniatura que o sistema gera (reduzida na leitura: a foto inteira nunca vai para a memória), ou null se o
     * provedor não sabe fazê-la. Um quadro só: um GIF aparece parado, e o selo diz o que ele é. Se o chamador cancela,
     * a leitura em curso termina e o resultado é descartado.
     */
    private suspend fun thumbnail(uri: Uri, maxPx: Int): PlatformImage? = withContext(Dispatchers.IO) {
        try {
            StillImage(app.contentResolver.loadThumbnail(uri, Size(maxPx, maxPx), null))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // sem prévia: o cartão mostra o ícone do tipo
        }
    }

    override suspend fun saveToDownloads(name: String, mimeType: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = app.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return@withContext false
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            true
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            false
        }
    }

    override suspend fun decodeImage(bytes: ByteArray, maxPx: Int, animate: Boolean): PlatformImage? = images.decode(bytes, maxPx, animate)

    override fun shareMedia(name: String, mimeType: String, bytes: ByteArray): Boolean {
        val type = SharedMedia.sendType(mimeType)
        val token = SharedMedia.store.put(name, type, bytes)
        // O depósito só se limpa quando alguém o usa; sem isto os bytes de um compartilhamento esquecido ficariam na memória.
        Handler(Looper.getMainLooper()).postDelayed({ SharedMedia.store.purge() }, (SharedMediaStore.LIFETIME + 1.seconds).inWholeMilliseconds)
        val uri = SharedMedia.uri(app, token, name)
        val chooser = Intent.createChooser(SharedMedia.sendIntent(uri, name, type), null)
        if (host !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            host.startActivity(chooser)
            true
        } catch (e: ActivityNotFoundException) {
            SharedMedia.store.discard(token)
            false
        }
    }

    override fun receiveMedia(onMedia: (List<OutgoingFile>) -> Unit): Modifier = receivedMedia.modifier(onMedia)
}

/**
 * Esconde as barras do sistema na janela do diálogo que contém a composição (tela cheia de verdade), com o gesto de
 * deslizar da borda para vê-las de passagem. Fora de um diálogo não faz nada: a janela do app é da tela de conversa.
 */
@Composable
internal fun HideSystemBarsInDialog() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window == null) return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
