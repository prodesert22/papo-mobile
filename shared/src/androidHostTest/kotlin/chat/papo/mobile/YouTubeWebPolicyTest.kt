package chat.papo.mobile

import chat.papo.mobile.platform.WebNavigation
import chat.papo.mobile.platform.youTubeBaseUrl
import chat.papo.mobile.platform.youTubeNavigation
import chat.papo.mobile.platform.youTubePage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A página do player do YouTube e a política de navegação do WebView dele: só texto e regras, sem WebView. */
class YouTubeWebPolicyTest {
    private val id = "aB3dE5gH7jK"

    // youTubePage

    @Test
    fun aPaginaTemUmUnicoIframeDoEmbedDoVideo() {
        val page = youTubePage(id, fullscreenButton = true)

        assertEquals(1, Regex("<iframe ").findAll(page).count())
        assertTrue("""src="https://www.youtube.com/embed/$id?autoplay=1&amp;playsinline=1&amp;fs=1"""" in page)
    }

    @Test
    fun aPaginaNaoTemScriptNemMaisNenhumEndereco() {
        val page = youTubePage(id, fullscreenButton = true)

        assertFalse("<script" in page.lowercase())
        assertFalse("onload" in page.lowercase())
        // Os únicos endereços são a política de conteúdo e o iframe, e os dois são do YouTube.
        val origins = Regex("""https?://[A-Za-z0-9.-]+""").findAll(page).map { it.value }.toList()
        assertEquals(listOf("https://www.youtube.com", "https://www.youtube.com"), origins)
    }

    @Test
    fun aPoliticaDeConteudoSoDeixaCarregarOIframeDoYouTube() {
        val page = youTubePage(id, fullscreenButton = false)

        assertTrue("""default-src 'none'; frame-src https://www.youtube.com; style-src 'unsafe-inline'""" in page)
    }

    @Test
    fun aPaginaNaoSuprimeOReferer() {
        val page = youTubePage(id, fullscreenButton = false)

        assertTrue("""referrerpolicy="strict-origin-when-cross-origin"""" in page)
        assertFalse("no-referrer" in page)
        assertFalse("noreferrer" in page)
    }

    @Test
    fun semOBotaoDeTelaCheiaOParametroFsVaiZerado() {
        assertTrue("fs=0" in youTubePage(id, fullscreenButton = false))
        assertTrue("fs=1" in youTubePage(id, fullscreenButton = true))
    }

    @Test
    fun soAPaginaDoMesmoIdMudaDeUmVideoParaOutro() {
        val other = "zzzzzzzzzzz"

        assertEquals(youTubePage(id, true).replace(id, other), youTubePage(other, true))
    }

    @Test
    fun umIdInvalidoNuncaVaiParaOHtml() {
        listOf("", "short", "aB3dE5gH7jK\"><script>", "aB3dE5gH7jKx", "aB3dE5gH7j\n").forEach { bad ->
            assertFailsWith<IllegalStateException>(bad) { youTubePage(bad, fullscreenButton = true) }
        }
    }

    // youTubeBaseUrl

    @Test
    fun aOrigemDaPaginaEOIdDoAppComHttps() {
        assertEquals("https://chat.papo.mobile", youTubeBaseUrl("chat.papo.mobile"))
    }

    // youTubeNavigation

    @Test
    fun qualquerNavegacaoDoQuadroPrincipalSaiDoEmbedEVaiParaOYouTube() {
        listOf(
            "https://www.youtube.com/watch?v=$id",
            "https://www.youtube.com/embed/$id",
            "https://evil.example.com/",
            "http://www.youtube.com/watch?v=$id",
            "intent://scan/#Intent;scheme=zxing;end",
            "javascript:alert(1)",
            "file:///sdcard/segredo.txt",
        ).forEach { url ->
            assertEquals(WebNavigation.OpenOutside, youTubeNavigation(url, isMainFrame = true), url)
        }
    }

    @Test
    fun osQuadrosDoPlayerCarregamSoHttps() {
        assertEquals(WebNavigation.Allow, youTubeNavigation("https://www.youtube.com/embed/$id?autoplay=1", isMainFrame = false))
        assertEquals(WebNavigation.Allow, youTubeNavigation("https://googleads.g.doubleclick.net/pagead/x", isMainFrame = false))
        assertEquals(WebNavigation.Allow, youTubeNavigation("HTTPS://www.youtube.com/embed/$id", isMainFrame = false))
    }

    @Test
    fun osQuadrosDoPlayerNaoCarregamOResto() {
        listOf(
            "http://www.youtube.com/embed/$id",
            "intent://scan/#Intent;scheme=zxing;end",
            "market://details?id=com.example",
            "javascript:alert(1)",
            "file:///sdcard/segredo.txt",
            "content://com.example/x",
            "data:text/html,<p>oi</p>",
            "",
        ).forEach { url ->
            assertEquals(WebNavigation.Block, youTubeNavigation(url, isMainFrame = false), url)
        }
    }

    @Test
    fun aPaginaEmBrancoDeUmQuadroPodeCarregar() {
        assertEquals(WebNavigation.Allow, youTubeNavigation("about:blank", isMainFrame = false))
        assertEquals(WebNavigation.Allow, youTubeNavigation("about:srcdoc", isMainFrame = false))
    }
}
