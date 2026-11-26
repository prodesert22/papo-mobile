package chat.papo.mobile

import androidx.compose.ui.graphics.Color
import chat.papo.mobile.chat.DraftField
import chat.papo.mobile.chat.EmojiDraft
import chat.papo.mobile.chat.EmojiMatch
import chat.papo.mobile.ui.chat.messageText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Os `:nome:` do rascunho que o compositor mostra como imagem, e a edição deles como um caractere só. */
class EmojiDraftTest {
    private val names = mapOf("ok" to "id-ok", "party" to "id-party", "a_b" to "id-ab", "x1" to "id-x1")

    private fun find(text: String) = EmojiDraft.find(text, names)

    private fun ranges(text: String) = find(text).map { it.start to it.end }

    /** O cursor marcado por `|`, ou a seleção entre `[` e `]`. */
    private fun field(marked: String): DraftField {
        val cursor = marked.indexOf('|')
        if (cursor >= 0) return DraftField(marked.replace("|", ""), cursor)
        val start = marked.indexOf('[')
        return DraftField(marked.replace("[", "").replace("]", ""), start, marked.indexOf(']') - 1)
    }

    private fun show(field: DraftField): String {
        if (field.collapsed) return field.text.substring(0, field.selectionStart) + "|" + field.text.substring(field.selectionStart)
        val low = minOf(field.selectionStart, field.selectionEnd)
        val high = maxOf(field.selectionStart, field.selectionEnd)
        return field.text.substring(0, low) + "[" + field.text.substring(low, high) + "]" + field.text.substring(high)
    }

    /** O que o campo faz com uma tecla: [before] é o rascunho de antes e [after] o que ele entrega; devolve o rascunho acertado. */
    private fun settle(before: String, after: String): String {
        val previous = field(before)
        return show(EmojiDraft.settle(previous, find(previous.text), field(after), names))
    }

    // find

    @Test
    fun soAchaOsNomesQueAContaTem() {
        assertEquals(
            listOf(EmojiMatch(3, 7, "ok", "id-ok"), EmojiMatch(21, 28, "party", "id-party")),
            find("oi :ok: :nao_existe: :party:"),
        )
    }

    @Test
    fun semCatalogoOuSemDoisPontosNaoHaEmoji() {
        assertTrue(EmojiDraft.find(":ok:", emptyMap()).isEmpty())
        assertTrue(find("ok sem nada").isEmpty())
        assertTrue(find("").isEmpty())
    }

    @Test
    fun doisEmojisSeguidosSaoDoisIntervalos() {
        assertEquals(listOf(0 to 4, 4 to 11), ranges(":ok::party:"))
    }

    @Test
    fun emojiColadoNoTextoEmQualquerLugar() {
        assertEquals(listOf(2 to 6), ranges("ab:ok:cd"))
        assertEquals(listOf(0 to 4), ranges(":ok:cd"))
        assertEquals(listOf(2 to 6), ranges("ab:ok:"))
    }

    @Test
    fun doisPontosSoltosNaoViramEmoji() {
        assertTrue(find("10:30:45 e : ok : e :ok e ok:").isEmpty())
        assertTrue(find("::").isEmpty())
        assertEquals(listOf(0 to 4), ranges(":ok:ok:"))
    }

    @Test
    fun nomeDesconhecidoConsomeOsDoisPontosComoNaMensagem() {
        // `:nao:` é lido (e ignorado) primeiro e leva o `:` de `:ok:`, igual ao que a mensagem faz.
        assertTrue(find(":nao:ok:").isEmpty())
    }

    @Test
    fun emojiNoInicioENoFim() {
        val text = ":ok: meio :party:"
        assertEquals(listOf(0 to 4, 10 to 17), ranges(text))
        assertEquals(text.length, find(text).last().end)
    }

    @Test
    fun acentoEEmojiUnicodeNoMeioContamEmUtf16() {
        // "ação " tem 5 unidades, o 😀 tem 2 (par substituto) e o espaço depois dele é a 8ª.
        val text = "ação 😀 :ok:"
        assertEquals(12, text.length)
        assertEquals(listOf(8 to 12), ranges(text))
    }

    @Test
    fun nomeComSublinhadoELetrasENumeros() {
        assertEquals(listOf(0 to 5, 6 to 10), ranges(":a_b: :x1:"))
    }

    @Test
    fun codigoEmLinhaNaoViraImagem() {
        assertEquals(listOf(9 to 16), ranges("`:ok:` e :party:"))
        assertEquals(listOf(9 to 13), ranges("`:ok:` e :x1:"))
    }

