package chat.papo.mobile

import chat.papo.mobile.chat.UnicodeEmojiCatalog
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Confere o arquivo `emoji-catalog.tsv` que o app embute, sem passar pelos recursos do Compose. */
class EmojiCatalogFileTest {
    private val file = File("src/commonMain/composeResources/files/emoji-catalog.tsv")
    private val catalog by lazy { UnicodeEmojiCatalog.parse(file.readText()) }

    @Test
    fun arquivoTemOCatalogoCompleto() {
        assertTrue(file.exists(), "esperava ${file.absolutePath}")
        assertTrue(catalog.emojis.size > 1800, "só ${catalog.emojis.size} emojis")
        assertEquals((0 until UnicodeEmojiCatalog.GROUP_COUNT).toSet(), catalog.emojis.map { it.group }.toSet())
        assertTrue(catalog.emojis.all { it.name.isNotBlank() && it.char.isNotEmpty() })
    }

    @Test
    fun todasAsLinhasTemSeisColunas() {
        val rows = file.readLines().filter { it.isNotEmpty() && !it.startsWith("#") }
        assertTrue(rows.all { it.split('\t').size == 6 }, "linha malformada")
        assertEquals(rows.size, catalog.emojis.size)
    }

    @Test
    fun oReconhecedorCobreCadaEmojiDoCatalogo() {
        val sem = catalog.emojis.filter { catalog.emojiLengthAt(it.char, 0) != it.char.length }
        assertTrue(sem.isEmpty(), "não reconhecidos: " + sem.take(10).joinToString { it.char + " " + it.name })
    }

    @Test
    fun emojisSeguidosSaoReconhecidosUmAUm() {
        val text = "😀👍❤️🇧🇷"
        val sizes = buildList {
            var i = 0
            while (i < text.length) {
                val n = catalog.emojiLengthAt(text, i)
                assertTrue(n > 0, "sem emoji em $i")
                add(n)
                i += n
            }
        }
        assertEquals(4, sizes.size)
    }

    @Test
    fun buscasComunsAchamOEmojiCerto() {
        assertEquals("👍", catalog.search("polegar para cima").first().char)
        assertEquals("👍", catalog.search("thumbsup").first().char)
        assertEquals("👍", catalog.search(":+1:").first().char)
        assertEquals("😂", catalog.search("joy").first().char)
        assertEquals("🙏", catalog.search("pray").first().char)
        assertTrue(catalog.search("coracao").any { it.char == "❤️" })
        assertTrue(catalog.search("bandeira brasil").any { it.char == "🇧🇷" })
        assertTrue(catalog.search("cachorro").any { it.char == "🐶" })
    }

    @Test
    fun sugestoesPorAtalhoComecamPeloExato() {
        assertEquals("👍", catalog.suggest("thumbsup", 3).first().char)
        assertEquals("😂", catalog.suggest("joy", 3).first().char)
        assertTrue(catalog.suggest("hear", 8).any { it.char == "❤️" })
        assertEquals("👍", catalog.forShortcode("+1")?.char)
    }
}
