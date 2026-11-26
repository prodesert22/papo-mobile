package chat.papo.mobile

import chat.papo.mobile.chat.Message
import chat.papo.mobile.chat.youTubeKey
import chat.papo.mobile.data.api.EmbedDto
import chat.papo.mobile.data.api.EmbedMediaDto
import chat.papo.mobile.ui.media.OpenYouTube
import chat.papo.mobile.ui.media.findOpenYouTube
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Achar o vídeo do YouTube aberto na tela cheia a partir da chave que sobrevive a girar o aparelho. */
class YouTubeViewerTest {
    private val id = "aB3dE5gH7jK"

    private fun youTube(embedId: String, videoId: String = id, title: String? = "Título $embedId") =
        EmbedDto(id = embedId, title = title, video = EmbedMediaDto(url = "https://www.youtube.com/embed/$videoId"))

    private fun message(id: String, vararg embeds: EmbedDto) = Message(
        id = id,
        channelId = "c1",
        authorId = "u1",
        content = "",
        createdAt = null,
        createdAtIso = "",
        edited = false,
        replyTo = null,
        webhookId = null,
        externalAuthor = null,
        attachments = emptyList(),
        embeds = embeds.toList(),
        reactions = emptyList(),
        userReactions = emptyList(),
    )

    @Test
    fun achaOEmbedDoYouTubePelaChaveDoVideo() {
        val found = findOpenYouTube(listOf(message("m1", youTube("e1"))), youTubeKey(id))

        assertEquals(OpenYouTube(id, "Título e1"), found)
    }

    @Test
    fun entreVariasMensagensAcheOVideoDaChave() {
        val messages = listOf(
            message("m1", youTube("e1", videoId = "zzzzzzzzzzz")),
            message("m2", youTube("e2", videoId = id)),
        )

        assertEquals("Título e2", findOpenYouTube(messages, youTubeKey(id))?.title)
    }

    @Test
    fun seOMesmoVideoApareceEmDuasMensagensValeOPrimeiro() {
        val messages = listOf(message("m1", youTube("e1")), message("m2", youTube("e2")))

        assertEquals("Título e1", findOpenYouTube(messages, youTubeKey(id))?.title)
    }

    @Test
    fun semTituloOTituloFicaVazio() {
        assertEquals("", findOpenYouTube(listOf(message("m1", youTube("e1", title = null))), youTubeKey(id))?.title)
    }

    @Test
    fun mensagemApagadaOuAindaNaoCarregadaNaoTemOVideo() {
        assertNull(findOpenYouTube(emptyList(), youTubeKey(id)))
        assertNull(findOpenYouTube(listOf(message("m1", youTube("e1", videoId = "zzzzzzzzzzz"))), youTubeKey(id)))
    }

    @Test
    fun umEmbedQueNaoEOPlayerDoYouTubeNaoTem() {
        val evil = EmbedDto(id = "e1", video = EmbedMediaDto(url = "https://youtube.com.evil.com/embed/$id"))

        assertNull(findOpenYouTube(listOf(message("m1", evil)), youTubeKey(id)))
    }

    @Test
    fun aChaveDeUmVideoDoServidorNaoAcha() {
        assertNull(findOpenYouTube(listOf(message("m1", youTube("e1"))), "embed:e1"))
        assertNull(findOpenYouTube(listOf(message("m1", youTube("e1"))), "attachment:e1"))
    }
}
