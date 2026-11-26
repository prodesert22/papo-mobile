package chat.papo.mobile.ui.media

import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.chat.Message
import chat.papo.mobile.data.api.AttachmentDto
import chat.papo.mobile.media.ModerationView
import chat.papo.mobile.media.PlayableMedia
import chat.papo.mobile.media.moderationView
import chat.papo.mobile.media.playableAttachment

/** Uma página do visualizador de mídia de uma mensagem: uma imagem (um GIF é imagem) ou um vídeo anexado. */
internal sealed interface ViewerItem {
    val attachment: AttachmentDto

    /** Identifica a página entre recomposições e giros de tela: `attachment:<id>`, a mesma chave de [PlayableMedia.key]. */
    val key: String get() = viewerKey(attachment.id)

    data class Image(override val attachment: AttachmentDto) : ViewerItem

    data class Video(override val attachment: AttachmentDto, val media: PlayableMedia) : ViewerItem
}

/** As páginas do visualizador e a que abre primeiro. */
internal data class ViewerItems(val items: List<ViewerItem>, val start: Int)

/** A chave da página de um anexo; é a de [PlayableMedia.key], que é como o resto do app chama o vídeo anexado. */
internal fun viewerKey(attachmentId: String): String = "attachment:$attachmentId"

/**
 * As páginas do visualizador de [message], na ordem dos anexos, com a de [tapped] (uma chave de [viewerKey]) como a primeira
 * a aparecer. Entram as imagens que o cartão da mensagem mostra como imagem (com miniatura) e os vídeos que o player abre;
 * o resto (arquivos, áudio, imagem sem miniatura) segue só no cartão. Ficam de fora o anexo bloqueado pela moderação e o
 * sensível que não foi o tocado: o toque que abre um sensível é o que o revela, mas revelar outro no cartão é estado do
 * cartão, que o visualizador não vê, e ele não pode mostrar o que a pessoa ainda não pediu para ver (regra 6).
 *
 * Se [tapped] não está entre as páginas (bloqueado, ou chave que não é de anexo), começa pela primeira.
 */
internal fun viewerItems(server: ServerUrl, message: Message, tapped: String): ViewerItems {
    val items = message.attachments.mapNotNull { attachment ->
        val item = itemOf(server, attachment) ?: return@mapNotNull null
        item.takeIf { moderationView(attachment.moderationStatus) != ModerationView.Sensitive || it.key == tapped }
    }
    return ViewerItems(items, items.indexOfFirst { it.key == tapped }.coerceAtLeast(0))
}

/** Mesma regra do cartão (`MessageAttachment`): imagem com miniatura primeiro, depois o vídeo que o player abre. */
private fun itemOf(server: ServerUrl, attachment: AttachmentDto): ViewerItem? {
    if (moderationView(attachment.moderationStatus) == ModerationView.Blocked) return null
    if (attachment.isImage && attachment.thumbnailId != null) return ViewerItem.Image(attachment)
    return playableAttachment(server, attachment)?.let { ViewerItem.Video(attachment, it) }
}
