package chat.papo.mobile

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import chat.papo.mobile.account.AccountResources
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.chat.ChatStore
import chat.papo.mobile.data.api.ApiException
import chat.papo.mobile.data.api.MemoryCookieStore
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.data.ws.WsEvent
import chat.papo.mobile.media.MediaCache
import chat.papo.mobile.platform.JcaCrypto
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reagir e remover a própria reação: a resposta do servidor e o evento `react_update` chegam em qualquer ordem,
 * e o que a tela mostra (contagem e "eu reagi") tem de acabar certo em todas elas.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatStoreReactionsTest {
    private val requests = CopyOnWriteArrayList<String>()

    // As respostas de reação esperam aqui quando o teste as segura; a chave é o emoji do corpo do pedido.
    private val held = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val arrived = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    @Volatile
    private var failWith: HttpStatusCode? = null

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        if (!path.endsWith("/reactions")) return@MockEngine respond("", HttpStatusCode.NoContent)
        val body = request.body.toByteArray().decodeToString()
        requests += "${request.method.value} $path $body"
        val key = emojiOf(body)
        arrived[key]?.complete(Unit)
        held[key]?.await()
        val status = failWith
        when {
            status != null -> respond(
                """{"title":"Erro","status":${status.value}}""",
                status,
                headersOf(HttpHeaders.ContentType, "application/problem+json"),
            )
            request.method == HttpMethod.Post -> respond("", HttpStatusCode.Created)
            else -> respond("", HttpStatusCode.NoContent)
        }
    }

    private fun emojiOf(body: String) = Regex(""""(?:unicode|emoji_id)":"([^"]+)"""").find(body)?.groupValues?.get(1) ?: body

    /** O que a tela mostra de uma reação: quantos reagiram e se eu estou entre eles. */
    private data class Shown(val count: Int, val mine: Boolean)

    private suspend fun ChatStore.shown(unicode: String? = "👍", emojiId: String? = null): Shown {
        val message = assertNotNull(storedMessage("c1", "m1"), "m1 deveria estar guardada")
        return Shown(
            message.reactions.firstOrNull { it.unicode == unicode && it.emojiId == emojiId }?.count ?: 0,
            message.userReactions.any { it.unicode == unicode && it.emojiId == emojiId },
        )
    }

    /** A mensagem m1 de outra pessoa; [count] reações 👍 vindas do servidor, e a minha entre elas quando [mine]. */
    private suspend fun ChatStore.seed(count: Int = 0, mine: Boolean = false) {
        handle(WsEvent.MessageCreated("m1", "c1", "u2", content = "oi", createdAt = "2026-01-01T00:00:01Z"))
        if (mine) toggleReaction(assertNotNull(storedMessage("c1", "m1")), null, "👍")
        if (count > 0) handle(WsEvent.ReactionUpdated("m1", null, "👍", count))
        requests.clear()
    }

    private fun hold(emoji: String) {
        held[emoji] = CompletableDeferred()
        arrived[emoji] = CompletableDeferred()
    }

    /** Alterna a reação e deixa o pedido em voo até [release]; devolve a tarefa que o espera. */
    private suspend fun TestScope.toggleInFlight(chat: ChatStore, emoji: String): Job {
        val stored = assertNotNull(chat.storedMessage("c1", "m1"))
        hold(emoji)
        val job = launch { chat.toggleReaction(stored, null, emoji) }
        assertNotNull(arrived[emoji]).await() // o motor falso responde em outra thread
        return job
    }

    private suspend fun release(emoji: String, job: Job) {
        assertNotNull(held[emoji]).complete(Unit)
        job.join()
    }

    private fun event(count: Int, unicode: String = "👍") = WsEvent.ReactionUpdated("m1", null, unicode, count)

    // Tirar a própria reação

    @Test
    fun tirarAUltimaReacaoComOEventoAntesDaRespostaZeraAContagem() = runTest {
        val chat = TestResources.session(engine, this, open = listOf("c1")).chat
        chat.seed(count = 1, mine = true)

        val job = toggleInFlight(chat, "👍")
        chat.handle(event(0))
        release("👍", job)

        assertEquals(Shown(0, false), chat.shown())
        assertEquals(listOf("DELETE /channels/c1/messages/m1/reactions {\"unicode\":\"👍\"}"), requests)
    }

    @Test
    fun tirarAUltimaReacaoComOEventoDepoisDaRespostaZeraAContagem() = runTest {
        val chat = TestResources.session(engine, this, open = listOf("c1")).chat
        chat.seed(count = 1, mine = true)

        val job = toggleInFlight(chat, "👍")
        release("👍", job)
        assertEquals(Shown(1, false), chat.shown()) // o "eu reagi" já saiu; a contagem espera o servidor
        chat.handle(event(0))

        assertEquals(Shown(0, false), chat.shown())
    }

    @Test
    fun tirarAReacaoSemEventoNaoInventaAContagem() = runTest {
        val chat = TestResources.session(engine, this, open = listOf("c1")).chat
        chat.seed(count = 1, mine = true)

        chat.toggleReaction(assertNotNull(chat.storedMessage("c1", "m1")), null, "👍")

        assertEquals(Shown(1, false), chat.shown())
    }

    @Test
    fun tirarAReacaoQuandoOutrosTambemReagiramFicaComAContagemDoEventoAntesDaResposta() = runTest {
        val chat = TestResources.session(engine, this, open = listOf("c1")).chat
        chat.seed(count = 2, mine = true)

        val job = toggleInFlight(chat, "👍")
        chat.handle(event(1)) // não zera: o evento sozinho não tira o "eu reagi"
        assertEquals(Shown(1, true), chat.shown())
        release("👍", job)

        assertEquals(Shown(1, false), chat.shown())
    }

    @Test
    fun tirarAReacaoQuandoOutrosTambemReagiramFicaComAContagemDoEventoDepoisDaResposta() = runTest {
        val chat = TestResources.session(engine, this, open = listOf("c1")).chat
        chat.seed(count = 2, mine = true)

        val job = toggleInFlight(chat, "👍")
        release("👍", job)
        chat.handle(event(1))

        assertEquals(Shown(1, false), chat.shown())
    }

    // Reagir

    @Test
    fun reagirComOEventoAntesDaRespostaMostraAContagemEMarcaComoMinha() = runTest {
        val chat = TestResources.session(engine, this, open = listOf("c1")).chat
        chat.seed()

        val job = toggleInFlight(chat, "👍")
        chat.handle(event(1))
        assertEquals(Shown(1, false), chat.shown())
        release("👍", job)

        assertEquals(Shown(1, true), chat.shown())
        assertEquals(listOf("POST /channels/c1/messages/m1/reactions {\"unicode\":\"👍\"}"), requests)
    }

    @Test
    fun reagirComOEventoDepoisDaRespostaMostraAContagemEMarcaComoMinha() = runTest {
        val chat = TestResources.session(engine, this, open = listOf("c1")).chat
        chat.seed()

        val job = toggleInFlight(chat, "👍")
        release("👍", job)
        assertEquals(Shown(0, true), chat.shown())
        chat.handle(event(1))

        assertEquals(Shown(1, true), chat.shown())
    }

    @Test
    fun reagirSemEventoMarcaComoMinhaSemInventarAContagem() = runTest {
        val chat = TestResources.session(engine, this, open = listOf("c1")).chat
        chat.seed(count = 1)

        chat.toggleReaction(assertNotNull(chat.storedMessage("c1", "m1")), null, "👍")

        assertEquals(Shown(1, true), chat.shown())
    }

    @Test
    fun reagirPorCimaDeOutrosComOEventoNaOrdemQueChegar() = runTest {
        val chat = TestResources.session(engine, this, open = listOf("c1")).chat
        chat.seed(count = 1)

        val before = toggleInFlight(chat, "👍")
        chat.handle(event(2))
        release("👍", before)
        assertEquals(Shown(2, true), chat.shown())

        // Tirar de novo, agora com o evento depois da resposta.
        val after = toggleInFlight(chat, "👍")
        release("👍", after)
        chat.handle(event(1))
        assertEquals(Shown(1, false), chat.shown())
    }

    @Test
    fun reagirDeNovoQuandoOServidorJaTemAReacaoNaoDuplicaOEuReagi() = runTest {
        val chat = TestResources.session(engine, this, open = listOf("c1")).chat
        chat.seed(count = 1, mine = true)
        val stale = assertNotNull(chat.storedMessage("c1", "m1")).copy(userReactions = emptyList()) // a tela ainda não sabia

        chat.toggleReaction(stale, null, "👍") // o servidor responde 201 do mesmo jeito (a rota é idempotente)

        val own = assertNotNull(chat.storedMessage("c1", "m1")).userReactions
        assertEquals(1, own.count { it.unicode == "👍" })
    }

    // Falha e cancelamento

    @Test
    fun falhaAoTirarAReacaoDeixaTudoComoEstavaESemNovaTentativa() = runTest {
        val chat = TestResources.session(engine, this, open = listOf("c1")).chat
        chat.seed(count = 1, mine = true)
        failWith = HttpStatusCode.InternalServerError

        val error = assertFailsWith<ApiException> { chat.toggleReaction(assertNotNull(chat.storedMessage("c1", "m1")), null, "👍") }

        assertEquals(500, error.status)
        assertEquals(Shown(1, true), chat.shown())
        assertEquals(1, requests.size) // uma mutação que falha não é repetida sozinha
    }

    @Test
    fun falhaAoReagirDeixaTudoComoEstavaESemNovaTentativa() = runTest {
        val chat = TestResources.session(engine, this, open = listOf("c1")).chat
        chat.seed(count = 1)
        failWith = HttpStatusCode.NotFound

        val error = assertFailsWith<ApiException> { chat.toggleReaction(assertNotNull(chat.storedMessage("c1", "m1")), null, "👍") }

        assertTrue(error.isNotFound)
        assertEquals(Shown(1, false), chat.shown())
        assertEquals(1, requests.size)
    }

    @Test
    fun cancelarEnquantoOPedidoEstaEmVooNaoMexeNoQueEstaGuardado() = runTest {
        val chat = TestResources.session(engine, this, open = listOf("c1")).chat
        chat.seed(count = 1, mine = true)

        val job = toggleInFlight(chat, "👍")
        job.cancelAndJoin()
        assertNotNull(held["👍"]).complete(Unit)

        assertTrue(job.isCancelled) // o cancelamento não vira falha comum nem some
        assertEquals(Shown(1, true), chat.shown())
    }

    // Várias reações

    @Test
    fun duasReacoesEmVooAoMesmoTempoFicamAsDuasMarcadasComAsSuasContagens() = runTest {
        val chat = TestResources.session(engine, this, open = listOf("c1")).chat
        chat.seed()

        val thumbs = toggleInFlight(chat, "👍")
        val heart = toggleInFlight(chat, "❤")
        chat.handle(event(1, "👍")) // chega antes da resposta dela
        release("❤", heart)
        release("👍", thumbs)
        chat.handle(event(1, "❤")) // e este depois

        assertEquals(Shown(1, true), chat.shown("👍"))
        assertEquals(Shown(1, true), chat.shown("❤"))
    }

    @Test
    fun trocarDeIdaEVoltaEmSequenciaTerminaMarcadoComAContagemDoServidor() = runTest {
        val chat = TestResources.session(engine, this, open = listOf("c1")).chat
        chat.seed()
        suspend fun toggle() = chat.toggleReaction(assertNotNull(chat.storedMessage("c1", "m1")), null, "👍")

        toggle()
        chat.handle(event(1))
        toggle()
        chat.handle(event(0))
        toggle()
        chat.handle(event(1))

        assertEquals(Shown(1, true), chat.shown())
        assertEquals(listOf("POST", "DELETE", "POST"), requests.map { it.substringBefore(' ') })
    }

    @Test
    fun reacoesDiferentesNaoSeMisturam() = runTest {
        val chat = TestResources.session(engine, this, open = listOf("c1")).chat
        chat.seed(count = 1, mine = true)
        suspend fun toggle(emojiId: String?, unicode: String?) =
            chat.toggleReaction(assertNotNull(chat.storedMessage("c1", "m1")), emojiId, unicode)

        toggle(null, "❤") // outro emoji unicode
        chat.handle(event(1, "❤"))
        toggle("e1", null) // emoji do servidor
        chat.handle(WsEvent.ReactionUpdated("m1", "e1", null, 1))

        toggle(null, "👍") // tira só o 👍
        chat.handle(event(0))
        assertEquals(Shown(0, false), chat.shown("👍"))
        assertEquals(Shown(1, true), chat.shown("❤"))
        assertEquals(Shown(1, true), chat.shown(unicode = null, emojiId = "e1"))

        toggle("e1", null) // e agora o emoji do servidor
        chat.handle(WsEvent.ReactionUpdated("m1", "e1", null, 0))
        assertEquals(Shown(0, false), chat.shown(unicode = null, emojiId = "e1"))
        assertEquals(Shown(1, true), chat.shown("❤"))
    }

    @Test
    fun mensagemQueSumiuNaoQuebraNemReaparece() = runTest {
        val chat = TestResources.session(engine, this, open = listOf("c1")).chat
        chat.seed(count = 1, mine = true)
        val stored = assertNotNull(chat.storedMessage("c1", "m1"))
        chat.handle(WsEvent.MessageDeleted("m1", "c1"))

        chat.toggleReaction(stored, null, "👍") // a mensagem foi apagada enquanto o pedido voava
        chat.handle(event(3))

        assertNull(chat.storedMessage("c1", "m1"))
    }

    // Duas threads de verdade: o evento e a resposta gravam a mesma linha ao mesmo tempo

    /** Um banco que, uma vez, para a thread que acabou de ler uma mensagem e deixa outra coisa rodar no meio. */
    private class InterleavingDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
        private val hook = AtomicReference<(() -> Unit)?>(null)

        fun afterNextMessageRead(action: () -> Unit) = hook.set(action)

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> {
            val result = delegate.executeQuery(identifier, sql, mapper, parameters, binders)
            if (sql.startsWith("SELECT message.id") && sql.endsWith("FROM message WHERE id = ?")) hook.getAndSet(null)?.invoke() // selectMessage
            return result
        }
    }

    private val background = CoroutineScope(Dispatchers.Default + Job())

    @AfterTest
    fun closeBackground() = background.cancel()

    /** O que roda no meio da leitura; guardado para o teste esperar até o fim dele. */
    private var interleaved: Job? = null

    /**
     * Roda [action] noutra thread enquanto quem leu a linha espera. Se [action] termina, ela passou entre a leitura
     * e a gravação de quem leu; se não termina, ela está esperando a vez (a trava das reações), e a espera acaba
     * depois de [HOLD_MS]. Nos dois casos o teste só confere o resultado final.
     */
    private fun interleave(action: suspend () -> Unit): () -> Unit = {
        val job = background.launch { action() }
        interleaved = job
        runBlocking { withTimeoutOrNull(HOLD_MS) { job.join() } }
    }

    private fun TestScope.threadedChat(): Pair<ChatStore, InterleavingDriver> {
        val driver = InterleavingDriver(TestResources.driver())
        val crypto = JcaCrypto()
        val resources = AccountResources(driver, MediaCache(TestResources.tempDir(), crypto.randomBytes(32), crypto, Dispatchers.IO))
        val client = PapoClient(engine, ServerUrl("https://papo.test"), MemoryCookieStore("jwt"))
        val chat = AccountSession("conta", "u1", client, backgroundScope, resources, Dispatchers.IO).chat
        chat.conversationOpened("c1") // só conversa aberta guarda mensagens do tempo real
        return chat to driver
    }

    @Test
    fun oEventoQueChegaNoMeioDaGravacaoDaRespostaNaoEApagadoPorEla() = runTest {
        val (chat, driver) = threadedChat()
        chat.seed(count = 1, mine = true)
        val stored = assertNotNull(chat.storedMessage("c1", "m1"))

        // A resposta leu a linha (contagem 1) e, antes de gravar, o servidor avisa que a contagem caiu para 0.
        driver.afterNextMessageRead(interleave { chat.handle(event(0)) })
        chat.toggleReaction(stored, null, "👍")
        assertNotNull(interleaved).join()

        assertEquals(Shown(0, false), chat.shown())
    }

    @Test
    fun aRespostaQueChegaNoMeioDaGravacaoDoEventoNaoApagaOEuReagi() = runTest {
        val (chat, driver) = threadedChat()
        chat.seed(count = 1)
        val stored = assertNotNull(chat.storedMessage("c1", "m1"))

        // O evento leu a linha (sem "eu reagi") e, antes de gravar, a resposta do meu pedido chega.
        driver.afterNextMessageRead(interleave { chat.toggleReaction(stored, null, "👍") })
        chat.handle(event(2))
        assertNotNull(interleaved).join()

        assertEquals(Shown(2, true), chat.shown())
    }

    private companion object {
        /** Quanto quem leu espera pelo outro; só pesa no tempo do teste quando o outro está mesmo esperando a vez. */
        const val HOLD_MS = 400L
    }
}
