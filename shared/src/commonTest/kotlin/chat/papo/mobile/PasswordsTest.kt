package chat.papo.mobile

import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.chat.PasswordCheck
import chat.papo.mobile.chat.PasswordProblem
import chat.papo.mobile.chat.ResetLink
import chat.papo.mobile.chat.checkNewPassword
import chat.papo.mobile.chat.parseResetLink
import chat.papo.mobile.chat.passwordProblems
import chat.papo.mobile.chat.redactSecrets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PasswordsTest {
    @Test
    fun senhaQueServeNaoTemProblemas() {
        assertEquals(emptyList(), passwordProblems("Senha@123"))
        assertEquals(emptyList(), passwordProblems("Abcdefg!"))
    }

    @Test
    fun cadaRegraFaltandoApareceSozinha() {
        assertEquals(listOf(PasswordProblem.TooShort), passwordProblems("Ab@1"))
        assertEquals(listOf(PasswordProblem.NoUppercase), passwordProblems("senha@123"))
        assertEquals(listOf(PasswordProblem.NoSymbol), passwordProblems("Senha1234"))
    }

    @Test
    fun todasAsRegrasFaltandoVemNaOrdemDaTela() {
        assertEquals(listOf(PasswordProblem.TooShort, PasswordProblem.NoUppercase, PasswordProblem.NoSymbol), passwordProblems("abc"))
        assertEquals(listOf(PasswordProblem.TooShort, PasswordProblem.NoUppercase, PasswordProblem.NoSymbol), passwordProblems(""))
    }

    @Test
    fun tamanhoContaCodePointsEEspacoEOutrasMarcasSaoSimbolo() {
        // 7 code points: o emoji de dois chars conta uma vez só (e é o símbolo), mas ainda falta um.
        assertEquals(listOf(PasswordProblem.TooShort), passwordProblems("Abcde😀f"))
        // 8 code points, com o emoji como símbolo.
        assertEquals(emptyList(), passwordProblems("Abcde😀fg"))
        assertEquals(emptyList(), passwordProblems("Senha 123"))
        assertEquals(emptyList(), passwordProblems("Áreas_123"))
    }

    @Test
    fun maiusculaAcentuadaContaEDigitoNaoESimbolo() {
        assertEquals(emptyList(), passwordProblems("ébcdefgÉ!"))
        assertEquals(listOf(PasswordProblem.NoUppercase, PasswordProblem.NoSymbol), passwordProblems("abcdefgh12"))
    }

    @Test
    fun substitutoSolitarioNaoQuebra() {
        assertEquals(emptyList(), passwordProblems("Abcdefg\uD83D"))
    }

    @Test
    fun conferirSenhaNovaPedeFortePrimeiroDepoisIgual() {
        assertEquals(PasswordCheck.Ok, checkNewPassword("Senha@123", "Senha@123"))
        assertEquals(PasswordCheck.Mismatch, checkNewPassword("Senha@123", "Senha@124"))
        assertEquals(PasswordCheck.Weak(listOf(PasswordProblem.NoUppercase)), checkNewPassword("senha@123", "senha@123"))
        assertEquals(PasswordCheck.Weak(listOf(PasswordProblem.NoUppercase)), checkNewPassword("senha@123", "outra"))
    }

    private val server = ServerUrl("https://api.papo.test")
    private val token = "abcDEF_123-xyz"

    @Test
    fun linkCompletoDoMesmoServidorDevolveOTokenSemOutroEndereco() {
        assertEquals(ResetLink(token, null), parseResetLink("https://api.papo.test/passwordchange/$token", server))
    }

    @Test
    fun linkDeOutroEnderecoDevolveOEnderecoDoLink() {
        assertEquals(
            ResetLink(token, "papo.cyberasilo.online"),
            parseResetLink("  https://Papo.Cyberasilo.Online/passwordchange/$token  ", server),
        )
    }

    @Test
    fun portaNaoMudaOHostMasSimOutroNome() {
        assertEquals(ResetLink(token, null), parseResetLink("http://api.papo.test:8080/passwordchange/$token", server))
        assertEquals(ResetLink(token, "[::1]"), parseResetLink("http://[::1]:8080/passwordchange/$token", server))
    }

    @Test
    fun valeOUltimoSegmentoDepoisDePasswordchangeComBarraFinalConsulta() {
        assertEquals(ResetLink(token, null), parseResetLink("https://api.papo.test/app/passwordchange/$token/", server))
        assertEquals(ResetLink(token, null), parseResetLink("https://api.papo.test/passwordchange/$token?a=b#c", server))
        assertEquals(ResetLink("b2", null), parseResetLink("https://api.papo.test/passwordchange/a1/b2", server))
    }

    @Test
    fun tokenCruValeComEspacosNasPontas() {
        assertEquals(ResetLink(token, null), parseResetLink(token, server))
        assertEquals(ResetLink(token, null), parseResetLink("  $token\n", server))
    }

    @Test
    fun lixoVaiParaNulo() {
        assertNull(parseResetLink("", server))
        assertNull(parseResetLink("   ", server))
        assertNull(parseResetLink("não é um link", server))
        assertNull(parseResetLink("abc def", server))
        assertNull(parseResetLink("tok=en", server))
        assertNull(parseResetLink("https://api.papo.test/passwordchange/", server))
        assertNull(parseResetLink("https://api.papo.test/passwordchange", server))
        assertNull(parseResetLink("https://api.papo.test/outro/$token", server))
        assertNull(parseResetLink("https://api.papo.test/", server))
        assertNull(parseResetLink("https://api.papo.test", server))
        assertNull(parseResetLink("https://api.papo.test/passwordchange/tok en", server))
    }

    @Test
    fun linkSemEsquemaHttpOuComEnderecoInvalidoVaiParaNulo() {
        assertNull(parseResetLink("ftp://api.papo.test/passwordchange/$token", server))
        assertNull(parseResetLink("javascript://api.papo.test/passwordchange/$token", server))
        assertNull(parseResetLink("api.papo.test/passwordchange/$token", server))
        assertNull(parseResetLink("https://user:senha@api.papo.test/passwordchange/$token", server))
        assertNull(parseResetLink("https://ap i.papo.test/passwordchange/$token", server))
    }

    @Test
    fun tokenAte256CaracteresEMaisQueIssoNao() {
        val limit = "a".repeat(256)
        assertEquals(ResetLink(limit, null), parseResetLink(limit, server))
        assertEquals(ResetLink(limit, null), parseResetLink("https://api.papo.test/passwordchange/$limit", server))
        assertNull(parseResetLink("a".repeat(257), server))
        assertNull(parseResetLink("https://api.papo.test/passwordchange/${"a".repeat(257)}", server))
    }

    @Test
    fun tokenForaDoAlfabetoDeUrlNaoPassa() {
        assertNull(parseResetLink("abc+def", server))
        assertNull(parseResetLink("abc=", server))
        assertNull(parseResetLink("abç", server))
        assertNull(parseResetLink("https://api.papo.test/passwordchange/abc.def", server))
    }

    @Test
    fun redacaoTiraSegredosEIgnoraOsCurtos() {
        assertEquals("a senha … falhou e o token …", redactSecrets("a senha Senha@123 falhou e o token tok-abc", "Senha@123", "tok-abc"))
        assertEquals("sem segredo", redactSecrets("sem segredo", "xyz", ""))
        assertEquals("…e …", redactSecrets("abcde abcd", "abcd"))
    }
}
