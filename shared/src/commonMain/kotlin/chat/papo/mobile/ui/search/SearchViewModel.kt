package chat.papo.mobile.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.SearchFilters
import chat.papo.mobile.chat.SearchHit
import chat.papo.mobile.chat.toHit
import chat.papo.mobile.data.api.SearchRequest
import chat.papo.mobile.ui.components.UiError
import chat.papo.mobile.ui.components.toUiError
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val filters: SearchFilters = SearchFilters(),
    val results: List<SearchHit> = emptyList(),
    /** A última busca terminou e [results] é a resposta dela. */
    val searched: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    /** Há mais resultados no servidor, mas a lista bateu no teto local. */
    val truncated: Boolean = false,
    val error: UiError? = null,
)

/**
 * Busca de mensagens no servidor de uma conta. Nada é pesquisado enquanto se
 * digita: o texto só vai ao servidor quando o usuário manda buscar. Mudar um
 * filtro refaz a busca com o texto atual. Os resultados ficam só na memória.
 */
class SearchViewModel(private val session: AccountSession, initialChannelId: String? = null) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState(filters = SearchFilters(channelId = initialChannelId)))
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    val recentFilters: StateFlow<List<SearchFilters>> get() = session.recentSearches.items

    private var job: Job? = null

    /** Consulta a que os resultados na tela pertencem; "carregar mais" continua essa, não o que está digitado. */
    private var submitted: SearchRequest? = null
    private var submittedText = ""

    /** `created_at` e `id` do último resultado recebido: o cursor da página seguinte. */
    private var cursor: Pair<String, String>? = null

    fun submit(text: String) = run(text, _state.value.filters)

    fun setFilters(filters: SearchFilters, text: String) {
        if (filters == _state.value.filters) return
        _state.update { it.copy(filters = filters) }
        run(text, filters)
    }

    /** Repete a última busca depois de um erro. Nunca é chamado sozinho. */
    fun retry() = run(submittedText, _state.value.filters)

    private fun run(text: String, filters: SearchFilters) {
        job?.cancel()
        cursor = null
        submittedText = text
        val request = filters.toRequest(text)
        submitted = request
        if (request == null) {
            _state.update { SearchUiState(filters = filters) }
            return
        }
        _state.update { SearchUiState(filters = filters, loading = true) }
        job = viewModelScope.launch {
            try {
                val page = session.api.search(request)
                cursor = page.results.lastOrNull()?.let { it.createdAt to it.id }
                session.recentSearches.remember(filters)
                _state.update {
                    it.copy(
                        results = page.results.map { result -> result.toHit() },
                        searched = true,
                        loading = false,
                        hasMore = page.hasMore && page.results.isNotEmpty(),
                    )
                }
            } catch (e: Throwable) {
                // toUiError relança o cancelamento: uma busca trocada por outra não vira erro na tela.
                val error = e.toUiError()
                _state.update { it.copy(loading = false, error = error) }
            }
        }
    }

    fun loadMore() {
        val current = _state.value
        val request = submitted ?: return
        val (since, lastId) = cursor ?: return
        if (current.loading || current.loadingMore || !current.hasMore) return
        _state.update { it.copy(loadingMore = true, error = null) }
        job = viewModelScope.launch {
            try {
                val page = session.api.search(request, since = since, lastId = lastId)
                page.results.lastOrNull()?.let { cursor = it.createdAt to it.id }
                _state.update { state ->
                    val known = state.results.mapTo(HashSet()) { it.id }
                    val merged = state.results + page.results.filter { it.id !in known }.map { it.toHit() }
                    val more = page.hasMore && page.results.isNotEmpty()
                    state.copy(
                        results = merged.take(MAX_RESULTS),
                        loadingMore = false,
                        hasMore = more && merged.size < MAX_RESULTS,
                        truncated = more && merged.size >= MAX_RESULTS,
                    )
                }
            } catch (e: Throwable) {
                // Os resultados já carregados ficam; o usuário decide se tenta de novo.
                val error = e.toUiError()
                _state.update { it.copy(loadingMore = false, error = error) }
            }
        }
    }

    companion object {
        /** Teto local de resultados numa busca; além dele, a tela pede para refinar. */
        const val MAX_RESULTS = 1000
    }
}
