package chat.papo.mobile.chat

import chat.papo.mobile.resources.Res
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Um emoji Unicode do catálogo embutido no app. */
class UnicodeEmoji(
    /** O emoji como texto, já na forma completa (com FE0F onde precisa). */
    val char: String,
    /** Índice do grupo (0..8), na ordem de [UnicodeEmojiCatalog.GROUP_COUNT]. */
    val group: Int,
    /** Nome em português. */
    val name: String,
    val nameEn: String,
    val keywords: List<String>,
    /** Atalhos `:nome:` sem os dois-pontos; o primeiro é o principal. */
    val shortcodes: List<String>,
) {
    internal val nameKey = foldForSearch(name)
    internal val nameEnKey = foldForSearch(nameEn)
    internal val keywordKeys = keywords.map(::foldForSearch)
    internal val shortcodeKeys = shortcodes.map { it.lowercase() }

    /** Palavras de todos os nomes e palavras-chave, sem acento, para casar o começo de uma palavra. */
    internal val words: List<String> = buildList {
        for (text in listOf(nameKey, nameEnKey) + keywordKeys) addAll(text.split(' ').filter { it.isNotEmpty() })
        for (code in shortcodeKeys) addAll(code.split('_').filter { it.isNotEmpty() })
    }.distinct()
}

/**
 * Catálogo dos emojis Unicode: busca por nome, palavra-chave e atalho, e
 * reconhecimento de emojis dentro de um texto (inclusive sequências com tom de
 * pele, ZWJ, bandeiras e teclas). Os dados vêm de `files/emoji-catalog.tsv`,
 * gerado por `tools/dev/emoji_catalog.py`.
 */
class UnicodeEmojiCatalog(val emojis: List<UnicodeEmoji>) {
    private val byShortcode: Map<String, UnicodeEmoji> = buildMap {
        // O primeiro emoji que declara um atalho fica com ele.
        for (emoji in emojis) for (code in emoji.shortcodeKeys) putIfAbsent(code, emoji)
    }

    /** Primeiros code points de emojis que já são figura sozinhos (😀). */
    private val presentationBases = HashSet<Int>()

    /** Code points que só viram figura com FE0F depois (❤️). */
    private val textBases = HashSet<Int>()

    init {
        for (emoji in emojis) {
            val points = codePoints(emoji.char)
            when {
                points.size == 1 -> presentationBases += points[0]
                points.size == 2 && points[1] == VARIATION_SELECTOR -> textBases += points[0]
            }
        }
    }

    private val byChar: Map<String, UnicodeEmoji> = emojis.associateBy { it.char }

    /** Os emojis de cada grupo (0 até [GROUP_COUNT] - 1), na ordem do catálogo. */
    val groups: List<List<UnicodeEmoji>> = List(GROUP_COUNT) { group -> emojis.filter { it.group == group } }

    fun forShortcode(name: String): UnicodeEmoji? = byShortcode[name.lowercase()]

    fun forChar(char: String): UnicodeEmoji? = byChar[char]

    /**
     * Emojis que combinam com [query], os melhores primeiro. Cada palavra da busca
     * precisa casar; a ordem do catálogo desempata. Ignora maiúsculas, acentos e
     * os dois-pontos nas pontas (`:joia:` e `joia` dão o mesmo).
     */
    fun search(query: String, limit: Int = Int.MAX_VALUE): List<UnicodeEmoji> {
        val tokens = foldForSearch(query).trim().trim(':').split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        return emojis.asSequence()
            .mapNotNull { emoji -> score(emoji, tokens)?.let { Ranked(emoji, it, nearest(emoji, tokens)) } }
            .sortedWith(compareBy({ it.level }, { it.length })) // estável: a ordem do catálogo desempata
            .map { it.emoji }
            .take(limit)
            .toList()
    }

