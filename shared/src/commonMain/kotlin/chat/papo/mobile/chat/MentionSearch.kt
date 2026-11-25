package chat.papo.mobile.chat

/** Menção sendo digitada: o `@` fica em [start] e [text] é o que vem depois dele, até o cursor. */
data class MentionQuery(val start: Int, val text: String)

/** Maior consulta de menção, em caracteres depois do `@`. */
const val MAX_MENTION_QUERY = 32

/**
 * Se o `@` na posição [at] de [text] pode abrir uma menção: no começo do texto, depois de espaço ou
 * quebra de linha, de `(` ou de `"`. Depois de letra ou número ele faz parte de outra coisa, como o
 * `a@b.com` de um e-mail. O autocompletar e [encodeMentions] usam esta mesma regra, para que o
 * que é oferecido seja também o que é convertido.
 */
internal fun mentionMayStartAt(text: String, at: Int): Boolean {
    if (at == 0) return true
    val before = text[at - 1]
    return before.isWhitespace() || before == '(' || before == '"'
}

/**
 * A menção que o cursor está terminando de digitar, se houver: o `@` mais próximo antes de [cursor], com
 * até [MAX_MENTION_QUERY] caracteres depois dele. Cabe um espaço no meio da consulta, para achar
 * "João Silva" pelo nome de exibição; uma quebra de linha, um segundo espaço ou um espaço logo depois
 * do `@` encerram a menção.
 */
fun mentionQueryAt(text: String, cursor: Int): MentionQuery? {
    if (cursor !in 0..text.length) return null
    val oldest = maxOf(0, cursor - MAX_MENTION_QUERY - 1)
    val at = (cursor - 1 downTo oldest).firstOrNull { text[it] == '@' } ?: return null
    if (!mentionMayStartAt(text, at)) return null
    val query = text.substring(at + 1, cursor)
    val blanks = query.count { it.isWhitespace() }
    if (blanks > 1 || (blanks == 1 && (' ' !in query || query.first() == ' '))) return null
    return MentionQuery(at, query)
}

/**
 * Quem pode ser mencionado com [query]: todos menos [me] e os banidos, do melhor casamento (nome igual,
 * começa com, começo de palavra, contém; ver [searchByName]) ao pior, no username ou no nome de exibição.
 * Entre casamentos iguais vêm primeiro quem falou há pouco no canal ([recentAuthors], do mais recente ao
 * mais antigo), depois quem está online, depois a ordem alfabética sem acento. Consulta vazia lista todos.
 */
fun suggestMentions(
    query: String,
    users: Collection<User>,
    me: String,
    recentAuthors: List<String>,
    presence: Map<String, Presence>,
): List<User> {
    val recency = HashMap<String, Int>()
    recentAuthors.forEachIndexed { index, id -> recency.getOrPut(id) { index } }
    val candidates = users.filter { it.id != me && !it.banned }
    val alphabetical = candidates.associate { it.id to foldForSearch(it.displayName) }
    val ordered = candidates.sortedWith(
        compareBy<User> { recency[it.id] ?: Int.MAX_VALUE }
            .thenBy { if (presence[it.id]?.online == true) 0 else 1 }
            .thenBy { alphabetical.getValue(it.id) }
            .thenBy { it.username.lowercase() }
            .thenBy { it.id },
    )
    return searchByName(query, ordered) { listOf(it.username, it.displayName) }
}

/** Autores das [messages], do mais recente ao mais antigo e sem repetir (mensagens sem autor, como as de webhook, ficam de fora). */
fun authorsByRecency(messages: List<Message>): List<String> = messages.mapNotNull { it.authorId }.distinct()
