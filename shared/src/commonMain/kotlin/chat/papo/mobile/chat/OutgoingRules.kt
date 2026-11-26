package chat.papo.mobile.chat

import chat.papo.mobile.data.api.OutgoingFile

// Limites do `POST /messages`. Estão no código do backend (`handlers/messages.go`, `services/attachments.go` e
// `middleware/bodylimit.go`) e não na OpenAPI: o app confere antes de enfileirar e ainda traduz o 400/413 do servidor.

/** Arquivos por mensagem. */
const val MAX_ATTACHMENTS = 10

/** Tamanho de cada arquivo: o servidor recusa mais que 100 MiB com 400. */
const val MAX_ATTACHMENT_MB = 100
const val MAX_ATTACHMENT_BYTES = MAX_ATTACHMENT_MB * 1024L * 1024

/** Soma dos arquivos: o corpo vai até 110 MiB (413); os 5 MiB de folga cobrem o texto e a moldura do multipart. */
const val MAX_ATTACHMENTS_TOTAL_MB = 105
const val MAX_ATTACHMENTS_TOTAL_BYTES = MAX_ATTACHMENTS_TOTAL_MB * 1024L * 1024

/** Caracteres (pontos de código, como o servidor conta) do nome de um arquivo. */
const val MAX_ATTACHMENT_NAME = 128

/** Uma extensão mais comprida que isto é parte do nome, não extensão: ao cortar o nome ela não é poupada. */
private const val MAX_EXTENSION = 16

/** Por que arquivos escolhidos não podem entrar na mensagem. */
sealed interface AttachmentProblem {
    /** Passaria de [MAX_ATTACHMENTS] arquivos. */
    data object TooMany : AttachmentProblem

    /** O arquivo [name] passa de [MAX_ATTACHMENT_BYTES]. */
    data class FileTooLarge(val name: String) : AttachmentProblem

    /** Os arquivos juntos passariam de [MAX_ATTACHMENTS_TOTAL_BYTES]. */
    data object TotalTooLarge : AttachmentProblem
}

/**
 * O primeiro limite que [files], como uma mensagem só, não respeita; null se todos são respeitados. Arquivo
 * sem tamanho conhecido não pesa na conta: quem decide por ele é o servidor, e o app traduz a recusa.
 */
fun checkLimits(files: List<OutgoingFile>): AttachmentProblem? {
    if (files.size > MAX_ATTACHMENTS) return AttachmentProblem.TooMany
    files.firstOrNull { (it.size ?: 0L) > MAX_ATTACHMENT_BYTES }?.let { return AttachmentProblem.FileTooLarge(it.name) }
    // Cada um tem até 100 MiB e são no máximo dez: a soma cabe num Long.
    if (files.sumOf { it.size ?: 0L } > MAX_ATTACHMENTS_TOTAL_BYTES) return AttachmentProblem.TotalTooLarge
    return null
}

/**
 * O nome com que o arquivo sai: o do provedor ([displayName]) como o servidor o limpa (sem pasta, sem
 * caracteres de controle, sem espaços nas pontas), cortado em [MAX_ATTACHMENT_NAME] caracteres sem perder a
 * extensão. Sem nome, um genérico do tipo ([mimeType]): `imagem.gif`, `video.mp4`, `arquivo`.
 */
fun nameFor(displayName: String?, mimeType: String): String {
    val cleaned = displayName.orEmpty().substringAfterLast('/').substringAfterLast('\\').filterNot { it.isISOControl() }.trim()
    return limitName(cleaned.ifEmpty { genericName(mimeType) })
}

/** É um GIF (a prévia leva o selo). */
fun OutgoingFile.isGif(): Boolean = baseType(mimeType) == "image/gif"

/** É imagem ou vídeo: o cartão do anexo mostra uma prévia em vez de nome e tamanho. */
fun OutgoingFile.isVisualMedia(): Boolean = baseType(mimeType).let { it.startsWith("image/") || it.startsWith("video/") }

private fun baseType(mimeType: String) = mimeType.substringBefore(';').trim().lowercase()

private fun genericName(mimeType: String): String {
    val type = baseType(mimeType)
    val base = when (type.substringBefore('/')) {
        "image" -> "imagem"
        "video" -> "video"
        "audio" -> "audio"
        else -> "arquivo"
    }
    val extension = EXTENSIONS[type] ?: return base
    return "$base.$extension"
}

private fun limitName(name: String): String {
    if (codePointCount(name) <= MAX_ATTACHMENT_NAME) return name
    val dot = name.lastIndexOf('.')
    val extension = if (dot > 0) name.substring(dot) else ""
    if (extension.isEmpty() || codePointCount(extension) > MAX_EXTENSION) return takeCodePoints(name, MAX_ATTACHMENT_NAME).trimEnd()
    return takeCodePoints(name.substring(0, dot), MAX_ATTACHMENT_NAME - codePointCount(extension)).trimEnd() + extension
}

private fun codePointCount(text: String): Int {
    var count = 0
    var index = 0
    while (index < text.length) {
        index += width(text, index)
        count++
    }
    return count
}

private fun takeCodePoints(text: String, count: Int): String {
    var taken = 0
    var index = 0
    while (index < text.length && taken < count) {
        index += width(text, index)
        taken++
    }
    return text.substring(0, index)
}

/** Quantos `Char` tem o ponto de código que começa em [index]: 2 para um par substituto, senão 1. */
private fun width(text: String, index: Int): Int =
    if (text[index].isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) 2 else 1

private val EXTENSIONS = mapOf(
    "image/gif" to "gif",
    "image/jpeg" to "jpg",
    "image/png" to "png",
    "image/webp" to "webp",
    "image/heic" to "heic",
    "image/heif" to "heif",
    "image/avif" to "avif",
    "image/bmp" to "bmp",
    "video/mp4" to "mp4",
    "video/webm" to "webm",
    "video/quicktime" to "mov",
    "video/3gpp" to "3gp",
    "video/x-matroska" to "mkv",
    "audio/mpeg" to "mp3",
    "audio/ogg" to "ogg",
    "audio/mp4" to "m4a",
    "audio/wav" to "wav",
    "audio/x-wav" to "wav",
)
