package chat.papo.mobile

import app.cash.sqldelight.db.SqlDriver
import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.chat.AccessStore
import chat.papo.mobile.chat.ChannelGrant
import chat.papo.mobile.chat.PermissionSnapshot
import chat.papo.mobile.chat.RoleFlag
import chat.papo.mobile.chat.RoleFlags
import chat.papo.mobile.data.api.AuthApi
import chat.papo.mobile.data.api.ChannelDto
import chat.papo.mobile.data.api.ChannelPermissionEntryDto
import chat.papo.mobile.data.api.ChannelPermissionsDto
import chat.papo.mobile.data.api.ChatApi
import chat.papo.mobile.data.api.MemoryCookieStore
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.data.db.account.AccountDb
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** As fontes e a persistência do [AccessStore], com o motor HTTP simulado e o banco em memória. */
@OptIn(ExperimentalCoroutinesApi::class)
class AccessStoreTest {
    // O motor responde em outra thread: o que ele lê e anota precisa ser visível entre elas.
    private val requests = CopyOnWriteArrayList<String>()

    @Volatile private var serverBody = """{"id":"s","name":"papo","owner_id":"boss"}"""

    @Volatile private var serverStatus = HttpStatusCode.OK

    @Volatile private var rolesBody = """{"roles":[{"id":"mod","name":"Mod","permissions":{"pin_message":true}}]}"""

    @Volatile private var rolesStatus = HttpStatusCode.OK

