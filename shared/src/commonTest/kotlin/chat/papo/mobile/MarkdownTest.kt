package chat.papo.mobile

import chat.papo.mobile.chat.Markdown
import chat.papo.mobile.chat.MarkdownBlock
import chat.papo.mobile.chat.TextSpan
import chat.papo.mobile.chat.hidesDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

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

    // @everyone

    private fun everyone(text: String) = TextSpan(text, everyone = true)

    @Test
    fun everyoneViraUmTrechoProprioSemDiferenciarMaiusculas() {
        assertEquals(listOf(TextSpan("oi "), everyone("@everyone"), TextSpan("!")), p("oi @everyone!"))
        assertEquals(listOf(everyone("@EVERYONE")), p("@EVERYONE"))
        assertEquals(listOf(TextSpan("a "), everyone("@Everyone"), TextSpan(" b")), p("a @Everyone b"))
    }

    @Test
    fun everyoneSoCasaAteALetraDigitoOuSublinhadoAscii() {
        assertEquals(listOf(TextSpan("@everyones")), p("@everyones"))
        assertEquals(listOf(TextSpan("@everyone_x")), p("@everyone_x"))
        assertEquals(listOf(TextSpan("@everyone1")), p("@everyone1"))
        assertEquals(listOf(everyone("@everyone"), TextSpan("é")), p("@everyoneé"))
        assertEquals(listOf(everyone("@everyone"), TextSpan(".")), p("@everyone."))
    }

    @Test
    fun arrobaQueNaoEEveryoneFicaComoTexto() {
        assertEquals(listOf(TextSpan("@every")), p("@every")) // curto demais para caber `@everyone`
        assertEquals(listOf(TextSpan("oi @everyon")), p("oi @everyon"))
        assertEquals(listOf(TextSpan("@joaozinho!")), p("@joaozinho!")) // cabe, mas é outro nome
        assertEquals(listOf(TextSpan("@")), p("@"))
    }

    @Test
    fun everyoneColadoNumaPalavraTambemCasaComoNoWeb() {
        // O servidor e o web casam em qualquer lugar do texto; só o dourado pede início ou espaço antes.
        assertEquals(listOf(TextSpan("x"), everyone("@everyone")), p("x@everyone"))
    }

    @Test
    fun everyoneEmCodigoEmLinkOuEscapadoNaoViraTrecho() {
        assertEquals(listOf(TextSpan("@everyone", code = true)), p("`@everyone`"))
        assertEquals(listOf(TextSpan("@everyone", code = true)), p("```\n@everyone\n```"))
        assertEquals(listOf(TextSpan("@everyone", link = "https://x.test/p")), p("[@everyone](https://x.test/p)"))
        assertEquals(
            listOf(TextSpan("https://x.test/@everyone", link = "https://x.test/@everyone")),
            p("https://x.test/@everyone"),
        )
        assertEquals(listOf(TextSpan("@everyone")), p("\\@everyone"))
    }

    @Test
    fun everyoneHerdaOEstiloDeOndeEstaEDoisSeguidosNaoSeFundem() {
        assertEquals(listOf(TextSpan("@everyone", bold = true, everyone = true)), p("**@everyone**"))
        assertEquals(listOf(everyone("@everyone"), everyone("@everyone")), p("@everyone@everyone"))
        assertEquals(
            listOf(TextSpan("hey ", italic = true, quote = true), TextSpan("@everyone", italic = true, quote = true, everyone = true)),
            p("> hey @everyone"),
        )
    }

    @Test
    fun everyoneEmTituloELista() {
        assertEquals(listOf(TextSpan("@everyone", bold = true, everyone = true)), p("# @everyone"))
        assertEquals(
            listOf(MarkdownBlock.ListItem(0, "•", listOf(TextSpan("avisem "), everyone("@everyone")))),
            Markdown.blocks("- avisem @everyone"),
        )
    }

    @Test
    fun semOEveryoneOTextoVoltaAoQueEraAntesDeOReconhecer() {
        assertEquals(listOf(TextSpan("oi @everyone!")), Markdown.withoutEveryone(p("oi @everyone!")))
        assertEquals(
            listOf(TextSpan("hey @everyone", italic = true, quote = true)),
            Markdown.withoutEveryone(p("> hey @everyone")),
        )
        assertEquals(
            listOf(TextSpan("a "), TextSpan("b", bold = true), TextSpan(" @everyone")),
            Markdown.withoutEveryone(p("a **b** @everyone")),
        )
    }

    @Test
    fun semOEveryoneNaoMexeNosOutrosTrechosNemCopiaALista() {
        val plain = p("oi **tudo** `bem` [site](https://x.test/p)")
        assertSame(plain, Markdown.withoutEveryone(plain))
        assertEquals(emptyList(), Markdown.withoutEveryone(emptyList()))
    }

    // literalRanges: onde o texto é literal e um `:nome:` não vira emoji.

    @Test
    fun codigoEmLinhaEUmTrechoLiteralComAsCrases() {
        assertEquals(listOf(0..5), Markdown.literalRanges("`:ok:` e"))
        assertEquals(listOf(2..7), Markdown.literalRanges("a `b :c` d"))
    }

    @Test
    fun blocoDeCodigoEUmTrechoLiteralComAsCercas() {
        assertEquals(listOf(2..10), Markdown.literalRanges("a\n```\nb\n```\nc"))
    }

    @Test
    fun linkNomeadoEEnderecoSoltoSaoLiterais() {
        assertEquals(listOf(0..17), Markdown.literalRanges("[x](https://a.com) y"))
        // O ponto do fim da frase não faz parte do endereço.
        assertEquals(listOf(5..19), Markdown.literalRanges("veja https://a.com/x."))
    }

    @Test
    fun trechosDentroDeEnfaseEEmOutraLinhaContamDoInicioDoTexto() {
        assertEquals(listOf(4..6), Markdown.literalRanges("**a `b` c**"))
        assertEquals(listOf(2..7), Markdown.literalRanges("x\n`:ok:`"))
    }

    @Test
    fun semCodigoNemLinkNaoHaTrechoLiteral() {
        assertEquals(emptyList(), Markdown.literalRanges("oi :ok: e **negrito**"))
        assertEquals(emptyList(), Markdown.literalRanges(""))
        assertEquals(emptyList(), Markdown.literalRanges("`sem fechar"))
        assertEquals(emptyList(), Markdown.literalRanges("\\`escapada\\` e `` vazia"))
    }

    // `www.`: vira link `https://www...`, com as bordas do cliente de terminal.

    private fun www(text: String) = TextSpan(text, link = "https://$text")

    @Test
    fun wwwNoInicioDoTextoViraLinkHttps() {
        assertEquals(listOf(www("www.exemplo.com")), p("www.exemplo.com"))
        assertEquals(listOf(TextSpan("abra "), www("www.exemplo.com/a?b=1"), TextSpan(" já")), p("abra www.exemplo.com/a?b=1 já"))
    }

    @Test
    fun wwwDepoisDeParentesePontuacaoOuAspaVira() {
        assertEquals(listOf(TextSpan("("), www("www.exemplo.com"), TextSpan(")")), p("(www.exemplo.com)"))
        assertEquals(listOf(TextSpan("\""), www("www.exemplo.com"), TextSpan("\"")), p("\"www.exemplo.com\""))
        assertEquals(listOf(TextSpan("[x] "), www("www.exemplo.com")), p("[x] www.exemplo.com"))
    }

    @Test
    fun wwwNaoViraLinkNoMeioDePalavraNemDeEnderecoMaior() {
        assertEquals(listOf(TextSpan("awww.exemplo.com")), p("awww.exemplo.com"))
        assertEquals(listOf(TextSpan("1www.exemplo.com")), p("1www.exemplo.com"))
        assertEquals(listOf(TextSpan("x/www.exemplo.com")), p("x/www.exemplo.com"))
        assertEquals(listOf(TextSpan("a.www.exemplo.com")), p("a.www.exemplo.com"))
        assertEquals(listOf(TextSpan("a-www.exemplo.com")), p("a-www.exemplo.com"))
        assertEquals(listOf(TextSpan("a_www.exemplo.com")), p("a_www.exemplo.com"))
        assertEquals(listOf(TextSpan("x:www.exemplo.com")), p("x:www.exemplo.com"))
        // Depois de um caractere fora do ASCII, mesmo que seja aspa tipográfica.
        assertEquals(listOf(TextSpan("éwww.exemplo.com")), p("éwww.exemplo.com"))
        assertEquals(listOf(TextSpan("“www.exemplo.com”")), p("“www.exemplo.com”"))
    }

    @Test
    fun wwwEmEnderecoDeEmailNaoViraLink() {
        assertEquals(listOf(TextSpan("ana@www.exemplo.com")), p("ana@www.exemplo.com"))
        assertEquals(listOf(TextSpan("ana@wwwx.com")), p("ana@wwwx.com"))
    }

    @Test
    fun wwwSemPontoDepoisDoTresOuSemNomeDeSiteNaoViraLink() {
        assertEquals(listOf(TextSpan("wwwx.com")), p("wwwx.com"))
        assertEquals(listOf(TextSpan("www")), p("www"))
        assertEquals(listOf(TextSpan("www.")), p("www."))
        assertEquals(listOf(TextSpan("www. exemplo")), p("www. exemplo"))
        assertEquals(listOf(TextSpan("www.-exemplo.com")), p("www.-exemplo.com"))
        assertEquals(listOf(TextSpan("www..com")), p("www..com"))
    }

    @Test
    fun wwwComLetraForaDoAsciiNoNomeDoSiteVira() {
        assertEquals(listOf(www("www.é.com")), p("www.é.com"))
    }

    @Test
    fun wwwTiraAPontuacaoDaFraseDoFimDoEndereco() {
        assertEquals(listOf(TextSpan("veja "), www("www.exemplo.com"), TextSpan(".")), p("veja www.exemplo.com."))
        assertEquals(listOf(www("www.exemplo.com/a"), TextSpan(", ok")), p("www.exemplo.com/a, ok"))
        assertEquals(listOf(www("www.exemplo.com"), TextSpan("?!")), p("www.exemplo.com?!"))
        assertEquals(listOf(TextSpan("("), www("www.exemplo.com/a"), TextSpan(");")), p("(www.exemplo.com/a);"))
        assertEquals(listOf(www("www.exemplo.com"), TextSpan(":")), p("www.exemplo.com:"))
    }

    @Test
    fun wwwAceitaMaiusculasEGuardaOTextoComoFoiEscrito() {
        assertEquals(listOf(www("WWW.Exemplo.COM")), p("WWW.Exemplo.COM"))
        assertEquals(listOf(www("Www.exemplo.com")), p("Www.exemplo.com"))
    }

    @Test
    fun wwwHerdaOEstiloEFicaForaDeCodigo() {
        assertEquals(listOf(TextSpan("www.exemplo.com", bold = true, link = "https://www.exemplo.com")), p("**www.exemplo.com**"))
        assertEquals(listOf(TextSpan("www.exemplo.com", code = true)), p("`www.exemplo.com`"))
        assertEquals(listOf(TextSpan("www.exemplo.com", code = true)), p("```\nwww.exemplo.com\n```"))
    }

    @Test
    fun wwwDentroDeOutroEnderecoFicaNoEnderecoDeFora() {
        val url = "https://exemplo.com/www.outro.com"
        assertEquals(listOf(TextSpan(url, link = url)), p(url))
        assertEquals(listOf(TextSpan("a "), www("www.a.com/www.b.com")), p("a www.a.com/www.b.com"))
    }

    @Test
    fun wwwEUmTrechoLiteralParaOsEmojisDoCompositor() {
        assertEquals(listOf(5..15), Markdown.literalRanges("veja www.a.com/x."))
        assertEquals(emptyList(), Markdown.literalRanges("veja awww.a.com"))
    }

    // linksIn: o destino de cada link, para a folha de ações da mensagem.

    @Test
    fun linksInDevolveODestinoNaOrdemEmQueAparece() {
        assertEquals(
            listOf("https://a.test/1", "https://b.test", "https://www.c.test/x"),
            Markdown.linksIn("veja https://a.test/1, [site](https://b.test) e www.c.test/x."),
        )
    }

    @Test
    fun linksInTrazOEnderecoDoLinkNomeadoEDoWwwComEsquema() {
        assertEquals(listOf("https://outro.example"), Markdown.linksIn("[banco](https://outro.example)"))
        assertEquals(listOf("https://www.exemplo.com"), Markdown.linksIn("www.exemplo.com"))
    }

    @Test
    fun linksInNaoRepeteODestino() {
        assertEquals(listOf("https://a.test"), Markdown.linksIn("https://a.test e [de novo](https://a.test) e https://a.test"))
    }

    @Test
    fun linksInIgnoraCodigoEEsquemaPerigosoESemLinkDevolveVazio() {
        assertEquals(emptyList(), Markdown.linksIn("`https://a.test` e ```\nwww.a.test\n```"))
        assertEquals(emptyList(), Markdown.linksIn("[x](javascript:alert(1)) e ftp://a.test"))
        assertEquals(emptyList(), Markdown.linksIn("sem nada"))
        assertEquals(emptyList(), Markdown.linksIn(""))
    }

    @Test
    fun linksInEnxergaOsLinksDeItensDeListaEDeCitacao() {
        assertEquals(listOf("https://a.test", "https://b.test"), Markdown.linksIn("- https://a.test\n> [b](https://b.test)"))
    }

    // hidesDestination: o texto do link esconde para onde ele leva?

    @Test
    fun linkNomeadoComTextoDiferenteEscondeODestino() {
        assertEquals(true, p("[banco](https://outro.example)").single().hidesDestination())
        assertEquals(true, p("[https://banco.example](https://outro.example)").single().hidesDestination())
        assertEquals(true, p("[www.banco.example](https://www.banco.example.outro.example)").single().hidesDestination())
    }

    @Test
    fun enderecoSoltoENomeadoComOProprioEnderecoNaoEscondemNada() {
        assertEquals(false, p("https://a.test/x").single().hidesDestination())
        assertEquals(false, p("www.a.test/x").single().hidesDestination())
        assertEquals(false, p("[https://a.test](https://a.test)").single().hidesDestination())
        assertEquals(false, p("[www.a.test](https://www.a.test)").single().hidesDestination())
    }

    @Test
    fun trechoSemLinkNaoEscondeNada() {
        assertEquals(false, TextSpan("oi").hidesDestination())
    }
}
