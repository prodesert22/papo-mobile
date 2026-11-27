package chat.papo.mobile

import chat.papo.mobile.data.api.AttachmentDto
import chat.papo.mobile.data.api.EmbedDto
import chat.papo.mobile.media.LIST_GIF_MAX_BYTES
import chat.papo.mobile.media.MESSAGE_MEDIA_PREFIX
import chat.papo.mobile.media.attachmentOriginalKey
import chat.papo.mobile.media.attachmentThumbKey
import chat.papo.mobile.media.embedAuthorKey
import chat.papo.mobile.media.embedImageKey
import chat.papo.mobile.media.listUsesOriginalGif
import chat.papo.mobile.media.messageMediaKeys
import chat.papo.mobile.media.posterKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Os nomes da mídia de mensagem no cache: a área delas e a lista do que uma mensagem pode ter guardado. */
class MessageMediaTest {
    @Test
    fun todoNomeDeMidiaDeMensagemComecaPeloPrefixoDaArea() {
        val names = listOf(
            attachmentThumbKey("a"), attachmentOriginalKey("a"), posterKey("a"), embedImageKey("e"), embedAuthorKey("e"),
        )

        assertTrue(names.all { it.startsWith(MESSAGE_MEDIA_PREFIX) })
        assertEquals(names.size, names.toSet().size) // nenhum se confunde com outro
    }

    @Test
    fun osNomesDeAvataresEmojisEBannersFicamForaDaArea() {
        assertTrue(listOf("avatar/u/1", "emoji/e", "media/sha", "webhook-avatar/w").none { it.startsWith(MESSAGE_MEDIA_PREFIX) })
    }

    @Test
    fun cadaAnexoTemMiniaturaOriginalECapaECadaEmbedImagemEIconeDoAutor() {
        val keys = messageMediaKeys(
            attachments = listOf(AttachmentDto("a1"), AttachmentDto("a2")),
            embeds = listOf(EmbedDto("e1")),
        )

        assertEquals(
            setOf(
                attachmentThumbKey("a1"), attachmentOriginalKey("a1"), posterKey("a1"),
                attachmentThumbKey("a2"), attachmentOriginalKey("a2"), posterKey("a2"),
                embedImageKey("e1"), embedAuthorKey("e1"),
            ),
            keys,
        )
    }

    @Test
    fun mensagemSemAnexoNemEmbedNaoTemMidia() {
        assertTrue(messageMediaKeys(emptyList(), emptyList()).isEmpty())
    }

    // GIF com miniatura parada: a lista anima o original

    private fun gif(size: Long, mime: String = "image/gif") = AttachmentDto("a1", mimeType = mime, fileName = "x.gif", sizeBytes = size)

    @Test
    fun gifComMiniaturaParadaUsaOOriginalNaLista() {
        assertTrue(listUsesOriginalGif(gif(3_000_000), thumbnailMayAnimate = false))
    }

    @Test
    fun miniaturaQueAnimaBastaSemBaixarOOriginal() {
        assertFalse(listUsesOriginalGif(gif(3_000_000), thumbnailMayAnimate = true))
    }

    @Test
    fun soGifUsaOOriginalOTipoNaoDiferenciaMaiuscula() {
        assertTrue(listUsesOriginalGif(gif(3_000_000, mime = "IMAGE/GIF"), thumbnailMayAnimate = false))
        assertFalse(listUsesOriginalGif(gif(3_000_000, mime = "image/webp"), thumbnailMayAnimate = false))
        assertFalse(listUsesOriginalGif(gif(3_000_000, mime = "image/png"), thumbnailMayAnimate = false))
    }

    @Test
    fun originalGrandeDemaisOuDeTamanhoDesconhecidoFicaNaMiniatura() {
        assertTrue(listUsesOriginalGif(gif(LIST_GIF_MAX_BYTES), thumbnailMayAnimate = false))
        assertFalse(listUsesOriginalGif(gif(LIST_GIF_MAX_BYTES + 1), thumbnailMayAnimate = false))
        assertFalse(listUsesOriginalGif(gif(0), thumbnailMayAnimate = false))
        assertTrue(listUsesOriginalGif(gif(1), thumbnailMayAnimate = false))
    }
}
