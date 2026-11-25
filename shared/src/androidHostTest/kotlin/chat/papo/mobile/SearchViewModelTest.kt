package chat.papo.mobile

import chat.papo.mobile.chat.AttachmentFilter
import chat.papo.mobile.chat.SearchFilters
import chat.papo.mobile.chat.SearchOrder
import chat.papo.mobile.ui.components.UiError
import chat.papo.mobile.ui.search.SearchUiState
import chat.papo.mobile.ui.search.SearchViewModel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private class Seen(val body: String, val since: String?, val lastId: String?)

    private val seen = CopyOnWriteArrayList<Seen>()

    @Volatile private var total = 3

    @Volatile private var failing = false
    private val slowStarted = CompletableDeferred<Unit>()
    private val slowRelease = CompletableDeferred<Unit>()

    private fun stamp(n: Int) = "2026-01-01T00:%02d:%02d.000001Z".format(n / 60, n % 60)

    /** Servidor com [total] resultados, r<total> o mais novo, 100 por página, cursor como o do backend. */
    private val engine = MockEngine { request ->
        val body = request.body.toByteArray().decodeToString()
        val lastId = request.url.parameters["last_id"]
        seen += Seen(body, request.url.parameters["since"], lastId)
        fun json(text: String, status: HttpStatusCode = HttpStatusCode.OK) =
            respond(text, status, headersOf(HttpHeaders.ContentType, if (status.value >= 400) "application/problem+json" else "application/json"))
        val text = Regex(""""text":"([^"]*)"""").find(body)?.groupValues?.get(1) ?: "sem texto"
        when {
            request.url.encodedPath != "/search" -> respond("", HttpStatusCode.NotFound)
            failing -> json(
                """{"type":"t","title":"Erro interno","status":500,"detail":"falha ao buscar as mensagens"}""",
                HttpStatusCode.InternalServerError,
            )
            else -> {
                if (text == "lenta") {
                    slowStarted.complete(Unit)
                    slowRelease.await()
                }
                val before = lastId?.removePrefix("r")?.toInt() ?: (total + 1)
                val ids = (before - 1 downTo maxOf(1, before - 100)).toList()
                val results = ids.joinToString(",") { n ->
                    """{"type":"message","id":"r$n","content":"$text $n","channel_id":"c1","channel_name":"papo",""" +
                        """"author_id":"u2","author_username":"bia","created_at":"${stamp(n)}"}"""
                }
                json("""{"results":[$results],"has_more":${(ids.lastOrNull() ?: 1) > 1}}""")
            }
        }
    }

    private fun TestScope.viewModel(channelId: String? = null): SearchViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return SearchViewModel(TestResources.session(engine, this), channelId)
    }

    /** O motor HTTP falso responde em outra thread: espera o estado chegar onde o teste precisa. */
    private suspend fun SearchViewModel.searched(): SearchUiState = state.first { it.searched }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun semTextoNemFiltroNaoVaiAoServidor() = runTest {
        val vm = viewModel()
        vm.submit("   ")
        vm.setFilters(SearchFilters(order = SearchOrder.Oldest), "")
        runCurrent()

        assertTrue(seen.isEmpty())
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.searched)
        assertEquals(SearchOrder.Oldest, vm.state.value.filters.order)
    }

    @Test
    fun buscaMostraOsResultadosEGuardaOsFiltrosUsados() = runTest {
        val vm = viewModel()
        vm.submit(" deploy ")
        assertTrue(vm.state.value.loading)
        val first = vm.searched()

        assertEquals(listOf("r3", "r2", "r1"), first.results.map { it.id })
        assertEquals("deploy 3", first.results.first().content)
        assertEquals("papo", first.results.first().channelName)
        assertFalse(first.hasMore)
        assertEquals("""{"text":"deploy","order":"desc"}""", seen.single().body)
        // Só o texto não é um filtro: nada vai para os filtros recentes.
        assertTrue(vm.recentFilters.value.isEmpty())

        // Mudar um filtro refaz a busca na hora, com o texto que está no campo.
        val filters = SearchFilters(authorId = "u2", attachments = AttachmentFilter.With)
        vm.setFilters(filters, "deploy")
        vm.searched()
        assertEquals("""{"text":"deploy","author":"u2","order":"desc","contains_attachment":true}""", seen[1].body)
        assertEquals(listOf(filters), vm.recentFilters.value)

        // Repetir os mesmos filtros não dispara outra busca.
        vm.setFilters(filters, "deploy")
        runCurrent()
        assertEquals(2, seen.size)
    }

    @Test
    fun abrirDeUmaConversaJaFiltraPorEla() = runTest {
        val vm = viewModel(channelId = "c9")
        assertEquals("c9", vm.state.value.filters.channelId)

        vm.submit("")
        vm.searched()
        assertEquals("""{"channel_id":"c9","order":"desc"}""", seen.single().body)

        // Sem o último filtro e sem texto não há busca: a tela volta ao começo.
        vm.setFilters(SearchFilters(), "")
        assertEquals(SearchUiState(), vm.state.value)
        runCurrent()
        assertEquals(1, seen.size)
    }

    @Test
    fun carregarMaisContinuaDoUltimoResultado() = runTest {
        total = 250
        val vm = viewModel()
        vm.submit("log")
        val first = vm.searched()
        assertEquals(100, first.results.size)
        assertTrue(first.hasMore)

        vm.loadMore()
        vm.loadMore() // um segundo toque enquanto carrega não pede a página duas vezes
        val second = vm.state.first { !it.loadingMore && it.results.size == 200 }
        assertTrue(second.hasMore)
        // O cursor é o created_at e o id do último resultado, exatamente como vieram.
        assertEquals(stamp(151), seen[1].since)
        assertEquals("r151", seen[1].lastId)
        assertEquals(seen[0].body, seen[1].body)

        vm.loadMore()
        val third = vm.state.first { !it.loadingMore && it.results.size == 250 }
        assertFalse(third.hasMore)
        assertFalse(third.truncated)
        assertEquals("r1", third.results.last().id)
        assertEquals(250, third.results.map { it.id }.toSet().size)

        vm.loadMore()
        runCurrent()
        assertEquals(3, seen.size)
    }

    @Test
    fun listaParaNoTetoLocal() = runTest {
        total = 1200
        val vm = viewModel()
        vm.submit("log")
        vm.searched()
        repeat(9) { round ->
            vm.loadMore()
            vm.state.first { !it.loadingMore && it.results.size == 100 * (round + 2) }
        }

        val state = vm.state.value
        assertEquals(SearchViewModel.MAX_RESULTS, state.results.size)
        assertTrue(state.truncated)
        assertFalse(state.hasMore)
        vm.loadMore()
        runCurrent()
        assertEquals(10, seen.size)
    }

    @Test
    fun erroFicaNaTelaESoRepeteQuandoOUsuarioPede() = runTest {
        val vm = viewModel()
        failing = true
        vm.submit("deploy")
        val failed = vm.state.first { it.error != null }

        assertEquals(UiError.Server("falha ao buscar as mensagens"), failed.error)
        assertFalse(failed.loading)
        assertFalse(failed.searched)
        runCurrent()
        assertEquals(1, seen.size) // nenhuma repetição automática

        failing = false
        vm.retry()
        val ok = vm.searched()
        assertNull(ok.error)
        assertEquals(3, ok.results.size)
        assertEquals(seen[0].body, seen[1].body)
    }

    @Test
    fun erroAoCarregarMaisMantemOQueJaVeio() = runTest {
        total = 150
        val vm = viewModel()
        vm.submit("log")
        vm.searched()

        failing = true
        vm.loadMore()
        val failed = vm.state.first { !it.loadingMore && it.error != null }
        assertEquals(100, failed.results.size)
        assertTrue(failed.hasMore) // "carregar mais" continua disponível para tentar de novo
        runCurrent()
        assertEquals(2, seen.size)

        failing = false
        vm.loadMore()
        val ok = vm.state.first { !it.loadingMore && it.results.size == 150 }
        assertNull(ok.error)
        // A página que falhou não avançou o cursor: a nova tentativa pede a mesma.
        assertEquals("r51", seen[1].lastId)
        assertEquals("r51", seen[2].lastId)
    }

    @Test
    fun buscaNovaCancelaAAnterior() = runTest {
        val vm = viewModel()
        vm.submit("lenta")
        slowStarted.await()

        vm.submit("rapida")
        val state = vm.searched()
        assertEquals("rapida 3", state.results.first().content)

        // A resposta atrasada da primeira busca não substitui a segunda.
        slowRelease.complete(Unit)
        runCurrent()
        assertEquals("rapida 3", vm.state.value.results.first().content)
        assertNull(vm.state.value.error)
        assertEquals(2, seen.size)
    }
}
