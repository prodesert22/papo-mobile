package chat.papo.mobile

import chat.papo.mobile.core.safeExternalUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExternalUrlTest {
    @Test
    fun httpEHttpsPassamComoVieram() {
        assertEquals("http://exemplo.org", safeExternalUrl("http://exemplo.org"))
        assertEquals("https://exemplo.org/a/b?c=1#d", safeExternalUrl("https://exemplo.org/a/b?c=1#d"))
    }

    @Test
    fun esquemaNaoDiferenciaMaiuscula() {
        assertEquals("HTTPS://Exemplo.org", safeExternalUrl("HTTPS://Exemplo.org"))
    }

    @Test
    fun espacosNasPontasSaem() {
        assertEquals("https://exemplo.org", safeExternalUrl("  https://exemplo.org\n"))
    }

    @Test
    fun portaECredenciaisNaoContamComoHost() {
        assertEquals("https://exemplo.org:8443/x", safeExternalUrl("https://exemplo.org:8443/x"))
        assertNull(safeExternalUrl("https://:8443/x"))
        assertNull(safeExternalUrl("https://usuario@/x"))
    }

    @Test
    fun outrosEsquemasNaoAbrem() {
        listOf(
            "javascript:alert(1)",
            "intent://exemplo#Intent;scheme=http;end",
            "file:///sdcard/a.txt",
            "content://chat.papo.mobile/x",
            "mailto:alguem@exemplo.org",
            "ftp://exemplo.org",
            "httpx://exemplo.org",
        ).forEach { assertNull(safeExternalUrl(it), it) }
    }

    @Test
    fun semHostOuSemBarrasNaoAbre() {
        assertNull(safeExternalUrl("https://"))
        assertNull(safeExternalUrl("https:exemplo.org"))
        assertNull(safeExternalUrl("https:/exemplo.org"))
        assertNull(safeExternalUrl("https://?q=1"))
        assertNull(safeExternalUrl("https://#x"))
    }

    @Test
    fun semEsquemaNaoAbre() {
        assertNull(safeExternalUrl(""))
        assertNull(safeExternalUrl("exemplo.org"))
        assertNull(safeExternalUrl(":foo"))
    }

    @Test
    fun espacoOuControleNoMeioNaoAbre() {
        assertNull(safeExternalUrl("https://exemplo.org/a b"))
        assertNull(safeExternalUrl("https://exem\u0000plo.org"))
        assertNull(safeExternalUrl("https://exemplo.org/\u001b"))
    }
}
