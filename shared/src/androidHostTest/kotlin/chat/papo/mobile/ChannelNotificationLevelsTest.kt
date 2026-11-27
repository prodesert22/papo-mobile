package chat.papo.mobile

import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.chat.Channel
import chat.papo.mobile.chat.ChannelNotificationLevels
import chat.papo.mobile.chat.ChannelType
import chat.papo.mobile.chat.LevelFailure
import chat.papo.mobile.chat.LevelFailureReason
import chat.papo.mobile.chat.LevelSave
import chat.papo.mobile.chat.NotificationLevel
import chat.papo.mobile.core.MutationResult
import chat.papo.mobile.data.api.ChatApi
import chat.papo.mobile.data.api.MemoryCookieStore
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.data.db.account.AccountDb
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Um servidor com a rota de nível do canal e a leitura dos canais, e o que cada uma responde. */
internal class LevelServer {
    val requests = CopyOnWriteArrayList<String>()
    val bodies = CopyOnWriteArrayList<String>()
    @Volatile var status = HttpStatusCode.OK
    @Volatile var channelsStatus = HttpStatusCode.OK
    @Volatile var timeout = false

    /** Devolve o que o pedido disse; um valor diferente simula uma resposta que não confirma o pedido. */
    @Volatile var answer: ((String) -> String)? = null
    @Volatile var gate: CompletableDeferred<Unit>? = null

