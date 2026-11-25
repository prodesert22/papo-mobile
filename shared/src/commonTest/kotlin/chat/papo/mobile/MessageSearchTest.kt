package chat.papo.mobile

import chat.papo.mobile.chat.AttachmentFilter
import chat.papo.mobile.chat.Message
import chat.papo.mobile.chat.RecentSearchFilters
import chat.papo.mobile.chat.SearchFilters
import chat.papo.mobile.chat.SearchOrder
import chat.papo.mobile.chat.User
import chat.papo.mobile.chat.filterPinned
import chat.papo.mobile.chat.foldForSearch
import chat.papo.mobile.chat.searchByName
import chat.papo.mobile.data.api.ExternalAuthorDto
import chat.papo.mobile.data.api.PapoJson
import chat.papo.mobile.data.api.SearchRequest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageSearchTest {
    private fun user(id: String, username: String, nickname: String? = null) =
        User(id, username, nickname, statusMessage = null, typingPhrase = null, banned = false, roleColor = null, avatarVersion = 0)

    private fun message(id: String, content: String, authorId: String? = null, webhook: String? = null) = Message(
        id = id,
        channelId = "c1",
        authorId = authorId,
        content = content,
        createdAt = null,
        createdAtIso = "",
        edited = false,
        replyTo = null,
        externalAuthor = webhook?.let { ExternalAuthorDto(it) },
        attachments = emptyList(),
        embeds = emptyList(),
        reactions = emptyList(),
        userReactions = emptyList(),
    )

    @Test
    fun dobraTiraAcentosEMaiusculas() {
        assertEquals("joao", foldForSearch("João"))
        assertEquals("aeiou c a o", foldForSearch("ÁÉÍÓÚ Ç Ã Õ"))
        assertEquals("acao e reacao", foldForSearch("AÇÃO e reação"))
        // Acento já decomposto (letra + marca) dá no mesmo.
        assertEquals("cafe", foldForSearch("café"))
        assertEquals("papo_geral-2", foldForSearch("Papo_Geral-2"))
    }

    @Test
    fun buscaPorNomeIgnoraAcentoEOrdenaPorRelevancia() {
        val people = listOf(
            user("1", "ana.joao"),
            user("2", "maria", "Maria João"),
            user("3", "joao"),
            user("4", "joaozinho", "Joãozinho"),
            user("5", "bia", "Bia"),
            user("6", "sub", "Banjoao"),
        )
        val found = searchByName("joão", people) { listOf(it.username, it.displayName) }.map { it.id }

        // Igual, começa com, palavra que começa com, só contém. Bia não casa.
        assertEquals(listOf("3", "4", "1", "2", "6"), found)
        assertEquals(listOf("2"), searchByName("  MARIA JO ", people) { listOf(it.username, it.displayName) }.map { it.id })
        // Consulta vazia não filtra nem reordena.
        assertEquals(people, searchByName("   ", people) { listOf(it.username) })
        assertTrue(searchByName("zé", people) { listOf(it.username, it.displayName) }.isEmpty())
    }

    @Test
    fun filtroDasFixadasOlhaTextoMencaoEAutor() {
        val bia = "0b0e7c1e-1111-2222-3333-444455556666"
        val users = mapOf(bia to user(bia, "bia", "Beatriz Gonçalves"), "u1" to user("u1", "ana"))
        val pinned = listOf(
            message("m1", "Reunião amanhã às 10h", authorId = "u1"),
            message("m2", "falem com @mention(<@$bia>)", authorId = "u1"),
            message("m3", "deploy concluído", webhook = "Robô de Deploy"),
            message("m4", "regras do canal", authorId = bia),
        )

        assertEquals(listOf("m1"), filterPinned(pinned, users, "reuniao").map { it.id })
        // Menção casa pelo nome mostrado; o autor casa pelo nome de exibição ou pelo usuário.
        assertEquals(listOf("m2", "m4"), filterPinned(pinned, users, "goncalves").map { it.id })
        assertEquals(listOf("m4"), filterPinned(pinned, users, "BIA").map { it.id })
        assertEquals(listOf("m3"), filterPinned(pinned, users, "robo").map { it.id })
        assertEquals(pinned, filterPinned(pinned, users, " "))
        assertTrue(filterPinned(pinned, users, "inexistente").isEmpty())
    }

    @Test
    fun semTextoNemFiltroNaoHaRequisicao() {
        assertNull(SearchFilters().toRequest("   "))
        // A ordem sozinha não é um critério para o servidor.
        assertNull(SearchFilters(order = SearchOrder.Oldest).toRequest(""))
        assertFalse(SearchFilters(order = SearchOrder.Oldest).hasCriteria)
        assertTrue(SearchFilters(attachments = AttachmentFilter.Without).hasCriteria)
    }

    @Test
    fun requisicaoLevaSoOsCamposEscolhidos() {
        val onlyText = SearchFilters().toRequest("  erro no deploy ")!!
        assertEquals("""{"text":"erro no deploy","order":"desc"}""", PapoJson.encodeToString(SearchRequest.serializer(), onlyText))

        val full = SearchFilters(
            channelId = "c1",
            authorId = "u2",
            mentionId = "u1",
            hasLink = true,
            attachments = AttachmentFilter.With,
            dateStart = LocalDate(2026, 11, 1),
            dateEnd = LocalDate(2026, 11, 24),
            order = SearchOrder.Oldest,
        ).toRequest("")!!
        assertEquals(
            """{"author":"u2","channel_id":"c1","mention":"u1","has":"link","order":"asc",""" +
                """"date_start":"2026-11-01","date_end":"2026-11-24","contains_attachment":true}""",
            PapoJson.encodeToString(SearchRequest.serializer(), full),
        )

        // "Sem anexos" é um filtro de verdade: vai como false, não some do corpo.
        val without = SearchFilters(attachments = AttachmentFilter.Without).toRequest("")!!
        assertEquals("""{"order":"desc","contains_attachment":false}""", PapoJson.encodeToString(SearchRequest.serializer(), without))
    }

    @Test
    fun datasInvertidasVaoNaOrdemQueOServidorAceita() {
        val request = SearchFilters(dateStart = LocalDate(2026, 12, 5), dateEnd = LocalDate(2026, 3, 9)).toRequest("")!!
        assertEquals("2026-03-09", request.dateStart)
        assertEquals("2026-12-05", request.dateEnd)

        val open = SearchFilters(dateStart = LocalDate(2026, 3, 9)).toRequest("")!!
        assertEquals("2026-03-09", open.dateStart)
        assertNull(open.dateEnd)
    }

    @Test
    fun filtrosRecentesGuardamCincoSemRepetir() {
        val recent = RecentSearchFilters()
        recent.remember(SearchFilters()) // sem critério: não entra
        recent.remember(SearchFilters(order = SearchOrder.Oldest))
        assertTrue(recent.items.value.isEmpty())

        val filters = (1..6).map { SearchFilters(authorId = "u$it") }
        filters.forEach(recent::remember)
        assertEquals(listOf("u6", "u5", "u4", "u3", "u2"), recent.items.value.map { it.authorId })

        // Usar de novo um filtro já guardado só o leva para a frente.
        recent.remember(filters[3])
        assertEquals(listOf("u4", "u6", "u5", "u3", "u2"), recent.items.value.map { it.authorId })
    }
}
