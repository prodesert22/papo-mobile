package chat.papo.mobile

import chat.papo.mobile.media.CropPlan
import chat.papo.mobile.media.ImageKind
import chat.papo.mobile.media.PROFILE_IMAGE_MAX_BYTES
import chat.papo.mobile.media.ProfileDraft
import chat.papo.mobile.media.ProfileField
import chat.papo.mobile.media.ProfileImageKind
import chat.papo.mobile.media.ScaledCrop
import chat.papo.mobile.media.codePoints
import chat.papo.mobile.media.fitsLimits
import chat.papo.mobile.media.formatFor
import chat.papo.mobile.media.mimeTypeOf
import chat.papo.mobile.media.planCrop
import chat.papo.mobile.media.profileDraftErrors
import chat.papo.mobile.media.scaleForDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** As regras puras das imagens do perfil (recorte, limites, formato) e dos textos (contagem em code points). */
class ProfileImageTest {
    @Test
    fun avatarDeFotoGrandeRecortaOCentroQuadradoEReduzPara512() {
        val plan = planCrop(4000, 3000, ProfileImageKind.Avatar)

        assertEquals(CropPlan(left = 500, top = 0, width = 3000, height = 3000, outWidth = 512, outHeight = 512), plan)
    }

    @Test
    fun avatarMenorQueOTamanhoFinalNaoEAmpliado() {
        val plan = planCrop(100, 80, ProfileImageKind.Avatar)

        assertEquals(CropPlan(left = 10, top = 0, width = 80, height = 80, outWidth = 80, outHeight = 80), plan)
    }

    @Test
    fun avatarJaDe512ficaComoEsta() {
        assertEquals(CropPlan(0, 0, 512, 512, 512, 512), planCrop(512, 512, ProfileImageKind.Avatar))
    }

    @Test
    fun avatarEmRetratoCortaEmCimaEEmBaixo() {
        assertEquals(CropPlan(0, 250, 1000, 1000, 512, 512), planCrop(1000, 1500, ProfileImageKind.Avatar))
    }

    @Test
    fun bannerDeFotoGrandeFicaEm3Por1ComOTamanhoFinal1536Por512() {
        val plan = planCrop(4000, 3000, ProfileImageKind.Banner)

        // 3 * 1333 = 3999: a proporção é exata, então sobra um pixel que o recorte divide pelo centro.
        assertEquals(CropPlan(left = 0, top = 833, width = 3999, height = 1333, outWidth = 1536, outHeight = 512), plan)
    }

    @Test
    fun bannerMuitoLargoCortaOsLados() {
        assertEquals(CropPlan(1500, 0, 3000, 1000, 1536, 512), planCrop(6000, 1000, ProfileImageKind.Banner))
    }

    @Test
    fun bannerPequenoNaoEAmpliado() {
        assertEquals(CropPlan(0, 150, 900, 300, 900, 300), planCrop(900, 600, ProfileImageKind.Banner))
    }

    @Test
    fun imagemSemPixelsOuPequenaDemaisParaAProporcaoNaoTemRecorte() {
        assertNull(planCrop(0, 100, ProfileImageKind.Avatar))
        assertNull(planCrop(100, 0, ProfileImageKind.Avatar))
        assertNull(planCrop(-5, 100, ProfileImageKind.Avatar))
        assertNull(planCrop(2, 100, ProfileImageKind.Banner))
    }

    @Test
    fun semReducaoODecodificadorSoRecorta() {
        val plan = planCrop(100, 80, ProfileImageKind.Avatar)!!

        assertEquals(ScaledCrop(targetWidth = 100, targetHeight = 80, left = 10, top = 0), scaleForDecoder(plan, 100, 80))
    }

    @Test
    fun comReducaoODecodificadorReduzAImagemInteiraEORecorteCaiNoMesmoLugar() {
        val plan = planCrop(4000, 3000, ProfileImageKind.Avatar)!!

        assertEquals(ScaledCrop(targetWidth = 683, targetHeight = 512, left = 85, top = 0), scaleForDecoder(plan, 4000, 3000))
    }

    @Test
    fun orecorteReduzidoSempreCabeNaImagemReduzidaEDaOTamanhoFinal() {
        val sizes = listOf(1 to 1, 3 to 1, 7 to 5, 513 to 512, 1001 to 1000, 1999 to 640, 3000 to 4000, 4032 to 3024, 12000 to 100, 100 to 12000)
        for (kind in ProfileImageKind.entries) {
            for ((width, height) in sizes) {
                val plan = planCrop(width, height, kind) ?: continue
                val scaled = scaleForDecoder(plan, width, height)

                assertTrue(scaled.left >= 0 && scaled.top >= 0, "$kind ${width}x$height")
                assertTrue(scaled.left + plan.outWidth <= scaled.targetWidth, "$kind ${width}x$height: largura")
                assertTrue(scaled.top + plan.outHeight <= scaled.targetHeight, "$kind ${width}x$height: altura")
                assertTrue(plan.outWidth <= kind.outWidth && plan.outHeight <= kind.outHeight, "$kind ${width}x$height: teto")
            }
        }
    }

