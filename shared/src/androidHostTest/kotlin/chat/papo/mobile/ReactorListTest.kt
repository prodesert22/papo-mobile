package chat.papo.mobile

import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.chat.ReactorList
import chat.papo.mobile.chat.ReactorsState
import chat.papo.mobile.core.parseInstant
import chat.papo.mobile.data.api.ApiException
import chat.papo.mobile.data.api.ChatApi
import chat.papo.mobile.data.api.MemoryCookieStore
import chat.papo.mobile.data.api.PapoClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A leitura paginada de quem reagiu, contra um servidor falso que pagina como o backend (cursor `since` + `last_id`,
 * da reação mais nova à mais antiga, grupos na ordem da primeira aparição na página). O motor responde em outra
 * thread: os testes esperam o estado, não o tempo virtual.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReactorListTest {
    private val fire = "🔥"
    private val heart = "❤"
    private val thumbs = "👍"

    /** A reação `n` de [emoji], feita `n` segundos depois de 12:00; o id cresce com `n`, como o horário. */
    private class Row(val n: Int, val emoji: String) {
        val id = "r%03d".format(n)
        val userId = "u-%03d".format(n)
        val createdAt = "2026-11-24T12:%02d:%02dZ".format(n / 60, n % 60)
    }

    /** O que o servidor tem. Cada teste monta o seu, com o emoji de cada reação. */
    private var rows = emptyList<Row>()
    private var pageSize = 3

    /** O cursor de cada pedido, na ordem em que chegaram: (`since`, `last_id`). */
    private val requests = CopyOnWriteArrayList<Pair<String?, String?>>()
    private val inFlight = AtomicInteger()
    private val mostInFlight = AtomicInteger()

    @Volatile private var failWith: HttpStatusCode? = null

    /** Quando não nulo, cada pedido espera por ele antes de responder; [arrived] avisa de cada pedido que chegou. */
    @Volatile private var hold: CompletableDeferred<Unit>? = null
    private val arrived = Channel<Unit>(Channel.UNLIMITED)

    /** Quando não nulo, é a resposta de qualquer pedido. */
    @Volatile private var canned: String? = null

    private val engine = MockEngine { request ->
        requests += request.url.parameters["since"] to request.url.parameters["last_id"]
        mostInFlight.accumulateAndGet(inFlight.incrementAndGet()) { a, b -> maxOf(a, b) }
        try {
            arrived.trySend(Unit)
            hold?.await()
            val status = failWith
            when {
                status != null -> respond(
                    """{"type":"t","title":"Acesso negado","status":${status.value},"detail":"usuário não tem permissão para ler o canal"}""",
                    status,
                    headersOf(HttpHeaders.ContentType, "application/problem+json"),
                )
                else -> respond(canned ?: page(request.url.parameters), HttpStatusCode.OK, json)
            }
        } finally {
            inFlight.decrementAndGet()
        }
    }

    private val json: Headers = headersOf(HttpHeaders.ContentType, "application/json")

    private val client = PapoClient(engine, ServerUrl("https://papo.test"), MemoryCookieStore("jwt"))

    @AfterTest
    fun closeClient() = client.close()

    private fun older(row: Row, since: String, lastId: String): Boolean {
        val rowAt = parseInstant(row.createdAt)!!
        val sinceAt = parseInstant(since)!!
        return rowAt < sinceAt || (rowAt == sinceAt && row.id < lastId)
    }

    private fun page(query: Parameters): String {
        val since = query["since"]
        val lastId = query["last_id"]
        val newestFirst = rows.sortedByDescending { it.n }
        val rest = if (since != null && lastId != null) newestFirst.filter { older(it, since, lastId) } else newestFirst
        val groups = rest.take(pageSize).groupBy { it.emoji }.entries.joinToString(",") { (emoji, users) ->
            val list = users.joinToString(",") { """{"id":"${it.id}","user_id":"${it.userId}","created_at":"${it.createdAt}"}""" }
            """{"emoji_id":null,"unicode":"$emoji","count":${users.size},"users":[$list]}"""
        }
        return """{"message_id":"m1","reactions":[$groups],"has_more":${rest.size > pageSize}}"""
    }

    /** [count] reações, da mais antiga (1) à mais nova, com o emoji de cada uma dado por [emojiOf]. */
    private fun serverWith(count: Int, emojiOf: (Int) -> String) {
        rows = (1..count).map { Row(it, emojiOf(it)) }
    }

    private fun TestScope.list(scope: CoroutineScope = backgroundScope) = ReactorList(ChatApi(client), "c1", "m1", scope)

    private suspend fun ReactorList.idle(): ReactorsState = state.first { !it.loading }

    private fun ReactorsState.ids(emoji: String) = groups.single { it.unicode == emoji }.users.map { it.id }

    // Ler tudo

    @Test
    fun duasPaginasJuntamOGrupoPartidoESemRepetirNinguem() = runTest {
        pageSize = 3
        serverWith(7) { if (it % 2 == 0) heart else fire } // 7 reações em 3 páginas, os dois emojis intercalados
        val list = list()

        list.reveal(null, fire)
        list.idle()
        list.loadMore()
        list.idle()
        list.loadMore()
        val done = list.idle()

        assertEquals(listOf("r007", "r005", "r003", "r001"), done.ids(fire))
        assertEquals(listOf("r006", "r004", "r002"), done.ids(heart))
        assertEquals(listOf(4, 3), done.groups.map { it.count })
        assertTrue(!done.hasMore && !done.loading && done.error == null)
        // Cada pedido leva o cursor da reação mais antiga da página anterior, como o servidor a mandou.
        assertEquals(listOf<Pair<String?, String?>>(null to null, "2026-11-24T12:00:05Z" to "r005", "2026-11-24T12:00:02Z" to "r002"), requests.toList())
    }

    @Test
    fun oCursorVoltaComOTextoDoServidorSemZerosAMais() = runTest {
        // O servidor corta os zeros finais da fração (RFC 3339 Nano); o cursor não pode reescrevê-la.
        canned = """{"message_id":"m1","has_more":true,"reactions":[
            {"unicode":"🔥","count":2,"users":[{"id":"r2","user_id":"u2","created_at":"2026-11-24T12:00:01.5Z"},
                                               {"id":"r1","user_id":"u1","created_at":"2026-11-24T12:00:01.12Z"}]}]}"""
        val list = list()

        list.reveal(null, thumbs)
        list.idle()

        assertEquals("2026-11-24T12:00:01.12Z" to "r1", requests[1])
    }

    @Test
    fun oEmojiJaLidoNaoPedeNada() = runTest {
        serverWith(4) { if (it > 2) fire else heart }
        val list = list()
        list.reveal(null, fire)
        list.idle()
        val before = requests.size

        list.reveal(null, fire)

        assertEquals(before, requests.size)
        assertTrue(!list.state.value.loading)
    }

    @Test
    fun semMaisPaginasNadaMaisSePede() = runTest {
        serverWith(2) { fire }
        val list = list()
        list.reveal(null, fire)
        list.idle()
        assertTrue(!list.state.value.hasMore)

        list.loadMore()
        list.reveal(null, heart)
        list.idle()

        assertEquals(1, requests.size)
    }

    // Procurar o emoji escolhido

    @Test
    fun aAberturaLeNoMaximoCincoPaginasAtrasDoEmojiEDepoisEsperaAPessoa() = runTest {
        pageSize = 1
        serverWith(9) { if (it == 1) fire else heart } // o único 🔥 é o mais antigo: está na nona página
        val list = list()

        list.reveal(null, fire)
        val opened = list.idle()

        assertEquals(5, requests.size)
        assertTrue(opened.hasMore && opened.error == null && opened.groups.none { it.unicode == fire })
        assertEquals(5, opened.groups.single().users.size)

        list.loadMore() // por rolagem ou botão: uma página só
        assertEquals(6, list.idle().groups.single().users.size)
        assertEquals(6, requests.size)

        list.reveal(null, fire) // pedir de novo é uma ação nova: mais cinco páginas, ou até achar
        val found = list.idle()
        assertEquals(9, requests.size)
        assertEquals(listOf("r001"), found.ids(fire))
        assertTrue(!found.hasMore)
    }

    @Test
    fun lerMaisSemNenhumEmojiPedidoLeUmaPaginaSo() = runTest {
        pageSize = 1
        serverWith(4) { if (it == 1) fire else heart }
        val list = list()

        list.loadMore() // por rolagem ou botão, sem `reveal`: nada a procurar, então não segue sozinha
        val done = list.idle()

        assertEquals(1, requests.size)
        assertEquals(listOf("r004"), done.ids(heart))
        assertTrue(done.hasMore)
    }

    @Test
    fun trocarDeEmojiComUmaLeituraEmVooContinuaAteAcharONovo() = runTest {
        pageSize = 1
        serverWith(4) { if (it == 1) fire else heart }
        val list = list()
        hold = CompletableDeferred()

        list.loadMore() // por rolagem: só a primeira página
        arrived.receive()
        list.reveal(null, fire) // a pessoa troca de emoji enquanto ela voa
        hold?.complete(Unit)
        val done = list.idle()

        assertEquals(listOf("r001"), done.ids(fire)) // seguiu pelas páginas até o 🔥
        assertEquals(4, requests.size)
        assertEquals(1, mostInFlight.get())
    }

    // Um pedido por vez

    @Test
    fun enquantoUmPedidoVoaOsOutrosNaoSaem() = runTest {
        serverWith(8) { if (it % 2 == 0) heart else fire }
        val list = list()
        hold = CompletableDeferred()

        list.loadMore()
        arrived.receive()
        repeat(3) { list.loadMore() }
        list.reveal(null, fire)
        assertEquals(1, requests.size)
        assertTrue(list.state.value.loading)
        hold?.complete(Unit)
        list.idle()

        assertEquals(1, requests.size) // o 🔥 já veio na primeira página: nada mais a ler
        assertEquals(1, mostInFlight.get())
    }

    // Falha e cancelamento

    @Test
    fun falhaDeixaOQueJaFoiLidoEOferecePedirDeNovoSemRepetirSozinha() = runTest {
        serverWith(7) { if (it % 2 == 0) heart else fire }
        val list = list()
        list.reveal(null, fire)
        list.idle()
        val firstPage = list.state.value.groups

        failWith = HttpStatusCode.Forbidden
        list.loadMore()
        val failed = list.state.first { it.error != null && !it.loading }

        val error = assertIs<ApiException>(failed.error)
        assertTrue(error.isForbidden)
        assertEquals(firstPage, failed.groups) // o que já estava lido continua ali
        assertTrue(failed.hasMore)
        assertEquals(2, requests.size) // e nada foi pedido de novo por conta própria

        failWith = null
        list.loadMore() // "Tentar de novo": o mesmo cursor
        val retried = list.idle()

        assertNull(retried.error)
        assertEquals(requests[1], requests[2])
        assertEquals(listOf("r007", "r005", "r003"), retried.ids(fire))
        assertEquals(listOf("r006", "r004", "r002"), retried.ids(heart))
    }

    @Test
    fun falhaNaPrimeiraPaginaNaoDeixaNadaELeMaisPedeDeNovoDoInicio() = runTest {
        serverWith(2) { fire }
        val list = list()
        failWith = HttpStatusCode.InternalServerError

        list.reveal(null, fire)
        val failed = list.state.first { it.error != null && !it.loading }

        assertEquals(emptyList(), failed.groups)
        assertTrue(failed.hasMore)

        failWith = null
        list.loadMore()
        assertEquals(listOf("r002", "r001"), list.idle().ids(fire))
        assertEquals(listOf<Pair<String?, String?>>(null to null, null to null), requests.toList())
    }

    @Test
    fun cancelarComOPedidoEmVooNaoMudaOQueFoiLidoNemViraErro() = runTest {
        serverWith(5) { fire }
        val owner = SupervisorJob()
        val list = list(CoroutineScope(StandardTestDispatcher(testScheduler) + owner))
        list.reveal(null, fire) // pageSize 3: a primeira página chega e ainda há mais
        arrived.receive()
        list.idle()
        val before = list.state.value
        hold = CompletableDeferred()

        list.loadMore()
        arrived.receive()
        owner.cancelAndJoin()
        hold?.complete(Unit)

        val after = list.idle()
        assertEquals(before.groups, after.groups)
        assertTrue(after.hasMore)
        assertNull(after.error)
        assertEquals(2, requests.size)
    }

    // Servidor que não ajuda

    @Test
    fun paginaVaziaComHasMoreEncerraSemLacar() = runTest {
        canned = """{"message_id":"m1","reactions":[],"has_more":true}"""
        val list = list()

        list.reveal(null, fire)
        val done = list.idle()

        assertTrue(!done.hasMore)
        assertNull(done.error)
        assertEquals(1, requests.size)
    }

    @Test
    fun cursorQueNaoAndaEncerraEAPaginaRepetidaNaoDuplicaNinguem() = runTest {
        canned = """{"message_id":"m1","has_more":true,"reactions":[
            {"unicode":"❤","count":1,"users":[{"id":"r1","user_id":"u1","created_at":"2026-11-24T12:00:01Z"}]}]}"""
        val list = list()

        list.reveal(null, fire) // o 🔥 nunca vem: o laço da abertura só para porque o cursor não andou
        val done = list.idle()

        assertTrue(!done.hasMore)
        assertEquals(2, requests.size)
        assertEquals(listOf("r1"), done.ids(heart))
    }
}
