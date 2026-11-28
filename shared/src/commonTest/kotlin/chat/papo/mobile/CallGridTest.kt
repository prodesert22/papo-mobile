package chat.papo.mobile

import chat.papo.mobile.ui.call.GridFit
import chat.papo.mobile.ui.call.fitGrid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CallGridTest {
    private fun fits(fit: GridFit, count: Int, width: Float, height: Float, aspect: Float, gap: Float) {
        val rows = (count + fit.columns - 1) / fit.columns
        assertTrue(fit.columns * fit.tileWidth + gap * (fit.columns - 1) <= width + 0.01f, "largura: $fit")
        assertTrue(rows * fit.tileWidth / aspect + gap * (rows - 1) <= height + 0.01f, "altura: $fit")
    }

    @Test
    fun umQuadroNaHorizontalOcupaAAlturaToda() {
        val fit = fitGrid(1, 800f, 360f, 4f / 3f, 8f)
        assertEquals(1, fit.columns)
        assertEquals(480f, fit.tileWidth, 0.01f)
    }

    @Test
    fun doisQuadrosNaHorizontalFicamLadoALado() {
        val fit = fitGrid(2, 800f, 360f, 4f / 3f, 8f)
        assertEquals(2, fit.columns)
        fits(fit, 2, 800f, 360f, 4f / 3f, 8f)
    }

    @Test
    fun doisQuadrosNaVerticalFicamUmEmCimaDoOutro() {
        val fit = fitGrid(2, 360f, 700f, 4f / 3f, 8f)
        assertEquals(1, fit.columns)
        fits(fit, 2, 360f, 700f, 4f / 3f, 8f)
    }

    @Test
    fun muitosQuadrosCabemSemRolagem() {
        for (count in 3..12) {
            val fit = fitGrid(count, 800f, 360f, 16f / 9f, 8f)
            fits(fit, count, 800f, 360f, 16f / 9f, 8f)
            assertTrue(fit.tileWidth > 0f)
        }
    }

    @Test
    fun cincoQuadrosNaHorizontalUsamTresColunas() {
        assertEquals(3, fitGrid(5, 800f, 360f, 4f / 3f, 8f).columns)
    }

    @Test
    fun areaOuContagemVaziaNaoQuebra() {
        assertEquals(GridFit(1, 0f), fitGrid(0, 800f, 360f, 4f / 3f, 8f))
        assertEquals(GridFit(1, 0f), fitGrid(3, 0f, 360f, 4f / 3f, 8f))
        assertEquals(GridFit(1, 0f), fitGrid(3, 800f, 0f, 4f / 3f, 8f))
    }
}