    /** Tamanho da menor chave (atalho ou nome) que começa com alguma palavra da busca; menor é mais próximo. */
    private fun nearest(emoji: UnicodeEmoji, tokens: List<String>): Int {
        var best = Int.MAX_VALUE
        for (token in tokens) {
            for (code in emoji.shortcodeKeys) if (code.startsWith(token) && code.length < best) best = code.length
            for (name in listOf(emoji.nameKey, emoji.nameEnKey)) if (name.startsWith(token) && name.length < best) best = name.length
        }
        return best
    }

    /**
     * Sugestões para quem digitou `:prefixo`: só atalhos que começam com o texto
     * (nada de busca ampla), em ordem alfabética dentro de cada nível.
     */
    fun suggest(prefix: String, limit: Int): List<UnicodeEmoji> {
        val key = prefix.lowercase()
        if (key.isEmpty()) return emptyList()
        val compact = key.replace("_", "")
        val folded = foldForSearch(key)
        return emojis.asSequence()
            .mapNotNull { emoji ->
                val exact = key in emoji.shortcodeKeys
                val starts = emoji.shortcodeKeys.filter { it.startsWith(key) }
                val loose = emoji.shortcodeKeys.filter { it.replace("_", "").startsWith(compact) }
                when {
                    exact -> Ranked(emoji, 0, key.length)
                    starts.isNotEmpty() -> Ranked(emoji, 1, starts.minOf { it.length })
                    loose.isNotEmpty() -> Ranked(emoji, 2, loose.minOf { it.length })
                    key.length >= 3 && emoji.words.any { it.startsWith(folded) } -> Ranked(emoji, 3, 0)
                    else -> null
                }
            }
            // Dentro de cada nível, o atalho mais curto é o mais perto do que foi digitado.
            .sortedWith(compareBy({ it.level }, { it.length })) // estável: a ordem do catálogo desempata
            .map { it.emoji }
            .take(limit)
            .toList()
    }

    private class Ranked(val emoji: UnicodeEmoji, val level: Int, val length: Int)

    private fun score(emoji: UnicodeEmoji, tokens: List<String>): Int? {
        var worst = 0
        for (token in tokens) {
            val compact = token.replace("_", "")
            val level = when {
                token in emoji.shortcodeKeys -> 0
                emoji.shortcodeKeys.any { it.startsWith(token) } ||
                    emoji.nameKey.startsWith(token) || emoji.nameEnKey.startsWith(token) -> 1
                emoji.words.any { it.startsWith(token) } -> 2
                emoji.nameKey.contains(token) || emoji.nameEnKey.contains(token) ||
                    emoji.keywordKeys.any { it.contains(token) } ||
                    (compact.length >= 3 && emoji.shortcodeKeys.any { it.replace("_", "").contains(compact) }) -> 3
                else -> return null
            }
            if (level > worst) worst = level
        }
        return worst
    }

    /**
     * Tamanho, em chars, do emoji que começa em [index] de [text]; 0 se ali não há
     * emoji. Aceita tom de pele, sequências ZWJ, bandeiras, teclas e tags. Um
     * símbolo que por padrão é texto (© ™ ❤) só conta com o FE0F depois dele.
     */
    fun emojiLengthAt(text: String, index: Int): Int {
        if (index >= text.length) return 0
        var i = index
        val first = codePointAt(text, i)
        when {
            first in REGIONAL_INDICATORS -> {
                val next = i + charCount(first)
                if (next < text.length && codePointAt(text, next) in REGIONAL_INDICATORS) {
                    return next + charCount(codePointAt(text, next)) - index
                }
                return 0
            }
            first == '#'.code || first == '*'.code || first in '0'.code..'9'.code -> {
                var j = i + 1
                if (j < text.length && text[j].code == VARIATION_SELECTOR) j++
                return if (j < text.length && text[j].code == KEYCAP) j + 1 - index else 0
            }
            first in presentationBases -> i += charCount(first)
            first in textBases && followedBy(text, i + charCount(first), VARIATION_SELECTOR) -> i += charCount(first) + 1
            else -> return 0
        }
        while (i < text.length) {
            val cp = codePointAt(text, i)
            when {
                cp == VARIATION_SELECTOR -> i++
                cp in SKIN_TONES -> i += charCount(cp)
                cp in TAGS -> i += charCount(cp)
                cp == ZWJ -> {
                    val after = i + 1
                    if (after >= text.length) break
                    val joined = codePointAt(text, after)
                    if (joined in presentationBases || joined in textBases || joined in HAIR) {
                        i = after + charCount(joined)
                    } else {
                        break
                    }
                }
                else -> break
            }
        }
        return i - index
    }