    @Test
    fun blocoDeCodigoEDeLinkContinuamTexto() {
        assertEquals(listOf(16 to 20), ranges("```\n:party:\n``` :ok:"))
        assertTrue(find("```\n:ok:\n```").isEmpty())
        assertTrue(find("[veja :ok:](https://exemplo.com/a)").isEmpty())
        assertTrue(find("https://exemplo.com/:ok:").isEmpty())
    }

    @Test
    fun crasesQueNaoFechamNaoSaoCodigo() {
        assertEquals(1, find("`:ok:").size)
        assertEquals(1, find("\\`:ok:`").size)
    }

    @Test
    fun igualAoQueAMensagemMostra() {
        val cases = listOf(
            "oi :ok: e :party:",
            "`:ok:` e :party:",
            "``:ok:`` e :party:",
            "**:ok:** e _:party:_",
            "```\n:ok:\n``` e :party:",
            "```kotlin\n:ok:```:party:",
            "# :ok:\n> :party:\n- :a_b:\n",
            "[:ok:](https://exemplo.com) :party:",
            "veja https://exemplo.com/:ok: e :party:",
            "\\`:ok:\\` e :party:",
            "`:ok: :party:",
            "texto `código` :ok:\n`outro :party:`\n:a_b:",
        )
        for (content in cases) {
            val shown = messageText(content, { null }, Color.Blue, Color.Gray, Color.DarkGray, emojiIds = names).emojiIds
            assertEquals(shown, find(content).map { it.id }.toSet(), "o rascunho e a mensagem divergem em: $content")
        }
    }

    // posições

    @Test
    fun posicoesDoRascunhoNoTextoMostrado() {
        // "a:ok:b:party:" aparece como "a#b#": cada emoji vira um caractere.
        val emojis = find("a:ok:b:party:")
        assertEquals(listOf(0, 1, 1, 1, 1, 2, 3, 3, 3, 3, 3, 3, 3, 4), (0..13).map { EmojiDraft.toShown(emojis, it) })
    }

    @Test
    fun posicoesDoTextoMostradoNoRascunho() {
        val emojis = find("a:ok:b:party:")
        assertEquals(listOf(0, 1, 5, 6, 13), (0..4).map { EmojiDraft.toDraft(emojis, it) })
    }

    @Test
    fun doisEmojisColadosTemUmaBordaSoEntreEles() {
        val emojis = find(":ok::party:")
        assertEquals(listOf(0, 4, 11), (0..2).map { EmojiDraft.toDraft(emojis, it) })
        assertEquals(listOf(0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 2), (0..11).map { EmojiDraft.toShown(emojis, it) })
    }

    @Test
    fun semEmojiAsPosicoesNaoMudam() {
        assertEquals(7, EmojiDraft.toShown(emptyList(), 7))
        assertEquals(7, EmojiDraft.toDraft(emptyList(), 7))
    }

    @Test
    fun idaEVoltaDasPosicoesComAcentoEEmojiUnicode() {
        val emojis = find("ação 😀 :ok: fim")
        for (shown in 0.."ação 😀 # fim".length) {
            assertEquals(shown, EmojiDraft.toShown(emojis, EmojiDraft.toDraft(emojis, shown)), "posição mostrada $shown")
        }
    }

    // a edição trata o emoji como um caractere só

    @Test
    fun apagarLogoDepoisDoEmojiLevaOEmojiInteiro() {
        assertEquals("oi |", settle("oi :ok:|", "oi :ok|"))
        assertEquals("|", settle(":ok:|", ":ok|"))
    }

    @Test
    fun apagarDepoisDoPrimeiroDeDoisEmojisSeguidosLevaOPrimeiro() {
        assertEquals("|:party:", settle(":ok:|:party:", ":ok|:party:"))
    }

    @Test
    fun apagarDepoisDoSegundoDeDoisEmojisSeguidosLevaOSegundo() {
        assertEquals(":ok:|", settle(":ok::party:|", ":ok::party|"))
    }

    @Test
    fun apagarParaAFrenteAntesDoEmojiLevaOEmojiInteiro() {
        assertEquals("oi |", settle("oi |:ok:", "oi |ok:"))
        assertEquals("oi |fim", settle("oi |:ok:fim", "oi |ok:fim"))
    }

    @Test
    fun apagarUmCaractereComumNaoMexeNosEmojisDeFora() {
        assertEquals("o|:ok:", settle("oi|:ok:", "o|:ok:"))
        assertEquals(":ok:o|", settle(":ok:oi|", ":ok:o|"))
    }

    @Test
    fun nomeDesconhecidoApagaDeCaractereEmCaractere() {
        assertEquals("a:nao_existe|", settle("a:nao_existe:|", "a:nao_existe|"))
    }

