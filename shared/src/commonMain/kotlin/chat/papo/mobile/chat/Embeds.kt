package chat.papo.mobile.chat

import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.data.api.EmbedDto
import chat.papo.mobile.data.api.EmbedFieldDto
import chat.papo.mobile.media.ImageKind
import chat.papo.mobile.media.aspectOrNull
import chat.papo.mobile.media.hasImage
import chat.papo.mobile.media.relayVideo
import chat.papo.mobile.media.sniffImage

/**
 * O link que um embed ou o texto dele pode abrir: só `http` e `https`, com host e sem espaços (regra 6). Qualquer outra
 * coisa (`javascript:`, `intent:`, `file:`, `content:`, uma URL sem esquema) vira null. O esquema sai em minúsculas: o
 * Android só resolve `https://` para o navegador, e `Https://` (como alguns teclados escrevem) não acha aplicativo nenhum.
 */
fun safeLink(url: String?): String? {
    if (url == null || !WEB_LINK.matches(url)) return null
    val schemeEnd = url.indexOf("://")
    return url.substring(0, schemeEnd).lowercase() + url.substring(schemeEnd)
}

private val WEB_LINK = Regex("""https?://[^\s/?#]+\S*""", RegexOption.IGNORE_CASE)

/**
 * A grade dos campos de um embed, uma linha por item: os campos na ordem de `position` (os de mesma posição, na ordem
 * em que vieram), os `inline` de dois em dois lado a lado e os outros sozinhos. Um campo que não é `inline` fecha a
 * linha que estava aberta, e um `inline` que sobra no fim fica sozinho. Campo sem nome e sem valor não desenha nada e sai.
 */
fun fieldRows(fields: List<EmbedFieldDto>): List<List<EmbedFieldDto>> {
    val rows = mutableListOf<List<EmbedFieldDto>>()
    val open = mutableListOf<EmbedFieldDto>()
    fun close() {
        if (open.isEmpty()) return
        rows += open.toList()
        open.clear()
    }
    for (field in fields.filter { it.name.isNotBlank() || it.value.isNotBlank() }.sortedBy { it.position }) {
        if (field.inline) {
            open += field
            if (open.size == INLINE_PER_ROW) close()
        } else {
            close()
            rows += listOf(field)
        }
    }
    close()
    return rows
}

private const val INLINE_PER_ROW = 2

/**
 * A proporção da caixa da imagem do embed, entre 0,5 e 2,5 (ver [aspectOrNull]), ou null se o servidor não deu as medidas.
 * A imagem que o servidor serve em `GET /embeds/{id}` é a `thumbnail` ou, sem ela, a `image`, então são as medidas dela
 * que valem (a `image` não vale quando há `thumbnail`, mesmo que esta não traga medidas). Sem as dela, valem as do `video`,
 * que dá a forma da caixa que leva o ▶. Sem medidas a caixa segue a imagem quando ela chega.
 */
fun embedMediaAspect(embed: EmbedDto): Float? {
    val served = embed.thumbnail ?: embed.image
    return aspectOrNull(served?.width, served?.height) ?: aspectOrNull(embed.video?.width, embed.video?.height)
}

/**
 * É um embed de GIF (Tenor, GIPHY, KLIPY)? Ele traz só a `thumbnail`, um GIF animado; sem ela não há o que mostrar e o
 * embed segue como um cartão comum.
 */
fun isGifEmbed(embed: EmbedDto): Boolean = embed.fetchMethod == "gif" && embed.thumbnail != null

/**
 * A imagem do [embed] abre no visualizador de mídia ao toque? O cartão decide por aqui e o visualizador monta as páginas por
 * aqui ([chat.papo.mobile.ui.media.viewerItems]), então um não mostra o que o outro não abre. Precisa de uma imagem que o
 * servidor sirva. A do GIF abre sempre. A de um embed com vídeo não: o cartão tem o gesto do vídeo (o ▶ toca na caixa, o
 * resto abre a tela cheia), seja o do relay do servidor ([relayVideo]) ou o do YouTube ([youTubeId]), e a imagem ali é só a capa.
 */
internal fun hasViewableImage(server: ServerUrl, embed: EmbedDto): Boolean =
    embed.hasImage() && (isGifEmbed(embed) || (youTubeId(embed) == null && relayVideo(server, embed) == null))

/** O nome e o tipo com que a imagem de um embed sai de **Salvar** e **Compartilhar**. */
data class EmbedImageFile(val fileName: String, val mimeType: String)

/**
 * O nome e o tipo da imagem de [embed] cujos bytes são [bytes], para **Salvar** e **Compartilhar**. O servidor não expõe a URL
 * de origem de uma imagem, então o nome sai do título do embed (ou do site, ou do provedor) com a extensão do tipo; sem
 * nenhum deles é `imagem.<extensão>`, como o nome genérico de [nameFor]. O tipo vem dos bytes ([sniffImage]): o `mime_type`
 * que o servidor informa só vale se os bytes não são de um formato que o app reconhece, e sem ele (ou sem ser de imagem) o
 * arquivo sai como binário genérico, sem extensão.
 */
fun embedImageFile(embed: EmbedDto, bytes: ByteArray): EmbedImageFile {
    val (mimeType, extension) = imageTypeOf(embed, bytes)
    val base = listOf(embed.title, embed.siteName, embed.provider).firstNotNullOfOrNull(::cleanTitle) ?: "imagem"
    return EmbedImageFile(nameFor(extension?.let { "$base.$it" } ?: base, mimeType), mimeType)
}

/** O MIME e a extensão da imagem: os dos bytes e, se eles não se reconhecem, os do `mime_type` servido (ver [embedImageFile]). */
private fun imageTypeOf(embed: EmbedDto, bytes: ByteArray): Pair<String, String?> {
    val sniffed = when (sniffImage(bytes)) {
        ImageKind.Gif -> "image/gif"
        ImageKind.Png, ImageKind.Apng -> "image/png"
        ImageKind.Webp, ImageKind.WebpAnimated -> "image/webp"
        ImageKind.Jpeg -> "image/jpeg"
        ImageKind.Heif -> "image/heif"
        ImageKind.Unknown -> null
    }
    val declared = (embed.thumbnail ?: embed.image)?.mimeType?.substringBefore(';')?.trim()?.lowercase()
    val mimeType = sniffed ?: declared?.takeIf { it.startsWith("image/") && it.length > "image/".length } ?: "application/octet-stream"
    return mimeType to IMAGE_EXTENSIONS[mimeType]
}

/**
 * [text] como parte de um nome de arquivo: as marcas de pasta e de caminho, os controles e as quebras viram espaço, os
 * espaços em sequência viram um, e pontos e espaços das pontas saem. Sem nada que sobre, null.
 */
private fun cleanTitle(text: String?): String? =
    text.orEmpty()
        .map { if (it in RESERVED_IN_NAMES || it.isISOControl() || it.isWhitespace()) ' ' else it }
        .joinToString("")
        .split(' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .trim('.', ' ')
        .takeIf { it.isNotEmpty() }

private const val RESERVED_IN_NAMES = "/\\:*?\"<>|"

private val IMAGE_EXTENSIONS = mapOf(
    "image/gif" to "gif",
    "image/png" to "png",
    "image/webp" to "webp",
    "image/jpeg" to "jpg",
    "image/heif" to "heic",
)