    @Test
    fun limitesDoServidorParaBytesELados() {
        val max = PROFILE_IMAGE_MAX_BYTES
        assertTrue(fitsLimits(max, 512, 512, ProfileImageKind.Avatar))
        assertFalse(fitsLimits(max + 1, 512, 512, ProfileImageKind.Avatar))
        assertFalse(fitsLimits(0, 512, 512, ProfileImageKind.Avatar))
        assertFalse(fitsLimits(1000, 513, 512, ProfileImageKind.Avatar))
        assertFalse(fitsLimits(1000, 512, 513, ProfileImageKind.Avatar))
        assertFalse(fitsLimits(1000, 0, 512, ProfileImageKind.Avatar))
        assertFalse(fitsLimits(1000, 512, 0, ProfileImageKind.Avatar))
    }

    @Test
    fun bannerAceitaAteOTetoDe2048() {
        assertTrue(fitsLimits(1000, 2048, 2048, ProfileImageKind.Banner))
        assertFalse(fitsLimits(1000, 2049, 100, ProfileImageKind.Banner))
        assertFalse(fitsLimits(1000, 100, 2049, ProfileImageKind.Banner))
    }

    @Test
    fun formatoVemDoTipoMimeSemDistinguirMaiusculasNemParametros() {
        assertEquals("PNG", formatFor("image/png"))
        assertEquals("JPEG", formatFor("image/jpeg"))
        assertEquals("JPEG", formatFor("image/jpg"))
        assertEquals("GIF", formatFor("image/gif"))
        assertEquals("WEBP", formatFor("image/webp"))
        assertEquals("PNG", formatFor("IMAGE/PNG"))
        assertEquals("WEBP", formatFor(" image/webp ; q=1"))
        assertNull(formatFor("image/heic"))
        assertNull(formatFor("application/octet-stream"))
        assertNull(formatFor(""))
    }

    @Test
    fun tipoMimeDosFormatosQueOServidorAceita() {
        assertEquals("image/png", mimeTypeOf(ImageKind.Png))
        assertEquals("image/png", mimeTypeOf(ImageKind.Apng))
        assertEquals("image/jpeg", mimeTypeOf(ImageKind.Jpeg))
        assertEquals("image/gif", mimeTypeOf(ImageKind.Gif))
        assertEquals("image/webp", mimeTypeOf(ImageKind.Webp))
        assertEquals("image/webp", mimeTypeOf(ImageKind.WebpAnimated))
        assertNull(mimeTypeOf(ImageKind.Heif))
        assertNull(mimeTypeOf(ImageKind.Unknown))
    }

    @Test
    fun codePointsContamCaracteresComoOServidor() {
        assertEquals(0, codePoints(""))
        assertEquals(4, codePoints("ação")) // já compostos
        assertEquals(2, codePoints("á")) // letra e acento soltos: dois code points
        assertEquals(1, codePoints("😀")) // dois chars UTF-16, um code point
        assertEquals(5, codePoints("👨‍👩‍👧")) // família: três pessoas e dois ligadores
        assertEquals(3, codePoints("a😀b"))
    }

    @Test
    fun surrogatesSoltosContamUmCadaUm() {
        assertEquals(1, codePoints("\uD83D")) // alto sozinho, no fim
        assertEquals(2, codePoints("\uD83Da")) // alto seguido de outra coisa
        assertEquals(1, codePoints("\uDE00")) // baixo sozinho
        assertEquals(2, codePoints("a\uD83D"))
    }

    @Test
    fun textoNoLimiteEValidoEUmAMaisNao() {
        val ok = ProfileDraft("a".repeat(32), "b".repeat(64), "c".repeat(512), "d".repeat(64))
        assertEquals(emptyList(), profileDraftErrors(ok))

        assertEquals(listOf(ProfileField.Nickname), profileDraftErrors(ok.copy(nickname = "a".repeat(33))))
        assertEquals(listOf(ProfileField.StatusMessage), profileDraftErrors(ok.copy(statusMessage = "b".repeat(65))))
        assertEquals(listOf(ProfileField.Description), profileDraftErrors(ok.copy(description = "c".repeat(513))))
        assertEquals(listOf(ProfileField.Typing), profileDraftErrors(ok.copy(typing = "d".repeat(65))))
    }

    @Test
    fun emojisContamPorCodePointEOsEspacosDasPontasNaoContam() {
        val ok = ProfileDraft(nickname = "😀".repeat(32), statusMessage = "", description = "", typing = "")
        assertEquals(emptyList(), profileDraftErrors(ok))
        assertEquals(listOf(ProfileField.Nickname), profileDraftErrors(ok.copy(nickname = "😀".repeat(33))))

        assertEquals(emptyList(), profileDraftErrors(ok.copy(nickname = "  " + "a".repeat(32) + "\n")))
    }

    @Test
    fun variosCamposPassandoDoLimiteSaemNaOrdemDaTela() {
        val draft = ProfileDraft("a".repeat(40), "b".repeat(10), "c".repeat(600), "d".repeat(70))

        assertEquals(listOf(ProfileField.Nickname, ProfileField.Description, ProfileField.Typing), profileDraftErrors(draft))
    }

    @Test
    fun rascunhoVazioEValidoPoisOContratoSoExigeQueOsCamposExistam() {
        assertEquals(emptyList(), profileDraftErrors(ProfileDraft("", "", "", "")))
    }

    @Test
    fun rascunhoDaAcessoPorCampoEAparaAsPontas() {
        val draft = ProfileDraft(" a ", " b", "c\n", "\td")

        assertEquals(" a ", draft[ProfileField.Nickname])
        assertEquals(" b", draft[ProfileField.StatusMessage])
        assertEquals("c\n", draft[ProfileField.Description])
        assertEquals("\td", draft[ProfileField.Typing])
        assertEquals(ProfileDraft("a", "b", "c", "d"), draft.trimmed())
    }
}
