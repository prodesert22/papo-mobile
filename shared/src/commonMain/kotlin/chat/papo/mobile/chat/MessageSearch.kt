package chat.papo.mobile.chat

import chat.papo.mobile.core.parseInstant
import chat.papo.mobile.data.api.ExternalAuthorDto
import chat.papo.mobile.data.api.SearchRequest
import chat.papo.mobile.data.api.SearchResultDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/** O servidor distingue mensagens com anexo, sem anexo e tanto faz. */
enum class AttachmentFilter { Any, With, Without }

enum class SearchOrder(val wire: String) { Newest("desc"), Oldest("asc") }

/**
 * Filtros de uma busca de mensagens. O texto fica de fora: ele não entra nos
 * filtros recentes. As datas são dias inteiros, com os dois extremos incluídos,
 * e o servidor conta esses dias em UTC, não no fuso do aparelho.
 */
data class SearchFilters(
    val channelId: String? = null,
    val authorId: String? = null,
    val mentionId: String? = null,
    val hasLink: Boolean = false,
    val attachments: AttachmentFilter = AttachmentFilter.Any,
    val dateStart: LocalDate? = null,
    val dateEnd: LocalDate? = null,
    val order: SearchOrder = SearchOrder.Newest,
) {
    /** Há algum critério além da ordem? Sozinha, a ordem não é um filtro para o servidor. */
    val hasCriteria: Boolean
        get() = channelId != null || authorId != null || mentionId != null || hasLink ||
            attachments != AttachmentFilter.Any || dateStart != null || dateEnd != null

    /** Corpo de `POST /search`, ou null quando não há texto nem filtro para mandar. */
    fun toRequest(text: String): SearchRequest? {
        val term = text.trim()
        if (term.isEmpty() && !hasCriteria) return null
        // O servidor recusa data inicial depois da final.
        val reversed = dateStart != null && dateEnd != null && dateStart > dateEnd
        return SearchRequest(
            text = term.ifEmpty { null },
            author = authorId,
            channelId = channelId,
            mention = mentionId,
            has = if (hasLink) "link" else null,
            order = order.wire,
            dateStart = (if (reversed) dateEnd else dateStart)?.toString(),
            dateEnd = (if (reversed) dateStart else dateEnd)?.toString(),
            containsAttachment = when (attachments) {
                AttachmentFilter.Any -> null
                AttachmentFilter.With -> true
                AttachmentFilter.Without -> false
            },
        )
    }
}

/** Um resultado de busca pronto para a tela. */
data class SearchHit(
    val id: String,
    val channelId: String,
    val channelName: String,
    val authorId: String?,
    val authorUsername: String?,
    val externalAuthor: ExternalAuthorDto?,
    val content: String,
    val createdAt: Instant?,
    val createdAtIso: String,
)

fun SearchResultDto.toHit() = SearchHit(
    id = id,
    channelId = channelId,
    channelName = channelName.orEmpty(),
    authorId = authorId,
    authorUsername = authorUsername,
    externalAuthor = externalAuthor,
    content = content.orEmpty(),
    createdAt = parseInstant(createdAt),
    createdAtIso = createdAt,
)

/**
 * Filtros das últimas buscas de uma conta, do mais novo para o mais antigo.
 * Ficam só na memória da sessão: somem quando o app bloqueia ou a conta sai, e
 * nunca vão para o disco.
 */
class RecentSearchFilters(private val capacity: Int = 5) {
    private val _items = MutableStateFlow<List<SearchFilters>>(emptyList())
    val items: StateFlow<List<SearchFilters>> = _items.asStateFlow()

    fun remember(filters: SearchFilters) {
        if (!filters.hasCriteria) return
        _items.update { (listOf(filters) + (it - filters)).take(capacity) }
    }
}
