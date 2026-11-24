package chat.papo.mobile

import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.account.ServerUrl.Parsed
import chat.papo.mobile.account.ServerUrl.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerUrlTest {
    private fun ok(input: String): ServerUrl = (ServerUrl.parse(input) as Parsed.Ok).url
    private fun problem(input: String): Problem = (ServerUrl.parse(input) as Parsed.Invalid).problem

    @Test
    fun semEsquemaAssumeHttps() {
        assertEquals("https://papo.example.com", ok("papo.example.com").value)
        assertTrue(ok("papo.example.com").isSecure)
    }

    @Test
    fun entradasEquivalentesDaoOMesmoEndereco() {
        val expected = "https://papo.example.com"
        listOf(
            "https://papo.example.com",
            "  HTTPS://Papo.Example.COM/  ",
            "https://papo.example.com:443",
            "papo.example.com///",
            "https://papo.example.com/?x=1#frag",
        ).forEach { assertEquals(expected, ok(it).value, it) }
    }

    @Test
    fun mantemPortaNaoPadraoEPrefixoDeCaminho() {
        assertEquals("http://192.168.0.10:8080", ok("http://192.168.0.10:8080/").value)
        assertEquals("https://example.com/papo", ok("example.com/papo/").value)
        assertEquals("http://localhost", ok("http://localhost:80").value)
        assertFalse(ok("http://localhost:8080").isSecure)
    }

    @Test
    fun ipv6() {
        assertEquals("http://[::1]:8080", ok("http://[::1]:8080").value)
    }

    @Test
    fun enderecoDoWebSocketSegueOEsquema() {
        assertEquals("wss://example.com/papo/ws", ok("example.com/papo").webSocketUrl)
        assertEquals("ws://10.0.2.2:8080/ws", ok("http://10.0.2.2:8080").webSocketUrl)
        assertEquals("10.0.2.2:8080", ok("http://10.0.2.2:8080").host)
    }

    @Test
    fun rejeitaEntradasInvalidas() {
        assertEquals(Problem.Empty, problem("   "))
        assertEquals(Problem.Scheme, problem("ftp://example.com"))
        assertEquals(Problem.Credentials, problem("https://user:pass@example.com"))
        assertEquals(Problem.Host, problem("https://exa mple.com"))
        assertEquals(Problem.Host, problem("https://"))
        assertEquals(Problem.Host, problem("https://-bad.com"))
        assertEquals(Problem.Port, problem("example.com:0"))
        assertEquals(Problem.Port, problem("example.com:70000"))
        assertEquals(Problem.Port, problem("example.com:abc"))
        assertEquals(Problem.Path, problem("example.com/a b"))
    }
}
