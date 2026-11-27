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
 * O autocompletar oferece `@everyone` para [query]? Só com [allowed] (a permissão do autor e nunca numa DM, que já
 * avisa todos: ver `ConversationAccess.mentionEveryone`) e se `everyone` começa com o que foi digitado, sem
 * diferenciar maiúsculas, como o web. Consulta vazia oferece; `everyone ` (com espaço) já não.
 */
fun offersEveryone(query: String, allowed: Boolean): Boolean = allowed && EVERYONE_NAME.startsWith(query.lowercase())

/** O rascunho depois de escolher uma menção: o [text] novo e onde fica o cursor. */
data class MentionEdit(val text: String, val cursor: Int)

/**
 * Troca a [mention] que o cursor ([cursor]) está terminando de digitar por `@nome ` e põe o cursor depois do
 * espaço. É texto comum: o servidor lê o `@everyone` literal, e o `@username` escolhido vira `@mention(<@id>)` só
 * no envio (ver [encodeMentions]).
 */
fun completeMention(text: String, mention: MentionQuery, cursor: Int, name: String): MentionEdit {
    val inserted = "@$name "
    return MentionEdit(text.substring(0, mention.start) + inserted + text.substring(cursor), mention.start + inserted.length)
}

/**
 * Quem pode ser mencionado com [query]: todos menos [me] e os banidos, ou, numa conversa direta, só os dois
 * [participants] (o próprio usuário incluso, menos um banido), do melhor casamento (nome igual,
 * começa com, começo de palavra, contém, contém atravessando espaço, `_`, `.` ou `-`, como `lum` em
 * "Lu Moraes"; ver [searchByName]) ao pior, no username ou no nome de exibição.
 * Entre casamentos iguais vêm primeiro quem falou há pouco no canal ([recentAuthors], do mais recente ao
 * mais antigo), depois quem está online, depois a ordem alfabética sem acento. Consulta vazia lista todos.
 */
fun suggestMentions(
    query: String,
    users: Collection<User>,
    me: String,
    recentAuthors: List<String>,
    presence: Map<String, Presence>,
    /** Os dois membros de uma conversa direta; nulo num canal, onde todos do servidor podem ser mencionados. */
    participants: Set<String>? = null,
): List<User> {
    val recency = HashMap<String, Int>()
    recentAuthors.forEachIndexed { index, id -> recency.getOrPut(id) { index } }
    val candidates = users.filter { !it.banned && if (participants != null) it.id in participants else it.id != me }
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
