package chat.papo.mobile.media

import kotlin.math.roundToInt

/** O maior tamanho que o servidor aceita para um avatar ou um banner (2 MiB, já decodificado do base64). */
const val PROFILE_IMAGE_MAX_BYTES = 2 * 1024 * 1024

/**
 * As imagens do perfil. O recorte é sempre central e na proporção [aspectWidth]:[aspectHeight]; a imagem final tem no
 * máximo [outWidth] x [outHeight] (nunca é ampliada). [maxSide] é o maior lado que o servidor aceita: só vale para uma
 * animação, que vai como está porque recodificá-la perderia os quadros.
 */
enum class ProfileImageKind(val aspectWidth: Int, val aspectHeight: Int, val outWidth: Int, val outHeight: Int, val maxSide: Int) {
    Avatar(aspectWidth = 1, aspectHeight = 1, outWidth = 512, outHeight = 512, maxSide = 512),

    /** O servidor documenta 2048 no corpo e 512 no resumo da rota; 1536 x 512 cabe nas duas leituras de altura e é o que o web envia. */
    Banner(aspectWidth = 3, aspectHeight = 1, outWidth = 1536, outHeight = 512, maxSide = 2048),
}

/** O retângulo da imagem de origem que vira o perfil ([left], [top], [width], [height]) e o tamanho final dele. */
data class CropPlan(val left: Int, val top: Int, val width: Int, val height: Int, val outWidth: Int, val outHeight: Int)

/**
 * O recorte central de uma imagem de [width] x [height] para [kind]: o maior retângulo da proporção do tipo que cabe
 * na imagem, centrado. Se ele é maior que o tamanho final, reduz até o tamanho final; se é menor, fica como está
 * (nunca amplia). Null se a imagem não tem pixels ou é pequena demais para a proporção (um banner com menos de 3 pixels
 * de largura).
 */
fun planCrop(width: Int, height: Int, kind: ProfileImageKind): CropPlan? {
    if (width <= 0 || height <= 0) return null
    val unit = minOf(width / kind.aspectWidth, height / kind.aspectHeight)
    if (unit == 0) return null
    val cropWidth = unit * kind.aspectWidth
    val cropHeight = unit * kind.aspectHeight
    val (outWidth, outHeight) =
        if (cropWidth > kind.outWidth) kind.outWidth to kind.outHeight else cropWidth to cropHeight
    return CropPlan((width - cropWidth) / 2, (height - cropHeight) / 2, cropWidth, cropHeight, outWidth, outHeight)
}

/**
 * Como pedir o [CropPlan] a um decodificador que primeiro reduz a imagem inteira ([targetWidth] x [targetHeight]) e
 * depois recorta o retângulo que começa em ([left], [top]) e tem o tamanho final do plano.
 */
data class ScaledCrop(val targetWidth: Int, val targetHeight: Int, val left: Int, val top: Int)

/** Converte [plan], dado em pixels da imagem de [width] x [height], para a ordem reduzir-e-recortar do decodificador. */
fun scaleForDecoder(plan: CropPlan, width: Int, height: Int): ScaledCrop {
    val factor = plan.outWidth.toDouble() / plan.width
    val targetWidth = maxOf((width * factor).roundToInt(), plan.outWidth)
    val targetHeight = maxOf((height * factor).roundToInt(), plan.outHeight)
    // O arredondamento não pode empurrar o recorte para fora da imagem reduzida.
    val left = (plan.left * factor).roundToInt().coerceIn(0, targetWidth - plan.outWidth)
    val top = (plan.top * factor).roundToInt().coerceIn(0, targetHeight - plan.outHeight)
    return ScaledCrop(targetWidth, targetHeight, left, top)
}

/** [bytes] bytes e [width] x [height] pixels cabem no que o servidor aceita para [kind]? */
fun fitsLimits(bytes: Int, width: Int, height: Int, kind: ProfileImageKind): Boolean =
    bytes in 1..PROFILE_IMAGE_MAX_BYTES && width in 1..kind.maxSide && height in 1..kind.maxSide

/**
 * O valor de `avatar_format` e `banner_format` para um tipo MIME, ou null se o servidor não aceita o formato
 * (GIF, JPEG, PNG e WEBP). Ignora maiúsculas e parâmetros (`image/png; charset=...`).
 */
fun formatFor(mimeType: String): String? = when (mimeType.substringBefore(';').trim().lowercase()) {
    "image/png" -> "PNG"
    "image/jpeg", "image/jpg" -> "JPEG"
    "image/gif" -> "GIF"
    "image/webp" -> "WEBP"
    else -> null
}

/** O tipo MIME de um formato que o servidor aceita, pelos bytes (ver [sniffImage]); null para os outros. */
fun mimeTypeOf(kind: ImageKind): String? = when (kind) {
    ImageKind.Png, ImageKind.Apng -> "image/png"
    ImageKind.Jpeg -> "image/jpeg"
    ImageKind.Gif -> "image/gif"
    ImageKind.Webp, ImageKind.WebpAnimated -> "image/webp"
    ImageKind.Heif, ImageKind.Unknown -> null
}

/** Os campos de texto do perfil e o limite de cada um, em code points (como o servidor conta). */
enum class ProfileField(val limit: Int) {
    Nickname(32),
    StatusMessage(64),
    Description(512),
    Typing(64),
}

/** O que o usuário digitou. Os espaços das pontas não contam nem são enviados. */
data class ProfileDraft(val nickname: String, val statusMessage: String, val description: String, val typing: String) {
    operator fun get(field: ProfileField): String = when (field) {
        ProfileField.Nickname -> nickname
        ProfileField.StatusMessage -> statusMessage
        ProfileField.Description -> description
        ProfileField.Typing -> typing
    }

    fun trimmed() = ProfileDraft(nickname.trim(), statusMessage.trim(), description.trim(), typing.trim())
}

/**
 * Quantos caracteres [text] tem para o servidor: code points, não unidades UTF-16. Um emoji de fora do plano básico
 * vale um, e uma letra com acento já composta também. Um surrogate solto conta um, como um byte inválido conta um no Go.
 */
fun codePoints(text: String): Int {
    var count = 0
    var index = 0
    while (index < text.length) {
        val pair = text[index].isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()
        index += if (pair) 2 else 1
        count++
    }
    return count
}

/** Os campos de [draft] que passam do limite, na ordem da tela. Vazio é válido: o contrato só exige que os campos existam. */
fun profileDraftErrors(draft: ProfileDraft): List<ProfileField> =
    ProfileField.entries.filter { codePoints(draft[it].trim()) > it.limit }
