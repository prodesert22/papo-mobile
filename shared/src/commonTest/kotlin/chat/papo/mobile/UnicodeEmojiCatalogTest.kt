package chat.papo.mobile

import chat.papo.mobile.chat.UnicodeEmojiCatalog
import chat.papo.mobile.chat.foldForSearch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnicodeEmojiCatalogTest {
    private val heart = "\u2764\uFE0F"
    private val pointUp = "\u261D\uFE0F"
    private val copyright = "\u00A9\uFE0F"
    private val keycapOne = "1\uFE0F\u20E3"

    private val tsv = listOf(
        "# comentário",
        "😀\t0\trosto risonho\tgrinning face\tfeliz|sorriso\tgrinning|grinning_face",
        "😂\t0\trosto chorando de rir\tface with tears of joy\tgargalhada|rir\tjoy|face_with_tears_of_joy",
        "👍\t1\tpolegar para cima\tthumbs up\tjoia|beleza|ok\t+1|thumbsup",
        "$heart\t7\tcoração vermelho\tred heart\tamor|coração\theart|red_heart",
        "$pointUp\t1\tindicador apontando para cima\tindex pointing up\tdedo\tpoint_up",
        "👆\t1\tdorso da mão com dedo indicador apontando para cima\tbackhand index pointing up\tdedo\tpoint_up_2",
        "🇧🇷\t8\tbandeira: Brasil\tflag: Brazil\tpaís\tflag_br",
        "👩\t1\tmulher\twoman\tmulher\twoman",
        "👩‍🦰\t1\tmulher: cabelo vermelho\twoman: red hair\tmulher\tred_haired_woman",
        "$copyright\t7\tdireitos autorais\tcopyright\tcopyright\tcopyright",
        "$keycapOne\t7\ttecla: 1\tkeycap: 1\tnúmero\tone",
    ).joinToString("\n")

    private val catalog = UnicodeEmojiCatalog.parse(tsv)

    @Test
    fun linhasDeComentarioNaoViramEmoji() {
        assertEquals(11, catalog.emojis.size)
    }

    @Test
    fun foldTiraAcentoEMaiuscula() {
        assertEquals("joao", foldForSearch("João"))
        assertEquals("acao", foldForSearch("AÇÃO"))
        assertEquals("coracao", foldForSearch("coração"))
    }

    @Test
    fun buscaPorNomeSemAcentoEPorAtalho() {
        assertEquals("👍", catalog.search("polegar").first().char)
        assertEquals("👍", catalog.search("joia").first().char)
        assertEquals("👍", catalog.search("JÓIA").first().char)
        assertEquals(heart, catalog.search("coracao").first().char)
        assertEquals("👍", catalog.search(":thumbsup:").first().char)
        assertEquals("👍", catalog.search("+1").first().char)
        assertEquals("👍", catalog.search("thumbs up").first().char)
    }

    @Test
    fun buscaIgnoraOSublinhadoDosAtalhos() {
        assertEquals(pointUp, catalog.search("pointup").first().char)
        assertEquals("😂", catalog.search("tearsofjoy").first().char)
    }

    @Test
    fun buscaComVariasPalavrasExigeTodas() {
        assertEquals(listOf("😂"), catalog.search("chorando rir").map { it.char })
        assertTrue(catalog.search("chorando polegar").isEmpty())
    }

    @Test
    fun atalhoExatoVemAntesDeQuemSoComecaComOTexto() {
        // `point_up` é exato para ☝️; 👆 só tem `point_up_2`, que começa com o texto.
        assertEquals(listOf(pointUp, "👆"), catalog.search("point_up").map { it.char })
    }

    @Test
    fun buscaVaziaNaoDevolveNada() {
        assertTrue(catalog.search("").isEmpty())
        assertTrue(catalog.search("  ::  ").isEmpty())
    }

    @Test
    fun atalhoPeloNome() {
        assertEquals("👍", catalog.forShortcode("thumbsup")?.char)
        assertEquals("👍", catalog.forShortcode("+1")?.char)
        assertEquals("👍", catalog.forShortcode("THUMBSUP")?.char)
        assertNull(catalog.forShortcode("naoexiste"))
    }

    @Test
    fun sugestoesPorPrefixoDeAtalho() {
        assertEquals("👍", catalog.suggest("thumbs", 5).first().char)
        assertEquals("👍", catalog.suggest("+", 5).first().char)
        // sem sublinhado também acha
        assertEquals(pointUp, catalog.suggest("pointup", 5).first().char)
        // sugestão não faz busca ampla: "rir" é só palavra-chave e tem menos de 3 letras úteis no prefixo
        assertTrue(catalog.suggest("xyz", 5).isEmpty())
    }

    @Test
    fun reconheceEmojiSimplesEComposto() {
        fun len(text: String) = catalog.emojiLengthAt(text, 0)
        assertEquals(2, len("😀"))
        assertEquals("👍\uD83C\uDFFD".length, len("👍\uD83C\uDFFD")) // tom de pele
        assertEquals(2, len(heart)) // com FE0F
        assertEquals(4, len("🇧🇷")) // bandeira
        assertEquals("👩‍🦰".length, len("👩‍🦰")) // ZWJ com cabelo
        assertEquals(keycapOne.length, len(keycapOne)) // tecla
        assertEquals("👩🏽‍🦰".length, len("👩🏽‍🦰")) // tom de pele dentro de ZWJ
    }

    @Test
    fun simboloDeTextoSemFe0fNaoEEmoji() {
        assertEquals(0, catalog.emojiLengthAt("\u00A9", 0))
        assertEquals(0, catalog.emojiLengthAt("\u2764", 0))
        assertEquals(0, catalog.emojiLengthAt("1", 0))
        assertEquals(0, catalog.emojiLengthAt("a", 0))
        assertEquals(0, catalog.emojiLengthAt("#", 0))
    }

    @Test
    fun reconheceEmojiNoMeioDoTexto() {
        val text = "oi 😀!"
        assertEquals(0, catalog.emojiLengthAt(text, 0))
        assertEquals(2, catalog.emojiLengthAt(text, 3))
        assertEquals(0, catalog.emojiLengthAt(text, 5))
        assertEquals(0, catalog.emojiLengthAt(text, text.length))
    }

    @Test
    fun zwjPendenteNaoEngoleOQueVemDepois() {
        // ZWJ solto no fim, ou seguido de letra, termina o emoji antes dele.
        assertEquals(2, catalog.emojiLengthAt("👍‍", 0))
        assertEquals(2, catalog.emojiLengthAt("👍‍a", 0))
    }
}
