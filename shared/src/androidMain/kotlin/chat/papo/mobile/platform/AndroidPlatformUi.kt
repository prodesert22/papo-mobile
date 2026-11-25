package chat.papo.mobile.platform

import android.content.ContentValues
import android.content.Context
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidPlatformUi(context: Context) : PlatformUi {
    private val app = context.applicationContext
    private val images = AndroidImages(app)

    // Paint não é seguro entre threads; as consultas são curtas, então um cadeado basta.
    private val glyphPaint = Paint()

    override fun supportsEmoji(emoji: String): Boolean = synchronized(glyphPaint) { glyphPaint.hasGlyph(emoji) }

    @Composable
    override fun rememberFilePicker(onPicked: (List<OutgoingFile>) -> Unit): () -> Unit {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isNotEmpty()) onPicked(uris.map(::describe))
        }
        return { launcher.launch("*/*") }
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

    // Vazio até a trilha do player (ExoPlayer) entrar; quem chama já pode compor a área do vídeo.
    @Composable
    override fun VideoPlayer(
        client: PapoClient,
        media: PlayableMedia,
        startAtMs: Long,
        modifier: Modifier,
        onPosition: (Long) -> Unit,
        onFailure: (PlaybackFailure) -> Unit,
    ) {
        Box(modifier)
    }

    private fun describe(uri: Uri): OutgoingFile {
        var name = uri.lastPathSegment ?: "arquivo"
        var size = 0L
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0)?.let { name = it }
                if (!cursor.isNull(1)) size = cursor.getLong(1)
            }
        }
        val mime = app.contentResolver.getType(uri) ?: "application/octet-stream"
        // O conteúdo só é lido na hora do envio, direto do provedor, sem cópia em disco.
        return OutgoingFile(name, mime, size) {
            app.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
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
}
