package chat.papo.mobile

import chat.papo.mobile.data.api.AttachmentDto
import chat.papo.mobile.media.PixelSize
import chat.papo.mobile.media.PosterSource
import chat.papo.mobile.media.isNearlyBlack
import chat.papo.mobile.media.posterKey
import chat.papo.mobile.media.posterRetryTimeUs
import chat.papo.mobile.media.posterSize
import chat.papo.mobile.media.posterSource
import chat.papo.mobile.media.videoCardAspect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** As decisões puras da capa de um vídeo: de onde vem, onde fica no cache, que instante, se o quadro é preto e que proporção. */
class VideoPosterTest {
    private fun attachment(
        thumbnailId: String? = null,
        sizeBytes: Long = 30_000_000,
        moderationStatus: String? = null,
    ) = AttachmentDto(
        id = "att-1",
        mimeType = "video/mp4",
        fileName = "passeio.mp4",
        sizeBytes = sizeBytes,
        width = 1280,
        height = 720,
        thumbnailId = thumbnailId,
        moderationStatus = moderationStatus,
    )

    // posterKey

    @Test
    fun achaveDaCapaTemAContaEOAnexo() {
        assertEquals("att-poster/conta-1/att-1", posterKey("conta-1", "att-1"))
    }

    @Test
    fun achaveDaCapaDifereEntreContasEntreAnexosEDaMiniaturaDoServidor() {
        assertNotEquals(posterKey("conta-1", "att-1"), posterKey("conta-2", "att-1"))
        assertNotEquals(posterKey("conta-1", "att-1"), posterKey("conta-1", "att-2"))
        // A miniatura do servidor usa "att-thumb/<id>": as duas nunca se confundem no mesmo cache.
        assertNotEquals("att-thumb/att-1", posterKey("conta-1", "att-1"))
    }

    // posterSource

    @Test
    fun comMiniaturaDoServidorUsaAMiniatura() {
        assertEquals(PosterSource.Server, posterSource(attachment(thumbnailId = "thumb-1"), revealed = true))
    }

    @Test
    fun semMiniaturaExtraiDoProprioVideo() {
        assertEquals(PosterSource.Extract, posterSource(attachment(), revealed = true))
    }

    @Test
    fun anexoSensivelAindaEscondidoNaoTemCapaDeFonteNenhuma() {
        val sensitive = attachment(moderationStatus = "sensitive")
        assertEquals(PosterSource.None, posterSource(sensitive, revealed = false))
        // Nem a miniatura do servidor: o que ele marcou como sensível só aparece quando a pessoa pede.
        assertEquals(PosterSource.None, posterSource(attachment(thumbnailId = "thumb-1", moderationStatus = "sensitive"), revealed = false))
    }

    @Test
    fun anexoSensivelReveladoTemCapa() {
        assertEquals(PosterSource.Extract, posterSource(attachment(moderationStatus = "sensitive"), revealed = true))
    }

    @Test
    fun semTamanhoConhecidoNaoHaComoPedirTrechosEFicaSemCapa() {
        assertEquals(PosterSource.None, posterSource(attachment(sizeBytes = 0), revealed = true))
        assertEquals(PosterSource.None, posterSource(attachment(sizeBytes = -1), revealed = true))
    }

    @Test
    fun aMiniaturaDoServidorNaoDependeDoTamanhoDoArquivo() {
        assertEquals(PosterSource.Server, posterSource(attachment(thumbnailId = "thumb-1", sizeBytes = 0), revealed = true))
    }

    // posterRetryTimeUs

    @Test
    fun semDuracaoOuComDuracaoInvalidaUsaOPrimeiroQuadro() {
        assertEquals(0L, posterRetryTimeUs(null))
        assertEquals(0L, posterRetryTimeUs(0))
        assertEquals(0L, posterRetryTimeUs(-5))
    }

    @Test
    fun videoCurtoUsaAMetadeDaDuracao() {
        assertEquals(300_000L, posterRetryTimeUs(600))
        assertEquals(999_500L, posterRetryTimeUs(1_999))
    }

    @Test
    fun videoDeDoisSegundosOuMaisUsaUmSegundo() {
        assertEquals(1_000_000L, posterRetryTimeUs(2_000))
        assertEquals(1_000_000L, posterRetryTimeUs(600_000))
    }

    // isNearlyBlack

    private val black = 0xFF000000.toInt()

