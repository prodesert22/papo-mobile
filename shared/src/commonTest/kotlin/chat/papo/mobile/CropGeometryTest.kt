package chat.papo.mobile

import chat.papo.mobile.media.CROP_MAX_ZOOM
import chat.papo.mobile.media.CropGeometry
import chat.papo.mobile.media.CropPlan
import chat.papo.mobile.media.CropRequest
import chat.papo.mobile.media.CropState
import chat.papo.mobile.media.FrameSize
import chat.papo.mobile.media.ProfileImageKind
import chat.papo.mobile.media.cropFrameSize
import chat.papo.mobile.media.planCrop
import chat.papo.mobile.media.planManualCrop
import chat.papo.mobile.media.sliderToZoom
import chat.papo.mobile.media.zoomToSlider
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A conta do recorte manual: enquadramento, zoom, giro, a região final e o retângulo em pixels que o decodificador recebe. */
class CropGeometryTest {
    private fun assertNear(expected: Float, actual: Float, message: String = "") =
        assertTrue(abs(expected - actual) < 1e-4f, "$message esperado $expected, veio $actual")

    private fun assertState(turns: Int, zoom: Float, cx: Float, cy: Float, actual: CropState) {
        assertEquals(turns, actual.quarterTurns)
        assertNear(zoom, actual.zoom, "zoom")
        assertNear(cx, actual.centerX, "centerX")
        assertNear(cy, actual.centerY, "centerY")
    }

    /** Uma foto larga (4000 x 3000) sob uma moldura quadrada de 300 px: 0,1 px de tela por pixel da imagem a 100%. */
    private val wide = CropGeometry(4000, 3000, 300f, 300f)

    /** Uma foto em pé (3000 x 4000) sob a mesma moldura. */
    private val tall = CropGeometry(3000, 4000, 300f, 300f)

    // Zoom mínimo

    @Test
    fun oZoomMinimoFazAImagemCobrirAMolduraNasDuasDirecoes() {
        assertNear(0.1f, wide.minScale(0)) // a altura manda: 300 / 3000
        assertNear(0.1f, tall.minScale(0)) // a largura manda: 300 / 3000
        assertNear(0.1f, wide.minScale(1)) // girada vira em pé
    }

    @Test
    fun aMolduraLargaDoBannerUsaALarguraDaImagemQuandoElaEEstreita() {
        // 1000 x 1000 sob 600 x 200: a largura pede 0,6 e a altura 0,2; vale o maior.
        assertNear(0.6f, CropGeometry(1000, 1000, 600f, 200f).minScale(0))
        // 3000 x 600 sob 600 x 200: a largura pede 0,2 e a altura 0,333; vale o maior.
        assertNear(1f / 3f, CropGeometry(3000, 600, 600f, 200f).minScale(0))
    }

    @Test
    fun oEnquadramentoInicialE100PorCentoNoCentro() {
        val state = wide.initial()

        assertEquals(100, state.percent)
        assertState(0, 1f, 0.5f, 0.5f, wide.clamp(state))
    }

    @Test
    fun aPorcentagemAcompanhaOZoom() {
        assertEquals(250, CropState(zoom = 2.5f).percent)
        assertEquals(800, CropState(zoom = CROP_MAX_ZOOM).percent)
    }

    // Limites

    @Test
    fun zoomForaDosLimitesVoltaAoLimite() {
        assertEquals(1f, wide.clamp(CropState(zoom = 0.3f)).zoom)
        assertEquals(CROP_MAX_ZOOM, wide.clamp(CropState(zoom = 50f)).zoom)
        assertEquals(3f, wide.clamp(CropState(zoom = 3f)).zoom)
    }

    @Test
    fun voltasForaDeZeroATresViramOResto() {
        assertEquals(3, wide.clamp(CropState(quarterTurns = -1)).quarterTurns)
        assertEquals(1, wide.clamp(CropState(quarterTurns = 5)).quarterTurns)
        assertEquals(2, wide.clamp(CropState(quarterTurns = 2)).quarterTurns)
        assertEquals(0, wide.initial(8).quarterTurns)
    }

    @Test
    fun noZoomMinimoSoSeAndaNaDirecaoQueSobra() {
        // 4000 x 3000: a moldura mostra 75% da largura e a altura inteira.
        assertState(0, 1f, 0.375f, 0.5f, wide.clamp(CropState(centerX = 0f, centerY = 0f)))
        assertState(0, 1f, 0.625f, 0.5f, wide.clamp(CropState(centerX = 1f, centerY = 1f)))
        // Em pé é o contrário.
        assertState(0, 1f, 0.5f, 0.375f, tall.clamp(CropState(centerX = 0f, centerY = 0f)))
    }

