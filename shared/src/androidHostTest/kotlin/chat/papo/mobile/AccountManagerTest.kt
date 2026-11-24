package chat.papo.mobile

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import chat.papo.mobile.account.AccountManager
import chat.papo.mobile.account.AccountStore
import chat.papo.mobile.account.AddAccountResult
import chat.papo.mobile.account.InvalidServerUrl
import chat.papo.mobile.core.AppDispatchers
import chat.papo.mobile.data.api.ApiException
import chat.papo.mobile.data.db.registry.RegistryDb
import chat.papo.mobile.security.AppPrefs
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Backend falso: usuários por servidor, senha de servidor opcional, sessão por cookie. */
internal class FakeBackend(private val serverPassword: String? = null) {
    val users = mutableMapOf("ana" to "Senha@123", "bia" to "Senha@456")
    val loggedOut = mutableListOf<String>()

    val engine = MockEngine { request ->
        val host = request.url.host
        val cookie = request.headers[HttpHeaders.Cookie]?.removePrefix("Auth=")
        val body = request.body.toByteArray().decodeToString()
        fun field(name: String) = Regex("\"$name\":\"([^\"]*)\"").find(body)?.groupValues?.get(1)
        fun json(text: String, status: HttpStatusCode = HttpStatusCode.OK, setCookie: String? = null) = respond(
            text,
            status,
            if (setCookie != null) {
                headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.SetCookie to listOf("Auth=$setCookie; Path=/; HttpOnly"))
            } else {
                headersOf(HttpHeaders.ContentType, "application/json")
            },
        )
        fun denied(detail: String) = json("""{"type":"t","title":"Não autorizado","status":401,"detail":"$detail"}""", HttpStatusCode.Unauthorized)

