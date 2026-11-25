package chat.papo.mobile.chat

import chat.papo.mobile.core.parseInstant
import chat.papo.mobile.data.api.ChatApi
import chat.papo.mobile.data.api.ReactionGroupDto
import chat.papo.mobile.data.api.ReactionSummaryDto
import chat.papo.mobile.data.api.ReactionUserDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Instant

/*
 * Quem reagiu a uma mensagem. O servidor entrega as reações em páginas de 100, da mais nova à mais antiga, agrupadas
 * por emoji dentro de cada página: o mesmo emoji pode aparecer em várias páginas, e a contagem de cada grupo é a da
 * página. Não há filtro por emoji nem tamanho de página, então ler quem usou um emoji pode pedir várias páginas.
 */

/** O par (`created_at`, `id`) da última reação lida, como o servidor o mandou: é o que ele espera de volta em `since` e `last_id`. */
data class ReactorCursor(val since: String, val lastId: String)

/**
 * O cursor da página seguinte de [page]: a reação mais antiga de **todos** os grupos, não a do último grupo, porque a
 * última linha da página pode estar num grupo que apareceu antes. O servidor ordena por (`created_at`, `id`), e
 * [ReactorCursor.since] volta com o texto original: o RFC 3339 corta os zeros finais da fração, então comparar textos erra
 * ("…01Z" ficaria depois de "…01.5Z"), e reescrever o instante pode mudar o que o servidor entende.
 *
 * Nulo quando [page] não tem nenhuma reação com data legível: não há por onde continuar.
 */
fun nextReactorCursor(page: List<ReactionGroupDto>): ReactorCursor? {
    val oldest = page.asSequence()
        .flatMap { it.users }
        .mapNotNull { user -> parseInstant(user.createdAt)?.let { Dated(it, user) } }
        .minWithOrNull(compareBy<Dated>({ it.at }, { it.user.id }))
        ?: return null
    return ReactorCursor(oldest.user.createdAt, oldest.user.id)
}

private class Dated(val at: Instant, val user: ReactionUserDto)

/**
 * Junta uma página nova aos grupos já lidos: o mesmo emoji (`emojiId`, `unicode`) vira um grupo só, na ordem em que
 * apareceu, e a mesma reação (pelo `id`) entra uma vez. [ReactionGroupDto.count] passa a ser o que já foi lido.
 */
fun mergeReactors(groups: List<ReactionGroupDto>, page: List<ReactionGroupDto>): List<ReactionGroupDto> {
    val users = LinkedHashMap<Pair<String?, String?>, MutableList<ReactionUserDto>>()
    val seen = HashSet<String>()
    for (group in groups + page) {
        val into = users.getOrPut(group.emojiId to group.unicode) { mutableListOf() }
        group.users.filterTo(into) { seen.add(it.id) }
    }
    return users.map { (key, list) -> ReactionGroupDto(key.first, key.second, list.size, list) }
}

/**
 * Um grupo por reação de [summary], na ordem dela (a das bolinhas da mensagem), com quem já foi lido em [groups].
 * [ReactionGroupDto.count] é o total de [summary], que é vivo; [ReactionGroupDto.users] pode ter menos, ou nenhum
 * enquanto o emoji não apareceu em nenhuma página. Grupo lido que a mensagem já não tem (todos tiraram a reação) some.
 */
fun orderLike(summary: List<ReactionSummaryDto>, groups: List<ReactionGroupDto>): List<ReactionGroupDto> {
    val read = groups.associateBy { it.emojiId to it.unicode }
    return summary.map { reaction ->
        ReactionGroupDto(reaction.emojiId, reaction.unicode, reaction.count, read[reaction.emojiId to reaction.unicode]?.users.orEmpty())
    }
}

/** O que já foi lido de quem reagiu. */
data class ReactorsState(
    val groups: List<ReactionGroupDto> = emptyList(),
    /** Há páginas por ler. Vale `true` antes da primeira: ainda não se sabe que não há. */
    val hasMore: Boolean = true,
    /** Um pedido em andamento; nunca há dois. */
    val loading: Boolean = false,
    /** A última leitura falhou. O que já foi lido continua em [groups], e nada se repete sozinho. */
    val error: Throwable? = null,
)

/**
 * A lista de quem reagiu a uma mensagem, lida página a página. O dono é quem passa o [scope] (a folha que a mostra):
 * fechá-la cancela o pedido em voo. O cursor e o emoji pedido são mexidos só pela thread desse escopo e de quem chama
 * (a da tela); [state] pode ser lido de qualquer uma.
 *
 * Só há um pedido por vez. [reveal] lê páginas até o emoji pedido ter alguém, no máximo [OPENING_PAGES] seguidas;
 * depois disso [loadMore] lê uma de cada vez, por rolagem ou por botão. Nada é pedido sem uma dessas chamadas, e
 * uma leitura que falhou só recomeça por [loadMore]. A lista é uma foto de quando foi lida: o servidor não avisa
 * quem reagiu (só a contagem), então abrir a folha de novo é que a atualiza.
 */
class ReactorList(
    private val api: ChatApi,
    private val channelId: String,
    private val messageId: String,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(ReactorsState())
    val state: StateFlow<ReactorsState> = _state.asStateFlow()

    private var cursor: ReactorCursor? = null

    /** O emoji que a pessoa quer ver e quantas páginas ainda se pode ler sozinho atrás dele. */
    private var wanted: Pair<String?, String?>? = null
    private var budget = 0

    /** Lê páginas até o emoji ter alguém; se já tem, não pede nada. */
    fun reveal(emojiId: String?, unicode: String?) {
        wanted = emojiId to unicode
        budget = OPENING_PAGES
        if (seeking()) loadMore()
    }

    /** Lê a página seguinte (e as que faltarem para o emoji de [reveal]). Sem efeito se há um pedido em voo ou nada a ler. */
    fun loadMore() {
        if (!claim()) return
        scope.launch { readPages() }
    }

    /** Ainda se lê por conta própria: há um emoji pedido, sobra página no teto da abertura e ele ainda não tem ninguém. */
    private fun seeking(): Boolean {
        val key = wanted ?: return false
        return budget > 0 && _state.value.groups.none { it.emojiId to it.unicode == key && it.users.isNotEmpty() }
    }

    /** Marca o pedido como em voo; falso se já há um ou se não há mais o que ler. Atômico: [loading] é a trava. */
    private fun claim(): Boolean {
        val before = _state.getAndUpdate { if (it.loading || !it.hasMore) it else it.copy(loading = true, error = null) }
        return !before.loading && before.hasMore
    }

    private suspend fun readPages() {
        try {
            do {
                val more = readPage()
            } while (more && seeking())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e) }
        } finally {
            _state.update { it.copy(loading = false) }
        }
    }

    /** Lê uma página e a junta ao que já há. Devolve se ainda há o que ler depois dela. */
    private suspend fun readPage(): Boolean {
        val current = cursor
        val page = api.reactions(channelId, messageId, current?.since, current?.lastId)
        val next = nextReactorCursor(page.reactions)
        // Sem cursor, ou com um que não andou, a página seguinte seria a mesma: parar vale mais que um laço.
        val more = page.hasMore && next != null && next != current
        cursor = next
        budget = maxOf(0, budget - 1)
        _state.update { it.copy(groups = mergeReactors(it.groups, page.reactions), hasMore = more) }
        return more
    }

    companion object {
        /** Quantas páginas seguidas [reveal] lê atrás de um emoji sem ninguém na lista, antes de esperar a pessoa pedir mais. */
        const val OPENING_PAGES = 5
    }
}
