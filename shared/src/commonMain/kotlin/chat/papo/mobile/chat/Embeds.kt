package chat.papo.mobile.chat

import chat.papo.mobile.data.api.EmbedDto
import chat.papo.mobile.data.api.EmbedFieldDto
import chat.papo.mobile.media.aspectOrNull

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