    @Test
    fun apagarUmaSelecaoQueCortaUmEmojiLevaOEmojiTodo() {
        assertEquals("u| fim", settle("u[m :o]k: fim", "u|k: fim"))
    }

    @Test
    fun substituirUmaSelecaoQueCortaUmEmojiTrocaOEmojiTodo() {
        assertEquals("uX| fim", settle("u[m :o]k: fim", "uX|k: fim"))
    }

    @Test
    fun selecaoQueCobreOEmojiInteiroApagaSoOQueCobre() {
        assertEquals("u|fim", settle("u[ :ok: ]fim", "u|fim"))
    }

    @Test
    fun digitarNaBordaDoEmojiNaoMexeNele() {
        assertEquals(":ok:x|", settle(":ok:|", ":ok:x|"))
        assertEquals("x|:ok:", settle("|:ok:", "x|:ok:"))
        assertEquals(":ok:x|:party:", settle(":ok:|:party:", ":ok:x|:party:"))
    }

    @Test
    fun textoQueEntraNoMeioDoEmojiEntraDepoisDele() {
        assertEquals(":ok:x|", settle(":o|k:", ":ox|k:"))
    }

    @Test
    fun colarUmEmojiNaBordaDeOutroMantemOsDois() {
        assertEquals("a:ok::party:|b", settle("a:ok:|b", "a:ok::party:|b"))
    }

    @Test
    fun editarComAcentoEEmojiUnicodeAntes() {
        assertEquals("ação 😀 |", settle("ação 😀 :ok:|", "ação 😀 :ok|"))
        assertEquals("ação 😀 :ok:|", settle("ação 😀 |:ok:", "ação 😀 :|ok:"))
    }

    // cursor e seleção

    @Test
    fun cursorNasBordasFicaOndeEsta() {
        for (marked in listOf("|:ok: fim", ":ok:| fim", "x|:ok:", "x:ok:|", ":ok::party:|", ":ok:|:party:", "|:ok::party:")) {
            val same = field(marked)
            assertEquals(marked, show(EmojiDraft.settle(same, find(same.text), same, names)), marked)
        }
    }

    @Test
    fun cursorQueAndaParaOMeioDoEmojiAtravessaNoSentidoDoMovimento() {
        assertEquals("a:ok:|b", settle("a|:ok:b", "a:|ok:b"))
        assertEquals("a:ok:|b", settle("a|:ok:b", "a:o|k:b"))
        assertEquals("a|:ok:b", settle("a:ok:|b", "a:ok|:b"))
        assertEquals("a|:ok:b", settle("a:ok:|b", "a:o|k:b"))
    }

    @Test
    fun cursorNoMeioSemSentidoVaiParaABordaMaisPerto() {
        // O texto mudou e o cursor ficou dentro do emoji, sem um movimento anterior para dar o sentido.
        val before = DraftField("a", 1)
        assertEquals(DraftField("a:ok:b", 1), EmojiDraft.settle(before, emptyList(), DraftField("a:ok:b", 2), names))
        assertEquals(DraftField("a:ok:b", 5), EmojiDraft.settle(before, emptyList(), DraftField("a:ok:b", 4), names))
        // No empate, vai para o fim.
        assertEquals(DraftField("x:ok:", 5), EmojiDraft.settle(before, emptyList(), DraftField("x:ok:", 3), names))
    }

    @Test
    fun selecaoCrescePraPegarOEmojiInteiro() {
        assertEquals("a[:ok:b]c", settle("a|:ok:bc", "a:o[k:b]c"))
        assertEquals("[a:ok:]b", settle("|a:ok:b", "[a:o]k:b"))
        assertEquals("a[:ok:]b", settle("a|:ok:b", "a:[o]k:b"))
    }

    @Test
    fun selecaoDeTrasParaFrenteContinuaDeTrasParaFrente() {
        val start = DraftField("a:ok:bc", 0)
        val reversed = DraftField("a:ok:bc", 6, 3)
        assertEquals(DraftField("a:ok:bc", 6, 1), EmojiDraft.settle(start, find(start.text), reversed, names))
    }

    @Test
    fun selecaoNasBordasNaoMuda() {
        val field = DraftField("a:ok:b", 1, 5)
        assertEquals(field, EmojiDraft.settle(field, find(field.text), field, names))
    }

    @Test
    fun semEmojiNadaMuda() {
        val previous = DraftField("ola mundo", 9)
        val next = DraftField("ola mund", 8)
        assertEquals(next, EmojiDraft.settle(previous, emptyList(), next, names))
    }
}
