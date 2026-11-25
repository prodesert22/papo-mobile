package chat.papo.mobile

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import chat.papo.mobile.account.AccountManager
import chat.papo.mobile.account.AccountStore
import chat.papo.mobile.chat.OpenConversation
import chat.papo.mobile.chat.SwitchEntry
import chat.papo.mobile.chat.SwitchKind
import chat.papo.mobile.core.AppDispatchers
import chat.papo.mobile.data.db.account.AccountDb
import chat.papo.mobile.data.db.registry.RegistryDb
import chat.papo.mobile.data.ws.WsEvent
import chat.papo.mobile.security.AppPrefs
import chat.papo.mobile.ui.switcher.QuickSwitcherViewModel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A troca rápida só lê o que as sessões já têm no banco. O motor HTTP simulado responde em outra thread, então
 * nada aqui avança o relógio virtual esperando por uma resposta: cada teste espera o estado do view model chegar
 * ao ponto que interessa e, no fim, confere que o motor não recebeu nenhum pedido a mais.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuickSwitcherViewModelTest {
    private class Prefs : AppPrefs {
        private val map = mutableMapOf<String, String>()
        override fun getString(key: String) = map[key]
        override fun putString(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    private class Fixture(private val scope: TestScope) {
        val backend = FakeBackend()
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        private val drivers = mutableMapOf<String, SqlDriver>()
        val store = AccountStore(RegistryDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { RegistryDb.Schema.create(it) }), dispatcher)

        /** Um gerente sobre o mesmo registro; as contas gravadas só ganham sessão quando ele chama `load()`. */
        fun newManager() = AccountManager(
            store = store,
            engine = backend.engine,
            prefs = Prefs(),
            dispatchers = AppDispatchers(dispatcher, dispatcher, dispatcher),
            scope = scope.backgroundScope,
            openResources = { id -> TestResources.inMemory(dispatcher).also { drivers[id] = it.driver } },
            deleteLocalData = {},
        ).also { it.setForeground(false) } // sem tempo real: o motor falso não fala WebSocket

        val manager = newManager()

        /** Entra com [user] no servidor [host]; o mesmo usuário em dois servidores tem o mesmo id de usuário. */
        suspend fun login(host: String, user: String = "ana", password: String = "Senha@123"): String =
            manager.begin(manager.probe(host)).login(user, password).account.id

        fun db(accountId: String) = AccountDb(drivers.getValue(accountId))

        fun channel(
            accountId: String,
            id: String,
            name: String,
            lastAt: String? = null,
            lastText: String? = null,
            unread: Boolean = false,
            type: String = "text",
        ) = db(accountId).chatQueries.upsertChannel(
            id = id,
            name = name,
            type = type,
            position = 0,
            topic = null,
            parent_id = null,
            last_message_id = if (lastAt != null || unread) "m-$id" else null,
            last_message_at = lastAt,
            last_message_text = lastText,
            last_message_author = null,
            last_read_message = if (unread) "antiga" else "m-$id",
            notification_settings = null,
        )
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(f: Fixture, open: OpenConversation? = null, manager: AccountManager = f.manager): QuickSwitcherViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return QuickSwitcherViewModel(manager, open)
    }

    private fun pairs(entries: List<SwitchEntry>?) = entries.orEmpty().map { it.accountId to it.channelId }

    @Test
    fun reuneAsConversasDeDuasContasSemFazerNenhumaRequisicao() = runTest {
        val f = Fixture(this)
        f.manager.load()
        val um = f.login("um.test")
        val dois = f.login("dois.test")
        // O mesmo usuário e o mesmo id de canal em dois servidores: nada disso é a mesma coisa.
        f.channel(um, "c1", "geral", lastAt = "2026-01-01T10:00:00Z")
        f.channel(dois, "c1", "geral", lastAt = "2026-01-01T09:00:00Z")
        f.channel(dois, "v1", "Sala", type = "voice")
        f.channel(dois, "cat", "Categoria", type = "category")
        f.db(um).chatQueries.upsertUser("u2", "bia", "Bia B.", null, null, null, false, null)
        f.db(um).chatQueries.upsertDm("d1", "u2", "2026-01-01T00:00:00Z", "m-d1", "2026-01-01T08:00:00Z", "oi", null, "antiga", 2)
        runCurrent()
        val requestsBefore = f.backend.engine.requestHistory.size
        assertTrue(requestsBefore > 0) // o login fez pedidos; é a partir daqui que não pode haver nenhum

        val vm = viewModel(f)
        val first = vm.entries.first { it?.size == 4 }.orEmpty()

        // A conversa direta tem mensagens não lidas e vai à frente; depois vem a atividade mais recente.
        assertEquals(listOf(um to "d1", um to "c1", dois to "c1", dois to "v1"), pairs(first))
        assertEquals(listOf(SwitchKind.Dm, SwitchKind.Text, SwitchKind.Text, SwitchKind.Voice), first.map { it.kind })
        assertEquals(listOf("Bia B.", "geral", "geral", "Sala"), first.map { it.name })
        assertEquals(first.map { it.accountId }.toSet(), setOf(um, dois))
        assertNotEquals(first[1].key, first[2].key)

        // Chega uma conversa direta com mensagem nova na outra conta: o ranking se refaz sozinho, ainda sem ir à rede.
        f.db(dois).chatQueries.upsertUser("u3", "caio", null, null, null, null, false, null)
        runCurrent()
        f.db(dois).chatQueries.upsertDm("d9", "u3", "2026-01-01T00:00:00Z", "m-d9", "2026-01-01T11:00:00Z", "oi", null, "antiga", 1)
        val updated = vm.entries.first { list -> list?.any { it.mention && it.accountId == dois } == true }.orEmpty()
        assertEquals(listOf(dois to "d9", um to "d1", um to "c1", dois to "c1", dois to "v1"), pairs(updated))
        assertEquals("caio", updated.first().name)
        assertEquals(1, updated.first().unreadCount)

        assertEquals(requestsBefore, f.backend.engine.requestHistory.size)
    }

    @Test
    fun aConversaAbertaSaiSoDaContaDela() = runTest {
        val f = Fixture(this)
        f.manager.load()
        val um = f.login("um.test")
        val dois = f.login("dois.test")
        f.channel(um, "c1", "geral")
        f.channel(dois, "c1", "geral")
        runCurrent()

        val vm = viewModel(f, open = OpenConversation(um, "c1"))

        assertEquals(listOf(dois to "c1"), pairs(vm.entries.first { it != null && it.isNotEmpty() }))
    }

    @Test
    fun contaComASessaoExpiradaNaoEntraEVoltaQuandoEntraDeNovo() = runTest {
        val f = Fixture(this)
        f.manager.load()
        val um = f.login("um.test")
        val dois = f.login("dois.test")
        f.channel(um, "c1", "geral")
        f.channel(dois, "c2", "outro")
        runCurrent()
        val vm = viewModel(f)
        assertEquals(setOf(um to "c1", dois to "c2"), pairs(vm.entries.first { it?.size == 2 }).toSet())

        f.store.setCookie(dois, null)

        assertEquals(listOf(um to "c1"), pairs(vm.entries.first { it?.size == 1 }))

        f.store.setCookie(dois, "jwt-dois.test-ana")

        assertEquals(setOf(um to "c1", dois to "c2"), pairs(vm.entries.first { it?.size == 2 }).toSet())
    }

    @Test
    fun contaGravadaQueAindaNaoTemSessaoNaoEntraEEntraQuandoASessaoNasce() = runTest {
        val f = Fixture(this)
        f.manager.load()
        val um = f.login("um.test")
        // Como depois de reabrir o app: a conta já está no registro, mas a sessão só nasce no `load()`.
        val reopened = f.newManager()
        runCurrent()
        assertEquals(listOf(um), reopened.accounts.value.map { it.id })
        assertTrue(reopened.sessionMap.value.isEmpty())
        val vm = viewModel(f, manager = reopened)

        assertEquals(emptyList<SwitchEntry>(), vm.entries.first { it != null })

        reopened.load()
        f.channel(um, "c1", "geral")

        assertEquals(listOf(um to "c1"), pairs(vm.entries.first { it?.size == 1 }))
    }

    @Test
    fun semContasAListaFicaVaziaENaoNulaEContaNovaEntraERemovidaSai() = runTest {
        val f = Fixture(this)
        f.manager.load()
        val vm = viewModel(f)

        // Sem nenhuma conta já se sabe que não há conversas: a tela não fica esperando.
        assertEquals(emptyList<SwitchEntry>(), vm.entries.first { it != null })

        val um = f.login("um.test")
        f.channel(um, "c1", "geral")
        assertEquals(listOf(um to "c1"), pairs(vm.entries.first { it?.size == 1 }))

        f.manager.remove(um)

        assertEquals(emptyList<SwitchEntry>(), vm.entries.first { it?.isEmpty() == true })
    }

    @Test
    fun oCanalGuardaOMomentoDaUltimaMensagemVindoDoServidorEDoTempoReal() = runTest {
        val engine = MockEngine { request ->
            fun json(body: String) = respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            when (request.url.encodedPath) {
                "/channels" -> json(
                    """{"channels":[
                        {"id":"c1","name":"geral","type":"text","position":1,
                         "last_message":{"id":"m1","content":"oi","author_username":"bia","created_at":"2026-01-01T10:00:00Z"},"last_read_message":"m1"},
                        {"id":"c2","name":"vazio","type":"text","position":2}]}""",
                )
                "/dms" -> json("""{"dms":[]}""")
                "/users" -> json("""{"users":[],"has_more":false}""")
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val chat = TestResources.session(engine, this).chat
        chat.sync()
        runCurrent()

        assertEquals("2026-01-01T10:00:00Z", chat.channels.value.first { it.id == "c1" }.lastMessageAt)
        assertNull(chat.channels.value.first { it.id == "c2" }.lastMessageAt)

        chat.handle(WsEvent.MessageCreated("m2", "c1", "u2", content = "de novo", createdAt = "2026-01-01T11:30:00Z"))
        runCurrent()

        assertEquals("2026-01-01T11:30:00Z", chat.channels.value.first { it.id == "c1" }.lastMessageAt)
    }
}
