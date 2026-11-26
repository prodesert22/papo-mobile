package chat.papo.mobile

import chat.papo.mobile.chat.isYouTubeId
import chat.papo.mobile.chat.isYouTubeKey
import chat.papo.mobile.chat.youTubeId
import chat.papo.mobile.chat.youTubeKey
import chat.papo.mobile.chat.youTubeWatchUrl
import chat.papo.mobile.data.api.EmbedDto
import chat.papo.mobile.data.api.EmbedMediaDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Quando um embed é o player do YouTube, e o que se monta a partir do id que sai dele. Ids de exemplo inventados. */
class YouTubeTest {
    private val id = "aB3dE5gH7jK"

    private fun videoEmbed(url: String?) = EmbedDto(id = "e1", video = EmbedMediaDto(url = url))

    // youTubeId

    @Test
    fun aPaginaDePlayerDoServidorDaOId() {
        assertEquals(id, youTubeId(videoEmbed("https://www.youtube.com/embed/$id")))
    }

    @Test
    fun oIdPodeTerTracoESublinhado() {
        assertEquals("a-b_c-d_e-f", youTubeId(videoEmbed("https://www.youtube.com/embed/a-b_c-d_e-f")))
    }

    @Test
    fun oEmbedUrlAntigoTambemVale() {
        assertEquals(id, youTubeId(EmbedDto(id = "e1", embedUrl = "https://www.youtube.com/embed/$id")))
    }

    @Test
    fun oEmbedUrlVemAntesDoVideo() {
        val embed = EmbedDto(
            id = "e1",
            embedUrl = "https://www.youtube.com/embed/$id",
            video = EmbedMediaDto(url = "https://www.youtube.com/embed/zzzzzzzzzzz"),
        )

        assertEquals(id, youTubeId(embed))
    }

    @Test
    fun seOEmbedUrlNaoServeOVideoDecide() {
        val embed = EmbedDto(
            id = "e1",
            embedUrl = "https://youtube.com.evil.com/embed/$id",
            video = EmbedMediaDto(url = "https://www.youtube.com/embed/zzzzzzzzzzz"),
        )

        assertEquals("zzzzzzzzzzz", youTubeId(embed))
    }

    @Test
    fun semVideoNemEmbedUrlNaoEYouTube() {
        assertNull(youTubeId(EmbedDto(id = "e1")))
        assertNull(youTubeId(videoEmbed(null)))
    }

    @Test
    fun hostQueApenasComecaComYouTubeNaoVale() {
        assertNull(youTubeId(videoEmbed("https://www.youtube.com.evil.com/embed/$id")))
        assertNull(youTubeId(videoEmbed("https://youtube.com.evil.com/embed/$id")))
        assertNull(youTubeId(videoEmbed("https://www.youtube.com@evil.com/embed/$id")))
        assertNull(youTubeId(videoEmbed("https://evil.com/https://www.youtube.com/embed/$id")))
    }

    @Test
    fun soHttpsVale() {
        assertNull(youTubeId(videoEmbed("http://www.youtube.com/embed/$id")))
        assertNull(youTubeId(videoEmbed("//www.youtube.com/embed/$id")))
        assertNull(youTubeId(videoEmbed("javascript:https://www.youtube.com/embed/$id")))
    }

    @Test
    fun parametrosFragmentoEBarraNoFimNaoValem() {
        assertNull(youTubeId(videoEmbed("https://www.youtube.com/embed/$id?autoplay=1")))
        assertNull(youTubeId(videoEmbed("https://www.youtube.com/embed/$id#t=10")))
        assertNull(youTubeId(videoEmbed("https://www.youtube.com/embed/$id/")))
        assertNull(youTubeId(videoEmbed("https://www.youtube.com/embed/$id\n")))
        assertNull(youTubeId(videoEmbed(" https://www.youtube.com/embed/$id")))
    }

