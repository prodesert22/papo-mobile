package chat.papo.mobile.media

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** O maior zoom da tela de recorte, em relação ao mínimo (100%): 800%. */
const val CROP_MAX_ZOOM = 8f

/**
 * O enquadramento do recorte. [zoom] vale 1 quando a imagem cobre a moldura da forma mais justa (100%) e [CROP_MAX_ZOOM] no
 * máximo; [centerX] e [centerY] são o ponto da imagem girada (0 a 1) que fica no centro da moldura. Como nada aqui depende
 * do tamanho da tela, o enquadramento sobrevive a uma mudança de layout.
 */
data class CropState(
    val quarterTurns: Int = 0,
    val zoom: Float = 1f,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
) {
    /** O zoom como a tela mostra: 100% é a imagem cobrindo a moldura. */
    val percent: Int get() = (zoom * 100f).roundToInt()
}

/** O tamanho da moldura de corte, em pixels da tela. */
data class FrameSize(val width: Float, val height: Float)

/**
 * O maior retângulo de proporção [aspectWidth]:[aspectHeight] que cabe numa área de [areaWidth] x [areaHeight] com
 * [margin] livre em volta; null se não sobra espaço.
 */
fun cropFrameSize(areaWidth: Float, areaHeight: Float, aspectWidth: Int, aspectHeight: Int, margin: Float): FrameSize? {
    val availableWidth = areaWidth - 2 * margin
    val availableHeight = areaHeight - 2 * margin
    if (availableWidth <= 0f || availableHeight <= 0f || aspectWidth <= 0 || aspectHeight <= 0) return null
    val ratio = aspectWidth.toFloat() / aspectHeight
    return if (availableWidth / availableHeight <= ratio) {
        FrameSize(availableWidth, availableWidth / ratio)
    } else {
        FrameSize(availableHeight * ratio, availableHeight)
    }
}

/** A posição do controle de zoom (0 a 1) para [zoom]. A escala é logarítmica: cada passo do controle dá a mesma ampliação. */
fun zoomToSlider(zoom: Float): Float = (ln(zoom.coerceIn(1f, CROP_MAX_ZOOM)) / ln(CROP_MAX_ZOOM)).coerceIn(0f, 1f)

/** O zoom para a posição [slider] (0 a 1) do controle; o inverso de [zoomToSlider]. */
fun sliderToZoom(slider: Float): Float = exp(slider.coerceIn(0f, 1f) * ln(CROP_MAX_ZOOM)).coerceIn(1f, CROP_MAX_ZOOM)

/**
 * A geometria do recorte, sem tela nem plataforma. A imagem tem [imageWidth] x [imageHeight] já na orientação certa (a do
 * EXIF aplicada); a moldura tem [frameWidth] x [frameHeight] pixels na tela. Toda operação devolve um [CropState] válido:
 * zoom entre 100% e [CROP_MAX_ZOOM] e a moldura sempre inteira dentro da imagem, sem nunca mostrar fundo.
 */
class CropGeometry(val imageWidth: Int, val imageHeight: Int, val frameWidth: Float, val frameHeight: Float) {
    init {
        require(imageWidth > 0 && imageHeight > 0) { "imagem sem pixels" }
        require(frameWidth > 0f && frameHeight > 0f) { "moldura sem área" }
    }

    /** A largura da imagem depois de [quarterTurns] voltas: a altura se o número de voltas é ímpar. */
    fun rotatedWidth(quarterTurns: Int): Int = if ((quarterTurns and 1) == 0) imageWidth else imageHeight

    fun rotatedHeight(quarterTurns: Int): Int = if ((quarterTurns and 1) == 0) imageHeight else imageWidth

    /** Pixels da tela por pixel da imagem a 100%: o menor que ainda cobre a moldura nas duas direções. */
    fun minScale(quarterTurns: Int): Float =
        max(frameWidth / rotatedWidth(quarterTurns), frameHeight / rotatedHeight(quarterTurns))

    /** Pixels da tela por pixel da imagem em [state]. */
    fun scale(state: CropState): Float = minScale(state.quarterTurns) * state.zoom

    /** O enquadramento inicial depois de [quarterTurns] voltas: 100%, no centro. */
    fun initial(quarterTurns: Int = 0): CropState = CropState(quarterTurns = quarterTurns.mod(4))

    /** O mais próximo de [state] que é válido: voltas de 0 a 3, zoom dentro dos limites e a moldura dentro da imagem. */
    fun clamp(state: CropState): CropState {
        val turns = state.quarterTurns.mod(4)
        val zoom = state.zoom.coerceIn(1f, CROP_MAX_ZOOM)
        val scale = minScale(turns) * zoom
        // No zoom mínimo uma das direções cobre a imagem toda (meia largura = 0,5), então o centro nela fica fixo.
        val halfWidth = (frameWidth / (scale * rotatedWidth(turns)) / 2f).coerceAtMost(0.5f)
        val halfHeight = (frameHeight / (scale * rotatedHeight(turns)) / 2f).coerceAtMost(0.5f)
        return CropState(
            quarterTurns = turns,
            zoom = zoom,
            centerX = state.centerX.coerceIn(halfWidth, 1f - halfWidth),
            centerY = state.centerY.coerceIn(halfHeight, 1f - halfHeight),
        )
    }

