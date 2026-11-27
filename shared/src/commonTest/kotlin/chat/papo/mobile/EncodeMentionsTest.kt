package chat.papo.mobile

import chat.papo.mobile.chat.User
import chat.papo.mobile.chat.encodeMentions
import chat.papo.mobile.chat.DraftMention
import chat.papo.mobile.chat.findEveryone
import chat.papo.mobile.chat.findMentions
import kotlin.test.Test
import kotlin.test.assertEquals

class EncodeMentionsTest {
    private val joao = "0b0e7c1e-0000-4000-8000-00000000000a"
    private val bia = "0b0e7c1e-0000-4000-8000-00000000000b"
    private val ana = "0b0e7c1e-0000-4000-8000-00000000000c"

    private fun user(id: String, username: String) =
        User(id, username, nickname = null, statusMessage = null, typingPhrase = null, banned = false, roleColor = null, avatarVersion = 0)

    private val users = listOf(user(joao, "joao"), user(bia, "bia"), user(ana, "ana.silva"))

    private fun mention(id: String) = "@mention(<@$id>)"

    private fun encode(text: String, picked: Map<String, String> = emptyMap(), who: List<User> = users) =
        encodeMentions(text, picked, who)

    // Nome digitado à mão

    @Test
    fun nomeDigitadoQueCasaComUmSoUsuarioViraMencao() {
        assertEquals("oi ${mention(joao)}, tudo bem?", encode("oi @joao, tudo bem?"))
        assertEquals("${mention(joao)} e ${mention(bia)}", encode("@joao e @bia"))
        assertEquals("${mention(joao)} ${mention(joao)}", encode("@joao @joao"))
    }

    @Test
    fun maiusculasNaoImportamParaOUsernameDigitado() {
        assertEquals(mention(joao), encode("@JoAo"))
    }

    @Test
    fun doisUsernamesQueSoDiferemNaCaixaDeixamOTextoComoEstaMasOEscolhidoVale() {
        val twins = listOf(user(joao, "Joao"), user(bia, "joao"))
        assertEquals("oi @joao", encode("oi @joao", who = twins))
        assertEquals("oi ${mention(bia)}", encode("oi @joao", picked = mapOf("joao" to bia), who = twins))
    }

    @Test
    fun nomeQueNaoExisteOuSoEPrefixoFicaComoTexto() {
        assertEquals("oi @maria e @joaozinho e @joa", encode("oi @maria e @joaozinho e @joa"))
        assertEquals("@", encode("@"))
        assertEquals("fim @", encode("fim @"))
        assertEquals("@ joao", encode("@ joao"))
    }

    @Test
    fun acentoNaoCasaComUsernameSemAcento() {
        // A regra é só "sem diferenciar maiúsculas": @joão não é @joao.
        assertEquals("@joão", encode("@joão"))
    }

    @Test
    fun usernameComAcentoEPontoFinalDaFrase() {
        val accented = listOf(user(joao, "joão"))
        assertEquals("${mention(joao)}, ${mention(joao)}.", encode("@joão, @joão.", who = accented))
        assertEquals("@joãozinho", encode("@joãozinho", who = accented))
    }

    // Pontuação no fim

    @Test
    fun pontuacaoNoFimDaFraseNaoImpedeAMencao() {
        assertEquals("${mention(joao)}.", encode("@joao."))
        assertEquals("${mention(joao)},", encode("@joao,"))
        assertEquals("${mention(joao)}!", encode("@joao!"))
        assertEquals("${mention(joao)}?", encode("@joao?"))
        assertEquals("${mention(joao)}...", encode("@joao..."))
        assertEquals("(${mention(joao)})", encode("(@joao)"))
        assertEquals("\"${mention(joao)}\"", encode("\"@joao\""))
        assertEquals("${mention(joao)}\n${mention(bia)}", encode("@joao\n@bia"))
    }

    @Test
    fun pontoNoMeioDoUsernameFazParteDele() {
        assertEquals("${mention(ana)}.", encode("@ana.silva."))
        assertEquals("oi ${mention(ana)}", encode("oi @ana.silva"))
        // Só "ana" não é "ana.silva": quem escreve @ana.silva sem que exista fica com texto.
        assertEquals("@ana.silva", encode("@ana.silva", who = listOf(user(joao, "ana"))))
        assertEquals("${mention(joao)}.", encode("@ana.", who = listOf(user(joao, "ana"))))
    }

