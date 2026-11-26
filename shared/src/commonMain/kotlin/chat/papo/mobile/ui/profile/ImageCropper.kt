package chat.papo.mobile.ui.profile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import chat.papo.mobile.media.CropGeometry
import chat.papo.mobile.media.CropRequest
import chat.papo.mobile.media.CropState
import chat.papo.mobile.media.cropFrameSize
import chat.papo.mobile.media.sliderToZoom
import chat.papo.mobile.media.zoomToSlider
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.crop_area
import chat.papo.mobile.resources.crop_cancel
import chat.papo.mobile.resources.crop_confirm
import chat.papo.mobile.resources.crop_reset
import chat.papo.mobile.resources.crop_rotate
import chat.papo.mobile.resources.crop_title
import chat.papo.mobile.resources.crop_zoom
import chat.papo.mobile.resources.crop_zoom_percent
import chat.papo.mobile.resources.profile_edit_refused_not_an_image
import chat.papo.mobile.ui.components.LocalPlatformUi
import chat.papo.mobile.ui.components.PlatformImage
import org.jetbrains.compose.resources.stringResource

/** O maior lado da imagem que a tela de recorte mantém decodificada: o bastante para o zoom máximo sem estourar a memória. */
private const val CROP_PREVIEW_PX = 2048

private val FrameMargin = 24.dp
private val FrameBorder = 1.5.dp
private val GridLine = 1.dp
private val PanelColor = Color(0xFF1E1F22)
private val ScrimColor = Color.Black.copy(alpha = 0.65f)

/**
 * A tela "Editar imagem": recorte manual do avatar ou do banner, em tela cheia sobre a tela de edição. A moldura tem a
 * proporção do tipo de [pending] e fica parada; a imagem anda sob ela (um dedo move, a pinça dá zoom) e o controle de
 * baixo dá zoom, gira 90 graus ou volta ao enquadramento inicial. O fora da moldura aparece escurecido. O voltar do sistema
 * cancela ([onCancel]); o visto entrega a região escolhida a [onConfirm], e quem recorta de fato é o `ImageReducer`.
 *
 * Escrito aqui e não com uma biblioteca (regra 13): as de recorte para Compose que existem (Krop, easycrop, ImageCrop,
 * icorp) ou dependem do Coil, que guarda cache em disco sem cifra, ou são só Android, ou trazem o próprio decodificador e
 * a própria tela; nenhuma usa o decodificador em memória do app nem dá este desenho. A parte difícil, a conta do
 * enquadramento, é pura e fica em `CropGeometry`.
 *
 * A imagem mostrada vem de [chat.papo.mobile.ui.components.PlatformUi.decodeImage] reduzida a [CROP_PREVIEW_PX] e é fechada
 * ao sair. O giro é só desenho; a geometria usa as dimensões já giradas.
 */
@Composable
fun ImageCropper(pending: PendingCrop, onConfirm: (CropRequest) -> Unit, onCancel: () -> Unit) {
    val platform = LocalPlatformUi.current
    val preview = produceState<CropPreview>(CropPreview.Loading, pending) {
        value = CropPreview.Loading
        value = platform.decodeImage(pending.bytes, CROP_PREVIEW_PX, animate = false)?.let(CropPreview::Ready) ?: CropPreview.Failed
    }.value
    DisposableEffect(preview) { onDispose { (preview as? CropPreview.Ready)?.image?.close() } }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        platform.HideSystemBars()
        CropperContent(pending, preview, onConfirm, onCancel)
    }
}

/** A imagem da tela de recorte: decodificando, pronta (e então com dono) ou que o aparelho não abriu. */
private sealed interface CropPreview {
    data object Loading : CropPreview

    class Ready(val image: PlatformImage) : CropPreview

    data object Failed : CropPreview
}

/** Só quatro números: sobrevivem a uma mudança de configuração sem guardar a imagem. */
private val CropStateSaver = listSaver<CropState, Any>(
    save = { listOf(it.quarterTurns, it.zoom, it.centerX, it.centerY) },
    restore = { CropState(it[0] as Int, it[1] as Float, it[2] as Float, it[3] as Float) },
)

