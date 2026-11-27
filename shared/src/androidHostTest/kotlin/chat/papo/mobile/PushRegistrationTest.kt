package chat.papo.mobile

import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.data.api.MemoryCookieStore
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.data.api.PushApi
import chat.papo.mobile.data.db.account.AccountDb
import chat.papo.mobile.push.PushRegistration
import chat.papo.mobile.push.PushTokens
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** O token de push do aparelho, trocado à mão. */
internal class FakeTokens(
    @Volatile var token: String? = "tok-1",
    override val available: Boolean = true,
    override val deviceName: String = "Pixel 9",
) : PushTokens {
    val published = MutableSharedFlow<String>(replay = 1)
    override val changes: Flow<String> = published

    /** Quando não nulo, `current()` falha com ele. */
    @Volatile var failure: Exception? = null

    override suspend fun current(): String? = failure?.let { throw it } ?: token

    /** O FCM trocou o token: o de agora e o que a plataforma publica passam a ser o novo, como no aparelho. */
    suspend fun rotate(next: String) {
        token = next
        published.emit(next)
    }
}

/** O servidor de push: registra cada chamada (método, cookie, corpo) e responde o status escolhido. */
private class PushServer {
    class Call(val method: String, val cookie: String?, val body: JsonObject)

    val calls = CopyOnWriteArrayList<Call>()
    @Volatile var putStatus = 204
    @Volatile var deleteStatus = 204

    /** Quando não nulo, cada chamada espera por ele antes de responder (depois de registrada). */
    @Volatile var hold: CompletableDeferred<Unit>? = null

    val engine = MockEngine { request ->
        val text = request.body.toByteArray().decodeToString()
        calls += Call(request.method.value, request.headers[HttpHeaders.Cookie], Json.parseToJsonElement(text).jsonObject)
        hold?.await()
        val status = if (request.method.value == "PUT") putStatus else deleteStatus
        if (status < 400) {
            respond("", HttpStatusCode.fromValue(status))
        } else {
            respond(
                """{"type":"t","title":"Erro","status":$status}""",
                HttpStatusCode.fromValue(status),
                headersOf(HttpHeaders.ContentType, "application/problem+json"),
            )
        }
    }

    fun tokens(method: String) = calls.filter { it.method == method }.map { it.body.getValue("token").jsonPrimitive.content }
    val puts get() = tokens("PUT")
    val deletes get() = tokens("DELETE")
}

@OptIn(ExperimentalCoroutinesApi::class)
class PushRegistrationTest {
    private class Fixture(private val test: TestScope, val tokens: FakeTokens = FakeTokens()) {
        val server = PushServer()
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val meta = AccountDb(TestResources.driver()).metaQueries
        @Volatile var hasSession = true

        /** Uma sessão de conta nova sobre o mesmo banco da conta: o que a anterior gravou continua lá. */
        fun registration(scope: CoroutineScope = test.backgroundScope): PushRegistration {
            val client = PapoClient(server.engine, ServerUrl("https://papo.test"), MemoryCookieStore("jwt"))
            return PushRegistration(PushApi(client), tokens, meta, scope, dispatcher) { hasSession }
        }

        fun remembered(): String? = meta.get("push.token").executeAsOneOrNull()

        /**
         * Uma chamada que não deve vir não tem postcondição a esperar: dá um tempo curto, em tempo real, para ela aparecer
         * (o motor HTTP responde numa thread própria) e falha se aparecer.
         */
        suspend fun assertNoMoreThan(count: Int) {
            assertFailsWith<TimeoutCancellationException> { TestResources.waitUntil(250.milliseconds) { server.calls.size > count } }
        }
    }

    @Test
    fun registraOTokenDeAgoraUmaVezComOCorpoDoContrato() = runTest {
        val f = Fixture(this)

        f.registration().start()
        runCurrent()
        TestResources.waitUntil { f.server.calls.size == 1 }
        runCurrent()

        val call = f.server.calls.single()
        assertEquals("PUT", call.method)
        assertEquals("Auth=jwt", call.cookie)
        assertEquals(
            mapOf("token" to "tok-1", "platform" to "android", "device_name" to "Pixel 9", "provider" to "fcm"),
            call.body.mapValues { it.value.jsonPrimitive.content },
        )
        assertEquals("tok-1", f.remembered())
    }

    @Test
    fun onomeDoAparelhoVaiCortadoEm32Bytes() = runTest {
        val f = Fixture(this, FakeTokens(deviceName = "  " + "M".repeat(40)))

        f.registration().start()
        runCurrent()
        TestResources.waitUntil { f.server.calls.size == 1 }

        assertEquals("M".repeat(32), f.server.calls.single().body.getValue("device_name").jsonPrimitive.content)
    }