    @Test
    fun usernameQueTerminaEmPontoCasaPorInteiroAntesDeAparar() {
        val dotted = listOf(user(joao, "joao."))
        assertEquals("${mention(joao)} oi", encode("@joao. oi", who = dotted))
    }

    // Escolhidos no autocompletar

    @Test
    fun nomeEscolhidoViraMencaoMesmoSemUsuariosCarregados() {
        assertEquals("oi ${mention(bia)}, tudo bem?", encode("oi @bia, tudo bem?", picked = mapOf("bia" to bia), who = emptyList()))
    }

    @Test
    fun nomeEscolhidoNaoAfetaPrefixosNemOutroUsuario() {
        assertEquals(
            "@biazinha e ${mention(bia)} e ${mention(joao)}",
            encode("@biazinha e @bia e @joao", picked = mapOf("bia" to bia)),
        )
    }

    @Test
    fun nomeEscolhidoComPontoFinalDaFraseTambemViraMencao() {
        // O defeito antigo: o lookahead (?![\w.-]) nunca codificava "@bia." no fim da frase.
        assertEquals("falei com ${mention(bia)}.", encode("falei com @bia.", picked = mapOf("bia" to bia), who = emptyList()))
    }

    // O que nunca vira menção

    @Test
    fun enderecoDeEmailFicaIntacto() {
        assertEquals("escreve para joao@bia.com agora", encode("escreve para joao@bia.com agora"))
        assertEquals("a@joao", encode("a@joao"))
        assertEquals("(joao@joao)", encode("(joao@joao)"))
        assertEquals("joao@bia.com e ${mention(bia)}", encode("joao@bia.com e @bia"))
        assertEquals("joao@bia", encode("joao@bia", picked = mapOf("bia" to bia)))
    }

    @Test
    fun arrobaDepoisDeBarraOuPontuacaoQueNaoAbreMencaoFicaIntacto() {
        assertEquals("https://exemplo.com/@joao", encode("https://exemplo.com/@joao"))
        assertEquals("oi,@joao", encode("oi,@joao"))
        assertEquals("\\@joao", encode("\\@joao")) // escapado pelo autor
    }

    @Test
    fun codigoEmLinhaFicaIntacto() {
        assertEquals("use `@joao` aqui e ${mention(joao)}", encode("use `@joao` aqui e @joao"))
        assertEquals("`@joao` ${mention(bia)} `@bia`", encode("`@joao` @bia `@bia`"))
        assertEquals("${mention(joao)} `x` ${mention(joao)}", encode("@joao `x` @joao"))
    }

    @Test
    fun codigoEmLinhaQueNaoFechaNaMesmaLinhaNaoEProtegido() {
        // O Markdown do app só abre código em linha se as crases fecham na mesma linha e há algo entre elas.
        assertEquals("`a\n${mention(joao)}`", encode("`a\n@joao`"))
        assertEquals("` ${mention(joao)}", encode("` @joao"))
        assertEquals("`` ${mention(joao)}", encode("`` @joao")) // duas crases coladas não abrem código
    }

    @Test
    fun blocoDeCodigoFicaIntacto() {
        assertEquals("```\n@joao\n```", encode("```\n@joao\n```"))
        assertEquals("```kotlin\nval a = \"@joao\"\n```\n${mention(joao)}", encode("```kotlin\nval a = \"@joao\"\n```\n@joao"))
        assertEquals("antes ${mention(bia)} ```@joao``` depois ${mention(bia)}", encode("antes @bia ```@joao``` depois @bia"))
    }

    @Test
    fun blocoSemFechamentoNaoEProtegido() {
        assertEquals("```\n${mention(joao)}", encode("```\n@joao"))
    }

    @Test
    fun codigoEscolhidoNoAutocompletarTambemFicaIntacto() {
        assertEquals("`@bia` ${mention(bia)}", encode("`@bia` @bia", picked = mapOf("bia" to bia)))
    }

    // Casos de borda

    @Test
    fun textoSemArrobaOuVazioNaoMuda() {
        assertEquals("", encode(""))
        assertEquals("sem menção nenhuma", encode("sem menção nenhuma"))
    }

    @Test
    fun semUsuariosNemEscolhidosNadaMuda() {
        assertEquals("oi @joao", encode("oi @joao", who = emptyList()))
    }

    @Test
    fun mencaoNoComecoEDepoisDeEspacosEQuebrasDeLinha() {
        assertEquals("${mention(joao)} ${mention(bia)}", encode("@joao @bia"))
        assertEquals("a\n\n${mention(joao)}", encode("a\n\n@joao"))
    }

    // @everyone

