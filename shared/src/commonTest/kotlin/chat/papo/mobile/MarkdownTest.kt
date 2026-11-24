package chat.papo.mobile

import chat.papo.mobile.chat.Markdown
import chat.papo.mobile.chat.TextSpan
import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownTest {
    private fun p(text: String) = Markdown.parse(text)

    @Test
    fun textoSimplesFicaIntacto() {
        assertEquals(listOf(TextSpan("oi, tudo bem?")), p("oi, tudo bem?"))
        assertEquals(listOf(TextSpan("linha 1\nlinha 2")), p("linha 1\nlinha 2"))
    }

    @Test
    fun enfases() {
        assertEquals(listOf(TextSpan("teste", bold = true)), p("**teste**"))
        assertEquals(listOf(TextSpan("teste", bold = true)), p("__teste__"))
        assertEquals(listOf(TextSpan("teste", italic = true)), p("*teste*"))
        assertEquals(listOf(TextSpan("teste", italic = true)), p("_teste_"))
        assertEquals(listOf(TextSpan("teste", strike = true)), p("~~teste~~"))
        assertEquals(listOf(TextSpan("teste", bold = true, italic = true)), p("***teste***"))
        assertEquals(listOf(TextSpan("teste", bold = true, italic = true)), p("___teste___"))
    }

    @Test
    fun enfasesAninhadasENoMeioDoTexto() {
        assertEquals(listOf(TextSpan("teste", bold = true, strike = true)), p("~~**teste**~~"))
        assertEquals(listOf(TextSpan("teste", bold = true, italic = true)), p("__*teste*__"))
        assertEquals(
            listOf(TextSpan("isso é "), TextSpan("muito", bold = true), TextSpan(" bom")),
            p("isso é **muito** bom"),
        )
    }

    @Test
    fun marcadoresSemParFicamLiterais() {
        assertEquals(listOf(TextSpan("2 * 3 = 6")), p("2 * 3 = 6"))
        assertEquals(listOf(TextSpan("**sem fechar")), p("**sem fechar"))
        assertEquals(listOf(TextSpan("arquivo snake_case_longo.txt")), p("arquivo snake_case_longo.txt"))
        assertEquals(listOf(TextSpan("*literal*")), p("""\*literal\*"""))
    }

    @Test
    fun codigoEmLinhaEEmBloco() {
        assertEquals(
            listOf(TextSpan("rode "), TextSpan("ls **-la**", code = true), TextSpan(" aqui")),
            p("rode `ls **-la**` aqui"),
        )
        // O que vem colado na cerca é o nome da linguagem, não código.
        assertEquals(listOf(TextSpan("a\nb\nc", code = true)), p("```teste\na\nb\nc\n```"))
        assertEquals(listOf(TextSpan("solto", code = true)), p("```\nsolto\n```"))
        assertEquals(
            listOf(TextSpan("antes\n"), TextSpan("x = 1", code = true), TextSpan("\ndepois")),
            p("antes\n```kotlin\nx = 1\n```\ndepois"),
        )
    }

    @Test
    fun titulosECitacoes() {
        assertEquals(listOf(TextSpan("Título", bold = true), TextSpan("\ntexto")), p("## Título\ntexto"))
        assertEquals(listOf(TextSpan("citado", italic = true, quote = true)), p("> citado"))
        assertEquals(listOf(TextSpan("#semespaço")), p("#semespaço"))
    }

    @Test
    fun linksEMencoes() {
        val id = "0b0e7c1e-1111-2222-3333-444455556666"
        assertEquals(
            listOf(TextSpan("veja "), TextSpan("https://papo.example.com/a_b", link = "https://papo.example.com/a_b"), TextSpan(".")),
            p("veja https://papo.example.com/a_b."),
        )
        assertEquals(
            listOf(TextSpan("o "), TextSpan("site", link = "https://x.test/p"), TextSpan(" novo")),
            p("o [site](https://x.test/p) novo"),
        )
        assertEquals(
            listOf(TextSpan("oi "), TextSpan("@mention(<@$id>)", mentionUserId = id), TextSpan("!")),
            p("oi @mention(<@$id>)!"),
        )
        assertEquals(
            listOf(TextSpan("@mention(<@$id>)", bold = true, mentionUserId = id)),
            p("**@mention(<@$id>)**"),
        )
        // Links com esquema perigoso não viram link.
        assertEquals(listOf(TextSpan("[x](javascript:alert(1))")), p("[x](javascript:alert(1))"))
    }
}