    @Test
    fun comZoomAMolduraNuncaSaiDaImagem() {
        // A 400% a região mostra 18,75% da largura e 25% da altura.
        assertState(0, 4f, 0.09375f, 0.125f, wide.clamp(CropState(zoom = 4f, centerX = -3f, centerY = -3f)))
        assertState(0, 4f, 0.90625f, 0.875f, wide.clamp(CropState(zoom = 4f, centerX = 7f, centerY = 7f)))
        assertState(0, 4f, 0.4f, 0.6f, wide.clamp(CropState(zoom = 4f, centerX = 0.4f, centerY = 0.6f)))
    }

    // Arrastar

    @Test
    fun arrastarAImagemParaADireitaMoveARegiaoParaAEsquerda() {
        // 10 px de tela são 10 / (0,1 * 4000) = 2,5% da largura.
        assertState(0, 1f, 0.475f, 0.5f, wide.transform(wide.initial(), 10f, 0f, 1f, 0f, 0f))
        assertState(0, 1f, 0.525f, 0.5f, wide.transform(wide.initial(), -10f, 0f, 1f, 0f, 0f))
    }

    @Test
    fun arrastarDemaisParaNoLimiteDaImagem() {
        assertState(0, 1f, 0.375f, 0.5f, wide.transform(wide.initial(), 100000f, 100000f, 1f, 0f, 0f))
        assertState(0, 2f, 0.1875f, 0.25f, wide.transform(CropState(zoom = 2f), 100000f, 100000f, 1f, 0f, 0f))
    }

    @Test
    fun arrastarNaDirecaoQueJaCobreAImagemNaoFazNada() {
        assertState(0, 1f, 0.5f, 0.5f, wide.transform(wide.initial(), 0f, 500f, 1f, 0f, 0f))
        assertState(0, 1f, 0.5f, 0.5f, tall.transform(tall.initial(), 500f, 0f, 1f, 0f, 0f))
    }

    // Zoom

    @Test
    fun zoomEmTornoDeUmPontoMantemOPontoSobOFoco() {
        val before = wide.initial()
        val focusX = 100f
        val focusY = -50f
        val pointX = before.centerX + focusX / (wide.scale(before) * 4000)
        val pointY = before.centerY + focusY / (wide.scale(before) * 3000)

        val after = wide.transform(before, 0f, 0f, 2f, focusX, focusY)

        assertNear(2f, after.zoom)
        assertNear(pointX, after.centerX + focusX / (wide.scale(after) * 4000), "x sob o foco")
        assertNear(pointY, after.centerY + focusY / (wide.scale(after) * 3000), "y sob o foco")
    }

    @Test
    fun zoomEmTornoDoBordoDaMolduraDaOValorConhecido() {
        // O ponto sob o foco está em 0,5 + 150 / 400 = 0,875 da largura.
        val after = wide.transform(wide.initial(), 0f, 0f, 2f, 150f, 0f)

        assertState(0, 2f, 0.6875f, 0.5f, after)
    }

    @Test
    fun aPincaComArrastoMoveEAmplia() {
        // O dedo anda 20 px para a esquerda enquanto o zoom dobra: o ponto que estava sob o dedo o acompanha.
        val after = wide.transform(wide.initial(), -20f, 0f, 2f, 0f, 0f)

        assertNear(0.5f + 20f / (0.2f * 4000f), after.centerX)
    }

    @Test
    fun oZoomNaoPassaDoMaximoNemDoMinimo() {
        assertEquals(CROP_MAX_ZOOM, wide.transform(wide.initial(), 0f, 0f, 100f, 0f, 0f).zoom)
        assertEquals(1f, wide.transform(wide.initial(), 0f, 0f, 0.01f, 0f, 0f).zoom)
        // Com o zoom já no máximo, ampliar mais não mexe no centro.
        val max = CropState(zoom = CROP_MAX_ZOOM, centerX = 0.4f, centerY = 0.6f)
        assertState(0, CROP_MAX_ZOOM, 0.4f, 0.6f, wide.transform(max, 0f, 0f, 3f, 80f, -40f))
    }

