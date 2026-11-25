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
        webhookId = webhook?.let { "wh-$it" },
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

    private fun byName(query: String, people: List<User>) =
        searchByName(query, people) { listOf(it.username, it.displayName) }.map { it.id }

    @Test
    fun consultaAtravessaOEspacoDoNomeDeExibicao() {
        val people = listOf(user("a", "mestre7", "Lu Moraes"), user("b", "bia", "Bia"))
        assertEquals(listOf("a"), byName("lum", people))
        assertEquals(listOf("a"), byName("LUMOR", people))
        assertEquals(listOf("a"), byName("umor", people)) // do meio de uma palavra até a outra
        assertEquals(listOf("a"), byName("lu", people)) // o casamento de antes continua valendo
    }

    @Test
    fun consultaAtravessaUnderscorePontoEHifenDoUsername() {
        val people = listOf(user("1", "lu_moraes"), user("2", "lu.moraes"), user("3", "lu-moraes"), user("4", "bia"))
        assertEquals(listOf("1", "2", "3"), byName("lum", people))
        assertEquals(listOf("1", "2", "3"), byName("umor", people))
    }

    @Test
    fun consultaComQuebraCasaDoMesmoJeitoQueSemQuebra() {
        val people = listOf(user("a", "mestre7", "Lu Moraes"), user("b", "lumoraes2"), user("c", "bia"))
        assertEquals(listOf("a", "b"), byName("lu m", people)) // espaço: a começa com "lu m"; b só sem a quebra de a
        assertEquals(listOf("a", "b"), byName("lu_m", people))
        assertEquals(listOf("a", "b"), byName("lu.m", people))
        assertEquals(listOf("a", "b"), byName("lu-m", people))
        assertEquals(listOf("a", "b"), byName("l u m", people))
    }

    @Test
    fun casamentoAtravessandoQuebrasVemDepoisDeContem() {
        val people = listOf(
            user("quebra", "x1", "Lu Moraes"),
            user("contem", "alumno"),
            user("palavra", "x2", "Ana Lum"),
            user("comeca", "lumen"),
            user("igual", "lum"),
        )
        assertEquals(listOf("igual", "comeca", "palavra", "contem", "quebra"), byName("lum", people))
    }

    @Test
    fun dentroDoMesmoGrupoVaiAOrdemDeEntrada() {
        val people = listOf(user("2", "x2", "Lu Moraes"), user("1", "x1", "Lu Moura"), user("3", "x3", "Lu Mota"))
        assertEquals(listOf("2", "1", "3"), byName("lum", people))
        assertEquals(listOf("3", "1", "2"), byName("lum", people.reversed()))
    }

    @Test
    fun semAsQuebrasAsLetrasPrecisamEstarJuntasESeguidas() {
        val people = listOf(user("a", "mestre7", "Lu Moraes"))
        assertTrue(byName("lmo", people).isEmpty()) // "lumoraes" não tem l-m-o em sequência
        assertTrue(byName("l m", people).isEmpty())
        assertTrue(byName("moralu", people).isEmpty()) // fora da ordem
        assertTrue(byName("lumoraesx", people).isEmpty()) // maior que o nome
    }

    @Test
    fun consultaSoDeQuebrasSoCasaQuemTemEssaQuebra() {
        val people = listOf(user("a", "ana-bia"), user("b", "mestre7", "Lu Moraes"), user("c", "lu_moraes"), user("d", "cantorvivo"))
        assertEquals(listOf("a"), byName("-", people))
        assertEquals(listOf("c"), byName(" _ ", people))
        // Sem as quebras a consulta ficaria vazia e acharia todo mundo.
        assertTrue(byName(".", people).isEmpty())
        assertTrue(byName("- _", people).isEmpty())
    }

    @Test
    fun itemSemNomeNaoCasaComConsultaNenhuma() {
        assertTrue(searchByName("lu", listOf(1, 2)) { emptyList() }.isEmpty())
        assertEquals(listOf(1, 2), searchByName("", listOf(1, 2)) { emptyList() })
    }

    @Test
    fun pedacoDoMeioDoUsernameCasa() {
        val people = listOf(user("a", "cantorvivo"), user("b", "cantor_vivo"), user("c", "bia"))
        assertEquals(listOf("a", "b"), byName("torvi", people)) // contido, e contido atravessando o `_`
        assertEquals(listOf("a", "b"), byName("TORVI ", people))
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