    private val everyoneUser = user("0b0e7c1e-0000-4000-8000-0000000000ee", "everyone")

    @Test
    fun everyoneDigitadoFicaComoTextoEOServidorOLeLiteral() {
        assertEquals("avisem @everyone", encode("avisem @everyone"))
        assertEquals("@EVERYONE agora", encode("@EVERYONE agora"))
        assertEquals("@everyone.", encode("@everyone."))
    }

    @Test
    fun everyoneDigitadoNuncaViraUmUsuarioChamadoEveryone() {
        val who = users + everyoneUser
        assertEquals("avisem @everyone e ${mention(joao)}", encode("avisem @everyone e @joao", who = who))
        assertEquals("@Everyone", encode("@Everyone", who = who))
    }

    @Test
    fun usuarioChamadoEveryoneEscolhidoNaListaVira() {
        val who = users + everyoneUser
        assertEquals(mention(everyoneUser.id), encode("@everyone", picked = mapOf("everyone" to everyoneUser.id), who = who))
    }

    @Test
    fun nomeQueSoComecaComEveryoneContinuaSendoUmNomeComum() {
        val who = users + user("0b0e7c1e-0000-4000-8000-0000000000ef", "everyone.silva")
        assertEquals(mention("0b0e7c1e-0000-4000-8000-0000000000ef"), encode("@everyone.silva", who = who))
    }

    // Username com espaço

    private val yog = "0b0e7c1e-0000-4000-8000-00000000000d"
    private val yogUser = "0b0e7c1e-0000-4000-8000-00000000000e"

    @Test
    fun usernameComEspacoEscolhidoNaListaViraMencaoInteira() {
        val who = users + user(yog, "yog Sothoth")
        assertEquals("oi ${mention(yog)} tudo bem?", encode("oi @yog Sothoth tudo bem?", picked = mapOf("yog Sothoth" to yog), who = who))
    }

    @Test
    fun usernameComEspacoDigitadoAMaoViraMencaoSemDiferenciarMaiusculas() {
        val who = users + user(yog, "yog Sothoth")
        assertEquals("${mention(yog)}, oi", encode("@yog Sothoth, oi", who = who))
        assertEquals("${mention(yog)}.", encode("@YOG sothoth.", who = who))
        assertEquals(mention(yog), encode("@yog Sothoth", who = who))
    }

    @Test
    fun usernameComEspacoGanhaDoUsernameQueEComecoDele() {
        val who = users + user(yogUser, "yog") + user(yog, "yog Sothoth")
        assertEquals("${mention(yog)} e ${mention(yogUser)} sozinho", encode("@yog Sothoth e @yog sozinho", who = who))
    }

    @Test
    fun usernameComEspacoSoValeQuandoONomeAcabaAli() {
        val who = users + user(yogUser, "yog") + user(yog, "yog Sothoth")
        // "Sothothx" não é o nome: fica a menção do "yog".
        assertEquals("${mention(yogUser)} Sothothx", encode("@yog Sothothx", who = who))
        assertEquals("@yog Soth", encode("@yog Soth", who = listOf(user(yog, "yog Sothoth"))))
    }

    @Test
    fun usernameComEspacoDentroDeCodigoOuDepoisDeLetraFicaComoTexto() {
        val who = users + user(yog, "yog Sothoth")
        assertEquals("`@yog Sothoth` a@yog Sothoth", encode("`@yog Sothoth` a@yog Sothoth", who = who))
    }

    // O que o compositor destaca

    @Test
    fun findMentionsAchaOQueEncodeMentionsTroca() {
        val who = users + user(yog, "yog Sothoth")
        val text = "oi @joao e @yog Sothoth, `@bia` e @maria"
        assertEquals(
            listOf(DraftMention(3, 8, joao), DraftMention(11, 23, yog)),
            findMentions(text, emptyMap(), who),
        )
        assertEquals(listOf(DraftMention(0, 5, bia)), findMentions("@joao!", mapOf("joao" to bia), listOf(user(joao, "Joao"), user(bia, "joao"))))
        assertEquals(emptyList(), findMentions("sem menção", emptyMap(), who))
    }

    @Test
    fun findEveryoneAchaOAvisoATodosForaDeCodigo() {
        assertEquals(listOf(0 until 9, 13 until 22), findEveryone("@everyone e a@everyone"))
        assertEquals(listOf(28 until 37), findEveryone("oi `@everyone` x @everyones @everyone."))
        assertEquals(emptyList(), findEveryone("@everyones e `@everyone`"))
    }
}
