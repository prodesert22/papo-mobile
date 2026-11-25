package chat.papo.mobile

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import chat.papo.mobile.ui.chat.ListMetrics
import chat.papo.mobile.ui.chat.MessagePart
import chat.papo.mobile.ui.chat.MessageText
import chat.papo.mobile.ui.chat.messageText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MessageTextTest {
    private val id = "0b0e7c1e-1111-2222-3333-444455556666"
    private val nbsp = " "

    // Recuo de 100 sp por nível e 10 sp por caractere do marcador (com o espaço).
    private val metrics = ListMetrics(step = 100.sp) { marker -> (marker.length * 10).sp }

    private fun build(content: String, emojis: Map<String, String> = emptyMap()) = messageText(
        content = content,
        nameOf = { if (it == id) "Ana" else null },
        accent = Color.Blue,
        codeBackground = Color.Gray,
        muted = Color.DarkGray,
        emojiIds = emojis,
        lists = metrics,
    )

    private fun MessageText.texts() = parts.map { (it as MessagePart.Text).text }

    private fun AnnotatedString.indents(): List<Pair<Float, Float>> =
        paragraphStyles.map { it.item.textIndent!!.let { indent -> indent.firstLine.value to indent.restLine.value } }

    private fun assertIndent(expectedFirst: Float, expectedRest: Float, actual: Pair<Float, Float>) {
        assertEquals(expectedFirst, actual.first, 0.01f)
        assertEquals(expectedRest, actual.second, 0.01f)
    }

    @Test
    fun textoSemListaEUmaPecaSemParagrafoComEstilo() {
        val built = build("oi, **tudo** bem?")
        val only = built.parts.single()
        assertIs<MessagePart.Text>(only)
        assertEquals("oi, tudo bem?", only.text.text)
        assertTrue(only.text.paragraphStyles.isEmpty())
        assertFalse(built.hasImages)
    }

    @Test
    fun estiloDeCadaTipoDeTrecho() {
        val text = build("**forte** *itálico* ~~riscado~~ `código` [site](https://x.test/p) @mention(<@$id>)").texts().single()
        fun styleOf(word: String) = text.spanStyles.single { text.text.substring(it.start, it.end) == word }.item
        assertEquals(FontWeight.SemiBold, styleOf("forte").fontWeight)
        assertEquals(FontStyle.Italic, styleOf("itálico").fontStyle)
        assertEquals(TextDecoration.LineThrough, styleOf("riscado").textDecoration)
        assertEquals(FontFamily.Monospace, styleOf("código").fontFamily)
        assertEquals(Color.Gray, styleOf("código").background)
        assertEquals(Color.Blue, styleOf("@Ana").color)
        assertEquals(FontWeight.SemiBold, styleOf("@Ana").fontWeight)
        val link = text.getLinkAnnotations(0, text.length).single()
        assertEquals(Color.Blue, (link.item as LinkAnnotation.Url).styles?.style?.color)
        assertEquals(TextDecoration.Underline, (link.item as LinkAnnotation.Url).styles?.style?.textDecoration)
    }

    @Test
    fun citacaoGanhaABarraEACorSuave() {
        val text = build("> citado").texts().single()
        assertEquals("▎ citado", text.text)
        assertEquals(Color.DarkGray, text.spanStyles.single().item.color)
        assertEquals(FontStyle.Italic, text.spanStyles.single().item.fontStyle)
    }

    @Test
    fun itemDeListaTemOMarcadorEORecuoPendente() {
        val item = build("- arroz").texts().single()
        assertEquals("•${nbsp}arroz", item.text)
        // Primeira linha em 0,3 do recuo do nível; as seguintes onde o texto começa (marcador de 1 caractere: 10 sp).
        assertIndent(30f, 40f, item.indents().single())
    }

    @Test
    fun cadaNivelRecuaUmPasso() {
        val texts = build("- a\n  - b\n    - c").texts()
        assertEquals(listOf("•${nbsp}a", "◦${nbsp}b", "▪${nbsp}c"), texts.map { it.text })
        assertIndent(30f, 40f, texts[0].indents().single())
        assertIndent(130f, 140f, texts[1].indents().single())
        assertIndent(230f, 240f, texts[2].indents().single())
    }

    @Test
    fun larguraDoMarcadorVemDaMedidaPassada() {
        val item = build("10. dez").texts().single()
        assertEquals("10.${nbsp}dez", item.text)
        assertIndent(30f, 30f + 30f, item.indents().single())
    }

    @Test
    fun linhasDeContinuacaoSaoTextosComecandoNoRecuoDoTexto() {
        val lines = build("- primeira\nsegunda\nterceira").texts()
        assertEquals(listOf("•${nbsp}primeira", "segunda", "terceira"), lines.map { it.text })
        assertIndent(30f, 40f, lines[0].indents().single())
        // A tabulação vale só para a primeira linha de um parágrafo: nas outras ela já é o recuo do texto.
        assertIndent(40f, 40f, lines[1].indents().single())
        assertIndent(40f, 40f, lines[2].indents().single())
    }

    @Test
    fun cadaItemEUmTextoSeparado() {
        // O `depois` vem colado no último item, então é continuação dele (só uma linha em branco encerra a lista).
        assertEquals(listOf("antes", "•${nbsp}a", "•${nbsp}b", "depois"), build("antes\n- a\n- b\ndepois").texts().map { it.text })
    }

    @Test
    fun textoDepoisDeLinhaEmBrancoFicaFora() {
        assertEquals(listOf("•${nbsp}a", "\nfim"), build("- a\n\nfim").texts().map { it.text })
    }

    @Test
    fun reguaSeparaDoisTextos() {
        val built = build("a\n\n***\n\nb")
        assertEquals(3, built.parts.size)
        assertEquals("a\n", (built.parts[0] as MessagePart.Text).text.text)
        assertSame(MessagePart.Divider, built.parts[1])
        assertEquals("\nb", (built.parts[2] as MessagePart.Text).text.text)
    }

    @Test
    fun soReguaNaoTemTexto() {
        assertEquals(listOf<MessagePart>(MessagePart.Divider), build("---").parts)
    }

    @Test
    fun linhaEmBrancoEntreItensOcupaUmaLinha() {
        val texts = build("- a\n\n- b").texts().map { it.text }
        assertEquals(listOf("•${nbsp}a", " ", "•${nbsp}b"), texts)
    }

    @Test
    fun mencoesViramNomeDentroDoItem() {
        val item = build("- oi @mention(<@$id>) e @mention(<@ffffffff-ffff-ffff-ffff-ffffffffffff>)").texts().single()
        assertEquals("•${nbsp}oi @Ana e @usuário", item.text)
    }

    @Test
    fun linkDentroDoItemContinuaLink() {
        val item = build("- veja https://x.test/p").texts().single()
        val link = item.getLinkAnnotations(0, item.length).single()
        assertIs<LinkAnnotation.Url>(link.item)
        assertEquals("https://x.test/p", (link.item as LinkAnnotation.Url).url)
    }

    @Test
    fun emojiDoServidorDentroDoItemPedeImagemEMantemOLiteralNoCodigo() {
        val built = build("- oi :party: e `:party:`", mapOf("party" to "e1"))
        assertEquals(setOf("e1"), built.emojiIds)
        assertTrue(built.hasImages)
        // No código o `:nome:` continua texto: só uma imagem foi pedida.
        assertTrue(built.texts().single().text.endsWith(":party:"))
    }

    @Test
    fun semEmojiDoServidorNaoPedeImagem() {
        val built = build("- oi :party:")
        assertTrue(built.emojiIds.isEmpty())
        assertFalse(built.hasImages)
        assertEquals("•${nbsp}oi :party:", built.texts().single().text)
    }

    @Test
    fun conteudoVazioNaoTemPecas() {
        assertTrue(build("").parts.isEmpty())
    }

    @Test
    fun codigoCercadoNaoTemRecuoNemMarcador() {
        val texts = build("- a\n```\nx\n```\n- b").texts()
        assertEquals(listOf("•${nbsp}a", "x", "•${nbsp}b"), texts.map { it.text })
        assertTrue(texts[1].paragraphStyles.isEmpty())
    }

    @Test
    fun tarefaUsaACaixaComoMarcador() {
        val texts = build("- [ ] a\n- [x] b").texts()
        assertEquals(listOf("☐${nbsp}a", "☑${nbsp}b"), texts.map { it.text })
        assertNotNull(texts[0].paragraphStyles.single().item.textIndent)
    }
}