    @Test
    fun reduzirOZoomTrazARegiaoDeVoltaParaDentroDaImagem() {
        val zoomed = CropState(zoom = 4f, centerX = 0.9f, centerY = 0.9f)

        assertState(0, 1f, 0.625f, 0.5f, wide.withZoom(zoomed, 1f))
    }

    @Test
    fun oControleDeZoomAmpliaEmTornoDoCentroDaMoldura() {
        val state = CropState(zoom = 1.5f, centerX = 0.55f, centerY = 0.5f)

        assertState(0, 4f, 0.55f, 0.5f, wide.withZoom(state, 4f))
    }

    // Girar

    @Test
    fun girarAvancaUmaVoltaEWrapEmQuatro() {
        var state = wide.initial()
        val turns = (1..5).map { state = wide.rotate(state); state.quarterTurns }

        assertEquals(listOf(1, 2, 3, 0, 1), turns)
    }

    @Test
    fun girarMantemOCentroDaRegiaoNoSentidoHorario() {
        val square = CropGeometry(1000, 1000, 300f, 300f)
        // O ponto do meio da borda esquerda vai para o meio da borda de cima.
        val rotated = square.rotate(CropState(zoom = 2f, centerX = 0.25f, centerY = 0.5f))

        assertState(1, 2f, 0.5f, 0.25f, rotated)
    }

    @Test
    fun quatroGirosDevolvemOEnquadramento() {
        val square = CropGeometry(1000, 1000, 300f, 300f)
        val start = CropState(zoom = 2f, centerX = 0.3f, centerY = 0.6f)

        val end = (1..4).fold(start) { state, _ -> square.rotate(state) }

        assertState(0, 2f, 0.3f, 0.6f, end)
    }

    @Test
    fun girarUmaFotoLargaAjustaOCentroAoNovoLimite() {
        // A 100%, a foto larga vira em pé e a região anda na vertical.
        val rotated = wide.rotate(CropState(centerX = 0.6f, centerY = 0.5f))

        assertState(1, 1f, 0.5f, 0.6f, rotated)
        // Um centro que não cabe depois do giro é puxado para dentro (em pé, a região tem 75% da altura).
        assertState(1, 1f, 0.5f, 0.625f, wide.rotate(CropState(centerX = 0.95f, centerY = 0.5f)))
    }

    @Test
    fun comOFrameGiradoOMinimoUsaAsDimensoesGiradas() {
        val banner = CropGeometry(3000, 1000, 600f, 200f)

        assertNear(0.2f, banner.minScale(0)) // 3000 x 1000 sob 600 x 200: as duas pedem 0,2
        assertNear(0.6f, banner.minScale(1)) // em pé, 1000 x 3000: a largura pede 0,6
        assertEquals(1000, banner.rotatedWidth(1))
        assertEquals(3000, banner.rotatedHeight(1))
        assertEquals(3000, banner.rotatedWidth(2))
        assertEquals(1000, banner.rotatedHeight(2))
        assertEquals(1000, banner.rotatedWidth(3))
        assertEquals(1000, banner.rotatedWidth(-1))
    }

    // Reiniciar

    @Test
    fun tamanhoVoltaOZoomEAPosicaoMasMantemOGiro() {
        val state = CropState(quarterTurns = 3, zoom = 5f, centerX = 0.2f, centerY = 0.8f)

        assertState(3, 1f, 0.5f, 0.5f, wide.clamp(wide.reset(state)))
        assertEquals(100, wide.reset(state).percent)
    }

    // Região final

    @Test
    fun aRegiaoInicialDeUmaFotoLargaECentradaEComAAlturaToda() {
        val request = wide.request(wide.initial())

        assertEquals(0, request.quarterTurns)
        assertNear(0.125f, request.left)
        assertNear(0f, request.top)
        assertNear(0.75f, request.width)
        assertNear(1f, request.height)
    }

    @Test
    fun aRegiaoEncolheComOZoom() {
        val request = wide.request(CropState(zoom = 2f))

        assertNear(0.3125f, request.left)
        assertNear(0.25f, request.top)
        assertNear(0.375f, request.width)
        assertNear(0.5f, request.height)
    }

    @Test
    fun aRegiaoNuncaPassaDasBordasDaImagem() {
        val corner = wide.request(CropState(zoom = 4f, centerX = -10f, centerY = -10f))
        val far = wide.request(CropState(zoom = 4f, centerX = 10f, centerY = 10f))

        assertNear(0f, corner.left)
        assertNear(0f, corner.top)
        assertNear(1f, far.left + far.width)
        assertNear(1f, far.top + far.height)
    }

