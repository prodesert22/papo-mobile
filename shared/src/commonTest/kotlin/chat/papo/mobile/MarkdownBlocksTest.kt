package chat.papo.mobile

import chat.papo.mobile.chat.Markdown
import chat.papo.mobile.chat.MarkdownBlock
import chat.papo.mobile.chat.TextSpan
import kotlin.test.Test
import kotlin.test.assertEquals

/** Listas, tarefas, réguas e títulos com sublinhado, como o cliente web (marked + GFM) mostra. */
class MarkdownBlocksTest {
    private fun blocks(text: String) = Markdown.blocks(text)

    private fun item(level: Int, marker: String, text: String) = MarkdownBlock.ListItem(level, marker, listOf(TextSpan(text)))

    private fun para(text: String) = MarkdownBlock.Paragraph(listOf(TextSpan(text)))

    private val blank = MarkdownBlock.Paragraph(emptyList())

    private val rule = MarkdownBlock.Rule

    private fun markers(text: String) = blocks(text).map { (it as MarkdownBlock.ListItem).marker }

    // Marcadores

    @Test
    fun marcadoresDeListaSemNumero() {
        assertEquals(listOf(item(0, "•", "a"), item(0, "•", "b"), item(0, "•", "c")), blocks("- a\n* b\n+ c"))
    }

    @Test
    fun listaOrdenadaComPontoOuParentese() {
        assertEquals(listOf(item(0, "1.", "a"), item(0, "2.", "b")), blocks("1. a\n2. b"))
        assertEquals(listOf(item(0, "1.", "a"), item(0, "2.", "b")), blocks("1) a\n2) b"))
    }

    @Test
    fun listaOrdenadaComecaNoNumeroDoPrimeiroItemERenumera() {
        assertEquals(listOf("3.", "4.", "5."), markers("3. a\n3. b\n3. c"))
        assertEquals(listOf("1.", "2.", "3."), markers("1. a\n1. b\n1. c"))
        assertEquals(listOf("9.", "10.", "11."), markers("9. a\n1. b\n7. c"))
        assertEquals(listOf("123456789."), markers("123456789. a"))
    }

    @Test
    fun numeroComDezDigitosNaoELista() {
        assertEquals(listOf(para("1234567890. a")), blocks("1234567890. a"))
    }

    @Test
    fun marcadorSemEspacoOuSemTextoNaoELista() {
        assertEquals(listOf(para("-a")), blocks("-a"))
        assertEquals(listOf(para("1.a")), blocks("1.a"))
        assertEquals(listOf(para("-")), blocks("-"))
        assertEquals(listOf(para("1.")), blocks("1."))
    }

    @Test
    fun tabulacaoDepoisDoMarcador() {
        assertEquals(listOf(item(0, "•", "a")), blocks("-\ta"))
        assertEquals(listOf(item(0, "1.", "a")), blocks("1.\ta"))
    }

    @Test
    fun trocarOMarcadorComecaOutraLista() {
        // Como no marked: `1)` depois de `1.` abre uma lista nova e a numeração volta.
        assertEquals(listOf("1.", "1."), markers("1. a\n1) b"))
        assertEquals(listOf("1.", "•"), markers("1. a\n- b"))
        assertEquals(listOf("•", "2."), markers("- a\n2. b"))
    }

    // Linhas em branco

    @Test
    fun linhaEmBrancoEncerraAListaEOutraComecaNoSeuNumero() {
        assertEquals(listOf(item(0, "1.", "a"), blank, item(0, "2.", "b")), blocks("1. a\n\n2. b"))
        assertEquals(listOf(item(0, "1.", "a"), blank, item(0, "1.", "b")), blocks("1. a\n\n1. b"))
        assertEquals(listOf(item(0, "•", "a"), MarkdownBlock.Paragraph(listOf(TextSpan("\n"))), item(0, "•", "b")), blocks("- a\n\n\n- b"))
    }

    @Test
    fun linhaEmBrancoAntesDeTextoFicaNoTexto() {
        assertEquals(listOf(item(0, "•", "a"), para("\nb")), blocks("- a\n\nb"))
        assertEquals(listOf(para("antes\n"), item(0, "•", "a")), blocks("antes\n\n- a"))
    }

    @Test
    fun quebraNoFimNaoViraLinhaEmBranco() {
        assertEquals(listOf(item(0, "•", "a")), blocks("- a\n"))
        assertEquals(listOf(item(0, "•", "a"), blank), blocks("- a\n\n"))
        assertEquals(listOf(item(0, "•", "a"), rule), blocks("- a\n---\n"))
    }

