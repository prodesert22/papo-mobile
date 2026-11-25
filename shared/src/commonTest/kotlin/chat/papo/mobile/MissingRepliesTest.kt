package chat.papo.mobile

import chat.papo.mobile.ui.chat.plusBounded
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MissingRepliesTest {
    @Test
    fun acrescentaSemRepetir() {
        assertEquals(setOf("a", "b"), setOf("a").plusBounded("b"))
        assertEquals(setOf("a"), setOf("a").plusBounded("a"))
    }

    @Test
    fun repetirUmIdORenovaComoMaisRecente() {
        val set = linkedSetOf("a", "b", "c").plusBounded("a", max = 3).plusBounded("d", max = 3)
        // "a" voltou a ser o mais novo, então "b" é quem sai primeiro.
        assertEquals(listOf("c", "a", "d"), set.toList())
    }

    @Test
    fun noLimiteNadaSai() {
        val set = linkedSetOf("a", "b").plusBounded("c", max = 3)
        assertEquals(listOf("a", "b", "c"), set.toList())
    }

    @Test
    fun passandoDoLimiteSaiOMaisAntigo() {
        val set = linkedSetOf("a", "b", "c").plusBounded("d", max = 3)
        assertEquals(listOf("b", "c", "d"), set.toList())
        assertFalse("a" in set)
        assertTrue("d" in set)
    }
}