    @Test
    fun comOMesmoTokenJaRegistradoUmaSessaoNovaNaoChamaOServidor() = runTest {
        val f = Fixture(this)
        val first = Job()
        f.registration(CoroutineScope(f.dispatcher + first)).start()
        runCurrent()
        TestResources.waitUntil { f.server.calls.size == 1 }
        runCurrent()
        first.cancel() // a sessão acabou (o app travou); a conta continua com o banco

        // A sessão seguinte da mesma conta passa pelo token sem chamar o servidor; a troca depois dela prova que passou.
        f.registration().start()
        runCurrent()
        f.tokens.rotate("tok-2")
        runCurrent()
        TestResources.waitUntil { f.server.calls.size == 2 }

        assertEquals(listOf("tok-1", "tok-2"), f.server.puts)
    }

    @Test
    fun trocaDeTokenRegistraOTokenNovoEOMesmoTokenRepetidoNao() = runTest {
        val f = Fixture(this)
        f.registration().start()
        runCurrent()
        TestResources.waitUntil { f.server.calls.size == 1 }
        runCurrent()

        f.tokens.rotate("tok-2")
        f.tokens.rotate("tok-2")
        f.tokens.rotate("tok-3")
        runCurrent()
        TestResources.waitUntil { f.server.calls.size == 3 }
        runCurrent()

        assertEquals(listOf("tok-1", "tok-2", "tok-3"), f.server.puts)
        assertEquals("tok-3", f.remembered())
    }

    @Test
    fun otokenTrocadoAntesDaSessaoNaoSePerde() = runTest {
        // O serviço do FCM rodou sozinho, sem sessão, e `current()` ainda não tem nada: o último valor é repetido a quem ouve.
        val f = Fixture(this, FakeTokens(token = null))
        f.tokens.published.emit("tok-novo")

        f.registration().start()
        runCurrent()
        TestResources.waitUntil { f.server.calls.size == 1 }

        assertEquals(listOf("tok-novo"), f.server.puts)
    }

    @Test
    fun semTokenAindaNaoChamaOServidor() = runTest {
        val f = Fixture(this, FakeTokens(token = null))

        f.registration().start()
        runCurrent()
        f.tokens.published.emit("tok-depois")
        runCurrent()
        TestResources.waitUntil { f.server.calls.size == 1 }

        assertEquals(listOf("tok-depois"), f.server.puts)
    }

    @Test
    fun falhaAoLerOTokenNaoDerrubaOSessaoEORegistroSeguePeloFlow() = runTest {
        val f = Fixture(this)
        f.tokens.failure = IllegalStateException("sem Play Services")

        f.registration().start()
        runCurrent()
        f.tokens.published.emit("tok-2")
        runCurrent()
        TestResources.waitUntil { f.server.calls.size == 1 }

        assertEquals(listOf("tok-2"), f.server.puts)
    }

    @Test
    fun num429ChamaUmaVezSemRepetirEATentativaVoltaNaProximaSessao() = runTest {
        val f = Fixture(this)
        f.server.putStatus = 429
        val first = Job()
        f.registration(CoroutineScope(f.dispatcher + first)).start()
        runCurrent()
        TestResources.waitUntil { f.server.calls.size == 1 }
        runCurrent()

        // O mesmo token de novo, na mesma sessão, não é outra tentativa. O token novo mostra que o repetido já passou.
        f.tokens.rotate("tok-1")
        f.tokens.rotate("tok-2")
        runCurrent()
        TestResources.waitUntil { f.server.calls.size == 2 }
        runCurrent()

        assertEquals(listOf("tok-1", "tok-2"), f.server.puts)
        assertNull(f.remembered())

        first.cancel()
        f.server.putStatus = 204
        f.registration().start()
        runCurrent()
        TestResources.waitUntil { f.server.calls.size == 3 }
        runCurrent()

        assertEquals(listOf("tok-1", "tok-2", "tok-2"), f.server.puts)
        assertEquals("tok-2", f.remembered())
    }

    @Test
    fun errorDoServidorNaoGravaOTokenComoRegistrado() = runTest {
        val f = Fixture(this)
        f.server.putStatus = 500

        f.registration().start()
        runCurrent()
        TestResources.waitUntil { f.server.calls.size == 1 }
        runCurrent()

        assertNull(f.remembered())
    }

    @Test
    fun cancelarASessaoNoMeioDoRegistroNaoGravaOToken() = runTest {
        val f = Fixture(this)
        val gate = CompletableDeferred<Unit>()
        f.server.hold = gate
        val job = Job()

        f.registration(CoroutineScope(f.dispatcher + job)).start()
        runCurrent()
        TestResources.waitUntil { f.server.calls.size == 1 }
        job.cancel()
        gate.complete(Unit)
        runCurrent()

        assertNull(f.remembered())
    }