    @Test
    fun idComDezOuDozeCaracteresNaoVale() {
        assertNull(youTubeId(videoEmbed("https://www.youtube.com/embed/" + id.dropLast(1))))
        assertNull(youTubeId(videoEmbed("https://www.youtube.com/embed/" + id + "x")))
        assertNull(youTubeId(videoEmbed("https://www.youtube.com/embed/")))
    }

    @Test
    fun idComCaractereForaDoAlfabetoNaoVale() {
        assertNull(youTubeId(videoEmbed("https://www.youtube.com/embed/aB3dE5gH7j.")))
        assertNull(youTubeId(videoEmbed("https://www.youtube.com/embed/aB3dE5gH7j%")))
        assertNull(youTubeId(videoEmbed("https://www.youtube.com/embed/aB3dE5gH7j\"")))
    }

    @Test
    fun maiusculasNoHostOuNoCaminhoNaoValem() {
        assertNull(youTubeId(videoEmbed("https://WWW.YOUTUBE.COM/embed/$id")))
        assertNull(youTubeId(videoEmbed("https://www.youtube.com/EMBED/$id")))
        assertNull(youTubeId(videoEmbed("HTTPS://www.youtube.com/embed/$id")))
    }

    @Test
    fun outrosEnderecosDoYouTubeNaoSaoOPlayer() {
        assertNull(youTubeId(videoEmbed("https://www.youtube-nocookie.com/embed/$id")))
        assertNull(youTubeId(videoEmbed("https://youtube.com/embed/$id")))
        assertNull(youTubeId(videoEmbed("https://m.youtube.com/embed/$id")))
        assertNull(youTubeId(videoEmbed("https://www.youtube.com/watch?v=$id")))
        assertNull(youTubeId(videoEmbed("https://youtu.be/$id")))
        assertNull(youTubeId(videoEmbed("https://www.youtube.com/shorts/$id")))
    }

    @Test
    fun aUrlDaPaginaNaoDecideNada() {
        // O link original do embed (`url`) não vira player: só a página de player que o servidor derivou.
        assertNull(youTubeId(EmbedDto(id = "e1", url = "https://www.youtube.com/watch?v=$id")))
    }

    // isYouTubeId

    @Test
    fun soOnzeCaracteresDoAlfabetoSaoUmId() {
        assertTrue(isYouTubeId(id))
        assertTrue(isYouTubeId("-_-_-_-_-_-"))
        assertFalse(isYouTubeId(""))
        assertFalse(isYouTubeId(id.dropLast(1)))
        assertFalse(isYouTubeId(id + "x"))
        assertFalse(isYouTubeId("$id\n"))
        assertFalse(isYouTubeId("aB3dE5gH7j&"))
    }

    // youTubeWatchUrl e youTubeKey

    @Test
    fun aPaginaDoVideoVemSoDoIdValidado() {
        assertEquals("https://www.youtube.com/watch?v=$id", youTubeWatchUrl(id))
    }

    @Test
    fun umIdInvalidoNaoViraUrl() {
        assertNull(youTubeWatchUrl("$id&list=x"))
        assertNull(youTubeWatchUrl("javascript:alert(1)"))
        assertNull(youTubeWatchUrl(""))
    }

    @Test
    fun aChaveDaMidiaNaoSeConfundeComAsDoServidor() {
        assertEquals("youtube:$id", youTubeKey(id))
    }

    @Test
    fun aChaveDoYouTubeSeReconheceNaMidiaENoCartao() {
        assertTrue(isYouTubeKey(youTubeKey(id)))
        assertTrue(isYouTubeKey("0f8f9b1e-6c52-4a7e-9c1d-3b2a5e7d9a10/youtube:$id"))
        assertFalse(isYouTubeKey("embed:e1"))
        assertFalse(isYouTubeKey("m1/embed:e1"))
        assertFalse(isYouTubeKey("m1/attachment:youtube"))
        assertFalse(isYouTubeKey(""))
    }
}