    private fun followedBy(text: String, at: Int, code: Int) = at < text.length && text[at].code == code

    companion object {
        const val GROUP_COUNT = 9
        private const val VARIATION_SELECTOR = 0xFE0F
        private const val ZWJ = 0x200D
        private const val KEYCAP = 0x20E3
        private val REGIONAL_INDICATORS = 0x1F1E6..0x1F1FF
        private val SKIN_TONES = 0x1F3FB..0x1F3FF
        private val TAGS = 0xE0020..0xE007F
        private val HAIR = 0x1F9B0..0x1F9B3

        /** Lê o conteúdo de `emoji-catalog.tsv`. Linhas que começam com `#` são comentário. */
        fun parse(tsv: String): UnicodeEmojiCatalog {
            val rows = ArrayList<UnicodeEmoji>(2000)
            for (line in tsv.lineSequence()) {
                if (line.isEmpty() || line.startsWith("#")) continue
                val cols = line.split('\t')
                if (cols.size < 6) continue
                val group = cols[1].toIntOrNull() ?: continue
                rows += UnicodeEmoji(
                    char = cols[0],
                    group = group,
                    name = cols[2],
                    nameEn = cols[3],
                    keywords = cols[4].split('|').filter { it.isNotEmpty() },
                    shortcodes = cols[5].split('|').filter { it.isNotEmpty() },
                )
            }
            return UnicodeEmojiCatalog(rows)
        }

        private fun codePointAt(text: String, index: Int): Int {
            val high = text[index]
            if (high.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
                return 0x10000 + ((high.code - 0xD800) shl 10) + (text[index + 1].code - 0xDC00)
            }
            return high.code
        }

        private fun charCount(codePoint: Int) = if (codePoint >= 0x10000) 2 else 1

        private fun codePoints(text: String): List<Int> {
            val out = ArrayList<Int>(text.length)
            var i = 0
            while (i < text.length) {
                val cp = codePointAt(text, i)
                out += cp
                i += charCount(cp)
            }
            return out
        }
    }
}

/**
 * O catálogo é o mesmo para todas as contas (não tem dado de ninguém): é lido do
 * recurso do app uma vez por processo e guardado.
 */
object UnicodeEmojis {
    private val mutex = Mutex()
    private var loaded: UnicodeEmojiCatalog? = null
    private var drawable: UnicodeEmojiCatalog? = null

    /** Todos os emojis do catálogo, inclusive os que este aparelho não sabe desenhar. */
    suspend fun get(): UnicodeEmojiCatalog = mutex.withLock { load() }

    /**
     * Só os emojis que [canDraw] aceita, para o seletor e as sugestões não mostrarem
     * quadrados vazios. A resposta vale para o processo todo: a fonte não muda.
     */
    suspend fun drawable(canDraw: (String) -> Boolean): UnicodeEmojiCatalog = mutex.withLock {
        drawable ?: withContext(Dispatchers.Default) {
            UnicodeEmojiCatalog(load().emojis.filter { canDraw(it.char) })
        }.also { drawable = it }
    }

    private suspend fun load(): UnicodeEmojiCatalog = loaded ?: withContext(Dispatchers.Default) {
        UnicodeEmojiCatalog.parse(Res.readBytes("files/emoji-catalog.tsv").decodeToString())
    }.also { loaded = it }
}