        when (request.url.encodedPath) {
            "/server" -> json("""{"id":"s","name":"Servidor $host","public":${serverPassword == null},"member_count":2}""")
            "/auth/login_server" ->
                if (field("server_password") == serverPassword) respond("", HttpStatusCode.NoContent, headersOf(HttpHeaders.SetCookie, "Auth=temp; Max-Age=1800"))
                else denied("Senha do servidor incorreta")
            "/auth/login" -> {
                val user = field("username")
                when {
                    serverPassword != null && cookie != "temp" -> denied("Servidor não público")
                    users[user] != field("password") -> denied("Credenciais inválidas")
                    else -> json("""{"user":{"id":"id-$user","username":"$user"}}""", setCookie = "jwt-$host-$user")
                }
            }
            "/auth/register" -> {
                val user = field("username").orEmpty()
                when {
                    serverPassword != null && cookie != "temp" -> denied("Servidor não público")
                    user in users -> json("""{"type":"t","title":"Conflito","status":409,"detail":"Username já existe"}""", HttpStatusCode.Conflict)
                    else -> {
                        users[user] = field("password").orEmpty()
                        // Como no backend real: 201 sem cookie; é preciso entrar em seguida.
                        json("""{"id":"id-$user","username":"$user"}""", HttpStatusCode.Created)
                    }
                }
            }
            "/auth/whoami" -> {
                val user = cookie?.substringAfterLast('-')
                if (cookie?.startsWith("jwt-$host-") == true) json("""{"id":"id-$user","username":"$user","nickname":"Apelido $user"}""")
                else denied("Token ausente")
            }
            "/auth/logout" -> {
                loggedOut += cookie.orEmpty()
                respond("", HttpStatusCode.NoContent, headersOf(HttpHeaders.SetCookie, "Auth=; Max-Age=0"))
            }
            else -> denied("rota desconhecida")
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AccountManagerTest {
    private class Prefs : AppPrefs {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String) = map[key]
        override fun putString(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    private class Fixture(scope: TestScope, val backend: FakeBackend = FakeBackend()) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { RegistryDb.Schema.create(it) }
        val prefs = Prefs()
        val deleted = mutableListOf<String>()
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        private val dispatchers = AppDispatchers(dispatcher, dispatcher, dispatcher)
        val store = AccountStore(RegistryDb(driver), dispatcher)

        fun manager(scope: TestScope) = AccountManager(
            store = store,
            engine = backend.engine,
            prefs = prefs,
            dispatchers = dispatchers,
            scope = scope.backgroundScope,
            openResources = { TestResources.inMemory(dispatcher) },
            deleteLocalData = { deleted += it },
        ).also { it.setForeground(false) } // sem tempo real: o motor falso não fala WebSocket
    }

    @Test
    fun mesmoServidorComDuasContasGeraDuasEntradasIsoladas() = runTest {
        val f = Fixture(this)
        val manager = f.manager(this).also { it.load() }

        val probe = manager.probe("Papo.Test/")
        assertEquals("https://papo.test", probe.url.value)
        val ana = manager.begin(probe).login("ana", "Senha@123")
        val bia = manager.begin(manager.probe("https://papo.test")).login("bia", "Senha@456")
        runCurrent()

        assertIs<AddAccountResult.Added>(ana)
        assertIs<AddAccountResult.Added>(bia)
        assertNotEquals(ana.account.id, bia.account.id)
        assertEquals(listOf("ana @ papo.test", "bia @ papo.test"), manager.accounts.value.map { it.handle })
        assertEquals(bia.account.id, manager.activeId.value)

        // Cada sessão usa o próprio cookie.
        assertEquals("id-ana", manager.session(ana.account.id)!!.auth.whoami().id)
        assertEquals("id-bia", manager.session(bia.account.id)!!.auth.whoami().id)
        assertEquals("Auth=jwt-papo.test-ana", manager.session(ana.account.id)!!.client.cookieHeader())
    }

    @Test
    fun entrarDeNovoComAMesmaContaRenovaASessaoSemDuplicar() = runTest {
        val f = Fixture(this)
        val manager = f.manager(this).also { it.load() }

        val first = manager.begin(manager.probe("papo.test")).login("ana", "Senha@123")
        val again = manager.begin(manager.probe("https://PAPO.test:443/")).login("ana", "Senha@123")
        runCurrent()

        assertIs<AddAccountResult.Updated>(again)
        assertEquals(first.account.id, again.account.id)
        assertEquals(1, manager.accounts.value.size)
    }

    @Test
    fun mesmoUsuarioEmServidoresDiferentesSaoContasDiferentes() = runTest {
        val f = Fixture(this)
        val manager = f.manager(this).also { it.load() }

        val a = manager.begin(manager.probe("um.test")).login("ana", "Senha@123")
        val b = manager.begin(manager.probe("dois.test")).login("ana", "Senha@123")
        runCurrent()

        assertNotEquals(a.account.id, b.account.id)
        assertEquals(listOf("Servidor um.test", "Servidor dois.test"), manager.accounts.value.map { it.serverName })
    }

    @Test
    fun servidorNaoPublicoExigeASenhaDoServidorAntesDoLogin() = runTest {
        val f = Fixture(this, FakeBackend(serverPassword = "portaria"))
        val manager = f.manager(this).also { it.load() }

        val pending = manager.begin(manager.probe("papo.test"))
        assertTrue(pending.needsServerPassword)
        assertFailsWith<ApiException> { pending.login("ana", "Senha@123") }
        assertFailsWith<ApiException> { pending.submitServerPassword("errada") }
        assertTrue(pending.needsServerPassword)

        pending.submitServerPassword("portaria")
        assertTrue(!pending.needsServerPassword)
        val result = pending.login("ana", "Senha@123")
        runCurrent()
        // O cookie temporário do servidor foi substituído pelo da sessão.
        assertEquals("Auth=jwt-papo.test-ana", manager.session(result.account.id)!!.client.cookieHeader())
    }

    @Test
    fun contasECookiesSobrevivemAoReinicio() = runTest {
        val f = Fixture(this)
        val first = f.manager(this).also { it.load() }
        val ana = first.begin(first.probe("papo.test")).login("ana", "Senha@123")
        first.setAppearance(ana.account.id, "  Trabalho ", 0xFF112233.toInt())
        first.close()

        val second = f.manager(this).also { it.load() }
        runCurrent()
        val restored = second.accounts.value.single()
        assertEquals("Trabalho", restored.label)
        assertEquals("Trabalho", restored.title)
        assertEquals(0xFF112233.toInt(), restored.color)
        assertEquals(ana.account.id, second.activeId.value)
        assertEquals("id-ana", second.session(restored.id)!!.auth.whoami().id)
    }

    @Test
    fun removerContaFazLogoutEApagaOsDadosLocais() = runTest {
        val f = Fixture(this)
        val manager = f.manager(this).also { it.load() }
        val ana = manager.begin(manager.probe("papo.test")).login("ana", "Senha@123")
        val bia = manager.begin(manager.probe("papo.test")).login("bia", "Senha@456")

        manager.remove(bia.account.id)
        runCurrent()

        assertEquals(listOf("jwt-papo.test-bia"), f.backend.loggedOut)
        assertEquals(listOf(bia.account.id), f.deleted)
        assertEquals(listOf(ana.account.id), manager.accounts.value.map { it.id })
        assertNull(manager.session(bia.account.id))
        assertEquals(ana.account.id, manager.activeId.value)
    }

    @Test
    fun enderecoInvalidoNemChegaARede() = runTest {
        val f = Fixture(this)
        val manager = f.manager(this)
        assertFailsWith<InvalidServerUrl> { manager.probe("ftp://x") }
        assertEquals(0, f.backend.engine.requestHistory.size)
    }
}
