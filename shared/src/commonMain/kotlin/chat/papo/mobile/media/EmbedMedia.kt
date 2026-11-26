package chat.papo.mobile.media

import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.data.api.EmbedDto
import kotlin.io.encoding.Base64

/** Nome da imagem do embed no cache cifrado da conta: a `thumbnail` ou, sem ela, a `image`. */
internal fun embedImageKey(embedId: String) = "embed/$embedId"

/** Nome do ícone do autor do embed no cache cifrado da conta. */
internal fun embedAuthorKey(embedId: String) = "embed-author/$embedId"

/** O embed tem uma imagem que o servidor serve em `GET /embeds/{id}`? */
internal fun EmbedDto.hasImage() = thumbnail != null || image != null

/** O embed tem o ícone do autor, que o servidor serve em `GET /embeds/{id}`? */
internal fun EmbedDto.hasAuthorIcon() = author?.media != null

/**
 * Busca a imagem do embed. O mesmo `GET /embeds/{id}` traz o ícone do autor, e ele é gravado no cache aqui, por
 * [embedAuthorKey], para o cartão não fazer um segundo pedido (regra 15).
 */
internal suspend fun AccountSession.fetchEmbedImage(embedId: String): ByteArray? {
    val response = api.embed(embedId)
    response.authorImageData?.let { media.put(embedAuthorKey(embedId), Base64.decode(it)) }
    return response.imageData?.let { Base64.decode(it) }
}

/**
 * O ícone do autor de [embed]. Com imagem, o pedido dela já o guardou: espera esse pedido (que o cartão faz ao mesmo
 * tempo, e o cache junta os dois num só) e lê o que ficou. Um ícone que o servidor não mandou fica sem; um embed cuja
 * imagem já estava no cache de antes do ícone existir também (nenhum pedido a mais por causa disso). Sem imagem, o
 * ícone é a única razão do pedido.
 */
internal suspend fun AccountSession.fetchEmbedAuthorIcon(embed: EmbedDto): ByteArray? {
    if (!embed.hasImage()) return api.embed(embed.id).authorImageData?.let { Base64.decode(it) }
    media.get(embedImageKey(embed.id)) { fetchEmbedImage(embed.id) }
    return media.peek(embedAuthorKey(embed.id))
}
