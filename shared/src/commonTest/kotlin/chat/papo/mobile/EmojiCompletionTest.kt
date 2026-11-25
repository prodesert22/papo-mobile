package chat.papo.mobile

import chat.papo.mobile.chat.EmojiCompletion
import chat.papo.mobile.chat.EmojiEdit
import chat.papo.mobile.chat.EmojiQuery
import chat.papo.mobile.chat.UnicodeEmojiCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EmojiCompletionTest {
    private val catalog = UnicodeEmojiCatalog.parse(
        listOf(
            "👍\t1\tpolegar para cima\tthumbs up\tjoia\t+1|thumbsup",
            "😂\t0\trosto chorando de rir\tface with tears of joy\trir\tjoy",
            "🆗\t7\tbotão OK\tOK button\tok\tok",
        ).joinToString("\n"),
    )

    private fun query(text: String, cursor: Int = text.length) = EmojiCompletion.queryBefore(text, cursor)

    @Test
    fun achaOPrefixoDigitado() {
        assertEquals(EmojiQuery(0, "pol"), query(":pol"))
        assertEquals(EmojiQuery(3, "joy"), query("oi :joy"))
        assertEquals(EmojiQuery(1, "+1"), query("(:+1"))
        assertEquals(EmojiQuery(0, "a_b-c"), query(":a_b-c"))
    }

    @Test
    fun usaOCursorENaoOFimDoTexto() {
        assertEquals(EmojiQuery(0, "jo"), query(":jo e mais texto", cursor = 3))
    }

    @Test
    fun cedoDemaisOuForaDeLugarNaoSugere() {
        assertNull(query(":j")) // uma letra só
        assertNull(query(":)")) // carinha de texto
        assertNull(query("10:30")) // hora
        assertNull(query("https://exemplo.com")) // endereço
        assertNull(query("a:joy")) // sem espaço antes
        assertNull(query("oi"))
        assertNull(query(""))
        assertNull(query(":${"a".repeat(40)}"))
    }

    @Test
    fun trocaOPrefixoPeloEmojiMantendoOResto() {
        val text = "oi :jo e mais"
        val q = query(text, cursor = 6)!!
        assertEquals(EmojiEdit("oi 😂 e mais", 5), EmojiCompletion.replace(text, 6, q, "😂"))
    }

    private fun typeColon(before: String) = EmojiCompletion.closeShortcode(
        previous = before,
        current = "$before:",
        cursor = before.length + 1,
        catalog = catalog,
        serverNames = setOf("party_parrot"),
    )

    @Test
    fun doisPontosQueFechaAtalhoViraEmoji() {
        assertEquals(EmojiEdit("👍", 2), typeColon(":+1"))
        assertEquals(EmojiEdit("bora 😂", 7), typeColon("bora :joy"))
        assertEquals(EmojiEdit("🆗", 2), typeColon(":ok"))
    }

    @Test
    fun atalhoQueNaoExisteOuEstaEscritoDiferenteFicaComoTexto() {
        assertNull(typeColon(":naoexiste"))
        assertNull(typeColon(":JOY")) // precisa estar em minúsculas, como no cliente de terminal
        assertNull(typeColon("10:30")) // hora
    }

    @Test
    fun nomeQueOServidorTambemTemFicaComoTexto() {
        val withServer = EmojiCompletion.closeShortcode(":joy", ":joy:", 5, catalog, serverNames = setOf("joy"))
        assertNull(withServer)
    }

    @Test
    fun soVaiParaUmaTeclaDoisPontosNoCursor() {
        // Colar texto, apagar ou digitar outra letra não converte.
        assertNull(EmojiCompletion.closeShortcode(":joy", ":joy x", 6, catalog, emptySet()))
        assertNull(EmojiCompletion.closeShortcode(":joy:", ":joy", 4, catalog, emptySet()))
        assertNull(EmojiCompletion.closeShortcode("", ":joy:", 5, catalog, emptySet())) // colou um atalho inteiro
    }
}
