package chat.papo.mobile.chat

import chat.papo.mobile.data.api.EmbedDto
import chat.papo.mobile.media.aspectOrNull

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