    @Volatile private var serverGate: CompletableDeferred<Unit>? = null
    private val serverStarted = CompletableDeferred<Unit>()

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        requests += "${request.method.value} $path"
        fun json(text: String, status: HttpStatusCode) =
            respond(text, status, headersOf(HttpHeaders.ContentType, if (status.value >= 400) "application/problem+json" else "application/json"))
        when (path) {
            "/server" -> {
                serverStarted.complete(Unit)
                serverGate?.await()
                json(serverBody, serverStatus)
            }
            "/roles" -> json(rolesBody, rolesStatus)
            else -> respond("", HttpStatusCode.NotFound)
        }
    }

    private class Fixture(
        scope: TestScope,
        engine: MockEngine,
        val driver: SqlDriver = TestResources.driver(),
        withOwnerSource: Boolean = true,
    ) {
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        private val client = PapoClient(engine, ServerUrl("https://papo.test"), MemoryCookieStore("jwt"))
        val db = AccountDb(driver)
        val store = AccessStore(
            meta = db.metaQueries,
            api = ChatApi(client),
            scope = scope.backgroundScope,
            io = dispatcher,
            server = if (withOwnerSource) AuthApi(client)::server else null,
            timeSource = scope.testScheduler.timeSource,
            forbiddenInterval = 10.seconds,
        )

        fun stored(): String? = db.metaQueries.get(AccessStore.KEY).executeAsOneOrNull()
    }

    private fun entry(role: String, read: Boolean = false, send: Boolean = false, delete: Boolean = false, voice: Boolean = false) =
        ChannelPermissionEntryDto(role, permissions = ChannelPermissionsDto(read, send, delete, voice))

    private fun channel(id: String, vararg entries: ChannelPermissionEntryDto) = ChannelDto(id = id, permissions = entries.toList())

    // Dono e cargos

    @Test
    fun refreshMetaGuardaODonoEOsCargosComCadaFlag() = runTest {
        val wire = mapOf(
            RoleFlag.ManageServer to "manage_server",
            RoleFlag.ManageChannels to "manage_channels",
            RoleFlag.ManageRoles to "manage_roles",
            RoleFlag.ManageWebhooks to "manage_webhooks",
            RoleFlag.BanMembers to "ban_members",
            RoleFlag.PinMessage to "pin_message",
            RoleFlag.EveryoneMessage to "everyone_message",
            RoleFlag.SendAttachment to "send_attachment",
        )
        val single = RoleFlag.entries.joinToString(",") { """{"id":"${it.name}","permissions":{"${wire.getValue(it)}":true}}""" }
        val all = wire.values.joinToString(",") { "\"$it\":true" }
        rolesBody = """{"roles":[$single,{"id":"todas","permissions":{$all}},{"id":"nenhuma","permissions":{}}]}"""
        val f = Fixture(this, engine)

        f.store.refreshMeta()

        val snapshot = f.store.snapshot.value
        assertEquals("boss", snapshot.ownerId)
        assertTrue(snapshot.ownerKnown)
        val expected = RoleFlag.entries.associate { it.name to RoleFlags(setOf(it)) } +
            ("todas" to RoleFlags(RoleFlag.entries.toSet())) + ("nenhuma" to RoleFlags())
        assertEquals(expected, snapshot.roles)
        assertEquals(setOf("GET /server", "GET /roles"), requests.toSet())
    }

    @Test
    fun servidorSemDonoFicaConhecidoSemDonoETextoVazioTambem() = runTest {
        val f = Fixture(this, engine)
        serverBody = """{"id":"s","name":"papo"}"""
        f.store.refreshMeta()
        assertTrue(f.store.snapshot.value.ownerKnown)
        assertNull(f.store.snapshot.value.ownerId)

        serverBody = """{"id":"s","name":"papo","owner_id":"u1"}"""
        f.store.refreshMeta()
        assertEquals("u1", f.store.snapshot.value.ownerId)

        serverBody = """{"id":"s","name":"papo","owner_id":""}"""
        f.store.refreshMeta()
        assertNull(f.store.snapshot.value.ownerId)
        assertTrue(f.store.snapshot.value.ownerKnown)
    }

    @Test
    fun semFonteDoDonoOsCargosChegamEOdonoContinuaDesconhecido() = runTest {
        val f = Fixture(this, engine, withOwnerSource = false)
        f.store.refreshMeta()
        assertFalse(f.store.snapshot.value.ownerKnown)
        assertEquals(setOf("mod"), f.store.snapshot.value.roles?.keys)
        assertFalse("GET /server" in requests)
    }

    @Test
    fun fonteQueFalhaMantemOUltimoValorSemLancar() = runTest {
        val f = Fixture(this, engine)
        f.store.refreshMeta()
        val before = f.store.snapshot.value

        rolesStatus = HttpStatusCode.InternalServerError
        serverStatus = HttpStatusCode.InternalServerError
        serverBody = """{"title":"erro","status":500}"""
        f.store.refreshMeta() // não lança
        assertEquals(before, f.store.snapshot.value)

        // Cada fonte falha sozinha: o dono novo entra mesmo com os cargos fora do ar.
        serverStatus = HttpStatusCode.OK
        serverBody = """{"id":"s","name":"papo","owner_id":"novo"}"""
        f.store.refreshMeta()
        assertEquals("novo", f.store.snapshot.value.ownerId)
        assertEquals(before.roles, f.store.snapshot.value.roles)

        // E o contrário: cargos novos entram com o dono fora do ar.
        serverStatus = HttpStatusCode.InternalServerError
        serverBody = """{"title":"erro","status":500}"""
        rolesStatus = HttpStatusCode.OK
        rolesBody = """{"roles":[]}"""
        f.store.refreshMeta()
        assertEquals("novo", f.store.snapshot.value.ownerId)
        assertEquals(emptyMap(), f.store.snapshot.value.roles)
    }

    @Test
    fun primeiraFalhaDeixaOSnapshotVazio() = runTest {
        rolesStatus = HttpStatusCode.InternalServerError
        serverStatus = HttpStatusCode.Forbidden
        serverBody = """{"title":"x","status":403}"""
        val f = Fixture(this, engine)
        f.store.refreshMeta()
        assertEquals(PermissionSnapshot.EMPTY, f.store.snapshot.value)
    }

    @Test
    fun cancelarORefreshMetaRepassaOCancelamentoENaoAplicaNada() = runTest {
        val gate = CompletableDeferred<Unit>()
        serverGate = gate
        val f = Fixture(this, engine)
        var reachedEnd = false
        var cancelled = false
        val job = launch {
            try {
                f.store.refreshMeta()
                reachedEnd = true
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }

        serverStarted.await() // o pedido do dono está no ar e não vai responder
        job.cancel()
        job.join()

        assertTrue(cancelled)
        assertFalse(reachedEnd)
        assertFalse(f.store.snapshot.value.ownerKnown)
    }

    // Canais

    @Test
    fun applyChannelsGuardaAsEntradasECanalAusenteDeixaDeTer() = runTest {
        val f = Fixture(this, engine)
        f.store.applyChannels(
            listOf(
                channel("aberto"),
                channel("fechado", entry("a", read = true, send = true), entry("b", delete = true, voice = true)),
            ),
        )
        assertEquals(
            mapOf(
                "aberto" to emptyList(),
                "fechado" to listOf(
                    ChannelGrant("a", read = true, send = true, delete = false, voice = false),
                    ChannelGrant("b", read = false, send = false, delete = true, voice = true),
                ),
            ),
            f.store.snapshot.value.grants,
        )

        f.store.applyChannels(listOf(channel("aberto")))
        assertEquals(setOf("aberto"), f.store.snapshot.value.grants?.keys)

        f.store.applyChannels(emptyList())
        assertEquals(emptyMap(), f.store.snapshot.value.grants) // conhecido e vazio, não desconhecido
    }

    // Cargos dos usuários

    @Test
    fun applyUsersCompletoTiraQuemNaoTemCargoEIncompletoGuardaTudo() = runTest {
        val f = Fixture(this, engine)
        val roles = mapOf("u1" to setOf("a"), "u2" to emptySet())

        f.store.applyUsers(roles, complete = false, since = f.store.beginUsersSync())
        assertEquals(roles, f.store.snapshot.value.userRoles)
        assertFalse(f.store.snapshot.value.usersComplete)

        f.store.applyUsers(roles, complete = true, since = f.store.beginUsersSync())
        assertEquals(mapOf("u1" to setOf("a")), f.store.snapshot.value.userRoles)
        assertTrue(f.store.snapshot.value.usersComplete)
    }

    @Test
    fun roleAddERoleRemoveAplicamNaHoraAoUsuarioConhecido() = runTest {
        rolesBody = """{"roles":[{"id":"a"},{"id":"b"}]}"""
        val f = Fixture(this, engine)
        f.store.refreshMeta()
        f.store.applyUsers(mapOf("u1" to setOf("a")), complete = true, since = f.store.beginUsersSync())

        f.store.roleAdded("u1", "b")
        assertEquals(setOf("a", "b"), f.store.snapshot.value.userRoles["u1"])

        f.store.roleRemoved("u1", "a")
        assertEquals(setOf("b"), f.store.snapshot.value.userRoles["u1"])

        // Com todas as páginas lidas, quem não aparece não tinha cargos: o evento vale para ele também.
        f.store.roleAdded("u9", "a")
        assertEquals(setOf("a"), f.store.snapshot.value.userRoles["u9"])
        assertEquals(1, requests.count { it == "GET /roles" }) // os dois cargos já estavam no catálogo
    }

    @Test
    fun roleAddSemSaberOsOutrosCargosDoUsuarioNaoEAplicado() = runTest {
        val f = Fixture(this, engine)
        f.store.applyUsers(mapOf("u1" to setOf("a")), complete = false, since = f.store.beginUsersSync())

        f.store.roleAdded("u2", "a") // u2 não está nas páginas lidas: o evento pareceria a lista inteira
        f.store.roleRemoved("u2", "a")

        assertNull(f.store.snapshot.value.userRoles["u2"])
        assertEquals(setOf("a"), f.store.snapshot.value.userRoles["u1"])
    }

    @Test
    fun paginaPedidaAntesDoEventoNaoDesfazOEvento() = runTest {
        val f = Fixture(this, engine)
        f.store.applyUsers(mapOf("u1" to setOf("a"), "u2" to setOf("a")), complete = true, since = f.store.beginUsersSync())

        val since = f.store.beginUsersSync() // a leitura de /users sai agora...
        f.store.roleAdded("u1", "b") // ...e o evento chega antes de ela voltar
        f.store.roleRemoved("u2", "a")
        f.store.applyUsers(mapOf("u1" to setOf("a"), "u2" to setOf("a"), "u3" to setOf("c")), complete = true, since = since)

        assertEquals(setOf("a", "b"), f.store.snapshot.value.userRoles["u1"])
        assertNull(f.store.snapshot.value.userRoles["u2"]) // saiu do último cargo: não consta (completo)
        assertEquals(setOf("c"), f.store.snapshot.value.userRoles["u3"]) // o resto da página vale

        // Uma leitura pedida depois do evento já o conhece, e o que ela diz vale.
        f.store.applyUsers(mapOf("u1" to setOf("a")), complete = true, since = f.store.beginUsersSync())
        assertEquals(mapOf("u1" to setOf("a")), f.store.snapshot.value.userRoles)
    }

    @Test
    fun leituraAntigaQueTerminaDepoisDeOutraNaoDesfazOEvento() = runTest {
        val f = Fixture(this, engine)
        f.store.applyUsers(mapOf("u1" to setOf("a")), complete = true, since = f.store.beginUsersSync())

        val slow = f.store.beginUsersSync() // uma leitura longa começa
        f.store.roleAdded("u1", "b")
        val quick = f.store.beginUsersSync() // o evento dispara outra, que termina primeiro
        f.store.applyUsers(mapOf("u1" to setOf("a", "b")), complete = true, since = quick)
        f.store.applyUsers(mapOf("u1" to setOf("a")), complete = true, since = slow) // a antiga chega por último

        assertEquals(setOf("a", "b"), f.store.snapshot.value.userRoles["u1"])
    }

    @Test
    fun leituraAntigaNaoTrazDeVoltaQuemPerdeuOUltimoCargoPorEvento() = runTest {
        val f = Fixture(this, engine)
        f.store.applyUsers(mapOf("u1" to setOf("a")), complete = true, since = f.store.beginUsersSync())

        val slow = f.store.beginUsersSync()
        f.store.roleRemoved("u1", "a")
        // A leitura que o evento disparou termina antes e, completa, tira u1 (sem cargos) da tabela.
        f.store.applyUsers(emptyMap(), complete = true, since = f.store.beginUsersSync())
        assertNull(f.store.snapshot.value.userRoles["u1"])

        f.store.applyUsers(mapOf("u1" to setOf("a")), complete = true, since = slow)
        assertNull(f.store.snapshot.value.userRoles["u1"])
    }

    @Test
    fun roleAddDeCargoForaDoCatalogoLeOsCargosUmaVezSo() = runTest {
        val f = Fixture(this, engine)
        f.store.refreshMeta()
        f.store.applyUsers(emptyMap(), complete = true, since = f.store.beginUsersSync())
        rolesBody = """{"roles":[{"id":"mod","permissions":{"pin_message":true}},{"id":"novo","permissions":{"everyone_message":true}}]}"""
        requests.clear()

        f.store.roleAdded("u1", "novo")
        f.store.roleAdded("u2", "novo")
        f.store.roleAdded("u3", "novo")
        f.store.snapshot.first { "novo" in it.roles.orEmpty() }
        runCurrent()

        assertEquals(RoleFlags(setOf(RoleFlag.EveryoneMessage)), f.store.snapshot.value.roles?.get("novo"))
        assertEquals(listOf("GET /roles"), requests.toList())
    }

    @Test
    fun roleAddSemCatalogoTentaLerOsCargosDeNovo() = runTest {
        val f = Fixture(this, engine)
        f.store.applyUsers(emptyMap(), complete = true, since = f.store.beginUsersSync())
        assertNull(f.store.snapshot.value.roles)

        f.store.roleAdded("u1", "mod")
        f.store.snapshot.first { it.roles != null }

        assertEquals(setOf("mod"), f.store.snapshot.value.roles?.keys)
    }

    // Persistência

    @Test
    fun osCamposTodosSobrevivemAReabrirOBanco() = runTest {
        rolesBody = """{"roles":[{"id":"a","permissions":{"pin_message":true,"send_attachment":true}},{"id":"b"}]}"""
        val f = Fixture(this, engine)
        f.store.refreshMeta()
        f.store.applyChannels(listOf(channel("aberto"), channel("fechado", entry("a", read = true, voice = true))))
        f.store.applyUsers(mapOf("u1" to setOf("a", "b")), complete = true, since = f.store.beginUsersSync())
        runCurrent()
        val saved = f.store.snapshot.value
        assertNotNull(f.stored())

        // Outro AccessStore sobre o mesmo banco, como depois de bloquear e desbloquear o app.
        val reopened = Fixture(this, engine, driver = f.driver)
        reopened.store.snapshot.first { it != PermissionSnapshot.EMPTY }

        assertEquals(saved, reopened.store.snapshot.value)
        assertEquals("boss", reopened.store.snapshot.value.ownerId)
        assertEquals(RoleFlags(setOf(RoleFlag.PinMessage, RoleFlag.SendAttachment)), reopened.store.snapshot.value.roles?.get("a"))
        assertEquals(setOf("a", "b"), reopened.store.snapshot.value.userRoles["u1"])
    }

    @Test
    fun bancoQueFalhaNaoDerrubaOStoreEOSnapshotEmMemoriaSegueValendo() = runTest {
        val f = Fixture(this, engine)
        f.driver.close() // ler o guardado e gravar o novo falham

        f.store.applyChannels(listOf(channel("c")))
        runCurrent()
        f.store.refreshMeta()
        runCurrent()

        assertEquals(setOf("c"), f.store.snapshot.value.grants?.keys)
        assertEquals("boss", f.store.snapshot.value.ownerId)
    }

    @Test
    fun snapshotVazioNaoEGravado() = runTest {
        val f = Fixture(this, engine)
        runCurrent()
        assertNull(f.stored())
    }

    @Test
    fun valorGuardadoEstragadoEIgnoradoESubstituidoNaProximaGravacao() = runTest {
        for (bad in listOf("{ isto não é json", "[1,2]", """{"roles":"x"}""")) {
            val driver = TestResources.driver()
            AccountDb(driver).metaQueries.put(AccessStore.KEY, bad)
            val f = Fixture(this, engine, driver = driver)
            runCurrent()
            assertEquals(PermissionSnapshot.EMPTY, f.store.snapshot.value, "valor: $bad")

            f.store.refreshMeta()
            runCurrent()
            val stored = f.stored()
            assertNotNull(stored)
            assertEquals(f.store.snapshot.value, AccessStore.decode(stored), "valor: $bad")
        }
    }

    @Test
    fun oQueChegouDaRedeAntesDeLerOBancoNaoEPerdidoParaOGuardado() = runTest {
        val driver = TestResources.driver()
        val old = PermissionSnapshot(ownerId = "velho", ownerKnown = true, grants = mapOf("c" to emptyList()))
        AccountDb(driver).metaQueries.put(AccessStore.KEY, AccessStore.encode(old))
        val f = Fixture(this, engine, driver = driver)

        // Ainda não deu a vez do escopo ler o banco, e uma resposta já chegou.
        f.store.applyChannels(listOf(channel("novo")))
        runCurrent()

        assertEquals(setOf("novo"), f.store.snapshot.value.grants?.keys)
        assertNull(f.store.snapshot.value.ownerId)
        assertFalse(f.store.snapshot.value.ownerKnown)
        // E o que chegou da rede, não o guardado, é o que fica gravado.
        assertEquals(f.store.snapshot.value, AccessStore.decode(assertNotNull(f.stored())))
    }

    @Test
    fun flagDeCargoDesconhecidaNoGuardadoEIgnoradaECamposNovosNaoQuebram() {
        val text = """{"ownerKnown":true,"roles":{"r":["PinMessage","FlagDoFuturo"]},"campoNovo":1}"""
        val snapshot = assertNotNull(AccessStore.decode(text))
        assertEquals(mapOf("r" to RoleFlags(setOf(RoleFlag.PinMessage))), snapshot.roles)
        assertTrue(snapshot.ownerKnown)
        assertNull(AccessStore.decode("lixo"))
    }

    @Test
    fun duasContasNoMesmoServidorNaoDividemNada() = runTest {
        val a = Fixture(this, engine)
        val b = Fixture(this, engine)

        a.store.refreshMeta()
        a.store.applyChannels(listOf(channel("c", entry("mod", read = true))))
        a.store.applyUsers(mapOf("u1" to setOf("mod")), complete = true, since = a.store.beginUsersSync())
        runCurrent()

        assertEquals(PermissionSnapshot.EMPTY, b.store.snapshot.value)
        assertNull(b.stored())
        assertNotNull(a.stored())

        b.store.applyUsers(emptyMap(), complete = true, since = b.store.beginUsersSync())
        runCurrent()
        assertEquals(emptyMap(), b.store.snapshot.value.userRoles)
        assertEquals(setOf("mod"), a.store.snapshot.value.userRoles["u1"])
        assertNotEquals(a.stored(), b.stored())
    }

    // 403

    @Test
    fun aReleituraPorForbiddenSaiUmaVezACadaDezSegundos() = runTest {
        val f = Fixture(this, engine)
        assertTrue(f.store.allowForbiddenRefresh())
        assertFalse(f.store.allowForbiddenRefresh())

        advanceTimeBy(9.seconds)
        assertFalse(f.store.allowForbiddenRefresh())

        advanceTimeBy(1.seconds)
        assertTrue(f.store.allowForbiddenRefresh())
        assertFalse(f.store.allowForbiddenRefresh()) // a janela recomeça na última permitida
    }
}