    // Aninhamento

    @Test
    fun aninhamentoPorRecuoAteTresNiveis() {
        assertEquals(
            listOf(item(0, "•", "a"), item(1, "◦", "b"), item(2, "▪", "c")),
            blocks("- a\n  - b\n    - c"),
        )
    }

    @Test
    fun niveisMaisFundosFicamNoTerceiro() {
        assertEquals(
            listOf(item(0, "•", "a"), item(1, "◦", "b"), item(2, "▪", "c"), item(2, "▪", "d"), item(2, "▪", "e")),
            blocks("- a\n  - b\n    - c\n      - d\n        - e"),
        )
    }

    @Test
    fun recuoMenorQueOTextoDoItemEIrmao() {
        // O texto de `- a` começa na coluna 2: um espaço só não aninha.
        assertEquals(listOf(item(0, "•", "a"), item(0, "•", "b")), blocks("- a\n - b"))
        // O de `1. a` começa na coluna 3: dois espaços não bastam e o marcador muda, então é outra lista.
        assertEquals(listOf(item(0, "1.", "a"), item(0, "•", "b")), blocks("1. a\n  - b"))
        assertEquals(listOf(item(0, "1.", "a"), item(1, "◦", "b")), blocks("1. a\n   - b"))
    }

    @Test
    fun recuoDeQuatroEspacosEUmaTabulacaoAninham() {
        assertEquals(listOf(item(0, "•", "a"), item(1, "◦", "b")), blocks("- a\n    - b"))
        assertEquals(listOf(item(0, "•", "a"), item(1, "◦", "b")), blocks("- a\n\t- b"))
    }

    @Test
    fun voltarDeNivelContinuaANumeracaoDoNivel() {
        assertEquals(
            listOf(item(0, "1.", "a"), item(1, "◦", "x"), item(0, "2.", "b")),
            blocks("1. a\n   - x\n2. b"),
        )
        assertEquals(
            listOf(item(0, "•", "a"), item(1, "1.", "b"), item(1, "2.", "c"), item(0, "•", "d")),
            blocks("- a\n  1. b\n  2. c\n- d"),
        )
    }

    @Test
    fun irmaosComRecuosDiferentesNoMesmoNivel() {
        assertEquals(listOf("•", "2.", "3."), markers("- p\n   2. a\n  1. b"))
    }

    @Test
    fun cincoEspacosOuMaisDepoisDoMarcadorContamComoUmSo() {
        // O texto do item começa na coluna 2 (marcador e um espaço), então dois espaços já aninham.
        assertEquals(listOf(item(0, "•", "a"), item(1, "◦", "b")), blocks("-     a\n  - b"))
    }

    @Test
    fun itemComRecuoDeAteTresEspacosNoComecoEAindaLista() {
        assertEquals(listOf(item(0, "•", "a")), blocks("   - a"))
    }

    @Test
    fun recuoDeQuatroEspacosNoComecoFicaTexto() {
        assertEquals(listOf(para("    - a")), blocks("    - a"))
    }

    // Continuação

    @Test
    fun linhaComumLogoDepoisDoItemContinuaOItem() {
        assertEquals(
            listOf(MarkdownBlock.ListItem(0, "•", listOf(TextSpan("a\nlazy"))), item(0, "•", "b")),
            blocks("- a\nlazy\n- b"),
        )
        assertEquals(
            listOf(MarkdownBlock.ListItem(0, "1.", listOf(TextSpan("a\nb"))), item(0, "2.", "c")),
            blocks("1. a\n   b\n2. c"),
        )
    }

    @Test
    fun continuacaoTemEstiloEmLinha() {
        assertEquals(
            MarkdownBlock.ListItem(0, "•", listOf(TextSpan("a\n"), TextSpan("b", bold = true))),
            blocks("- a\n**b**").single(),
        )
    }

    @Test
    fun tituloCitacaoERéguaEncerramAContinuacao() {
        assertEquals(listOf(item(0, "•", "a"), MarkdownBlock.Paragraph(listOf(TextSpan("h", bold = true)))), blocks("- a\n# h"))
        assertEquals(
            listOf(item(0, "•", "a"), MarkdownBlock.Paragraph(listOf(TextSpan("q", italic = true, quote = true)))),
            blocks("- a\n> q"),
        )
        assertEquals(listOf(item(0, "•", "a"), rule), blocks("- a\n---"))
    }