    @Test
    fun semSessaoNaoRegistra() = runTest {
        val f = Fixture(this)
        f.hasSession = false

        f.registration().start()
        runCurrent()
        f.tokens.published.emit("tok-2")
        runCurrent()

        f.assertNoMoreThan(0)
        assertNull(f.remembered())
    }

    @Test
    fun semPushNoAparelhoNadaEChamado() = runTest {
        val f = Fixture(this, FakeTokens(available = false))
        val registration = f.registration()

        registration.start()
        runCurrent()
        registration.unregister()
        registration.registerAgain()
        runCurrent()

        f.assertNoMoreThan(0)
    }

    @Test
    fun comecarDuasVezesNaMesmaSessaoNaoRegistraDuasVezes() = runTest {
        val f = Fixture(this)
        val registration = f.registration()

        registration.start()
        registration.start()
        runCurrent()
        TestResources.waitUntil { f.server.calls.size == 1 }
        f.tokens.rotate("tok-2")
        runCurrent()
        TestResources.waitUntil { f.server.calls.size == 2 }

        assertEquals(listOf("tok-1", "tok-2"), f.server.puts)
    }

    // Tirar o registro

    @Test
    fun aoSairTiraOTokenRegistradoEOdeAgoraSemRepetirEEsqueceOQueLembrava() = runTest {
        val f = Fixture(this)
        val registration = f.registration()
        registration.start()
        runCurrent()
        TestResources.waitUntil { f.server.calls.size == 1 }
        runCurrent()
        // O FCM trocou o token e o registro do novo ainda não aconteceu: o servidor guarda o velho, e o novo pode ter ido sem resposta.
        f.tokens.token = "tok-2"

        registration.unregister()

        assertEquals(listOf("tok-1", "tok-2"), f.server.deletes)
        assertEquals("Auth=jwt", f.server.calls.last().cookie)
        assertNull(f.remembered())
    }

    @Test
    fun aoSairComOMesmoTokenTiraUmaVezSo() = runTest {
        val f = Fixture(this)
        val registration = f.registration()
        registration.start()
        runCurrent()
        TestResources.waitUntil { f.server.calls.size == 1 }
        runCurrent()

        registration.unregister()

        assertEquals(listOf("tok-1"), f.server.deletes)
    }

    @Test
    fun aoSairSemRegistroLembradoTiraOTokenDeAgora() = runTest {
        val f = Fixture(this)

        f.registration().unregister()

        assertEquals(listOf("tok-1"), f.server.deletes)
    }

    @Test
    fun aoSairUm404OuUm401SaoCaladosESaidaSegueAteOFim() = runTest {
        val f = Fixture(this)
        val registration = f.registration()
        registration.start()
        runCurrent()
        TestResources.waitUntil { f.server.calls.size == 1 }
        runCurrent()
        f.tokens.token = "tok-2"
        f.server.deleteStatus = 404

        registration.unregister()
        f.server.deleteStatus = 401
        registration.unregister()

        // 404 e 401 não abortam a saída: a primeira tentou os dois tokens e a segunda, sem registro lembrado, só o de agora.
        assertEquals(listOf("tok-1", "tok-2", "tok-2"), f.server.deletes)
        assertNull(f.remembered())
    }

    @Test
    fun aoSairSemSessaoNaoChamaOServidor() = runTest {
        val f = Fixture(this)
        f.hasSession = false

        f.registration().unregister()

        f.assertNoMoreThan(0)
    }

    @Test
    fun depoisDeSairUmaTrocaDeTokenNaoRegistraDeNovo() = runTest {
        val f = Fixture(this)
        val registration = f.registration()
        registration.start()
        runCurrent()
        TestResources.waitUntil { f.server.calls.size == 1 }
        runCurrent()
        registration.unregister()

        f.tokens.rotate("tok-2")
        runCurrent()

        f.assertNoMoreThan(2)
        assertEquals(listOf("tok-1"), f.server.puts)
        assertEquals(listOf("tok-1"), f.server.deletes)
    }

    // Registrar de novo

    @Test
    fun registrarDeNovoEsqueceOQueLembravaEChamaUmaVez() = runTest {
        val f = Fixture(this)
        val registration = f.registration()
        registration.start()
        runCurrent()
        TestResources.waitUntil { f.server.calls.size == 1 }
        runCurrent()

        registration.registerAgain()
        runCurrent()
        TestResources.waitUntil { f.server.calls.size == 2 }
        runCurrent()

        assertEquals(listOf("tok-1", "tok-1"), f.server.puts)
        assertEquals("tok-1", f.remembered())
    }

    @Test
    fun registrarDeNovoEmSessaoQueNaoComecouSoEsquece() = runTest {
        val f = Fixture(this)
        f.meta.put("push.token", "tok-1")

        f.registration().registerAgain()
        runCurrent()

        assertNull(f.remembered())
        f.assertNoMoreThan(0)
    }
}
