package chat.papo.mobile.media

import chat.papo.mobile.data.api.AttachmentDto
import chat.papo.mobile.data.api.EmbedDto

/**
 * Nomes, no cache de mídia da conta, da mídia que pertence a mensagens. Todos começam por [MESSAGE_MEDIA_PREFIX]: o
 * [MediaCache] guarda esses nomes numa área à parte, que a retenção de mensagens varre sem tocar em avatares, emojis,
 * banners nem avatares de webhook. Quem pede ou guarda mídia de mensagem usa estas funções, nunca um texto solto: assim
 * a interface e a lista do que se mantém ([messageMediaKeys]) não podem divergir.
 */
internal const val MESSAGE_MEDIA_PREFIX = "msg/"

/** A miniatura de um anexo de imagem; também é a capa que o servidor gera para um vídeo. */
fun attachmentThumbKey(attachmentId: String): String = "${MESSAGE_MEDIA_PREFIX}att-thumb/$attachmentId"

/** O original de um anexo de imagem, como o visualizador o busca. Vídeos não passam por aqui: tocam por streaming. */
fun attachmentOriginalKey(attachmentId: String): String = "${MESSAGE_MEDIA_PREFIX}att/$attachmentId"

/** Onde a capa de [attachmentId] fica no cache cifrado da conta: nunca é buscada nem extraída duas vezes. */
fun posterKey(attachmentId: String): String = "${MESSAGE_MEDIA_PREFIX}att-poster/$attachmentId"

/** Nome da imagem do embed no cache cifrado da conta: a `thumbnail` ou, sem ela, a `image`. */
internal fun embedImageKey(embedId: String) = "${MESSAGE_MEDIA_PREFIX}embed/$embedId"

/** Nome do ícone do autor do embed no cache cifrado da conta. */
internal fun embedAuthorKey(embedId: String) = "${MESSAGE_MEDIA_PREFIX}embed-author/$embedId"

/** Todos os nomes de mídia que as mensagens com estes [attachments] e [embeds] podem ter no cache. */
internal fun messageMediaKeys(attachments: List<AttachmentDto>, embeds: List<EmbedDto>): Set<String> {
    val keys = HashSet<String>()
    for (attachment in attachments) {
        keys += attachmentThumbKey(attachment.id)
        keys += attachmentOriginalKey(attachment.id)
        keys += posterKey(attachment.id)
    }
    for (embed in embeds) {
        keys += embedImageKey(embed.id)
        keys += embedAuthorKey(embed.id)
    }
    return keys
}