    // Interrupção de parágrafo

    @Test
    fun bulletEListaDeUmInterrompemOParagrafo() {
        assertEquals(listOf(para("texto"), item(0, "•", "a")), blocks("texto\n- a"))
        assertEquals(listOf(para("texto"), item(0, "1.", "a")), blocks("texto\n1. a"))
    }

    @Test
    fun listaQueNaoComecaEmUmNaoInterrompeOParagrafo() {
        assertEquals(listOf(para("texto\n2. a")), blocks("texto\n2. a"))
        assertEquals(listOf(para("texto\n2) a\n3. b")), blocks("texto\n2) a\n3. b"))
    }

    @Test
    fun listaQueNaoComecaEmUmComecaDepoisDeLinhaEmBrancoTituloOuCitacao() {
        assertEquals(listOf(para("texto\n"), item(0, "2.", "a")), blocks("texto\n\n2. a"))
        assertEquals(listOf(MarkdownBlock.Paragraph(listOf(TextSpan("T", bold = true))), item(0, "2.", "a")), blocks("# T\n2. a"))
        assertEquals(listOf(MarkdownBlock.Paragraph(listOf(TextSpan("q", italic = true, quote = true))), item(0, "2.", "a")), blocks("> q\n2. a"))
    }

    @Test
    fun listaQueNaoComecaEmUmPodeSeguirOutraLista() {
        assertEquals(listOf(item(0, "•", "a"), item(0, "2.", "b")), blocks("- a\n2. b"))
    }

    // Tarefas

    @Test
    fun tarefasViramCaixas() {
        assertEquals(
            listOf(item(0, "☐", "a"), item(0, "☑", "b"), item(0, "☑", "c")),
            blocks("- [ ] a\n- [x] b\n- [X] c"),
        )
    }

    @Test
    fun tarefaEmListaOrdenadaENoAninhamento() {
        assertEquals(listOf(item(0, "☐", "a"), item(1, "☑", "b")), blocks("1. [ ] a\n   - [x] b"))
        // O número continua contando por baixo da caixa.
        assertEquals(listOf("☐", "2.", "☑"), markers("1. [ ] a\n2. b\n3. [x] c"))
    }

    @Test
    fun colchetesQueNaoSaoTarefaFicamNoTexto() {
        assertEquals(listOf(item(0, "•", "[ ]a")), blocks("- [ ]a"))
        assertEquals(listOf(item(0, "•", "[y] a")), blocks("- [y] a"))
        assertEquals(listOf(item(0, "•", "[] a")), blocks("- [] a"))
        assertEquals(listOf(item(0, "•", "[ ]")), blocks("- [ ]"))
    }

    // Réguas

    @Test
    fun formasDeRegua() {
        for (line in listOf("---", "***", "___", "- - -", "* * *", "_ _ _", "-----", "   ---", "- -  - ")) {
            assertEquals(listOf(rule), blocks(line), line)
        }
    }

    @Test
    fun doisTracosOuMisturaNaoSaoRegua() {
        assertEquals(listOf(para("--")), blocks("--"))
        assertEquals(listOf(para("-*-")), blocks("-*-"))
        assertEquals(listOf(para("---a")), blocks("---a"))
        assertEquals(listOf(para("    ---")), blocks("    ---"))
    }

    @Test
    fun reguaSeparaOsTextos() {
        assertEquals(listOf(para("a\n"), rule, para("\nb")), blocks("a\n\n---\n\nb"))
        assertEquals(listOf(para("a"), rule, para("b")), blocks("a\n***\nb"))
        assertEquals(listOf(rule, rule), blocks("---\n***"))
    }

    // Título com sublinhado

    @Test
    fun linhaSeguidaDeTracosFicaEmNegrito() {
        assertEquals(listOf(MarkdownBlock.Paragraph(listOf(TextSpan("texto", bold = true)))), blocks("texto\n---"))
        assertEquals(listOf(MarkdownBlock.Paragraph(listOf(TextSpan("texto", bold = true)))), blocks("texto\n==="))
        assertEquals(listOf(MarkdownBlock.Paragraph(listOf(TextSpan("texto", bold = true)))), blocks("texto\n-"))
    }

