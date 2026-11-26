package chat.papo.mobile.media

/** Uma imagem pronta para o perfil: já recortada, reduzida e sem metadados, dentro dos limites do servidor. */
class ReducedImage(val bytes: ByteArray, val mimeType: String, val width: Int, val height: Int, val animated: Boolean)

/** Por que uma imagem não serve de avatar ou de banner. */
enum class ImageRefusal {
    /** Não é uma imagem que o aparelho decodifique, ou está cortada, ou é pequena demais. */
    NotAnImage,

    /** O arquivo passa de [PREVIEW_MAX_BYTES]: grande demais para ser lido em memória e reduzido (ver `OutgoingFile.readAtMost`). */
    SourceTooLarge,

    /** É uma animação (GIF, APNG, WEBP) maior que o servidor aceita; recodificá-la perderia os quadros. */
    AnimationTooLarge,

    /** Mesmo com a qualidade mais baixa que o app usa, a imagem passa de 2 MiB. */
    StillTooHeavy,

    /** O arquivo não pôde ser lido. */
    Unreadable,
}

/** O tamanho de uma imagem escolhida (já na orientação do EXIF) e se ela é uma animação, que não se recorta. */
data class SourceImageInfo(val width: Int, val height: Int, val animated: Boolean)

/**
 * O recorte que o usuário escolheu, como [CropGeometry.request] o devolve: a região ([left], [top], [width], [height],
 * de 0 a 1) da imagem orientada pelo EXIF e depois girada [quarterTurns] voltas de 90 graus no sentido horário (0 a 3).
 */
data class CropRequest(val quarterTurns: Int, val left: Float, val top: Float, val width: Float, val height: Float)

sealed interface ImageReduction {
    class Ready(val image: ReducedImage) : ImageReduction

    data class Refused(val reason: ImageRefusal) : ImageReduction
}

/**
 * Prepara uma imagem escolhida para ser avatar ou banner, na plataforma e em memória (nada vai para o disco): recorta na
 * proporção do tipo (a região de [CropRequest], ou o centro sem ele), reduz ao tamanho final, aplica a rotação do EXIF e
 * descarta todos os metadados (a localização inclusive) ao recodificar. Uma animação dentro dos limites do servidor segue
 * como veio, sem recorte; fora deles é recusada. Roda fora da thread principal.
 */
interface ImageReducer {
    /** Lê só o cabeçalho de [source]: o tamanho na orientação do EXIF e se é uma animação. Null se não é uma imagem que o aparelho abre. */
    suspend fun inspect(source: ByteArray): SourceImageInfo?

    suspend fun reduce(source: ByteArray, kind: ProfileImageKind, crop: CropRequest? = null): ImageReduction
}