    @Test
    fun semPontosNaoHaOQueJulgarEOQuadroNaoEhPreto() {
        assertFalse(isNearlyBlack(IntArray(0)))
    }

    @Test
    fun quadroSoDePontosPretosEhPreto() {
        assertTrue(isNearlyBlack(IntArray(144) { black }))
        assertTrue(isNearlyBlack(intArrayOf(0x00000000, 0xFF140000.toInt()))) // o alfa não conta; vermelho bem escuro também é preto
    }

    @Test
    fun umPontoClaroBastaParaOQuadroNaoSerPreto() {
        assertFalse(isNearlyBlack(IntArray(144) { black }.also { it[100] = 0xFFFFFFFF.toInt() }))
        assertFalse(isNearlyBlack(intArrayOf(0xFF0000FF.toInt()))) // azul puro: brilho 29, acima do limite
    }

    @Test
    fun oLimiteDeBrilhoEhVinteInclusive() {
        assertTrue(isNearlyBlack(intArrayOf(0xFF141414.toInt()))) // 20
        assertFalse(isNearlyBlack(intArrayOf(0xFF151515.toInt()))) // 21
    }

    // posterSize

    @Test
    fun aCapaCabeEmMaxPxNaProporcaoDoVideo() {
        assertEquals(PixelSize(640, 360), posterSize(1_920, 1_080, 0, 640))
        assertEquals(PixelSize(360, 640), posterSize(1_080, 1_920, null, 640))
    }

    @Test
    fun aCapaNuncaAmpliaOVideo() {
        assertEquals(PixelSize(160, 90), posterSize(160, 90, 0, 640))
    }

    @Test
    fun rotacaoDeNoventaOuDuzentosESetentaGrausTrocaOsLados() {
        // 1920 x 1080 gravado de lado, para ser visto em pé.
        assertEquals(PixelSize(360, 640), posterSize(1_920, 1_080, 90, 640))
        assertEquals(PixelSize(360, 640), posterSize(1_920, 1_080, 270, 640))
        assertEquals(PixelSize(360, 640), posterSize(1_920, 1_080, -90, 640))
        assertEquals(PixelSize(360, 640), posterSize(1_920, 1_080, 450, 640))
    }

    @Test
    fun rotacaoDeZeroOuCentoEOitentaGrausMantemOsLados() {
        assertEquals(PixelSize(640, 360), posterSize(1_920, 1_080, 0, 640))
        assertEquals(PixelSize(640, 360), posterSize(1_920, 1_080, 180, 640))
        assertEquals(PixelSize(640, 360), posterSize(1_920, 1_080, -180, 640))
    }

    @Test
    fun semMedidasDoConteinerAcapaPedeOQuadradoMaxPx() {
        val square = PixelSize(640, 640)
        assertEquals(square, posterSize(null, null, null, 640))
        assertEquals(square, posterSize(1_920, null, 0, 640))
        assertEquals(square, posterSize(null, 1_080, 0, 640))
        assertEquals(square, posterSize(0, 1_080, 0, 640))
        assertEquals(square, posterSize(1_920, -1, 0, 640))
    }

    // videoCardAspect

    @Test
    fun aCaixaSegueAsDimensoesDoVideo() {
        assertEquals(16f / 9f, videoCardAspect(1280, 720, 96, 64))
    }

    @Test
    fun semDimensoesDoVideoSegueAMiniaturaDoServidor() {
        assertEquals(96f / 64f, videoCardAspect(null, null, 96, 64))
        assertEquals(96f / 64f, videoCardAspect(1280, null, 96, 64))
        assertEquals(96f / 64f, videoCardAspect(null, 720, 96, 64))
        assertEquals(96f / 64f, videoCardAspect(0, 720, 96, 64))
        assertEquals(96f / 64f, videoCardAspect(1280, -1, 96, 64))
    }

    @Test
    fun semNenhumaDasDuasUsaDezesseisPorNove() {
        assertEquals(16f / 9f, videoCardAspect(null, null, null, null))
        assertEquals(16f / 9f, videoCardAspect(null, null, 96, null))
        assertEquals(16f / 9f, videoCardAspect(null, null, 0, 64))
    }

    @Test
    fun aProporcaoContinuaLimitadaEntreMeioEDoisEMeio() {
        assertEquals(0.5f, videoCardAspect(100, 1_000, null, null))
        assertEquals(2.5f, videoCardAspect(null, null, 1_000, 100))
    }
}