    @Test
    fun tituloComSublinhadoPegaOParagrafoTodoENaoAsLinhasDeAntes() {
        assertEquals(
            listOf(MarkdownBlock.Paragraph(listOf(TextSpan("a", bold = true), TextSpan("\n"), TextSpan("b", bold = true)))),
            blocks("a\nb\n---"),
        )
        // Uma linha em branco, um título ou uma citação delimitam o parágrafo.
        assertEquals(
            listOf(MarkdownBlock.Paragraph(listOf(TextSpan("a\n\n"), TextSpan("b", bold = true)))),
            blocks("a\n\nb\n---"),
        )
        assertEquals(
            listOf(MarkdownBlock.Paragraph(listOf(TextSpan("T", bold = true), TextSpan("\n"), TextSpan("b", bold = true)))),
            blocks("# T\nb\n---"),
        )
    }

    @Test
    fun tituloComSublinhadoMantemOEstiloEmLinha() {
        assertEquals(
            listOf(MarkdownBlock.Paragraph(listOf(TextSpan("a", bold = true, italic = true), TextSpan(" b", bold = true)))),
            blocks("*a* b\n---"),
        )
    }

    @Test
    fun sublinhadoSemTextoEmCimaOuNoMeioDeListaNaoFazTitulo() {
        assertEquals(listOf(para("===")), blocks("==="))
        assertEquals(listOf(rule), blocks("---"))
        assertEquals(listOf(item(0, "•", "a"), rule), blocks("- a\n---"))
        assertEquals(listOf(MarkdownBlock.ListItem(0, "•", listOf(TextSpan("a\n===")))), blocks("- a\n==="))
        // Depois de um título ou de outro sublinhado, o traço é régua.
        assertEquals(listOf(MarkdownBlock.Paragraph(listOf(TextSpan("T", bold = true))), rule), blocks("# T\n---"))
        assertEquals(listOf(MarkdownBlock.Paragraph(listOf(TextSpan("a", bold = true))), rule), blocks("a\n---\n---"))
    }

    @Test
    fun reguaComEspacosENaoTituloSeVemDepoisDeTexto() {
        assertEquals(listOf(para("texto"), rule), blocks("texto\n- - -"))
        assertEquals(listOf(para("texto"), rule), blocks("texto\n***"))
        assertEquals(listOf(para("texto"), rule), blocks("texto\n___"))
    }

    @Test
    fun linhaComAparenciaDeItemNoParagrafoImpedeOTitulo() {
        // Como no marked: o parágrafo não vira título e o traço é uma régua.
        assertEquals(listOf(para("abc\n2) y"), rule), blocks("abc\n2) y\n---"))
    }

    // O que não é lista

    @Test
    fun italicoNoComecoDaLinhaNaoELista() {
        assertEquals(listOf(MarkdownBlock.Paragraph(listOf(TextSpan("itálico", italic = true), TextSpan(" texto")))), blocks("*itálico* texto"))
        assertEquals(
            listOf(MarkdownBlock.Paragraph(listOf(TextSpan("a", italic = true), TextSpan("\n"), TextSpan("b", italic = true)))),
            blocks("*a*\n*b*"),
        )
        assertEquals(listOf(MarkdownBlock.Paragraph(listOf(TextSpan("negrito", bold = true)))), blocks("**negrito**"))
        assertEquals(listOf(para("2 * 3 = 6")), blocks("2 * 3 = 6"))
    }

    @Test
    fun tabelasContinuamTexto() {
        val table = "|a|b|\n|-|-|\n|1|2|"
        assertEquals(listOf(para(table)), blocks(table))
        assertEquals(listOf(para("a | b\n--- | ---\n1 | 2")), blocks("a | b\n--- | ---\n1 | 2"))
    }

    // Código

    @Test
    fun blocoDeCodigoEncerraAListaEOutraComecaDepois() {
        val code = MarkdownBlock.Paragraph(listOf(TextSpan("x", code = true)))
        assertEquals(listOf(item(0, "•", "a"), code, item(0, "•", "b")), blocks("- a\n```\nx\n```\n- b"))
        assertEquals(listOf(item(0, "1.", "a"), code, item(0, "2.", "b")), blocks("1. a\n```\nx\n```\n2. b"))
    }

    @Test
    fun codigoCercadoNaoViraListaNemRegua() {
        assertEquals(
            listOf(MarkdownBlock.Paragraph(listOf(TextSpan("- a\n---\n1. b", code = true)))),
            blocks("```\n- a\n---\n1. b\n```"),
        )
    }

