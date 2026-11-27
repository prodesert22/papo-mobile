package chat.papo.mobile

import chat.papo.mobile.data.api.AttachmentDto
import chat.papo.mobile.data.api.EmbedDto
import chat.papo.mobile.media.MESSAGE_MEDIA_PREFIX
import chat.papo.mobile.media.attachmentOriginalKey
import chat.papo.mobile.media.attachmentThumbKey
import chat.papo.mobile.media.embedAuthorKey
import chat.papo.mobile.media.embedImageKey
import chat.papo.mobile.media.messageMediaKeys
import chat.papo.mobile.media.posterKey
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