    @Test
    fun aRegiaoLevaAsVoltasEFicaNaImagemGirada() {
        val request = wide.request(CropState(quarterTurns = 1))

        assertEquals(1, request.quarterTurns)
        // Girada, a foto é 3000 x 4000: a moldura mostra a largura toda e 75% da altura.
        assertNear(0f, request.left)
        assertNear(0.125f, request.top)
        assertNear(1f, request.width)
        assertNear(0.75f, request.height)
    }

    @Test
    fun aRegiaoDeUmBannerTemAProporcaoDaMoldura() {
        val banner = CropGeometry(3000, 2000, 900f, 300f)

        val request = banner.request(banner.initial())

        // 3000 x 2000 sob 900 x 300: 0,3 px por pixel; a moldura mostra a largura toda e 1000 pixels de altura.
        assertNear(1f, request.width)
        assertNear(0.5f, request.height)
        assertNear(0.25f, request.top)
        assertNear(3f, request.width * 3000f / (request.height * 2000f))
    }

    // Moldura e controle

    @Test
    fun aMolduraQuadradaCabeNaMenorDimensaoDaArea() {
        assertEquals(FrameSize(260f, 260f), cropFrameSize(400f, 300f, 1, 1, 20f))
        assertEquals(FrameSize(360f, 360f), cropFrameSize(400f, 800f, 1, 1, 20f))
        assertEquals(FrameSize(360f, 360f), cropFrameSize(800f, 400f, 1, 1, 20f))
    }

    @Test
    fun aMolduraDoBannerEmPeELimitadaPelaLargura() {
        assertEquals(FrameSize(360f, 120f), cropFrameSize(400f, 800f, 3, 1, 20f))
    }

    @Test
    fun aMolduraDoBannerEmPaisagemPodeSerLimitadaPelaAltura() {
        assertEquals(FrameSize(300f, 100f), cropFrameSize(1000f, 140f, 3, 1, 20f))
    }

    @Test
    fun semEspacoOuComProporcaoInvalidaNaoHaMoldura() {
        assertNull(cropFrameSize(40f, 400f, 1, 1, 20f))
        assertNull(cropFrameSize(400f, 40f, 1, 1, 20f))
        assertNull(cropFrameSize(400f, 400f, 0, 1, 0f))
        assertNull(cropFrameSize(400f, 400f, 1, 0, 0f))
    }

    @Test
    fun oControleDeZoomELogaritmicoEVaiDe100A800() {
        assertEquals(0f, zoomToSlider(1f))
        assertNear(1f, zoomToSlider(CROP_MAX_ZOOM))
        assertNear(0.5f, zoomToSlider(kotlin.math.sqrt(CROP_MAX_ZOOM)))
        assertEquals(1f, sliderToZoom(0f))
        assertNear(CROP_MAX_ZOOM, sliderToZoom(1f))
        assertNear(2f, sliderToZoom(1f / 3f))
        listOf(1f, 1.7f, 4f, 7.5f).forEach { assertNear(it, sliderToZoom(zoomToSlider(it))) }
    }

    @Test
    fun oControleDeZoomLimitaOQueRecebe() {
        assertEquals(0f, zoomToSlider(0.2f))
        assertEquals(1f, zoomToSlider(100f))
        assertEquals(1f, sliderToZoom(-3f))
        assertEquals(CROP_MAX_ZOOM, sliderToZoom(3f))
    }

    @Test
    fun geometriaSemImagemOuSemMolduraELancada() {
        assertFailsWith<IllegalArgumentException> { CropGeometry(0, 100, 10f, 10f) }
        assertFailsWith<IllegalArgumentException> { CropGeometry(100, 0, 10f, 10f) }
        assertFailsWith<IllegalArgumentException> { CropGeometry(100, 100, 0f, 10f) }
        assertFailsWith<IllegalArgumentException> { CropGeometry(100, 100, 10f, 0f) }
    }

    // Retângulo em pixels

