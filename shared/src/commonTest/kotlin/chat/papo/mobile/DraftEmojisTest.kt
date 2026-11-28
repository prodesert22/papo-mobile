package chat.papo.mobile

import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import chat.papo.mobile.chat.EmojiDraft
import chat.papo.mobile.ui.chat.DraftEmojis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** O que o compositor mostra no lugar de cada `:nome:` e como entrega a edição ao rascunho. */
class DraftEmojisTest {
    private val emojis = DraftEmojis(mapOf("ok" to "id-ok", "party" to "id-party"))
    private val slot = "\u2003\u2060"

    @Test
    fun cadaEmojiViraUmEspacoDeUmEmSeguidoDeUmJuntaPalavras() {
        val shown = emojis.filter(AnnotatedString("oi :ok: e :party:!")).text
        assertEquals("oi $slot e $slot!", shown.text)
        assertEquals(EmojiDraft.SLOT_LENGTH, slot.length)
        assertTrue(shown.spanStyles.isEmpty())
    }

    @Test
    fun semEmojiOTextoEOMapeamentoSaoOsMesmos() {
        val text = AnnotatedString("oi :nao_existe: `:ok:` fim")
        val shown = emojis.filter(text)
        assertSame(text, shown.text)
        assertSame(OffsetMapping.Identity, shown.offsetMapping)
    }

    @Test
    fun oMapeamentoLevaAsPosicoesDeUmTextoParaOOutro() {
        val shown = emojis.filter(AnnotatedString("a:ok:b:party:"))
        val mapping = shown.offsetMapping
        assertEquals(listOf(0, 1, 2, 4, 5), listOf(0, 1, 5, 6, 13).map { mapping.originalToTransformed(it) })
        assertEquals(listOf(0, 1, 5, 5, 6, 13, 13), (0..6).map { mapping.transformedToOriginal(it) })
        // Dentro do emoji só há as bordas: o começo dele.
        assertEquals(1, mapping.originalToTransformed(3))
    }

    @Test
    fun achaOsEmojisDoTextoEGuardaAUltimaBusca() {
        val first = emojis.find("a :ok:")
        assertEquals(listOf(2 to 6), first.map { it.start to it.end })
        assertSame(first, emojis.find("a :ok:"))
        assertEquals(emptyList(), emojis.find("a ok"))
    }

    @Test
    fun apagarDepoisDoEmojiEntregaOTextoSemOEmojiESemComposicao() {
        val previous = TextFieldValue("oi :ok:", TextRange(7))
        val typed = TextFieldValue("oi :ok", TextRange(6), composition = TextRange(3, 6))

        val settled = emojis.settle(previous, typed)

        assertEquals("oi ", settled.text)
        assertEquals(TextRange(3), settled.selection)
        assertNull(settled.composition)
    }

    @Test
    fun cursorQueEntrouNoEmojiSaiParaABordaSemMexerNoResto() {
        val previous = TextFieldValue("a:ok:b", TextRange(1))
        val typed = TextFieldValue("a:ok:b", TextRange(3), composition = TextRange(0, 1))

        val settled = emojis.settle(previous, typed)

        assertEquals(TextRange(5), settled.selection)
        assertEquals("a:ok:b", settled.text)
        assertEquals(TextRange(0, 1), settled.composition)
    }

    @Test
    fun edicaoQueNaoTocaNosEmojisDevolveOMesmoValor() {
        val previous = TextFieldValue("a:ok:b", TextRange(6))
        val typed = TextFieldValue("a:ok:bc", TextRange(7))
        assertSame(typed, emojis.settle(previous, typed))
    }

    @Test
    fun semCatalogoOCampoSeComportaComoAntes() {
        val none = DraftEmojis(emptyMap())
        val previous = TextFieldValue("a:ok:", TextRange(5))
        val typed = TextFieldValue("a:ok", TextRange(4))
        assertSame(typed, none.settle(previous, typed))
        assertSame(OffsetMapping.Identity, none.filter(AnnotatedString("a:ok:")).offsetMapping)
        assertNotNull(none.find(":ok:"))
        assertTrue(none.find(":ok:").isEmpty())
    }

    // O campo de `TextFieldState`: a troca para a tela (`output`) e a edição acertada (`input`), como o campo as aplica.

    /** O que o campo mostra para [text]: a troca de `output` aplicada a um estado novo. */
    private fun shown(text: String): String {
        val state = TextFieldState(text)
        state.edit { with(emojis.output) { transformOutput() } }
        return state.text.toString()
    }

    /**
     * O estado depois de o usuário editar [text] (cursor em [cursor]) com [change] e o campo aplicar `input`: a edição e a
     * transformação acontecem no mesmo `edit`, como na entrada do usuário, em que o original é o texto de antes.
     */
    private fun typed(text: String, cursor: Int, change: TextFieldBuffer.() -> Unit): TextFieldState {
        val state = TextFieldState(text, TextRange(cursor))
        state.edit {
            change()
            with(emojis.input) { transformInput() }
        }
        return state
    }

    @Test
    fun oCampoMostraCadaEmojiComoUmEspacoDeUmEmSeguidoDeUmJuntaPalavras() {
        assertEquals("oi $slot e $slot!", shown("oi :ok: e :party:!"))
    }

    @Test
    fun oCampoNaoTrocaOQueNaoEEmoji() {
        assertEquals("oi :nao_existe: `:ok:` fim", shown("oi :nao_existe: `:ok:` fim"))
        assertEquals("", shown(""))
    }

    @Test
    fun apagarOFimDoEmojiNoCampoApagaOEmojiInteiro() {
        val state = typed("oi :ok:", 7) {
            replace(length - 1, length, "") // o teclado apagou o `:` do fim
            selection = TextRange(length)
        }
        assertEquals("oi ", state.text.toString())
        assertEquals(TextRange(3), state.selection)
    }

    @Test
    fun digitarDepoisDoEmojiNoCampoNaoMexeNoTexto() {
        val state = typed("oi :ok:", 7) {
            replace(length, length, "!")
            selection = TextRange(length)
        }
        assertEquals("oi :ok:!", state.text.toString())
        assertEquals(TextRange(8), state.selection)
    }

    @Test
    fun cursorQueEntrouNoEmojiNoCampoSaiParaABorda() {
        val state = typed("a:ok:b", 1) { selection = TextRange(3) }
        assertEquals("a:ok:b", state.text.toString())
        assertEquals(TextRange(5), state.selection)
    }

    @Test
    fun editarTextoSemEmojiNoCampoFicaComoFoi() {
        val state = typed("oi", 2) {
            replace(length, length, " tudo")
            selection = TextRange(length)
        }
        assertEquals("oi tudo", state.text.toString())
        assertEquals(TextRange(7), state.selection)
    }

    @Test
    fun shownOffsetDescontaOsEmojisAntesDaPosicao() {
        val text = "a :ok: @joao"
        assertEquals(2, emojis.shownOffset(text, 2))
        assertEquals(2 + slot.length + 1, emojis.shownOffset(text, 7))
        assertEquals(2 + slot.length + 6, emojis.shownOffset(text, text.length))
    }
}