    val engine = MockEngine { request ->
        val line = "${request.method.value} ${request.url.encodedPath}"
        requests += line
        fun problem(code: HttpStatusCode) = respond(
            """{"type":"https://papo.test/problems/x","title":"Erro","status":${code.value},"detail":"falhou ${code.value}"}""",
            code,
            headersOf(HttpHeaders.ContentType, "application/problem+json"),
        )
        when {
            request.method == HttpMethod.Post && request.url.encodedPath == "/channels/c1/user/u1/settings" -> {
                bodies += request.body.toByteArray().decodeToString()
                gate?.await()
                if (timeout) throw ConnectTimeoutException("sem rota", null)
                if (status.value !in 200..299) return@MockEngine problem(status)
                val sent = Regex("\"notification_settings\":\"([a-z_]+)\"").find(bodies.last())!!.groupValues[1]
                val mode = answer?.invoke(sent) ?: sent
                respond(
                    """{"user_id":"u1","channel_id":"c1","notification_settings":"$mode","updated_at":"2026-01-01T00:00:00Z"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            request.method == HttpMethod.Get && request.url.encodedPath == "/channels" ->
                if (channelsStatus == HttpStatusCode.OK) {
                    respond("""{"channels":[]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                } else {
                    problem(channelsStatus)
                }
            else -> problem(HttpStatusCode.NotFound)
        }
    }

    fun count(call: String) = requests.count { it == call }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelNotificationLevelsTest {
    private val post = "POST /channels/c1/user/u1/settings"
    private val reloadCall = "GET /channels"

    private class Fixture(val scope: TestScope, val server: LevelServer) {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val queries = AccountDb(TestResources.driver()).chatQueries
        val channels = MutableStateFlow<List<Channel>>(emptyList())
        val api = ChatApi(PapoClient(server.engine, ServerUrl("https://papo.test"), MemoryCookieStore("jwt")))
        val levels = ChannelNotificationLevels(
            api, queries, "u1", channels,
            reload = {
                api.channels()
                refreshChannels()
            },
            scope.backgroundScope, dispatcher,
        )

        init {
            queries.upsertChannel("c1", "geral", "text", 0, null, null, null, null, null, null, null, "all")
            refreshChannels()
        }

        private fun refreshChannels() {
            channels.value = queries.selectChannels().executeAsList().map {
                Channel(it.id, it.name, ChannelType.Text, 0, null, null, null, null, null, null, null, it.notification_settings)
            }
        }

        fun stored() = queries.selectChannel("c1").executeAsOne().notification_settings

        /** O canal como o app o leria depois de gravar: o nível sai do banco, que `save` atualizou. */
        fun republish() = refreshChannels()
    }

    @Test
    fun osNiveisSaemDosCanaisESemValorOuDesconhecidoViramSoMencoes() = runTest {
        val f = Fixture(this, LevelServer())
        runCurrent()
        assertEquals(mapOf("c1" to NotificationLevel.All), f.levels.levels.value)
        f.channels.value = listOf("a" to "off", "b" to null, "c" to "loud", "d" to "only_mentions").map { (id, mode) ->
            Channel(id, id, ChannelType.Text, 0, null, null, null, null, null, null, null, mode)
        }
        runCurrent()
        assertEquals(
            mapOf(
                "a" to NotificationLevel.Nothing,
                "b" to NotificationLevel.OnlyMentions,
                "c" to NotificationLevel.OnlyMentions,
                "d" to NotificationLevel.OnlyMentions,
            ),
            f.levels.levels.value,
        )
    }

    @Test
    fun gravarMandaOCampoUmaVezEAplicaOQueOServidorDevolveNoBanco() = runTest {
        val server = LevelServer()
        val f = Fixture(this, server)
        val result = f.levels.save("c1", NotificationLevel.Nothing)

        assertEquals(MutationResult.Done, result)
        assertEquals(listOf(post), server.requests.toList())
        assertEquals(listOf("""{"notification_settings":"off"}"""), server.bodies.toList())
        assertEquals("off", f.stored())
        assertEquals(LevelSave(), f.levels.save.value)
        f.republish()
        runCurrent()
        assertEquals(NotificationLevel.Nothing, f.levels.levels.value["c1"])
    }

    @Test
    fun recusaComPermissaoOuCanalAusenteNaoMudaOBancoNemRelêNemRepete() = runTest {
        for ((status, reason) in listOf(HttpStatusCode.Forbidden to LevelFailureReason.Forbidden, HttpStatusCode.NotFound to LevelFailureReason.NotFound)) {
            val server = LevelServer().also { it.status = status }
            val f = Fixture(this, server)
            val result = f.levels.save("c1", NotificationLevel.Nothing)

            assertTrue(result is MutationResult.Rejected, "$status")
            assertEquals(listOf(post), server.requests.toList(), "$status")
            assertEquals("all", f.stored(), "$status")
            assertEquals(LevelSave(failure = LevelFailure("c1", reason)), f.levels.save.value)
        }
    }

    @Test
    fun outroErroDe4xxEMarcadoComoRecusado() = runTest {
        val server = LevelServer().also { it.status = HttpStatusCode.BadRequest }
        val f = Fixture(this, server)
        assertTrue(f.levels.save("c1", NotificationLevel.All) is MutationResult.Rejected)
        assertEquals(LevelFailureReason.Rejected, f.levels.save.value.failure?.reason)
        assertEquals("all", f.stored())
    }

    @Test
    fun limiteDeRequisicoesGravaUmaVezSoENaoTentaDeNovo() = runTest {
        val server = LevelServer().also { it.status = HttpStatusCode.TooManyRequests }
        val f = Fixture(this, server)

        assertEquals(MutationResult.RateLimited, f.levels.save("c1", NotificationLevel.Nothing))
        runCurrent()
        advanceNothing()

        assertEquals(listOf(post), server.requests.toList()) // um POST, e nenhuma releitura
        assertEquals("all", f.stored())
        assertEquals(LevelFailureReason.RateLimited, f.levels.save.value.failure?.reason)
    }

    @Test
    fun resultadoIncertoReleOsCanaisUmaVezENaoReenviaOPedido() = runTest {
        val server = LevelServer().also { it.status = HttpStatusCode.InternalServerError }
        val f = Fixture(this, server)

        assertEquals(MutationResult.Uncertain, f.levels.save("c1", NotificationLevel.Nothing))

        assertEquals(listOf(post, reloadCall), server.requests.toList())
        assertEquals("all", f.stored()) // o que ficou foi o que o servidor mostrou
        assertEquals(LevelFailure("c1", LevelFailureReason.Uncertain), f.levels.save.value.failure)
    }

    @Test
    fun respostaQueNaoConfirmaOPedidoTambemEIncertaEReleOsCanais() = runTest {
        val server = LevelServer().also { it.answer = { "all" } }
        val f = Fixture(this, server)

        assertEquals(MutationResult.Uncertain, f.levels.save("c1", NotificationLevel.Nothing))

        assertEquals(1, server.count(post))
        assertEquals(1, server.count(reloadCall))
        assertEquals("all", f.stored())
    }

    @Test
    fun semConexaoNadaFoiEnviadoENaoReleNemRepete() = runTest {
        val server = LevelServer().also { it.timeout = true }
        val f = Fixture(this, server)

        assertEquals(MutationResult.NotSent, f.levels.save("c1", NotificationLevel.Nothing))

        assertEquals(listOf(post), server.requests.toList())
        assertEquals(LevelFailureReason.NotSent, f.levels.save.value.failure?.reason)
    }

    @Test
    fun falhaNaReleituraNaoEstragaOResultadoIncerto() = runTest {
        val server = LevelServer().also {
            it.status = HttpStatusCode.InternalServerError
            it.channelsStatus = HttpStatusCode.InternalServerError
        }
        val f = Fixture(this, server)

        assertEquals(MutationResult.Uncertain, f.levels.save("c1", NotificationLevel.Nothing))

        assertEquals(listOf(post, reloadCall), server.requests.toList())
        assertEquals(LevelFailureReason.Uncertain, f.levels.save.value.failure?.reason)
    }

    @Test
    fun umaGravacaoPorVezEOEstadoDizQualCanalEstaSalvando() = runTest {
        val server = LevelServer().also { it.gate = CompletableDeferred() }
        val f = Fixture(this, server)

        f.levels.set("c1", NotificationLevel.Nothing)
        f.levels.set("c1", NotificationLevel.OnlyMentions)
        TestResources.waitUntil { server.count(post) == 1 }
        runCurrent()
        assertEquals(LevelSave(savingChannelId = "c1"), f.levels.save.value)
        assertEquals(1, server.count(post)) // a segunda espera a primeira

        server.gate!!.complete(Unit)
        TestResources.waitUntil { server.count(post) == 2 && f.levels.save.value == LevelSave() }
        assertEquals(listOf("off", "only_mentions"), server.bodies.map { Regex("\"([a-z_]+)\"}").find(it)!!.groupValues[1] })
        assertEquals("only_mentions", f.stored())
    }

    @Test
    fun esquecerAFalhaLimpaSoAFalha() = runTest {
        val server = LevelServer().also { it.status = HttpStatusCode.Forbidden }
        val f = Fixture(this, server)
        f.levels.save("c1", NotificationLevel.Nothing)
        assertTrue(f.levels.save.value.failure != null)
        f.levels.clearFailure()
        assertNull(f.levels.save.value.failure)
    }

    private suspend fun advanceNothing() {
        // Dá ao motor falso tempo real para um repique que não deveria existir.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.delay(100) }
    }
}
