package chat.papo.mobile

import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.chat.ChatStore
import chat.papo.mobile.chat.PendingStatus
import chat.papo.mobile.chat.SendFailure
import chat.papo.mobile.data.api.ChatApi
import chat.papo.mobile.data.api.MemoryCookieStore
import chat.papo.mobile.data.api.OutgoingFile
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.data.db.account.AccountDb
import chat.papo.mobile.data.ws.WsClient
import chat.papo.mobile.data.ws.WsEvent
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/**
 * A fila de envio de ponta a ponta no [chat.papo.mobile.chat.ChatStore]: pedidos HTTP de verdade (motor
 * simulado, que responde em outra thread) e o banco em memória. O que o motor faz com cada POST muda por teste.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatStoreOutboxTest {
    private class Post(val content: String?, val replyTo: String?, val number: Int)

    private val requests = CopyOnWriteArrayList<String>()
    private val posts = AtomicInteger()
    private val inFlight = AtomicInteger()
    private val mostInFlight = AtomicInteger()

    /** Conteúdo de cada POST, na ordem em que o motor o começou a tratar. */
    private val started = Channel<String>(Channel.UNLIMITED)
    private val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val replies = CopyOnWriteArrayList<String?>()
    private var readBody = true
    private var historyPage = """{"messages":[],"has_more":false}"""
    private var behavior: suspend MockRequestHandleScope.(Post) -> HttpResponseData = { created(it.content.orEmpty(), "s${it.number}") }

    private fun MockRequestHandleScope.created(content: String, id: String, at: String = "2026-01-01T00:00:10Z") = respond(
        """{"id":"$id","channel_id":"c1","author_id":"u1","content":"$content","created_at":"$at"}""",
        HttpStatusCode.Created,
        headersOf(HttpHeaders.ContentType, "application/json"),
    )

    private fun MockRequestHandleScope.problem(status: HttpStatusCode, detail: String) = respond(
        """{"type":"t","title":"Erro","status":${status.value},"detail":"$detail"}""",
        status,
        headersOf(HttpHeaders.ContentType, "application/problem+json"),
    )

    private fun String.field(name: String): String? =
        Regex("""name="?$name"?[^\r\n]*\r\n(?:[^\r\n]+\r\n)*\r\n([^\r]*)""").find(this)?.groupValues?.get(1)

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        requests += "${request.method.value} $path"
        when (path) {
            "/messages" -> {
                val number = posts.incrementAndGet()
                // Ler o corpo é o que faz o progresso do envio andar até o fim.
                val body = if (readBody) request.body.toByteArray().decodeToString() else ""
                val now = inFlight.incrementAndGet()
                mostInFlight.updateAndGet { maxOf(it, now) }
                try {
                    started.trySend(body.field("content").orEmpty())
                    replies += body.field("reply_to")
                    behavior(Post(body.field("content"), body.field("reply_to"), number))
                } finally {
                    inFlight.decrementAndGet()
                }
            }
            "/channels/c1/messages" -> respond(historyPage, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            else -> respond("", HttpStatusCode.NoContent)
        }
    }

    /** Faz o motor segurar a resposta do POST com este conteúdo até o teste soltar. */
    private fun hold(content: String): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { gates[content] = it }

    private fun holdAll() {
        behavior = { post ->
            gates[post.content]?.await()
            created(post.content.orEmpty(), "s${post.number}")
        }
    }

    private fun file() = OutgoingFile("a.bin", "application/octet-stream", 3) { byteArrayOf(1, 2, 3) }

    @Test
    fun umCanalEnviaUmaPorVezNaOrdemEmQueAsMensagensForamMandadas() = runTest {
        val chat = TestResources.session(engine, this).chat
        val gateA = hold("a")
        val gateB = hold("b")
        hold("c").complete(Unit)
        holdAll()

        chat.enqueue("c1", "a", null, emptyList())
        chat.enqueue("c1", "b", null, emptyList())
        chat.enqueue("c1", "c", null, emptyList())
        assertEquals("a", started.receive())
        assertEquals(listOf(PendingStatus.Sending, PendingStatus.Queued, PendingStatus.Queued), chat.outbox.value.map { it.status })

        gateA.complete(Unit)
        assertEquals("b", started.receive())
        gateB.complete(Unit)
        assertEquals("c", started.receive())
        chat.outbox.first { it.isEmpty() }

        assertEquals(1, mostInFlight.get()) // nunca duas ao mesmo tempo no canal
        assertEquals(3, chat.messages("c1", 10).first().map { it.id }.toSet().size)
    }

    @Test
    fun umRateLimitFazExatamenteUmPostERetentativaSoPeloUsuario() = runTest {
        val chat = TestResources.session(engine, this).chat
        behavior = { problem(HttpStatusCode.TooManyRequests, "Limite de requisições excedido") }

        val a = chat.enqueue("c1", "a", null, emptyList())
        chat.enqueue("c1", "b", null, emptyList())
        chat.outbox.first { it.firstOrNull()?.status is PendingStatus.Failed }

        // Nem o passar do tempo reenvia sozinho; a segunda mensagem do canal também espera.
        advanceTimeBy(1.hours)
        runCurrent()
        assertEquals(1, posts.get())
        assertEquals(listOf(PendingStatus.Failed(SendFailure.RateLimited), PendingStatus.Queued), chat.outbox.value.map { it.status })

        assertTrue(chat.retry(a))
        chat.outbox.first { it.firstOrNull()?.status is PendingStatus.Failed }
        assertEquals(2, posts.get()) // o segundo pedido é o do usuário; nada de b ainda
    }

    @Test
    fun retirarDaFilaUmaMensagemEmEsperaNaoFazPedido() = runTest {
        val chat = TestResources.session(engine, this).chat
        val gate = hold("a")
        holdAll()

        chat.enqueue("c1", "a", null, emptyList())
        val b = chat.enqueue("c1", "b", null, emptyList())
        assertEquals("a", started.receive())

        chat.withdraw(b)
        gate.complete(Unit)
        chat.outbox.first { it.isEmpty() }
        runCurrent()

        assertEquals(1, posts.get()) // só a foi ao servidor
        assertEquals(listOf("a"), chat.messages("c1", 10).first().map { it.content })
    }

    @Test
    fun cancelarEmVooAntesDosBytesDescartaAMensagemEFechaOPedido() = runTest {
        val chat = TestResources.session(engine, this).chat
        readBody = false // o servidor ainda não leu nada do que foi enviado
        val closed = CompletableDeferred<Unit>()
        behavior = {
            try {
                awaitCancellation()
            } finally {
                closed.complete(Unit)
            }
        }

        val id = chat.enqueue("c1", "", null, listOf(file()))
        started.receive()
        chat.withdraw(id)
        chat.outbox.first { it.isEmpty() }
        closed.await() // o pedido em andamento foi mesmo cancelado

        assertEquals(1, posts.get())
        assertTrue(chat.messages("c1", 10).first().isEmpty())
    }

    @Test
    fun cancelarEmVooDepoisDeTodosOsBytesDeixaIncertaEPausaOCanal() = runTest {
        val chat = TestResources.session(engine, this).chat
        val closed = CompletableDeferred<Unit>()
        behavior = {
            try {
                awaitCancellation()
            } finally {
                closed.complete(Unit)
            }
        }

        val id = chat.enqueue("c1", "x", null, listOf(file()))
        started.receive()
        chat.outbox.first { it.firstOrNull()?.progress == 1f } // o corpo inteiro já foi entregue
        chat.enqueue("c1", "depois", null, emptyList())

        chat.withdraw(id)
        closed.await()
        chat.outbox.first { it.firstOrNull()?.status == PendingStatus.Uncertain }
        advanceTimeBy(1.hours)
        runCurrent()

        assertEquals(1, posts.get()) // a que veio depois não passa na frente da incerta
        assertEquals(PendingStatus.Queued, chat.outbox.value[1].status)
    }

    @Test
    fun eventoDoWebSocketAntesDaRespostaNaoDuplicaAMensagem() = runTest {
        val chat = TestResources.session(engine, this).chat
        val gate = hold("oi")
        hold("depois").complete(Unit)
        holdAll()

        chat.enqueue("c1", "oi", null, emptyList())
        assertEquals("oi", started.receive())
        // O servidor já criou a mensagem e avisou o app pelo WebSocket; a resposta do POST ainda não chegou.
        chat.handle(WsEvent.MessageCreated("s1", "c1", "u1", content = "oi", createdAt = "2026-01-01T00:00:10Z"))

        assertTrue(chat.outbox.value.isEmpty()) // a entrada saiu da fila: a mensagem de verdade a substitui
        assertEquals(listOf("s1"), chat.messages("c1", 10).first().map { it.id })

        // A resposta chega depois: a mensagem continua uma só. Mandar outra garante que o POST de "oi" já terminou.
        gate.complete(Unit)
        chat.enqueue("c1", "depois", null, emptyList())
        chat.outbox.first { it.isEmpty() }
        assertEquals(listOf("s2", "s1"), chat.messages("c1", 10).first().map { it.id })
    }

    @Test
    fun ecoTardioDeUmaMensagemJaConfirmadaNaoTiraAIgualQueAindaEstaSaindo() = runTest {
        val chat = TestResources.session(engine, this).chat
        val second = CompletableDeferred<Unit>()
        behavior = { post ->
            if (post.number == 2) second.await() // a primeira é respondida na hora; a segunda espera o teste
            created("oi", "s${post.number}")
        }

        chat.enqueue("c1", "oi", null, emptyList())
        chat.enqueue("c1", "oi", null, emptyList())
        started.receive()
        started.receive() // a primeira já foi respondida e guardada; a segunda saiu e espera
        chat.handle(WsEvent.MessageCreated("s1", "c1", "u1", content = "oi", createdAt = "2026-01-01T00:00:10Z"))

        // O eco é da primeira, que o banco já tinha: a segunda continua sendo mostrada como em saída.
        assertEquals(listOf(PendingStatus.Sending), chat.outbox.value.map { it.status })
        second.complete(Unit)
        chat.outbox.first { it.isEmpty() }
        assertEquals(setOf("s1", "s2"), chat.messages("c1", 10).first().map { it.id }.toSet())
    }

    @Test
    fun mensagemDeOutraPessoaComOMesmoTextoNaoTiraANossa() = runTest {
        val chat = TestResources.session(engine, this).chat
        val gate = hold("oi")
        holdAll()

        chat.enqueue("c1", "oi", null, emptyList())
        started.receive()
        chat.handle(WsEvent.MessageCreated("x1", "c1", "u2", content = "oi", createdAt = "2026-01-01T00:00:09Z"))
        // Sem autor (webhook) também não é nossa.
        chat.handle(WsEvent.MessageCreated("w1", "c1", null, content = "oi", createdAt = "2026-01-01T00:00:09Z"))
        assertEquals(1, chat.outbox.value.size)

        gate.complete(Unit)
        chat.outbox.first { it.isEmpty() }
        assertEquals(setOf("x1", "w1", "s1"), chat.messages("c1", 10).first().map { it.id }.toSet())
    }

    @Test
    fun reenviarUmaMensagemQueFalhouLevaOMesmoTextoEAResposta() = runTest {
        val chat = TestResources.session(engine, this).chat
        behavior = { post ->
            if (post.number == 1) problem(HttpStatusCode.Forbidden, "Sem permissão") else created(post.content.orEmpty(), "s${post.number}")
        }

        val id = chat.enqueue("c1", "de novo", "m1", emptyList())
        chat.outbox.first { it.firstOrNull()?.status is PendingStatus.Failed }
        assertEquals(PendingStatus.Failed(SendFailure.Rejected, "Sem permissão"), chat.outbox.value.single().status)

        assertTrue(chat.retry(id))
        chat.outbox.first { it.isEmpty() }

        assertEquals(2, posts.get())
        val stored = chat.messages("c1", 10).first().single()
        assertEquals("de novo", stored.content)
        assertEquals(listOf<String?>("m1", "m1"), replies) // a resposta em andamento foi nos dois pedidos
    }

    @Test
    fun respostaDeErroDoServidorFicaIncertaEReenviarPedeConfirmacao() = runTest {
        val chat = TestResources.session(engine, this).chat
        behavior = { post ->
            if (post.number == 1) problem(HttpStatusCode.BadGateway, "indisponível") else created(post.content.orEmpty(), "s${post.number}")
        }

        val id = chat.enqueue("c1", "talvez", null, emptyList())
        chat.outbox.first { it.firstOrNull()?.status == PendingStatus.Uncertain }

        assertFalse(chat.retry(id)) // pode duplicar: precisa da confirmação do usuário
        runCurrent()
        assertEquals(1, posts.get())

        assertTrue(chat.retry(id, confirmedDuplicate = true))
        chat.outbox.first { it.isEmpty() }
        assertEquals(2, posts.get())
    }

    @Test
    fun semConexaoANaoEnviadaAindaEPodeSerReenviadaSemConfirmar() = runTest {
        val chat = TestResources.session(engine, this).chat
        readBody = false
        behavior = { throw java.net.ConnectException("Failed to connect") }

        val id = chat.enqueue("c1", "sem rede", null, emptyList())
        chat.outbox.first { it.firstOrNull()?.status is PendingStatus.Failed }
        assertEquals(PendingStatus.Failed(SendFailure.NotSent), chat.outbox.value.single().status)

        readBody = true
        behavior = { created(it.content.orEmpty(), "s1") }
        assertTrue(chat.retry(id))
        chat.outbox.first { it.isEmpty() }
    }

    @Test
    fun conexaoCortadaDepoisDeEnviarOCorpoFicaIncerta() = runTest {
        val chat = TestResources.session(engine, this).chat
        behavior = { throw java.io.IOException("reset") }

        chat.enqueue("c1", "x", null, listOf(file()))
        chat.outbox.first { it.firstOrNull()?.status is PendingStatus.Uncertain }
        assertNull(chat.outbox.value.single().progress)
    }

    @Test
    fun paginaDoHistoricoComAMensagemIncertaAConfirmaSemDuplicar() = runTest {
        val chat = TestResources.session(engine, this).chat
        behavior = { problem(HttpStatusCode.GatewayTimeout, "demorou") }
        chat.enqueue("c1", "oi", null, emptyList())
        chat.outbox.first { it.firstOrNull()?.status == PendingStatus.Uncertain }

        // Uma mensagem igual, mas antiga, não é a que ficou incerta.
        historyPage = page("antiga", "oi", "2020-01-01T00:00:00Z")
        chat.refreshLatest("c1")
        assertEquals(1, chat.outbox.value.size)

        // A nova, minha e igual, é: ela entra na conversa e sai da fila.
        historyPage = page("criada", "oi", Clock.System.now().toString())
        chat.refreshLatest("c1")
        assertTrue(chat.outbox.value.isEmpty())
        assertEquals(1, chat.messages("c1", 10).first().count { it.id == "criada" })
    }

    /** Um [chat.papo.mobile.chat.ChatStore] com o banco à mão, para marcar um canal como lendo um trecho antigo. */
    private fun TestScope.storeWithDb(): Pair<ChatStore, AccountDb> {
        val client = PapoClient(engine, ServerUrl("https://papo.test"), MemoryCookieStore("jwt"))
        val db = AccountDb(TestResources.driver())
        val store = ChatStore(
            myUserId = "u1",
            db = db,
            api = ChatApi(client),
            ws = WsClient(client, backgroundScope, sessionStillValid = { true }),
            scope = backgroundScope,
            io = StandardTestDispatcher(testScheduler),
        )
        return store to db
    }

    @Test
    fun lendoUmTrechoAntigoOEventoNaoEntraNemConfirmaAAindaSaindo() = runTest {
        val (chat, db) = storeWithDb()
        chat.handle(WsEvent.MessageCreated("m1", "c1", "u2", content = "velha", createdAt = "2026-01-01T00:00:01Z"))
        db.metaQueries.put("detached:c1", "1") // o usuário lê em volta de uma mensagem antiga
        val gate = hold("oi")
        holdAll()
        historyPage = page("s1", "oi", Clock.System.now().toString())

        chat.enqueue("c1", "oi", null, emptyList())
        started.receive()
        chat.handle(WsEvent.MessageCreated("s1", "c1", "u1", content = "oi", createdAt = "2026-01-01T00:00:10Z"))

        // Entre o trecho antigo e a mensagem nova há um buraco: ela não entra, e a entrada segue à vista até o POST responder.
        assertEquals(listOf("m1"), chat.messages("c1", 10).first().map { it.id })
        assertEquals(1, chat.outbox.value.size)

        // A resposta leva o canal de volta ao fim da conversa, onde a mensagem entrou.
        gate.complete(Unit)
        chat.outbox.first { it.isEmpty() }
        assertEquals(listOf("s1", "m1"), chat.messages("c1", 10).first().map { it.id })
        assertFalse(chat.isDetached("c1"))
    }

    @Test
    fun carregarAsMaisNovasDoTrechoAntigoConfirmaAMensagemIncerta() = runTest {
        val (chat, db) = storeWithDb()
        chat.handle(WsEvent.MessageCreated("m1", "c1", "u2", content = "velha", createdAt = "2026-01-01T00:00:01Z"))
        db.metaQueries.put("detached:c1", "1")
        behavior = { problem(HttpStatusCode.GatewayTimeout, "demorou") }
        chat.enqueue("c1", "oi", null, emptyList())
        chat.outbox.first { it.firstOrNull()?.status == PendingStatus.Uncertain }

        historyPage = page("criada", "oi", Clock.System.now().toString())
        assertEquals(1, chat.loadNewer("c1"))

        assertTrue(chat.outbox.value.isEmpty())
        assertEquals(listOf("criada", "m1"), chat.messages("c1", 10).first().map { it.id })
    }

    @Test
    fun guardadaPeloBancoAMensagemSeLeSemIrAoServidor() = runTest {
        val chat = TestResources.session(engine, this).chat
        chat.handle(WsEvent.MessageCreated("m1", "c1", "u2", content = "guardada", createdAt = "2026-01-01T00:00:01Z"))

        assertEquals("guardada", chat.storedMessage("c1", "m1")?.content)
        assertNull(chat.storedMessage("c2", "m1")) // de outro canal
        assertNull(chat.storedMessage("c1", "nao-existe"))
        assertTrue(requests.isEmpty())
    }

    private fun page(id: String, content: String, at: String) =
        """{"messages":[{"id":"$id","channel_id":"c1","author_id":"u1","content":"$content","created_at":"$at"}],"has_more":false}"""
}
