package chat.papo.mobile

import chat.papo.mobile.chat.MAX_MENTION_QUERY
import chat.papo.mobile.chat.MentionQuery
import chat.papo.mobile.chat.Message
import chat.papo.mobile.chat.Presence
import chat.papo.mobile.chat.User
import chat.papo.mobile.chat.ConversationAccess
import chat.papo.mobile.chat.MentionEdit
import chat.papo.mobile.chat.authorsByRecency
import chat.papo.mobile.chat.completeMention
import chat.papo.mobile.chat.mentionQueryAt
import chat.papo.mobile.chat.offersEveryone
import chat.papo.mobile.chat.suggestMentions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MentionSearchTest {
    // Gatilho

    private fun query(text: String, cursor: Int = text.length) = mentionQueryAt(text, cursor)

    @Test
    fun arrobaNoComecoAbreAMencao() {
        assertEquals(MentionQuery(0, ""), query("@"))
        assertEquals(MentionQuery(0, "jo"), query("@jo"))
    }

    @Test
    fun arrobaDepoisDeEspacoQuebraDeLinhaParentesEAspas() {
        assertEquals(MentionQuery(3, "jo"), query("oi @jo"))
        assertEquals(MentionQuery(3, "jo"), query("oi\n@jo"))
        assertEquals(MentionQuery(3, "jo"), query("oi\t@jo"))
        assertEquals(MentionQuery(4, "jo"), query("oi (@jo"))
        assertEquals(MentionQuery(1, "jo"), query("(@jo"))
        assertEquals(MentionQuery(1, "jo"), query("\"@jo"))
    }

    @Test
    fun arrobaDepoisDeLetraNumeroOuOutraPontuacaoNaoAbreAMencao() {
        assertNull(query("a@b.com"))
        assertNull(query("a@"))
        assertNull(query("7@jo"))
        assertNull(query("oi,@jo"))
        assertNull(query("https://x.com/@jo"))
        assertNull(query("@@jo")) // o segundo @ vem depois de outro @
    }

    @Test
    fun oArrobaMaisProximoDoCursorEQueValeEUmEmailAntesNaoAtrapalha() {
        assertEquals(MentionQuery(7, "ma"), query("@ana e @ma"))
        assertEquals(MentionQuery(8, "jo"), query("a@b.com @jo"))
        assertNull(query("@ana a@b.co"))
    }

    @Test
    fun valeSoOQueEstaAntesDoCursor() {
        assertEquals(MentionQuery(3, "jo"), query("oi @joao e mais", cursor = 6))
        assertNull(query("oi @joao", cursor = 3)) // o cursor está antes do @
        assertEquals(MentionQuery(3, ""), query("oi @joao", cursor = 4))
    }

    @Test
    fun cursorForaDoTextoOuSemArrobaNaoTemMencao() {
        assertNull(query("", 0))
        assertNull(query("sem arroba", 4))
        assertNull(query("@jo", -1))
        assertNull(query("@jo", 4))
        assertNull(query("@jo", 0))
    }

    @Test
    fun cabeUmEspacoNoMeioParaAcharONomeDeExibicao() {
        assertEquals(MentionQuery(3, "João S"), query("oi @João S"))
        assertEquals(MentionQuery(3, "joao "), query("oi @joao ")) // o espaço recém-digitado ainda é da consulta
    }

    @Test
    fun segundoEspacoQuebraDeLinhaOuEspacoLogoDepoisDoArrobaEncerram() {
        assertNull(query("@joao silva e"))
        assertNull(query("@ oi"))
        assertNull(query("@ "))
        assertNull(query("@joao\nsilva"))
        assertNull(query("@joao\tsilva"))
        assertNull(query("@joao\n"))
    }

    @Test
    fun consultaTemNoMaximo32Caracteres() {
        val limit = "a".repeat(MAX_MENTION_QUERY)
        assertEquals(MentionQuery(0, limit), query("@$limit"))
        assertNull(query("@${limit}a"))
        // O espaço no meio conta no limite.
        val withSpace = "a".repeat(10) + " " + "b".repeat(MAX_MENTION_QUERY - 11)
        assertEquals(MentionQuery(0, withSpace), query("@$withSpace"))
        assertNull(query("@${withSpace}b"))
    }

    // Sugestões

    private fun user(id: String, username: String, nickname: String? = null, banned: Boolean = false) =
        User(id, username, nickname, statusMessage = null, typingPhrase = null, banned = banned, roleColor = null, avatarVersion = 0)

    private fun suggest(
        query: String,
        users: List<User>,
        recent: List<String> = emptyList(),
        online: Set<String> = emptySet(),
        me: String = "eu",
    ) = suggestMentions(query, users, me, recent, online.associateWith { Presence("online", null) })
        .map { it.id }

    @Test
    fun tiraVoceEOsBanidos() {
        val users = listOf(user("eu", "ana"), user("u2", "ana2"), user("u3", "ana3", banned = true))
        assertEquals(listOf("u2"), suggest("ana", users))
        assertEquals(listOf("u2"), suggest("", users))
    }

    @Test
    fun consultaVaziaListaTodosPelaOrdemDeDesempate() {
        val users = listOf(user("a", "zeca"), user("b", "bia"), user("c", "caio"), user("d", "dani"))
        // Falou há pouco primeiro (c e depois a), depois online (d), depois o alfabeto (b).
        assertEquals(listOf("c", "a", "d", "b"), suggest("", users, recent = listOf("c", "a"), online = setOf("d")))
    }

    @Test
    fun joaoAchaJoaoComAcentoPeloNomeDeExibicao() {
        val users = listOf(user("a", "js", nickname = "João Silva"), user("b", "maria"))
        assertEquals(listOf("a"), suggest("joao", users))
        assertEquals(listOf("a"), suggest("JOÃO", users))
        assertEquals(listOf("a"), suggest("js", users)) // e também pelo username
    }

    @Test
    fun comEspacoNoMeioAchaOSobrenome() {
        val users = listOf(user("a", "js", nickname = "João Silva"), user("b", "jp", nickname = "João Pedro"))
        assertEquals(listOf("a"), suggest("João S", users))
        assertEquals(listOf("a"), suggest("joao s ", users)) // espaço sobrando no fim não atrapalha
        assertEquals(listOf("b", "a"), suggest("joao", users)) // os dois começam igual: vale o alfabeto
    }

    @Test
    fun consultaAtravessaOEspacoDoNomeDeExibicao() {
        val users = listOf(user("a", "mestre7", nickname = "Lu Moraes"), user("b", "bia"))
        assertEquals(listOf("a"), suggest("lum", users))
        assertEquals(listOf("a"), suggest("LUMOR", users))
        assertEquals(listOf("a"), suggest("lu m", users)) // com o espaço, o mesmo resultado
        assertEquals(emptyList(), suggest("lmo", users)) // as letras têm de ficar juntas
    }

    @Test
    fun consultaAtravessaSeparadoresDoUsername() {
        val users = listOf(user("a", "lu_moraes"), user("b", "lu.moraes"), user("c", "lu-moraes"), user("d", "bia"))
        // Todos casam do mesmo jeito: vale o alfabeto, em que "-" vem antes de "." e este antes de "_".
        assertEquals(listOf("c", "b", "a"), suggest("lum", users))
    }

    @Test
    fun pedacoDoMeioDoUsernameAcha() {
        val users = listOf(user("a", "cantorvivo"), user("b", "bia"), user("c", "cantor_vivo", nickname = "Zé"))
        assertEquals(listOf("a", "c"), suggest("torvi", users))
    }

    @Test
    fun fluxoDoCampoAteAsSugestoesAchaOPedacoDoMeioEOQueAtravessaOEspaco() {
        val users = listOf(user("a", "cantorvivo"), user("b", "mestre7", nickname = "Lu Moraes"), user("c", "bia"))
        fun suggestionsFor(typed: String) = query(typed)?.let { suggest(it.text, users) }
        assertEquals(listOf("a"), suggestionsFor("oi @torvi"))
        assertEquals(listOf("a"), suggestionsFor("oi @torvi ")) // espaço que o teclado põe depois da palavra
        assertEquals(listOf("b"), suggestionsFor("oi @lum"))
        assertEquals(listOf("b"), suggestionsFor("oi @lu m"))
    }

    @Test
    fun casamentoAtravessandoQuebraNaoPassaNaFrenteDeQuemFalouHaPoucoMasPerdeParaContem() {
        val users = listOf(user("a", "x1", nickname = "Lu Moraes"), user("b", "alumno"), user("c", "x2", nickname = "Lu Moura"))
        // b contém "lum"; a e c só o têm atravessando o espaço. A relevância vem antes de quem falou há pouco.
        assertEquals(listOf("b", "a", "c"), suggest("lum", users, recent = listOf("a")))
        assertEquals(listOf("b", "c", "a"), suggest("lum", users, recent = listOf("c", "a")))
    }

    @Test
    fun melhorCasamentoVemPrimeiroIgualComecaComPalavraContem() {
        val users = listOf(
            user("contem", "banana"),
            user("palavra", "x", nickname = "Maria Ana"),
            user("comeca", "anabela"),
            user("igual", "ana"),
        )
        assertEquals(listOf("igual", "comeca", "palavra", "contem"), suggest("ana", users))
    }

    @Test
    fun oMelhorCasamentoDeUsernameOuNomeDeExibicaoContaERelevanciaVenceODesempate() {
        // "ana" é o username de b (igual) e só começa o nome de a; quem falou há pouco não passa na frente.
        val users = listOf(user("a", "zz", nickname = "Anabela"), user("b", "ana", nickname = "Zé"))
        assertEquals(listOf("b", "a"), suggest("ana", users, recent = listOf("a"), online = setOf("a")))
    }

    @Test
    fun desempateFalouHaPoucoDepoisOnlineDepoisAlfabeto() {
        val users = listOf(user("a", "ana1"), user("b", "ana2"), user("c", "ana3"), user("d", "ana4"))
        // Todos começam com "ana": só o desempate decide. b falou há pouco; c e d estão online; a só no alfabeto.
        assertEquals(listOf("b", "c", "d", "a"), suggest("ana", users, recent = listOf("b"), online = setOf("c", "d")))
        // Quem falou há mais tempo vem depois de quem falou agora, mesmo estando offline.
        assertEquals(listOf("d", "a", "b", "c"), suggest("ana", users, recent = listOf("d", "a")))
    }

    @Test
    fun autorRepetidoNaListaDeRecentesContaPelaPrimeiraVez() {
        val users = listOf(user("a", "ana1"), user("b", "ana2"))
        assertEquals(listOf("b", "a"), suggest("ana", users, recent = listOf("b", "a", "b")))
    }

    @Test
    fun ausenteEOcupadoContamComoOnlineEOfflineNao() {
        val users = listOf(user("a", "ana1"), user("b", "ana2"), user("c", "ana3"))
        val presence = mapOf("b" to Presence("away", null), "c" to Presence("offline", null))
        assertEquals(listOf("b", "a", "c"), suggestMentions("ana", users, "eu", emptyList(), presence).map { it.id })
    }

    @Test
    fun alfabetoIgnoraAcentoECaixa() {
        val users = listOf(user("b", "zz", nickname = "Bruno"), user("a", "yy", nickname = "Álvaro"), user("c", "xx", nickname = "carla"))
        assertEquals(listOf("a", "b", "c"), suggest("", users))
    }

    @Test
    fun mesmoNomeDeExibicaoDesempataPeloUsernameEDepoisPeloId() {
        val users = listOf(user("2", "bia", nickname = "Igual"), user("1", "bia", nickname = "Igual"), user("3", "ana", nickname = "Igual"))
        assertEquals(listOf("3", "1", "2"), suggest("", users))
    }

    @Test
    fun semCasamentoNaoSugereNinguem() {
        assertEquals(emptyList(), suggest("xyz", listOf(user("a", "ana"), user("b", "bia"))))
        assertEquals(emptyList(), suggest("ana", emptyList()))
    }

    @Test
    fun maisDeCincoResultadosVoltamTodos() {
        val users = (1..12).map { user("u$it", "ana%02d".format(it)) }
        assertEquals(12, suggest("ana", users).size) // a lista da tela rola; quem limita é a altura dela
    }

    // Autores recentes

    private fun message(id: String, author: String?) = Message(
        id, "c1", author, "texto", null, "", edited = false, replyTo = null, webhookId = null,
        externalAuthor = null, attachments = emptyList(), embeds = emptyList(), reactions = emptyList(), userReactions = emptyList(),
    )

    @Test
    fun autoresRecentesSaoDistintosEPulamMensagensSemAutor() {
        val messages = listOf(message("4", "b"), message("3", null), message("2", "a"), message("1", "b"))
        assertEquals(listOf("b", "a"), authorsByRecency(messages))
        assertEquals(emptyList(), authorsByRecency(emptyList()))
    }

    // @everyone no autocompletar

    @Test
    fun everyoneEOferecidoQuandoAConsultaEOComecoDeEveryone() {
        for (query in listOf("", "e", "ev", "every", "everyone", "EVERY", "Everyone")) {
            assertTrue(offersEveryone(query, allowed = true), query)
        }
    }

    @Test
    fun everyoneNaoEOferecidoParaOutraConsulta() {
        for (query in listOf("x", "joao", "everyones", "everyone ", " ", "ve", "everyonex")) {
            assertFalse(offersEveryone(query, allowed = true), query)
        }
    }

    @Test
    fun everyoneNuncaEOferecidoSemAPermissao() {
        for (query in listOf("", "ev", "everyone")) {
            assertFalse(offersEveryone(query, allowed = false), query)
        }
    }

    @Test
    fun everyoneSegueOAcessoDaConversa() {
        // Sem dado (acesso irrestrito) e na DM o acesso não traz `mentionEveryone`: nunca se oferece.
        val unknown = ConversationAccess.Unrestricted
        val direct = ConversationAccess.Unrestricted.copy(mentionEveryone = false, deleteOthers = false, joinVoice = false)
        val allowed = ConversationAccess.Unrestricted.copy(mentionEveryone = true)
        assertFalse(offersEveryone("", unknown.mentionEveryone))
        assertFalse(offersEveryone("", direct.mentionEveryone))
        assertTrue(offersEveryone("", allowed.mentionEveryone))
    }

    @Test
    fun escolherEveryoneInsereOTextoEPoeOCursorDepoisDoEspaco() {
        assertEquals(MentionEdit("@everyone ", 10), completeMention("@", MentionQuery(0, ""), 1, "everyone"))
        assertEquals(MentionEdit("oi @everyone ", 13), completeMention("oi @ev", MentionQuery(3, "ev"), 6, "everyone"))
    }

    @Test
    fun escolherUmNomePreservaOQueVemDepoisDoCursor() {
        // O espaço que já havia depois do cursor fica: a escolha põe o seu próprio e não apaga nada do que vem depois.
        assertEquals(MentionEdit("oi @joao  e mais", 9), completeMention("oi @jo e mais", MentionQuery(3, "jo"), 6, "joao"))
        assertEquals(MentionEdit("a\n@bia ", 7), completeMention("a\n@b", MentionQuery(2, "b"), 4, "bia"))
    }
}
