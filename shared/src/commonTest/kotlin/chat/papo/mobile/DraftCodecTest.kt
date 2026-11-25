package chat.papo.mobile

import chat.papo.mobile.chat.Draft
import chat.papo.mobile.chat.Drafts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DraftCodecTest {
    private val reply = "0b0e7c1e-1111-2222-3333-444455556666"

    private fun roundTrip(draft: Draft) = Drafts.decode(Drafts.encode(draft))

    @Test
    fun textoERespostaVoltamComoForam() {
        assertEquals(Draft("oi, tudo bem?", reply), roundTrip(Draft("oi, tudo bem?", reply)))
    }

    @Test
    fun soTextoSemResposta() {
        assertEquals(Draft("só texto"), roundTrip(Draft("só texto")))
        assertNull(roundTrip(Draft("só texto"))?.replyTo)
    }

    @Test
    fun soRespostaSemTextoAindaEUmRascunho() {
        assertEquals(Draft("", reply), roundTrip(Draft("", reply)))
    }

    @Test
    fun caracteresEspeciaisSobrevivem() {
        val text = "linha 1\nlinha 2\t\"aspas\" \\ barra {\"json\": [1,2]} @mention(<@x>) 😀 João"
        assertEquals(Draft(text), roundTrip(Draft(text)))
    }

    @Test
    fun vazioOuSoEspacosNaoEUmRascunho() {
        assertTrue(Draft("").isEmpty)
        assertTrue(Draft("  \n ").isEmpty)
        assertFalse(Draft("", reply).isEmpty)
        assertFalse(Draft("a").isEmpty)
        assertNull(roundTrip(Draft("   ")))
        assertNull(roundTrip(Draft("")))
    }

    @Test
    fun semValorNaoHaRascunho() {
        assertNull(Drafts.decode(null))
        assertNull(Drafts.decode(""))
    }

    @Test
    fun valorEstragadoEIgnorado() {
        for (garbage in listOf("{", "texto solto", "null", "[]", "[\"a\"]", "123", "{\"text\": 5}", "{\"text\": null}", "{\"replyTo\": \"x\"}", "{\"text\": [\"a\"]}")) {
            assertNull(Drafts.decode(garbage), garbage)
        }
    }

    @Test
    fun camposDesconhecidosSaoIgnorados() {
        assertEquals(Draft("oi"), Drafts.decode("""{"text":"oi","outra":true}"""))
    }

    @Test
    fun respostaVaziaOuGrandeDemaisSeDescartaMasOTextoFica() {
        assertEquals(Draft("oi"), Drafts.decode("""{"text":"oi","replyTo":""}"""))
        assertEquals(Draft("oi"), Drafts.decode("""{"text":"oi","replyTo":"${"x".repeat(65)}"}"""))
        assertEquals(Draft("oi", "x".repeat(64)), Drafts.decode("""{"text":"oi","replyTo":"${"x".repeat(64)}"}"""))
        // Sem texto, uma resposta inválida deixa o valor vazio.
        assertNull(Drafts.decode("""{"text":"","replyTo":""}"""))
    }

    @Test
    fun textoLimitadoA20MilCaracteres() {
        val atLimit = "a".repeat(Drafts.MAX_TEXT)
        assertEquals(Draft(atLimit), roundTrip(Draft(atLimit)))
        // Ao gravar, o excesso é cortado; um valor guardado que já passa do limite não é rascunho nosso.
        assertEquals(Draft(atLimit), roundTrip(Draft(atLimit + "b")))
        assertNull(Drafts.decode("""{"text":"${atLimit}b"}"""))
    }

    @Test
    fun aChaveEPorConversa() {
        assertEquals("draft:c1", Drafts.key("c1"))
        assertEquals("draft:$reply", Drafts.key(reply))
    }
}