    @Test
    fun codigoNaMesmaLinhaDoMarcadorFicaTexto() {
        assertEquals(
            listOf(MarkdownBlock.Paragraph(listOf(TextSpan("- a "), TextSpan("x", code = true)))),
            blocks("- a ```\nx\n```"),
        )
    }

    @Test
    fun codigoEmLinhaDentroDoItem() {
        assertEquals(
            MarkdownBlock.ListItem(0, "•", listOf(TextSpan("rode "), TextSpan("ls", code = true))),
            blocks("- rode `ls`").single(),
        )
    }

    @Test
    fun codigoComTextoAoRedorMantemAsQuebras() {
        assertEquals(
            listOf(MarkdownBlock.Paragraph(listOf(TextSpan("antes\n"), TextSpan("x = 1", code = true), TextSpan("\ndepois")))),
            blocks("antes\n```kotlin\nx = 1\n```\ndepois"),
        )
        // A quebra no fim do texto só termina a última linha; não é uma linha em branco a mais.
        assertEquals(
            listOf(item(0, "•", "a"), MarkdownBlock.Paragraph(listOf(TextSpan("x", code = true)))),
            blocks("- a\n```\nx\n```\n"),
        )
    }

    // Citações e títulos

    @Test
    fun citacaoComMarcadorDeListaContinuaCitacao() {
        assertEquals(
            listOf(MarkdownBlock.Paragraph(listOf(TextSpan("- a", italic = true, quote = true)))),
            blocks("> - a"),
        )
    }

    @Test
    fun citacaoVaziaMantemALinhaEmBranco() {
        assertEquals(
            listOf(
                MarkdownBlock.Paragraph(
                    listOf(TextSpan("a", italic = true, quote = true), TextSpan("\n\n"), TextSpan("b", italic = true, quote = true)),
                ),
            ),
            blocks("> a\n>\n> b"),
        )
    }

    @Test
    fun listaDepoisDeCitacaoETitulo() {
        assertEquals(
            listOf(MarkdownBlock.Paragraph(listOf(TextSpan("q", italic = true, quote = true))), item(0, "•", "a")),
            blocks("> q\n- a"),
        )
        assertEquals(listOf(MarkdownBlock.Paragraph(listOf(TextSpan("T", bold = true))), item(0, "•", "a")), blocks("# T\n- a"))
    }

    // Texto em linha dentro do item

    @Test
    fun itemTemNegritoLinkEMencao() {
        val id = "0b0e7c1e-1111-2222-3333-444455556666"
        assertEquals(
            MarkdownBlock.ListItem(
                0,
                "•",
                listOf(
                    TextSpan("a", bold = true),
                    TextSpan(" "),
                    TextSpan("@mention(<@$id>)", mentionUserId = id),
                    TextSpan(" "),
                    TextSpan("site", link = "https://x.test/p"),
                ),
            ),
            blocks("- **a** @mention(<@$id>) [site](https://x.test/p)").single(),
        )
    }

    @Test
    fun espacosNoFimDoItemSaem() {
        assertEquals(listOf(item(0, "•", "a")), blocks("- a   "))
    }

    // Compatibilidade

    @Test
    fun conteudoVazioNaoTemBlocos() {
        assertEquals(emptyList(), blocks(""))
    }

    @Test
    fun textoSemEstruturaEUmParagrafoIgualAoParse() {
        val inputs = listOf(
            "oi, tudo bem?",
            "linha 1\nlinha 2",
            "isso é **muito** bom",
            "## Título\ntexto",
            "> citado",
            "rode `ls **-la**` aqui",
            "antes\n```kotlin\nx = 1\n```\ndepois",
            "veja https://papo.example.com/a_b.",
            "texto\n\noutro",
            "texto com fim\n",
            "```\nsolto\n```",
            "```\nx\n```\n",
            "a ```\nx\n``` b",
            "   ",
        )
        for (input in inputs) {
            assertEquals(listOf(MarkdownBlock.Paragraph(Markdown.parse(input))), blocks(input), input)
        }
    }

    @Test
    fun parseDeixaListasEReguasComoTexto() {
        assertEquals(listOf(TextSpan("- a\n- b")), Markdown.parse("- a\n- b"))
        assertEquals(listOf(TextSpan("1. a\n---\ntexto\n===")), Markdown.parse("1. a\n---\ntexto\n==="))
    }

    @Test
    fun parseDeTextoVazioNaoTemTrechos() {
        assertEquals(emptyList(), Markdown.parse(""))
    }
}