    @Test
    fun aRegiaoInicialDaOMesmoRetanguloDoCorteCentral() {
        listOf(4000 to 3000, 3000 to 4000, 1000 to 1000, 4001 to 3001).forEach { (width, height) ->
            ProfileImageKind.entries.forEach { kind ->
                val frame = cropFrameSize(1000f, 1000f, kind.aspectWidth, kind.aspectHeight, 0f)!!
                val geometry = CropGeometry(width, height, frame.width, frame.height)

                val plan = planManualCrop(width, height, kind, geometry.request(geometry.initial()))

                // Pode diferir em um pixel pelo arredondamento do centro; o tamanho final é o mesmo.
                val central = planCrop(width, height, kind)!!
                assertNotNull(plan)
                assertEquals(central.outWidth to central.outHeight, plan.outWidth to plan.outHeight, "$kind $width x $height")
                assertTrue(abs(central.left - plan.left) <= 1 && abs(central.top - plan.top) <= 1, "$kind $width x $height: $plan x $central")
            }
        }
    }

    @Test
    fun avatarDeUmaRegiaoCentralIgualAoCorteCentralDeAntes() {
        val plan = planManualCrop(4000, 3000, ProfileImageKind.Avatar, CropRequest(0, 0.125f, 0f, 0.75f, 1f))

        assertEquals(CropPlan(left = 500, top = 0, width = 3000, height = 3000, outWidth = 512, outHeight = 512), plan)
    }

    @Test
    fun bannerReduzAte1536Por512() {
        val plan = planManualCrop(3000, 2000, ProfileImageKind.Banner, CropRequest(0, 0f, 0.25f, 1f, 0.5f))

        assertEquals(CropPlan(left = 0, top = 500, width = 3000, height = 1000, outWidth = 1536, outHeight = 512), plan)
    }

    @Test
    fun comZoomAltoARegiaoPequenaNaoEAmpliada() {
        // 100 x 100 pixels no meio de uma foto de 4000 x 3000.
        val plan = planManualCrop(4000, 3000, ProfileImageKind.Avatar, CropRequest(0, 0.4875f, 0.48333f, 0.025f, 0.033333f))

        assertEquals(CropPlan(left = 1950, top = 1450, width = 100, height = 100, outWidth = 100, outHeight = 100), plan)
    }

    @Test
    fun umaRegiaoForaDeProporcaoFicaNaProporcaoDoTipoEmTornoDoCentro() {
        // Mais alta que larga para um banner: vale a menor medida, em 3:1.
        val plan = assertNotNull(planManualCrop(3000, 2000, ProfileImageKind.Banner, CropRequest(0, 0.25f, 0.25f, 0.5f, 0.5f)))

        assertEquals(CropPlan(left = 750, top = 750, width = 1500, height = 500, outWidth = 1500, outHeight = 500), plan)
    }

    @Test
    fun umaRegiaoMenorQueOMinimoDaProporcaoNaoPassaDasBordas() {
        // Uma região de menos de um pixel pede ao menos 3 x 1 para um banner; o retângulo é puxado para dentro da imagem.
        val atStart = assertNotNull(planManualCrop(1000, 1000, ProfileImageKind.Banner, CropRequest(0, 0f, 0f, 0.0004f, 0.0001f)))
        val atEnd = assertNotNull(planManualCrop(1000, 1000, ProfileImageKind.Banner, CropRequest(0, 0.9996f, 0.9999f, 0.0004f, 0.0001f)))

        assertEquals(CropPlan(left = 0, top = 0, width = 3, height = 1, outWidth = 3, outHeight = 1), atStart)
        assertEquals(CropPlan(left = 997, top = 999, width = 3, height = 1, outWidth = 3, outHeight = 1), atEnd)
    }

    @Test
    fun umaVoltaTrocaOsLadosEVoltaARegiaoParaAImagemDeOrigem() {
        // Girada 90 graus no sentido horário, a foto de 4000 x 3000 é 3000 x 4000; o quadrado de 1500 em (750, 1000) dela
        // é o de 1500 em (1000, 750) da foto.
        val plan = planManualCrop(4000, 3000, ProfileImageKind.Avatar, CropRequest(1, 0.25f, 0.25f, 0.5f, 0.375f))

        assertEquals(CropPlan(left = 1000, top = 750, width = 1500, height = 1500, outWidth = 512, outHeight = 512), plan)
    }

    @Test
    fun umaVoltaDeUmBannerTrocaTambemOTamanhoFinal() {
        // 6000 x 3000 girada é 3000 x 6000; o banner de 3000 x 1000 no meio dela é o de 1000 x 3000 da foto.
        val plan = assertNotNull(planManualCrop(6000, 3000, ProfileImageKind.Banner, CropRequest(1, 0f, 0.4166667f, 1f, 1f / 6f)))

        assertEquals(CropPlan(left = 2500, top = 0, width = 1000, height = 3000, outWidth = 512, outHeight = 1536), plan)
    }