    /**
     * Um gesto: o dedo anda ([panX], [panY]) pixels, multiplica o zoom por [zoomFactor] e está em ([focusX], [focusY])
     * pixels do centro da moldura. O ponto da imagem que estava sob o dedo continua sob ele.
     */
    fun transform(state: CropState, panX: Float, panY: Float, zoomFactor: Float, focusX: Float, focusY: Float): CropState {
        val from = clamp(state)
        val zoom = (from.zoom * zoomFactor).coerceIn(1f, CROP_MAX_ZOOM)
        val oldScale = scale(from)
        val newScale = minScale(from.quarterTurns) * zoom
        val width = rotatedWidth(from.quarterTurns)
        val height = rotatedHeight(from.quarterTurns)
        return clamp(
            CropState(
                quarterTurns = from.quarterTurns,
                zoom = zoom,
                centerX = from.centerX + focusX / (oldScale * width) - (focusX + panX) / (newScale * width),
                centerY = from.centerY + focusY / (oldScale * height) - (focusY + panY) / (newScale * height),
            ),
        )
    }

    /** Muda o zoom para [zoom] em torno do centro da moldura (o controle de zoom). */
    fun withZoom(state: CropState, zoom: Float): CropState {
        val from = clamp(state)
        return transform(from, 0f, 0f, zoom / from.zoom, 0f, 0f)
    }

    /**
     * Gira a imagem 90 graus no sentido horário. O ponto que estava no centro da moldura continua nele (se o zoom
     * permite) e o zoom é mantido em relação ao novo mínimo.
     */
    fun rotate(state: CropState): CropState {
        val from = clamp(state)
        return clamp(
            CropState(
                quarterTurns = (from.quarterTurns + 1).mod(4),
                zoom = from.zoom,
                centerX = 1f - from.centerY,
                centerY = from.centerX,
            ),
        )
    }

    /** "Tamanho": volta o zoom e a posição ao enquadramento inicial, mantendo o giro. */
    fun reset(state: CropState): CropState = initial(state.quarterTurns)

    /** A região que [state] mostra, para o redutor. */
    fun request(state: CropState): CropRequest {
        val valid = clamp(state)
        val scale = scale(valid)
        val width = min(1f, frameWidth / (scale * rotatedWidth(valid.quarterTurns)))
        val height = min(1f, frameHeight / (scale * rotatedHeight(valid.quarterTurns)))
        return CropRequest(
            quarterTurns = valid.quarterTurns,
            left = (valid.centerX - width / 2f).coerceIn(0f, 1f - width),
            top = (valid.centerY - height / 2f).coerceIn(0f, 1f - height),
            width = width,
            height = height,
        )
    }
}

/**
 * O recorte de [crop] em pixels da imagem de [width] x [height] (a orientada pelo EXIF, antes de girar) para [kind]. A
 * região é ajustada à proporção do tipo em volta do seu centro e fica dentro da imagem; reduz até o tamanho final se é
 * maior e nunca amplia (como [planCrop]). O retângulo e o tamanho final saem na orientação de [width] x [height]: com um
 * número ímpar de voltas largura e altura finais estão trocadas, e quem decodifica gira o resultado depois. Null se a
 * imagem não tem pixels, a região não é válida ou a imagem é pequena demais para a proporção.
 */
fun planManualCrop(width: Int, height: Int, kind: ProfileImageKind, crop: CropRequest): CropPlan? {
    if (width <= 0 || height <= 0 || !crop.isValid()) return null
    val turns = crop.quarterTurns.mod(4)
    val rotatedWidth = if ((turns and 1) == 0) width else height
    val rotatedHeight = if ((turns and 1) == 0) height else width
    val unit = min(
        min(crop.width * rotatedWidth / kind.aspectWidth, crop.height * rotatedHeight / kind.aspectHeight).roundToInt(),
        min(rotatedWidth / kind.aspectWidth, rotatedHeight / kind.aspectHeight),
    ).coerceAtLeast(1)
    val cropWidth = unit * kind.aspectWidth
    val cropHeight = unit * kind.aspectHeight
    if (cropWidth > rotatedWidth || cropHeight > rotatedHeight) return null
    val left = ((crop.left + crop.width / 2f) * rotatedWidth - cropWidth / 2f).roundToInt().coerceIn(0, rotatedWidth - cropWidth)
    val top = ((crop.top + crop.height / 2f) * rotatedHeight - cropHeight / 2f).roundToInt().coerceIn(0, rotatedHeight - cropHeight)
    val (outWidth, outHeight) = if (cropWidth > kind.outWidth) kind.outWidth to kind.outHeight else cropWidth to cropHeight
    // O retângulo da imagem girada volta para a imagem de origem desfazendo as voltas.
    return when (turns) {
        0 -> CropPlan(left, top, cropWidth, cropHeight, outWidth, outHeight)
        1 -> CropPlan(top, height - left - cropWidth, cropHeight, cropWidth, outHeight, outWidth)
        2 -> CropPlan(width - left - cropWidth, height - top - cropHeight, cropWidth, cropHeight, outWidth, outHeight)
        else -> CropPlan(width - top - cropHeight, left, cropHeight, cropWidth, outHeight, outWidth)
    }
}

private fun CropRequest.isValid(): Boolean =
    left.isFinite() && top.isFinite() && width.isFinite() && height.isFinite() &&
        left >= 0f && top >= 0f && width > 0f && height > 0f && left + width <= 1f + EDGE_TOLERANCE && top + height <= 1f + EDGE_TOLERANCE

/** O erro de ponto flutuante que o cálculo da região pode deixar passar de 1. */
private const val EDGE_TOLERANCE = 1e-4f
