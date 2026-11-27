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

/** Até que tamanho a lista baixa o GIF original para animá-lo quando a miniatura do servidor veio parada. */
const val LIST_GIF_MAX_BYTES = 8L * 1024 * 1024

/**
 * Se a lista deve desenhar o original do [attachment] em vez da miniatura: o anexo é um GIF, a miniatura não pode
 * animar ([thumbnailMayAnimate] falso) e o original tem tamanho conhecido de até [LIST_GIF_MAX_BYTES]. O servidor gera
 * miniatura animada de um GIF, mas a troca por uma imagem parada (o primeiro quadro, em WebP) quando o GIF tem quadros
 * ou área demais; sem isto o GIF ficaria parado na conversa e só animaria na tela cheia.
 */
fun listUsesOriginalGif(attachment: AttachmentDto, thumbnailMayAnimate: Boolean): Boolean =
    !thumbnailMayAnimate &&
        attachment.mimeType.equals("image/gif", ignoreCase = true) &&
        attachment.sizeBytes in 1..LIST_GIF_MAX_BYTES
