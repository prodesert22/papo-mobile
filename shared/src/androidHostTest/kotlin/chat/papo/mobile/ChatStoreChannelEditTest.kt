package chat.papo.mobile

import chat.papo.mobile.chat.ChannelEditResult
import chat.papo.mobile.chat.ChatStore
import chat.papo.mobile.chat.canManageChannels
import chat.papo.mobile.core.MutationResult
import chat.papo.mobile.data.ws.WsEvent
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private const val CATEGORY = """{"id":"cat","name":"Geral","type":"category","position":1}"""
private const val PAPO = """{"id":"c1","name":"papo","type":"text","position":2,"topic":"Conversa fiada","parent_id":"cat"}"""

/**
 * Um servidor com a categoria `cat` e o canal `c1` dentro dela, a leitura dos canais e do acesso, e `PUT /channels/{id}`
 * com o que ele devolve ajustável. O dono é [ownerId].
 */
internal class ChannelEditServer {
    val requests = CopyOnWriteArrayList<String>()
    val bodies = CopyOnWriteArrayList<String>()

    @Volatile var putStatus = HttpStatusCode.OK
    @Volatile var network: Throwable? = null
    @Volatile var gate: CompletableDeferred<Unit>? = null

    /** A resposta do PUT leva o `parent_id`, como o servidor real; sem isso o app mantém o guardado. */
    @Volatile var answerParent = true

    /** `GET /channels` ainda lista o `c1`. */
    @Volatile var listed = true
    @Volatile var ownerId = "me"

    private val name = Regex("\"name\":\"([^\"]*)\"")
    private val topic = Regex("\"topic\":\"([^\"]*)\"")

    val engine = MockEngine { request ->
        val path = request.url.encodedPath
        requests += "${request.method.value} $path"
        fun json(text: String, status: HttpStatusCode = HttpStatusCode.OK) = respond(
            text,
            status,
            headersOf(HttpHeaders.ContentType, if (status.value >= 400) "application/problem+json" else "application/json"),
        )
        when {
            request.method == HttpMethod.Put && path.startsWith("/channels/") -> {
                val body = request.body.toByteArray().decodeToString()
                bodies += body
                gate?.await()
                network?.let { throw it }
                if (putStatus.value !in 200..299) {
                    return@MockEngine json(
                        """{"type":"https://papo.test/problems/x","title":"Erro","status":${putStatus.value},"detail":"falhou ${putStatus.value}"}""",
                        putStatus,
                    )
                }
                val id = path.removePrefix("/channels/")
                val newName = name.find(body)!!.groupValues[1]
                // Sem `topic` no pedido o servidor mantém o que havia: a categoria não tem, o `c1` tem "Conversa fiada".
                val kept = if (id == "cat") "null" else "\"Conversa fiada\""
                val newTopic = topic.find(body)?.groupValues?.get(1)?.let { "\"$it\"" } ?: kept
                val parent = if (answerParent && id == "c1") ",\"parent_id\":\"cat\"" else ""
                json("""{"id":"$id","name":"$newName","type":"${if (id == "cat") "category" else "text"}","position":2,"topic":$newTopic$parent}""")
            }
            path == "/server" -> json("""{"id":"s","name":"papo","owner_id":"$ownerId"}""")
            path == "/roles" -> json("""{"roles":[]}""")
            path == "/channels" -> json("""{"channels":[$CATEGORY${if (listed) ",$PAPO" else ""}]}""")
            path == "/dms" -> json("""{"dms":[]}""")
            path == "/users" -> json("""{"users":[{"id":"me","username":"ana"}],"has_more":false}""")
            else -> respond("", HttpStatusCode.NoContent)
        }
    }

    fun count(call: String) = requests.count { it == call }
}

