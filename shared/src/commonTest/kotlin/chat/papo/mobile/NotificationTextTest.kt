package chat.papo.mobile

import chat.papo.mobile.chat.NotificationTexts
import chat.papo.mobile.chat.notificationName
import chat.papo.mobile.chat.notificationText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** O texto simples que a notificação mostra: sem marcação, sem controle e sem nada que o servidor marcou como sensível. */
class NotificationTextTest {
    private val texts = NotificationTexts(attachment = "Anexo", unknownUser = "usuário", emptyMessage = "Nova mensagem")
    private val ana = "abcdef01-1111-4111-8111-111111111111"
    private val names = mapOf(ana to "Ana")

    private fun text(content: String?, attachments: Boolean = false, nameOf: (String) -> String? = names::get) =
        notificationText(content, attachments, nameOf, texts)

    @Test
    fun textoSimplesFicaComoEsta() {
        assertEquals("bom dia, pessoal", text("bom dia, pessoal"))
    }

    @Test
    fun marcacaoDoMarkdownSai() {
        assertEquals("negrito itálico riscado código", text("**negrito** *itálico* ~~riscado~~ `código`"))
    }

    @Test
    fun linkComTextoMostraSoOTextoENuncaOEndereco() {
        assertEquals("veja isto agora", text("veja [isto](https://exemplo.test/x) agora"))
    }

    @Test
    fun mencaoViraArrobaENomeDeExibicao() {
        assertEquals("oi @Ana, tudo bem?", text("oi @mention(<@$ana>), tudo bem?"))
    }

    @Test
    fun mencaoDeQuemOAppNaoConheceViraUsuario() {
        assertEquals("oi @usuário", text("oi @mention(<@$ana>)", nameOf = { null }))
    }

    @Test
    fun mencaoComNomeSoDeEspacosViraUsuario() {
        assertEquals("oi @usuário", text("oi @mention(<@$ana>)", nameOf = { "   " }))
    }

    @Test
    fun everyoneFicaComoTexto() {
        assertEquals("atenção @everyone", text("atenção @everyone"))
    }

    @Test
    fun linhasESeparadoresViramUmEspaco() {
        assertEquals("primeira segunda terceira", text("primeira\n\n  segunda\t\tterceira  "))
    }

    @Test
    fun mensagemSoComAnexoViraAnexo() {
        assertEquals("Anexo", text(null, attachments = true))
        assertEquals("Anexo", text("   ", attachments = true))
    }

    @Test
    fun mensagemComTextoEAnexoMostraOTexto() {
        assertEquals("olha isto", text("olha isto", attachments = true))
    }

    @Test
    fun mensagemSemTextoNemAnexoUsaOTextoVazio() {
        assertEquals("Nova mensagem", text(null))
        assertEquals("Nova mensagem", text("\n \n"))
    }

    @Test
    fun controlesEMarcasDeInversaoDeLeituraSaem() {
        val sujo = "a\u0000b‮c⁦d‏e"
        assertEquals("abcde", text(sujo))
    }

    @Test
    fun textoLongoEhCortadoComReticencias() {
        val longo = "a".repeat(500)

        val result = text(longo)

        assertEquals(301, result.length)
        assertTrue(result.endsWith("…"))
    }

    @Test
    fun textoNoLimiteNaoEhCortado() {
        val exato = "b".repeat(300)

        assertEquals(exato, text(exato))
    }

    @Test
    fun oCorteNaoPartePareSubstituto() {
        // O 300º caractere é a primeira metade de um emoji: o corte o leva junto, em vez de deixar meio emoji.
        val emoji = "😀"
        val result = text("c".repeat(299) + emoji + "fim")

        assertFalse(result.any { it.isHighSurrogate() })
        assertEquals("c".repeat(299) + "…", result)
    }

    @Test
    fun nomeNuloOuVazioUsaOReserva() {
        assertEquals("usuário", notificationName(null, "usuário"))
        assertEquals("usuário", notificationName("  \u0000 ", "usuário"))
    }

    @Test
    fun nomeSaiLimpoELimitado() {
        assertEquals("Ana Maria", notificationName("Ana‮  Maria\n", "x"))
        assertEquals(65, notificationName("n".repeat(100), "x").length)
    }
}
