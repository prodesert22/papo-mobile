package chat.papo.mobile.chat

import doist.x.normalize.Form
import doist.x.normalize.normalize

/**
 * Texto em minúsculas e sem acentos, para comparar nomes do jeito que as pessoas
 * digitam: `joao` acha "João". A decomposição Unicode vem da biblioteca
 * `normalize`, porque a biblioteca padrão comum do Kotlin não tem normalizador.
 */
fun foldForSearch(text: String): String =
    text.normalize(Form.NFD).filterNot { it.category == CharCategory.NON_SPACING_MARK }.lowercase()

private val wordBreaks = charArrayOf(' ', '_', '.', '-')

/** [this] sem as quebras de palavra: `lu moraes`, `lu_moraes` e `lu.moraes` viram `lumoraes`. */
private fun String.withoutBreaks(): String = filterNot { it in wordBreaks }

/**
 * Quão bem [name] casa com [query], ambos já dobrados; menor é melhor e null é "não casa". [bareQuery] é a
 * [query] sem as quebras de palavra, calculada uma vez por busca porque aqui roda a cada nome.
 */
private fun matchRank(name: String, query: String, bareQuery: String): Int? = when {
    name == query -> 0
    name.startsWith(query) -> 1
    name.split(*wordBreaks).any { it.startsWith(query) } -> 2
    query in name -> 3
    // Sem as quebras, a consulta pode atravessá-las: `lum` acha "Lu Moraes". Uma consulta feita só de quebras
    // ficaria vazia e acharia todo mundo, por isso só vale com letras.
    bareQuery.isNotEmpty() && bareQuery in name.withoutBreaks() -> 4
    else -> null
}

/**
 * Itens em que algum dos [names] contém [query], sem diferenciar acento nem
 * maiúsculas. Vêm primeiro os nomes iguais à consulta, depois os que começam
 * com ela, os que têm uma palavra começando com ela, os que a contêm e, por fim,
 * os que só a contêm atravessando quebras de palavra (espaço, `_`, `.` e `-`),
 * que são ignoradas nos dois lados: `lum` acha "Lu Moraes" e `lu m` acha `lu_moraes`.
 * As letras da consulta têm de ficar juntas; não há casamento aproximado.
 * Dentro de cada grupo vale a ordem de entrada. Consulta vazia devolve tudo.
 */
fun <T> searchByName(query: String, items: List<T>, names: (T) -> List<String>): List<T> {
    val folded = foldForSearch(query.trim())
    if (folded.isEmpty()) return items
    val bare = folded.withoutBreaks()
    return items
        .mapNotNull { item -> names(item).mapNotNull { matchRank(foldForSearch(it), folded, bare) }.minOrNull()?.let { it to item } }
        .sortedBy { it.first }
        .map { it.second }
}

/** Fixadas cujo texto ou autor casam com [query], sem diferenciar acento nem maiúsculas. */
fun filterPinned(pinned: List<Message>, users: Map<String, User>, query: String): List<Message> {
    val folded = foldForSearch(query.trim())
    if (folded.isEmpty()) return pinned
    return pinned.filter { message ->
        val author = message.authorId?.let { users[it] }
        val fields = listOfNotNull(
            renderMentions(message.content) { users[it]?.displayName },
            message.externalAuthor?.name,
            author?.displayName,
            author?.username,
        )
        fields.any { folded in foldForSearch(it) }
    }
}
