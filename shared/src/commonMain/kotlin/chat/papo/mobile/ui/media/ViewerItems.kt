package chat.papo.mobile.ui.media

import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.chat.Message
import chat.papo.mobile.chat.hasViewableImage
import chat.papo.mobile.data.api.AttachmentDto
import chat.papo.mobile.data.api.EmbedDto
import chat.papo.mobile.media.ModerationView
import chat.papo.mobile.media.PlayableMedia
import chat.papo.mobile.media.moderationView
import chat.papo.mobile.media.playableAttachment

/**
 * Uma página do visualizador de mídia de uma mensagem: uma imagem (um GIF é imagem) ou um vídeo anexado, ou a imagem de um
 * embed.
 */
internal sealed interface ViewerItem {
    /** Identifica a página entre recomposições e giros de tela: [viewerKey] num anexo, [viewerEmbedKey] numa imagem de embed. */
    val key: String

    /** O que a barra de cima diz da página: o nome do arquivo do anexo, ou o título do embed (vazio se ele não tem). */
    val title: String

    data class Image(val attachment: AttachmentDto) : ViewerItem {
        override val key: String get() = viewerKey(attachment.id)
        override val title: String get() = attachment.fileName
    }

    data class Video(val attachment: AttachmentDto, val media: PlayableMedia) : ViewerItem {
        /** A mesma chave de [PlayableMedia.key], que é como o resto do app chama o vídeo anexado. */
        override val key: String get() = viewerKey(attachment.id)
        override val title: String get() = attachment.fileName
    }

    /** A imagem que o servidor serve para [embed] (ver [hasViewableImage]); o cartão já a deixou no cache da conta. */
    data class EmbedImage(val embed: EmbedDto) : ViewerItem {
        override val key: String get() = viewerEmbedKey(embed.id)
        override val title: String get() = listOf(embed.title, embed.siteName, embed.provider).firstOrNull { !it.isNullOrBlank() }.orEmpty()
    }
}

/** As páginas do visualizador e a que abre primeiro. */
internal data class ViewerItems(val items: List<ViewerItem>, val start: Int)

/** A chave da página de um anexo; é a de [PlayableMedia.key], que é como o resto do app chama o vídeo anexado. */
internal fun viewerKey(attachmentId: String): String = "attachment:$attachmentId"

/** A chave da página da imagem do embed [embedId]. Não é a de [PlayableMedia.key] (`embed:<id>`, o vídeo do embed). */
internal fun viewerEmbedKey(embedId: String): String = "embed-image:$embedId"

/**
 * As páginas do visualizador de [message], com a de [tapped] (uma chave de [viewerKey] ou de [viewerEmbedKey]) como a
 * primeira a aparecer. Vêm primeiro os anexos, na ordem da mensagem: as imagens que o cartão mostra como imagem (com
 * miniatura) e os vídeos que o player abre; o resto (arquivos, áudio, imagem sem miniatura) segue só no cartão. Depois vêm
 * as imagens dos embeds, também na ordem da mensagem, uma por embed ([hasViewableImage]): o servidor serve uma só por embed
 * (ver `EmbedMedia.kt`), e o vídeo de um embed fica de fora, porque o cartão tem o gesto próprio dele.
 *
 * Ficam de fora o anexo bloqueado pela moderação e o sensível que não foi o tocado: o toque que abre um sensível é o que o
 * revela, mas revelar outro no cartão é estado do cartão, que o visualizador não vê, e ele não pode mostrar o que a pessoa
 * ainda não pediu para ver (regra 6). Embed não tem estado de moderação.
 *
 * Se [tapped] não está entre as páginas (bloqueado, embed que saiu da mensagem, ou chave que não é de página), começa pela
 * primeira.
 */
internal fun viewerItems(server: ServerUrl, message: Message, tapped: String): ViewerItems {
    val attachments = message.attachments.mapNotNull { attachment ->
        val item = itemOf(server, attachment) ?: return@mapNotNull null
        item.takeIf { moderationView(attachment.moderationStatus) != ModerationView.Sensitive || it.key == tapped }
    }
    // O id vem do servidor e o pager falha com chave repetida: um embed repetido na mensagem viraria duas páginas iguais.
    val embeds = message.embeds.filter { hasViewableImage(server, it) }.distinctBy { it.id }.map { ViewerItem.EmbedImage(it) }
    val items = attachments + embeds
    return ViewerItems(items, items.indexOfFirst { it.key == tapped }.coerceAtLeast(0))
}

/** Mesma regra do cartão (`MessageAttachment`): imagem com miniatura primeiro, depois o vídeo que o player abre. */
private fun itemOf(server: ServerUrl, attachment: AttachmentDto): ViewerItem? {
    if (moderationView(attachment.moderationStatus) == ModerationView.Blocked) return null
    if (attachment.isImage && attachment.thumbnailId != null) return ViewerItem.Image(attachment)
    return playableAttachment(server, attachment)?.let { ViewerItem.Video(attachment, it) }
}