/** `ChatStore.updateChannel` sobre o motor HTTP falso e um banco em memória. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatStoreChannelEditTest {
    private val put = "PUT /channels/c1"

    @Test
    fun nomeEDescricaoNovosVaoNoPedidoEFicamNoBancoSemSairDaCategoria() = runTest {
        val server = ChannelEditServer()
        val chat = TestResources.session(server.engine, this).chat
        chat.sync()
        runCurrent()

        val result = chat.updateChannel("c1", "novo", "Outra conversa")
        runCurrent()

        assertEquals(ChannelEditResult.Saved, result)
        assertEquals(listOf(put), server.requests.filter { it.startsWith("PUT") })
        assertEquals("""{"name":"novo","topic":"Outra conversa"}""", server.bodies.single())
        val channel = chat.channels.value.single { it.id == "c1" }
        assertEquals("novo", channel.name)
        assertEquals("Outra conversa", channel.topic)
        assertEquals("cat", channel.parentId)
        assertEquals(2, channel.position)
    }

    @Test
    fun descricaoNulaFicaForaDoCorpoEMantemADescricao() = runTest {
        val server = ChannelEditServer()
        val chat = TestResources.session(server.engine, this).chat
        chat.sync()
        runCurrent()

        assertEquals(ChannelEditResult.Saved, chat.updateChannel("c1", "novo", null))
        runCurrent()

        assertEquals("""{"name":"novo"}""", server.bodies.single())
        assertEquals("Conversa fiada", chat.channels.value.single { it.id == "c1" }.topic)
    }

    @Test
    fun descricaoVaziaApagaADescricao() = runTest {
        val server = ChannelEditServer()
        val chat = TestResources.session(server.engine, this).chat
        chat.sync()
        runCurrent()

        assertEquals(ChannelEditResult.Saved, chat.updateChannel("c1", "papo", ""))
        runCurrent()

        assertEquals("""{"name":"papo","topic":""}""", server.bodies.single())
        assertEquals("", chat.channels.value.single { it.id == "c1" }.topic)
    }

    @Test
    fun categoriaVaiSemDescricao() = runTest {
        val server = ChannelEditServer()
        val chat = TestResources.session(server.engine, this).chat
        chat.sync()
        runCurrent()

        assertEquals(ChannelEditResult.Saved, chat.updateChannel("cat", "Outras", null))
        runCurrent()

        assertEquals("""{"name":"Outras"}""", server.bodies.single())
        val category = chat.channels.value.single { it.id == "cat" }
        assertEquals("Outras", category.name)
        assertNull(category.topic)
    }

    @Test
    fun respostaSemParentIdMantemACategoriaGuardada() = runTest {
        val server = ChannelEditServer().also { it.answerParent = false }
        val chat = TestResources.session(server.engine, this).chat
        chat.sync()
        runCurrent()

        assertEquals(ChannelEditResult.Saved, chat.updateChannel("c1", "novo", null))
        runCurrent()

        assertEquals("cat", chat.channels.value.single { it.id == "c1" }.parentId)
    }

    @Test
    fun oChannelUpdateDepoisDoSucessoNaoTiraOCanalDaCategoria() = runTest {
        val server = ChannelEditServer()
        val chat = TestResources.session(server.engine, this).chat
        chat.sync()
        runCurrent()
        chat.updateChannel("c1", "novo", "Outra conversa")

        // O servidor manda o evento a todos, inclusive a quem editou.
        chat.handle(WsEvent.ChannelUpdated("c1", "novo", 2, "Outra conversa", "cat"))
        runCurrent()

        val channel = chat.channels.value.single { it.id == "c1" }
        assertEquals("novo", channel.name)
        assertEquals("cat", channel.parentId)
        assertEquals(listOf("cat", "c1"), chat.channels.value.map { it.id })
    }

    @Test
    fun nomeJaExistenteEUmaRecusaSemMudarONomeNemReler() = runTest {
        val server = ChannelEditServer().also { it.putStatus = HttpStatusCode.Conflict }
        val chat = TestResources.session(server.engine, this).chat
        chat.sync()
        runCurrent()

        assertEquals(ChannelEditResult.NameTaken, chat.updateChannel("c1", "geral", null))
        advanceTimeBy(5.seconds)
        runCurrent()

        assertEquals("papo", chat.channels.value.single { it.id == "c1" }.name)
        assertEquals(1, server.count(put))
        assertEquals(1, server.count("GET /channels"))
    }

    @Test
    fun semPermissaoPedeARelecturaDoAcessoENaoRepete() = runTest {
        val server = ChannelEditServer().also { it.putStatus = HttpStatusCode.Forbidden }
        val chat = TestResources.session(server.engine, this).chat
        chat.sync()
        runCurrent()
        assertTrue(canManage(chat))

        server.ownerId = "boss"
        assertEquals(ChannelEditResult.Forbidden, chat.updateChannel("c1", "novo", null))
        TestResources.waitUntil { !canManage(chat) }
        runCurrent()

        assertEquals(1, server.count(put))
        assertEquals(2, server.count("GET /server"))
        assertEquals("papo", chat.channels.value.single { it.id == "c1" }.name)
    }

    private fun canManage(chat: ChatStore) = canManageChannels(chat.access.value, "me")

    @Test
    fun canalQueNaoExisteMaisSaiDoAparelhoEOServidorEReLido() = runTest {
        val server = ChannelEditServer().also { it.putStatus = HttpStatusCode.NotFound }
        val chat = TestResources.session(server.engine, this).chat
        chat.sync()
        runCurrent()
        server.listed = false

        assertEquals(ChannelEditResult.NotFound, chat.updateChannel("c1", "novo", null))
        runCurrent()

        assertEquals(listOf("cat"), chat.channels.value.map { it.id })
        assertEquals(1, server.count(put))
        assertEquals(2, server.count("GET /channels"))
    }

    @Test
    fun limiteDeRequisicoesEUmPedidoSoESemReleitura() = runTest {
        val server = ChannelEditServer().also { it.putStatus = HttpStatusCode.TooManyRequests }
        val chat = TestResources.session(server.engine, this).chat
        chat.sync()
        runCurrent()

        assertEquals(ChannelEditResult.Failed(MutationResult.RateLimited), chat.updateChannel("c1", "novo", null))
        advanceTimeBy(5.seconds)
        runCurrent()

        assertEquals(1, server.count(put))
        assertEquals(1, server.count("GET /channels"))
        assertEquals("papo", chat.channels.value.single { it.id == "c1" }.name)
    }

    @Test
    fun outraRecusaTrazOTextoDoServidorSemReler() = runTest {
        val server = ChannelEditServer().also { it.putStatus = HttpStatusCode.BadRequest }
        val chat = TestResources.session(server.engine, this).chat
        chat.sync()
        runCurrent()

        val result = chat.updateChannel("c1", "novo", null)

        val failed = assertIs<ChannelEditResult.Failed>(result)
        assertEquals(MutationResult.Rejected("falhou 400", "https://papo.test/problems/x"), failed.result)
        assertEquals(1, server.count("GET /channels"))
    }

    @Test
    fun erroDoServidorEResultadoIncertoReleOsCanaisUmaVezENaoReenvia() = runTest {
        val server = ChannelEditServer().also { it.putStatus = HttpStatusCode.InternalServerError }
        val chat = TestResources.session(server.engine, this).chat
        chat.sync()
        runCurrent()

        assertEquals(ChannelEditResult.Failed(MutationResult.Uncertain), chat.updateChannel("c1", "novo", null))
        advanceTimeBy(5.seconds)
        runCurrent()

        assertEquals(1, server.count(put))
        assertEquals(2, server.count("GET /channels"))
    }

    @Test
    fun quedaDaConexaoDepoisDoEnvioEIncertaEReleOsCanais() = runTest {
        val server = ChannelEditServer().also { it.network = IOException("reset") }
        val chat = TestResources.session(server.engine, this).chat
        chat.sync()
        runCurrent()

        assertEquals(ChannelEditResult.Failed(MutationResult.Uncertain), chat.updateChannel("c1", "novo", null))

        assertEquals(1, server.count(put))
        assertEquals(2, server.count("GET /channels"))
    }

    @Test
    fun semConexaoNadaFoiEnviadoENadaEReLido() = runTest {
        val server = ChannelEditServer().also { it.network = ConnectTimeoutException("sem rota", null) }
        val chat = TestResources.session(server.engine, this).chat
        chat.sync()
        runCurrent()

        assertEquals(ChannelEditResult.Failed(MutationResult.NotSent), chat.updateChannel("c1", "novo", null))

        assertEquals(1, server.count("GET /channels"))
    }

    @Test
    fun cancelarRelancaOCancelamentoELiberaAVezDoProximo() = runTest {
        val server = ChannelEditServer().also { it.gate = CompletableDeferred() }
        val chat = TestResources.session(server.engine, this).chat
        chat.sync()
        runCurrent()

        val first = launch { chat.updateChannel("c1", "novo", null) }
        TestResources.waitUntil { server.count(put) == 1 }
        first.cancel()
        first.join()
        assertTrue(first.isCancelled)

        server.gate = null
        assertEquals(ChannelEditResult.Saved, chat.updateChannel("c1", "outro", null))
        assertEquals(2, server.count(put))
    }

    @Test
    fun umaEdicaoPorVez() = runTest {
        val gate = CompletableDeferred<Unit>()
        val server = ChannelEditServer().also { it.gate = gate }
        val chat = TestResources.session(server.engine, this).chat
        chat.sync()
        runCurrent()

        val first = launch { chat.updateChannel("c1", "um", null) }
        TestResources.waitUntil { server.count(put) == 1 }
        val second = launch { chat.updateChannel("c1", "dois", null) }
        runCurrent()
        advanceTimeBy(1.seconds)
        assertEquals(1, server.count(put)) // o segundo espera o primeiro

        gate.complete(Unit)
        first.join()
        second.join()
        assertEquals(2, server.count(put))
        assertEquals(listOf("""{"name":"um"}""", """{"name":"dois"}"""), server.bodies.toList())
    }
}
