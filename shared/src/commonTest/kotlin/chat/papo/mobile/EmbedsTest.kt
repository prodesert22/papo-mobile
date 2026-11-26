package chat.papo.mobile

import chat.papo.mobile.chat.embedMediaAspect
import chat.papo.mobile.data.api.EmbedDto
import chat.papo.mobile.data.api.EmbedMediaDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EmbedsTest {
    private fun media(width: Int?, height: Int?) = EmbedMediaDto(mimeType = "image/png", width = width, height = height)

    private fun embed(
        thumbnail: EmbedMediaDto? = null,
        image: EmbedMediaDto? = null,
        video: EmbedMediaDto? = null,
    ) = EmbedDto(id = "e1", thumbnail = thumbnail, image = image, video = video)

    // embedMediaAspect

    @Test
    fun aProporcaoVemDasMedidasDaThumbnail() {
        assertEquals(1200f / 630f, embedMediaAspect(embed(thumbnail = media(1200, 630))))
        assertEquals(1f, embedMediaAspect(embed(thumbnail = media(512, 512))))
    }

    @Test
    fun semThumbnailValeAImagem() {
        assertEquals(1f, embedMediaAspect(embed(image = media(300, 300))))
    }

    @Test
    fun aThumbnailVenceAImagemPorqueEsseECaQueOServidorServe() {
        assertEquals(2f, embedMediaAspect(embed(thumbnail = media(400, 200), image = media(300, 300))))
    }

    @Test
    fun thumbnailSemMedidasNaoEmprestaAsDaImagem() {
        // O servidor serve a thumbnail; a proporção da `image` seria a de outra figura.
        assertNull(embedMediaAspect(embed(thumbnail = media(null, null), image = media(300, 300))))
        assertEquals(16f / 9f, embedMediaAspect(embed(thumbnail = media(0, 0), image = media(300, 300), video = media(1280, 720))))
    }

    @Test
    fun semMedidasDaImagemValemAsDoVideo() {
        assertEquals(16f / 9f, embedMediaAspect(embed(video = media(1280, 720))))
        assertEquals(16f / 9f, embedMediaAspect(embed(thumbnail = media(null, 100), video = media(1280, 720))))
    }

    @Test
    fun aImagemVenceOVideoQuandoTemMedidas() {
        assertEquals(1f, embedMediaAspect(embed(thumbnail = media(200, 200), video = media(1280, 720))))
    }

    @Test
    fun aProporcaoFicaEntreMeioEDoisEMeio() {
        assertEquals(0.5f, embedMediaAspect(embed(thumbnail = media(100, 1000))))
        assertEquals(2.5f, embedMediaAspect(embed(thumbnail = media(1000, 100))))
        // Nos limites exatos e logo dentro deles, o valor passa sem mudar.
        assertEquals(0.5f, embedMediaAspect(embed(thumbnail = media(50, 100))))
        assertEquals(2.5f, embedMediaAspect(embed(thumbnail = media(250, 100))))
        assertEquals(0.6f, embedMediaAspect(embed(thumbnail = media(60, 100))))
    }

    @Test
    fun medidasAusentesZeroOuNegativasNaoDaoProporcao() {
        assertNull(embedMediaAspect(embed()))
        assertNull(embedMediaAspect(embed(thumbnail = media(null, 100))))
        assertNull(embedMediaAspect(embed(thumbnail = media(100, null))))
        assertNull(embedMediaAspect(embed(thumbnail = media(0, 100))))
        assertNull(embedMediaAspect(embed(thumbnail = media(100, 0))))
        assertNull(embedMediaAspect(embed(thumbnail = media(-100, 100))))
        assertNull(embedMediaAspect(embed(thumbnail = media(100, -100))))
    }
}