@Composable
private fun CropperContent(pending: PendingCrop, preview: CropPreview, onConfirm: (CropRequest) -> Unit, onCancel: () -> Unit) {
    var state by rememberSaveable(pending, stateSaver = CropStateSaver) { mutableStateOf(CropState()) }
    val density = LocalDensity.current
    var area by remember { mutableStateOf(IntSize.Zero) }
    // A geometria só existe depois de a área ser medida; antes disso os controles ficam desligados.
    val frame = remember(area, pending, density) {
        cropFrameSize(area.width.toFloat(), area.height.toFloat(), pending.kind.aspectWidth, pending.kind.aspectHeight, with(density) { FrameMargin.toPx() })
    }
    val geometry = remember(frame, pending) { frame?.let { CropGeometry(pending.width, pending.height, it.width, it.height) } }
    val enabled = preview is CropPreview.Ready && geometry != null

    Column(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
        CropperTopBar(
            enabled = enabled,
            onCancel = onCancel,
            onConfirm = { geometry?.let { onConfirm(it.request(state)) } },
        )
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().onSizeChanged { area = it }) {
            when {
                preview is CropPreview.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
                preview is CropPreview.Failed -> Text(
                    stringResource(Res.string.profile_edit_refused_not_an_image),
                    Modifier.align(Alignment.Center).padding(24.dp),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                geometry != null && frame != null && preview is CropPreview.Ready -> {
                    val areaDescription = stringResource(Res.string.crop_area)
                    val minScale = geometry.minScale(state.quarterTurns.mod(4))
                    Box(
                        Modifier
                            .fillMaxSize()
                            .semantics { contentDescription = areaDescription }
                            .pointerInput(geometry) {
                                detectTransformGestures { centroid, pan, zoom, _ ->
                                    // A moldura fica no centro da área; o foco é medido a partir dela.
                                    state = geometry.transform(state, pan.x, pan.y, zoom, centroid.x - size.width / 2f, centroid.y - size.height / 2f)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        // A imagem ocupa o tamanho do zoom mínimo; o zoom, o giro e o deslocamento são do desenho.
                        Image(
                            painter = preview.image.painter,
                            contentDescription = null,
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier
                                .requiredSize(
                                    with(density) { (pending.width * minScale).toDp() },
                                    with(density) { (pending.height * minScale).toDp() },
                                )
                                .graphicsLayer {
                                    val shown = geometry.clamp(state)
                                    val scale = geometry.scale(shown)
                                    scaleX = shown.zoom
                                    scaleY = shown.zoom
                                    rotationZ = 90f * shown.quarterTurns
                                    translationX = (0.5f - shown.centerX) * geometry.rotatedWidth(shown.quarterTurns) * scale
                                    translationY = (0.5f - shown.centerY) * geometry.rotatedHeight(shown.quarterTurns) * scale
                                },
                        )
                    }
                    CropFrame(frame.width, frame.height)
                }
            }
        }
        CropperPanel(
            state = state,
            enabled = enabled,
            onZoom = { value -> geometry?.let { state = it.withZoom(state, sliderToZoom(value)) } },
            onRotate = { geometry?.let { state = it.rotate(state) } },
            onReset = { geometry?.let { state = it.reset(state) } },
        )
    }
}

/** X, o título e o visto. */
@Composable
private fun CropperTopBar(enabled: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onCancel) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.crop_cancel), tint = Color.White)
        }
        Text(
            stringResource(Res.string.crop_title),
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = onConfirm, enabled = enabled) {
            Icon(
                Icons.Filled.Check,
                contentDescription = stringResource(Res.string.crop_confirm),
                tint = if (enabled) Color.White else Color.White.copy(alpha = 0.4f),
            )
        }
    }
}

/** O escurecimento fora da moldura, a borda branca fina e a grade de terços 3x3, centrados na área. */
@Composable
private fun CropFrame(frameWidth: Float, frameHeight: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val left = (size.width - frameWidth) / 2f
        val top = (size.height - frameHeight) / 2f
        val right = left + frameWidth
        val bottom = top + frameHeight
        drawRect(ScrimColor, Offset.Zero, Size(size.width, top))
        drawRect(ScrimColor, Offset(0f, bottom), Size(size.width, size.height - bottom))
        drawRect(ScrimColor, Offset(0f, top), Size(left, frameHeight))
        drawRect(ScrimColor, Offset(right, top), Size(size.width - right, frameHeight))
        val grid = Color.White.copy(alpha = 0.5f)
        for (step in 1..2) {
            val x = left + frameWidth * step / 3f
            val y = top + frameHeight * step / 3f
            drawLine(grid, Offset(x, top), Offset(x, bottom), strokeWidth = GridLine.toPx())
            drawLine(grid, Offset(left, y), Offset(right, y), strokeWidth = GridLine.toPx())
        }
        drawRect(Color.White, Offset(left, top), Size(frameWidth, frameHeight), style = Stroke(FrameBorder.toPx()))
    }
}

/** O painel de baixo: a porcentagem e o controle de zoom, o botão de girar e o "Tamanho". */
@Composable
private fun CropperPanel(state: CropState, enabled: Boolean, onZoom: (Float) -> Unit, onRotate: () -> Unit, onReset: () -> Unit) {
    val zoomLabel = stringResource(Res.string.crop_zoom)
    Column(Modifier.fillMaxWidth().background(PanelColor).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.crop_zoom_percent, state.percent),
                Modifier.width(64.dp),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Slider(
                value = zoomToSlider(state.zoom),
                onValueChange = onZoom,
                modifier = Modifier.weight(1f).semantics { contentDescription = zoomLabel },
                enabled = enabled,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                    disabledThumbColor = Color.White.copy(alpha = 0.4f),
                    disabledActiveTrackColor = Color.White.copy(alpha = 0.4f),
                    disabledInactiveTrackColor = Color.White.copy(alpha = 0.2f),
                ),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onRotate, enabled = enabled) {
                Icon(
                    Icons.Filled.Rotate90DegreesCw,
                    contentDescription = stringResource(Res.string.crop_rotate),
                    tint = if (enabled) Color.White else Color.White.copy(alpha = 0.4f),
                )
            }
            TextButton(onClick = onReset, enabled = enabled) {
                Icon(Icons.Filled.AspectRatio, contentDescription = null, tint = if (enabled) Color.White else Color.White.copy(alpha = 0.4f))
                Text(stringResource(Res.string.crop_reset), Modifier.padding(start = 8.dp), color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f))
            }
        }
    }
}
