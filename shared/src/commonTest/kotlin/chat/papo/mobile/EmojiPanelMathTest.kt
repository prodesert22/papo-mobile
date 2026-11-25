package chat.papo.mobile

import chat.papo.mobile.ui.chat.EmojiPanelMath
import chat.papo.mobile.ui.chat.EmojiPanelMode
import kotlin.test.Test
import kotlin.test.assertEquals

class EmojiPanelMathTest {
    private val collapsed = 800f
    private val max = 1800f
    private val search = 500f

    private fun visible(
        mode: EmojiPanelMode = EmojiPanelMode.Open,
        extra: Float = 0f,
        keyboard: Float = 0f,
        searching: Boolean = false,
    ) = EmojiPanelMath.visible(mode, collapsed, extra, keyboard, searching, search, max)

    @Test
    fun fechadaNaoOcupaNada() {
        assertEquals(0f, visible(EmojiPanelMode.Hidden))
        assertEquals(0f, visible(EmojiPanelMode.Hidden, extra = 300f))
    }

    @Test
    fun abertaTemAAlturaDoTeclado() {
        assertEquals(collapsed, visible())
    }

    @Test
    fun naTrocaAGavetaMaisOTecladoSomamSempreAMesmaAltura() {
        // Teclado descendo (abrindo a gaveta) ou subindo (voltando ao teclado):
        // o campo de mensagem fica parado, porque gaveta + teclado = altura do teclado.
        for (keyboard in listOf(800f, 600f, 300f, 50f, 0f)) {
            assertEquals(collapsed, visible(keyboard = keyboard) + keyboard)
            assertEquals(collapsed, visible(EmojiPanelMode.ToKeyboard, keyboard = keyboard) + keyboard)
        }
    }

    @Test
    fun arrastarParaCimaCresceAteOLimite() {
        assertEquals(collapsed + 400f, visible(extra = 400f))
        assertEquals(max, visible(extra = 5000f))
    }

    @Test
    fun arrastarParaBaixoEncolheAteZero() {
        assertEquals(collapsed - 300f, visible(extra = -300f))
        assertEquals(0f, visible(extra = -collapsed))
        assertEquals(0f, visible(extra = -5000f))
    }

    @Test
    fun naBuscaFicaAcimaDoTecladoComPeloMenosAAlturaDaBusca() {
        // Teclado da busca aberto: sobra a altura da busca acima dele.
        assertEquals(search, visible(keyboard = 800f, searching = true))
        // Enquanto o teclado ainda sobe, a gaveta só encolhe até a altura da busca.
        assertEquals(collapsed - 100f, visible(keyboard = 100f, searching = true))
    }

    @Test
    fun limiteMenorQueABuscaMandaNoLimite() {
        assertEquals(300f, EmojiPanelMath.visible(EmojiPanelMode.Open, collapsed, 0f, 800f, true, search, 300f))
    }

    private fun target(extra: Float, velocity: Float = 0f) =
        EmojiPanelMath.settleTarget(extra, velocity, collapsed, expandedExtra = 1000f, flingThreshold = 1500f)

    @Test
    fun semArremessoVaiParaOPontoMaisPerto() {
        assertEquals(0f, target(100f))
        assertEquals(1000f, target(700f))
        assertEquals(0f, target(-300f))
        assertEquals(-collapsed, target(-600f)) // passou da metade para baixo: fecha
    }

    @Test
    fun arremessoParaCimaAbreParaBaixoRecolheOuFecha() {
        assertEquals(1000f, target(50f, velocity = -3000f))
        assertEquals(0f, target(-200f, velocity = -3000f)) // vindo de baixo, volta para o tamanho do teclado
        assertEquals(0f, target(900f, velocity = 3000f)) // aberta inteira: recolhe
        assertEquals(-collapsed, target(-50f, velocity = 3000f)) // recolhida: fecha
    }
}
