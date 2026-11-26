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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
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
    var serverName: String? = null
    var serverIcon: String? = null
    var serverDown = false

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
            "/server" ->
                if (serverDown) json("""{"type":"t","title":"Erro","status":503,"detail":"fora do ar"}""", HttpStatusCode.ServiceUnavailable)
                else {
                    val icon = serverIcon?.let { ""","icon_blob":"$it"""" }.orEmpty()
                    json("""{"id":"s","name":"${serverName ?: "Servidor $host"}","public":${serverPassword == null},"member_count":2,"role_count":3$icon}""")
                }
            "/roles" -> json("""{"roles":[]}""")
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
        first.setLabel(ana.account.id, "  Trabalho ")
        first.setColor(ana.account.id, 0xFF112233.toInt())
        first.flushAppearance()
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

    @Test
    fun rotuloDigitadoEmPartesFicaInteiroMesmoComATelaJaFechada() = runTest {
        val f = Fixture(this)
        val manager = f.manager(this).also { it.load() }
        val ana = manager.begin(manager.probe("papo.test")).login("ana", "Senha@123")
        val ui = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

        // Uma tecla por chamada, como a tela; o escopo dela é cancelado antes de a última gravação rodar.
        ui.launch { manager.setLabel(ana.account.id, "T") }
        runCurrent()
        ui.launch { manager.setLabel(ana.account.id, "Trabalho") }
        runCurrent()
        ui.cancel()
        runCurrent()

        assertEquals("Trabalho", manager.accounts.value.single().label)
    }

    @Test
    fun variasTeclasSeguidasGravamSoAUltimaNaOrdemCerta() = runTest {
        val f = Fixture(this)
        val manager = f.manager(this).also { it.load() }
        val ana = manager.begin(manager.probe("papo.test")).login("ana", "Senha@123")

        "Trabalho".indices.forEach { manager.setLabel(ana.account.id, "Trabalho".take(it + 1)) }
        runCurrent()

        assertEquals("Trabalho", manager.accounts.value.single().label)
    }

    @Test
    fun trocarACorLogoAposDigitarNaoVoltaORotulo() = runTest {
        val f = Fixture(this)
        val manager = f.manager(this).also { it.load() }
        val ana = manager.begin(manager.probe("papo.test")).login("ana", "Senha@123")

        manager.setLabel(ana.account.id, "Trabalho")
        manager.setColor(ana.account.id, 0xFF112233.toInt())
        runCurrent()

        val account = manager.accounts.value.single()
        assertEquals("Trabalho", account.label)
        assertEquals(0xFF112233.toInt(), account.color)
    }

    @Test
    fun tirarACorNaoMudaORotuloENemOContrario() = runTest {
        val f = Fixture(this)
        val manager = f.manager(this).also { it.load() }
        val ana = manager.begin(manager.probe("papo.test")).login("ana", "Senha@123")
        manager.setLabel(ana.account.id, "Casa")
        manager.setColor(ana.account.id, 0xFF112233.toInt())
        runCurrent()

        manager.setColor(ana.account.id, null)
        runCurrent()
        assertEquals("Casa", manager.accounts.value.single().label)
        assertNull(manager.accounts.value.single().color)

        manager.setLabel(ana.account.id, "   ") // vazio de verdade: volta ao padrão
        runCurrent()
        assertNull(manager.accounts.value.single().label)
    }

    @Test
    fun rotuloECorDeUmaContaNaoMudamOutra() = runTest {
        val f = Fixture(this)
        val manager = f.manager(this).also { it.load() }
        val ana = manager.begin(manager.probe("papo.test")).login("ana", "Senha@123")
        val bia = manager.begin(manager.probe("papo.test")).login("bia", "Senha@456")

        manager.setLabel(ana.account.id, "Ana")
        manager.setLabel(bia.account.id, "Bia")
        manager.setColor(bia.account.id, 0xFF445566.toInt())
        runCurrent()

        val byId = manager.accounts.value.associateBy { it.id }
        assertEquals("Ana", byId.getValue(ana.account.id).label)
        assertNull(byId.getValue(ana.account.id).color)
        assertEquals("Bia", byId.getValue(bia.account.id).label)
        assertEquals(0xFF445566.toInt(), byId.getValue(bia.account.id).color)
    }

    @Test
    fun flushGravaOQueAindaEsperaOEscritor() = runTest {
        val f = Fixture(this)
        val manager = f.manager(this).also { it.load() }
        val ana = manager.begin(manager.probe("papo.test")).login("ana", "Senha@123")

        manager.setLabel(ana.account.id, "Trabalho")
        manager.setColor(ana.account.id, 0xFF112233.toInt())
        manager.flushAppearance() // sem o escritor ter rodado

        val saved = f.store.get(ana.account.id)!!
        assertEquals("Trabalho", saved.label)
        assertEquals(0xFF112233.toInt(), saved.color)
    }

    @Test
    fun contaRemovidaNaoGuardaRotuloPendente() = runTest {
        val f = Fixture(this)
        val manager = f.manager(this).also { it.load() }
        val ana = manager.begin(manager.probe("papo.test")).login("ana", "Senha@123")

        manager.setLabel(ana.account.id, "Trabalho")
        manager.setColor(ana.account.id, 1)
        manager.remove(ana.account.id)
        manager.flushAppearance()
        runCurrent()

        assertNull(f.store.get(ana.account.id))
    }

    @Test
    fun atualizarOServidorGravaNomeEIconeSemChamarWhoami() = runTest {
        val f = Fixture(this)
        val manager = f.manager(this).also { it.load() }
        val ana = manager.begin(manager.probe("papo.test")).login("ana", "Senha@123")
        runCurrent()
        f.backend.serverName = "Renomeado"
        f.backend.serverIcon = Base64.encode(byteArrayOf(1, 2, 3))
        val before = f.backend.engine.requestHistory.size

        val info = manager.refreshServer(ana.account.id)
        runCurrent()

        assertEquals("Renomeado", info!!.name)
        assertEquals(3, info.roleCount)
        val saved = manager.accounts.value.single()
        assertEquals("Renomeado", saved.serverName)
        assertEquals(listOf<Byte>(1, 2, 3), saved.serverIcon!!.toList())
        val paths = f.backend.engine.requestHistory.drop(before).map { it.url.encodedPath }
        assertEquals(listOf("/server"), paths)
    }

    @Test
    fun atualizarOServidorSemRedeLevantaOErroEMantemOQueEstavaGravado() = runTest {
        val f = Fixture(this)
        val manager = f.manager(this).also { it.load() }
        val ana = manager.begin(manager.probe("papo.test")).login("ana", "Senha@123")
        runCurrent()
        f.backend.serverDown = true

        assertFailsWith<ApiException> { manager.refreshServer(ana.account.id) }
        runCurrent()

        assertEquals("Servidor papo.test", manager.accounts.value.single().serverName)
    }

    @Test
    fun atualizarOServidorDeContaDesconhecidaNaoFazPedido() = runTest {
        val f = Fixture(this)
        val manager = f.manager(this).also { it.load() }

        assertNull(manager.refreshServer("nao-existe"))
        assertEquals(0, f.backend.engine.requestHistory.size)
    }

    @Test
    fun atualizarOServidorSemNomeUsaOEndereco() = runTest {
        val f = Fixture(this)
        val manager = f.manager(this).also { it.load() }
        val ana = manager.begin(manager.probe("papo.test")).login("ana", "Senha@123")
        f.backend.serverName = ""

        manager.refreshServer(ana.account.id)
        runCurrent()

        assertEquals("papo.test", manager.accounts.value.single().serverName)
    }
}