    @Test
    fun umBannerPequenoGiradoNaoEAmpliado() {
        val plan = assertNotNull(planManualCrop(2400, 1200, ProfileImageKind.Banner, CropRequest(1, 0f, 0.4166667f, 1f, 1f / 6f)))

        assertEquals(CropPlan(left = 1000, top = 0, width = 400, height = 1200, outWidth = 400, outHeight = 1200), plan)
    }

    @Test
    fun duasVoltasInvertemOsDoisEixos() {
        val plan = planManualCrop(4000, 3000, ProfileImageKind.Avatar, CropRequest(2, 0f, 0f, 0.75f, 1f))

        assertEquals(CropPlan(left = 1000, top = 0, width = 3000, height = 3000, outWidth = 512, outHeight = 512), plan)
    }

    @Test
    fun tresVoltasLevamOCantoDeCimaEsquerdaDaFotoGiradaParaACimaDireita() {
        // Girada 270 graus no sentido horário, a foto é 3000 x 4000; o canto de cima à esquerda dela é o de cima à direita da foto.
        val plan = planManualCrop(4000, 3000, ProfileImageKind.Avatar, CropRequest(3, 0f, 0f, 0.5f, 0.375f))

        assertEquals(CropPlan(left = 2500, top = 0, width = 1500, height = 1500, outWidth = 512, outHeight = 512), plan)
    }

    @Test
    fun voltasForaDoIntervaloValemOResto() {
        val request = CropRequest(1, 0.25f, 0.25f, 0.5f, 0.375f)

        assertEquals(planManualCrop(4000, 3000, ProfileImageKind.Avatar, request), planManualCrop(4000, 3000, ProfileImageKind.Avatar, request.copy(quarterTurns = 5)))
        assertEquals(
            planManualCrop(4000, 3000, ProfileImageKind.Avatar, request.copy(quarterTurns = 3)),
            planManualCrop(4000, 3000, ProfileImageKind.Avatar, request.copy(quarterTurns = -1)),
        )
    }

    @Test
    fun umaRegiaoInvalidaOuUmaImagemPequenaDemaisNaoDaRetangulo() {
        val ok = CropRequest(0, 0f, 0f, 1f, 1f)
        assertNotNull(planManualCrop(100, 100, ProfileImageKind.Avatar, ok))
        assertNull(planManualCrop(0, 100, ProfileImageKind.Avatar, ok))
        assertNull(planManualCrop(100, 0, ProfileImageKind.Avatar, ok))
        assertNull(planManualCrop(100, 100, ProfileImageKind.Avatar, ok.copy(left = Float.NaN)))
        assertNull(planManualCrop(100, 100, ProfileImageKind.Avatar, ok.copy(top = Float.POSITIVE_INFINITY)))
        assertNull(planManualCrop(100, 100, ProfileImageKind.Avatar, ok.copy(width = Float.NaN)))
        assertNull(planManualCrop(100, 100, ProfileImageKind.Avatar, ok.copy(height = Float.NaN)))
        assertNull(planManualCrop(100, 100, ProfileImageKind.Avatar, ok.copy(left = -0.1f)))
        assertNull(planManualCrop(100, 100, ProfileImageKind.Avatar, ok.copy(top = -0.1f)))
        assertNull(planManualCrop(100, 100, ProfileImageKind.Avatar, ok.copy(width = 0f)))
        assertNull(planManualCrop(100, 100, ProfileImageKind.Avatar, ok.copy(height = 0f)))
        assertNull(planManualCrop(100, 100, ProfileImageKind.Avatar, ok.copy(left = 0.5f, width = 0.6f)))
        assertNull(planManualCrop(100, 100, ProfileImageKind.Avatar, ok.copy(top = 0.5f, height = 0.6f)))
        // Dois pixels de largura não dão um banner 3:1.
        assertNull(planManualCrop(2, 100, ProfileImageKind.Banner, ok))
        // O erro de ponto flutuante de uma região que termina na borda é aceito.
        assertNotNull(planManualCrop(100, 100, ProfileImageKind.Avatar, ok.copy(left = 0.5f, width = 0.50005f)))
    }
}
