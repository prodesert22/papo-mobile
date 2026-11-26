package chat.papo.mobile

import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.chat.EmojiCatalog
import chat.papo.mobile.data.api.ChatApi
import chat.papo.mobile.data.api.MemoryCookieStore
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.data.db.account.AccountDb
import chat.papo.mobile.data.db.account.MetaQueries
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** O motor HTTP falso responde em outra thread: cada teste espera por um estado, nunca por tempo. */
class EmojiCatalogTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun close() = scope.cancel()

    private val requests = CopyOnWriteArrayList<String>()

    /**
     * Servidor com [total] emojis (e1, e2, ...), [perPage] por página, no formato do backend.
     * A página [failAtPage] responde [failStatus] nas primeiras [failTimes] vezes.
     */
    private fun api(
        total: Int,
        blob: (Int) -> String? = { "AQID" },
        failAtPage: Int? = null,
        failStatus: HttpStatusCode = HttpStatusCode.InternalServerError,
        failTimes: Int = Int.MAX_VALUE,
        latency: Duration = Duration.ZERO,
        perPage: Int = 1,
    ): ChatApi {
        val failures = java.util.concurrent.atomic.AtomicInteger(0)
        val engine = MockEngine { request ->
            requests += request.url.encodedQuery
            if (latency > Duration.ZERO) delay(latency)
            val lastId = request.url.parameters["last_id"]
            val page = (lastId?.removePrefix("e")?.toInt() ?: 0) / perPage + 1
            if (page == failAtPage && failures.getAndIncrement() < failTimes) {
                respond("""{"title":"recusado"}""", failStatus, headersOf(HttpHeaders.ContentType, "application/problem+json"))
            } else {
                val first = (page - 1) * perPage + 1
                val last = minOf(page * perPage, total)
                val items = (first..last).joinToString(",") { n ->
                    val image = blob(n)?.let { ""","image_blob":"$it"""" }.orEmpty()
                    """{"id":"e$n","name":"n${total - n}","format":"PNG","created_at":"2026-01-01T00:%02d:%02dZ"$image}""".format(n / 60, n % 60)
                }
                respond(
                    """{"emojis":[$items],"has_more":${last < total}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        return ChatApi(PapoClient(engine, ServerUrl("https://papo.test"), MemoryCookieStore("jwt")))
    }

    private fun catalog(
        api: ChatApi,
        delay: Duration = Duration.ZERO,
        meta: MetaQueries = AccountDb(TestResources.driver()).metaQueries,
    ): Pair<EmojiCatalog, chat.papo.mobile.media.MediaCache> {
        val resources = TestResources.inMemory(Dispatchers.IO)
        // A espera depois de um 429 fica curta no teste; a regra (esperar e repetir a mesma página) é a mesma.
        val catalog = EmojiCatalog(api, resources.media, meta, scope, Dispatchers.IO, pageInterval = delay, rateLimitBackoff = 20.milliseconds)
        return catalog to resources.media
    }

    private val cached = """[{"id":"old1","name":"zeta","format":"PNG"},{"id":"old2","name":"alfa"}]"""

    private suspend fun EmojiCatalog.await(until: (chat.papo.mobile.chat.ServerEmojis) -> Boolean) =
        withTimeout(10.seconds) { state.first(until) }

    @Test
    fun carregaTodasAsPaginasAlemDeOito() = runBlocking {
        val (catalog, _) = catalog(api(total = 20))
        catalog.start()
        val done = catalog.await { it.complete }

        assertEquals(20, done.list.size)
        assertEquals(20, requests.size)
        assertFalse(done.loading)
        // Cada página continua de onde a anterior parou.
        assertEquals("order=asc&since=2026-01-01T00%3A00%3A01Z&last_id=e1", requests[1])
        // A lista fica em ordem alfabética de nome, não na da carga.
        assertEquals(done.list.map { it.name }.sorted(), done.list.map { it.name })
        assertEquals("e20", done.idByName["n0"])
    }

    @Test
    fun comecaUmaVezSo() = runBlocking {
        val (catalog, _) = catalog(api(total = 3))
        catalog.start()
        catalog.start()
        catalog.await { it.complete }
        catalog.start() // lista recente: não busca de novo
        assertEquals(3, requests.size)
    }

    @Test
    fun chamadasEmParaleloDisparamUmaCargaSo() = runBlocking {
        val (catalog, _) = catalog(api(total = 3), delay = 50.milliseconds)
        val callers = (1..16).map { scope.launch(Dispatchers.Default) { catalog.start() } }
        callers.forEach { it.join() }
        catalog.await { it.complete }
        assertEquals(3, requests.size) // uma carga, três páginas: nada de buscas duplicadas
    }

    @Test
    fun espacaAsPaginasParaNaoEstourarOLimiteDeTaxa() = runBlocking {
        val (catalog, _) = catalog(api(total = 3), delay = 200.milliseconds)
        val started = TimeSource.Monotonic.markNow()
        catalog.start()
        catalog.await { it.complete }
        // Três páginas, duas esperas: o mínimo é 400 ms. Mais devagar não reprova.
        assertTrue(started.elapsedNow() >= 400.milliseconds, "terminou em ${started.elapsedNow()}")
    }

    @Test
    fun aListaAparecePouCoAPoucoNoMaximoUmaVezPorSegundo() = runBlocking {
        val (catalog, _) = catalog(api(total = 6), delay = 400.milliseconds)
        val partials = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val watcher = scope.launch { catalog.state.collect { if (it.loading && !it.complete) partials += it.list.size } }
        catalog.start()
        val partial = catalog.await { it.list.isNotEmpty() && !it.complete }
        assertTrue(partial.loading)
        assertTrue(partial.list.size < 6)
        catalog.await { it.complete }
        watcher.cancel()
        // Seis páginas a cada 400 ms levam uns 2 s: no máximo três listas parciais, não uma por página.
        assertTrue(partials.distinct().size <= 3, "parciais demais: $partials")
    }

    @Test
    fun paginasCheiasGuardamTodasAsImagensMesmoEmParalelo() = runBlocking {
        val (catalog, media) = catalog(api(total = 80, perPage = 25))
        catalog.start()
        val done = catalog.await { it.complete }
        assertEquals(80, done.list.size)
        assertEquals(4, requests.size)
        for (n in 1..80) assertContentEquals(byteArrayOf(1, 2, 3), media.peek(EmojiCatalog.mediaKey("e$n")), "e$n")
    }

    @Test
    fun limiteDeTaxaEsperaERepeteAMesmaPagina() = runBlocking {
        val (catalog, _) = catalog(api(total = 3, failAtPage = 2, failStatus = HttpStatusCode.TooManyRequests, failTimes = 2))
        catalog.start()
        val done = catalog.await { !it.loading }
        assertTrue(done.complete && !done.failed)
        assertEquals(3, done.list.size)
        // página 1, duas recusas e o acerto da página 2, página 3
        assertEquals(5, requests.size)
        assertEquals(3, requests.count { "last_id=e1" in it })
    }

    @Test
    fun limiteDeTaxaQueNaoPassaDesisteDepoisDeAlgumasTentativas() = runBlocking {
        val (catalog, _) = catalog(api(total = 3, failAtPage = 2, failStatus = HttpStatusCode.TooManyRequests))
        catalog.start()
        val failed = catalog.await { it.failed }
        assertEquals(4, requests.count { "last_id=e1" in it }) // a primeira e três repetições
        assertEquals(1, failed.list.size) // a página 1 já tinha chegado
        assertFalse(failed.loading)
    }

    @Test
    fun aImagemVaiParaOCacheCifradoENaoFicaNaMemoria() = runBlocking {
        val (catalog, media) = catalog(api(total = 2))
        catalog.start()
        catalog.await { it.complete }

        assertContentEquals(byteArrayOf(1, 2, 3), media.peek(EmojiCatalog.mediaKey("e1")))
        assertContentEquals(byteArrayOf(1, 2, 3), catalog.imageBytes("e2"))
        assertNull(catalog.imageBytes("inexistente"))
    }

    @Test
    fun imagemIlegivelOuAusenteFicaSemImagemMasOEmojiExiste() = runBlocking {
        val (catalog, _) = catalog(api(total = 2, blob = { if (it == 1) "não é base64!" else null }))
        catalog.start()
        val done = catalog.await { it.complete }

        assertEquals(2, done.list.size)
        assertNull(catalog.imageBytes("e1"))
        assertNull(catalog.imageBytes("e2"))
    }

    @Test
    fun imageBytesEsperaACargaChegarNoEmoji() = runBlocking {
        val (catalog, _) = catalog(api(total = 3), delay = 100.milliseconds)
        // Ninguém chamou start(): imageBytes dispara a carga e espera o emoji da última página.
        assertContentEquals(byteArrayOf(1, 2, 3), withTimeout(10.seconds) { catalog.imageBytes("e3") })
    }

    @Test
    fun falhaNoMeioMantemOQueChegouENaoTentaDeNovoSozinha() = runBlocking {
        val (catalog, _) = catalog(api(total = 5, failAtPage = 3))
        catalog.start()
        val failed = catalog.await { it.failed }

        assertEquals(2, failed.list.size) // as imagens das duas páginas boas terminaram de ser guardadas
        assertFalse(failed.loading)
        assertFalse(failed.complete)
        val before = requests.size
        catalog.start() // ainda dentro da espera depois de uma falha
        assertEquals(before, requests.size)
        assertEquals(3, requests.size) // 2 páginas boas + a que falhou: erro que não é 429 não se repete
    }

    /** Espera a carga em andamento acabar de vez: o estado final sai um instante antes de o trabalho dela terminar. */
    private suspend fun idle() = scope.coroutineContext.job.children.toList().forEach { it.join() }

    @Test
    fun forcarRepeteDepoisDeUmaFalha() = runBlocking {
        val (catalog, _) = catalog(api(total = 5, failAtPage = 3))
        catalog.start()
        catalog.await { it.failed }
        // Com a falha à vista e a carga ainda terminando, start() a vê em andamento e não faz nada.
        idle()
        catalog.start(force = true)
        catalog.await { it.failed && requests.size >= 6 }
        assertTrue(requests.size >= 6)
    }

    @Test
    fun aListaGuardadaApareceAntesDaRedeEInteira() = runBlocking {
        val meta = AccountDb(TestResources.driver()).metaQueries
        meta.put(EmojiCatalog.CACHE_KEY, cached)
        val (catalog, _) = catalog(api(total = 3, latency = 300.milliseconds), meta = meta)
        catalog.start()

        val early = catalog.await { it.list.isNotEmpty() }
        assertTrue(early.complete, "a lista guardada é uma foto inteira")
        assertTrue(early.loading, "e o servidor ainda está sendo lido")
        assertEquals(listOf("alfa", "zeta"), early.list.map { it.name })
        assertEquals(0, requests.count { "last_id" in it }) // nem a segunda página tinha chegado

        val done = catalog.await { !it.loading }
        assertEquals(setOf("e1", "e2", "e3"), done.byId.keys)
    }

    @Test
    fun releituraNuncaMostraUmaListaPelaMetade() = runBlocking {
        val meta = AccountDb(TestResources.driver()).metaQueries
        meta.put(EmojiCatalog.CACHE_KEY, cached)
        val (catalog, _) = catalog(api(total = 4), delay = 50.milliseconds, meta = meta)
        val seen = java.util.concurrent.CopyOnWriteArrayList<Set<String>>()
        val watcher = scope.launch { catalog.state.collect { seen += it.byId.keys } }
        catalog.start()
        catalog.await { !it.loading && it.byId.keys == setOf("e1", "e2", "e3", "e4") }
        watcher.cancel()
        // Só três fotos possíveis: vazia (antes de ler o cache), a guardada e a nova completa.
        val allowed = setOf(emptySet(), setOf("old1", "old2"), setOf("e1", "e2", "e3", "e4"))
        assertTrue(seen.all { it in allowed }, "apareceu lista parcial: $seen")
    }

    @Test
    fun aListaCompletaEGuardadaParaAProximaVez() = runBlocking {
        val meta = AccountDb(TestResources.driver()).metaQueries
        val (catalog, _) = catalog(api(total = 2), meta = meta)
        catalog.start()
        catalog.await { it.complete && !it.loading }
        withTimeout(10.seconds) { while (meta.get(EmojiCatalog.CACHE_KEY).executeAsOneOrNull() == null) delay(10) }
        val stored = meta.get(EmojiCatalog.CACHE_KEY).executeAsOne()
        assertTrue("\"e1\"" in stored && "\"e2\"" in stored, stored)
    }

    @Test
    fun cacheIlegivelEIgnorado() = runBlocking {
        val meta = AccountDb(TestResources.driver()).metaQueries
        meta.put(EmojiCatalog.CACHE_KEY, "isso não é json")
        val (catalog, _) = catalog(api(total = 2), meta = meta)
        catalog.start()
        val done = catalog.await { it.complete && !it.loading }
        assertEquals(setOf("e1", "e2"), done.byId.keys)
    }

    @Test
    fun falhaNaReleituraMantemAListaGuardada() = runBlocking {
        val meta = AccountDb(TestResources.driver()).metaQueries
        meta.put(EmojiCatalog.CACHE_KEY, cached)
        val (catalog, _) = catalog(api(total = 3, failAtPage = 2), meta = meta)
        catalog.start()
        val failed = catalog.await { it.failed }
        assertTrue(failed.complete)
        assertEquals(setOf("old1", "old2"), failed.byId.keys)
    }
}
