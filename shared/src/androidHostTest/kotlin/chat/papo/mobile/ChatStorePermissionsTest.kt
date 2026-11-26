package chat.papo.mobile

import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.chat.ChannelFlag
import chat.papo.mobile.chat.Decision
import chat.papo.mobile.chat.Draft
import chat.papo.mobile.chat.PendingStatus
import chat.papo.mobile.chat.PermissionSnapshot
import chat.papo.mobile.chat.RoleFlag
import chat.papo.mobile.chat.SendFailure
import chat.papo.mobile.data.api.ApiException
import chat.papo.mobile.data.api.MemoryCookieStore
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.data.db.account.AccountDb
import chat.papo.mobile.data.ws.WsEvent
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.isSuccess
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * O [chat.papo.mobile.chat.ChatStore] com as permissões ligadas: o que `sync` e os eventos pedem, o 403 e o
 * que acontece com um canal que o usuário deixa de ler. Motor HTTP simulado e banco em memória; o motor
 * responde em outra thread, então os testes esperam o efeito (o snapshot ou o banco), não o tempo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatStorePermissionsTest {
    private val requests = CopyOnWriteArrayList<String>()

    @Volatile private var ownerId = "boss"

    @Volatile private var rolesBody = ROLES

    @Volatile private var rolesStatus = HttpStatusCode.OK

    @Volatile private var channelsBody = channels(category("cat"), channel("c1"), channel("c2"), channel("v1", type = "voice"))

    @Volatile private var usersBody = users(user("me"), user("u2", roles = MOD))

    @Volatile private var usersHandler: (suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData)? = null

    @Volatile private var messagesStatus = HttpStatusCode.OK

    @Volatile private var sendStatus = HttpStatusCode.Created

    @Volatile private var pinStatus = HttpStatusCode.NoContent

    @Volatile private var mutationStatus = HttpStatusCode.NoContent

    @Volatile private var channelsHook: CompletableDeferred<Unit>? = null

    @Volatile private var serverGate: CompletableDeferred<Unit>? = null
    private val serverStarted = CompletableDeferred<Unit>()

    private fun MockRequestHandleScope.json(text: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
        respond(text, status, headersOf(HttpHeaders.ContentType, if (status.value >= 400) "application/problem+json" else "application/json"))

    private fun MockRequestHandleScope.problem(status: HttpStatusCode) =
        json("""{"title":"erro","status":${status.value},"detail":"sem permissão"}""", status)

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        requests += "${request.method.value} $path"
        when {
            path == "/server" -> {
                serverStarted.complete(Unit)
                serverGate?.await()
                json("""{"id":"s","name":"papo","owner_id":"$ownerId"}""")
            }
            path == "/roles" -> if (rolesStatus.isSuccess()) json(rolesBody) else problem(rolesStatus)
            path == "/channels" -> {
                channelsHook?.complete(Unit)
                json(channelsBody)
            }
            path == "/dms" -> json("""{"dms":[]}""")
            path == "/users" -> usersHandler?.invoke(this, request) ?: json(usersBody)
            path == "/messages" -> if (sendStatus.isSuccess()) json(SENT, sendStatus) else problem(sendStatus)
            path.endsWith("/messages") && request.method == HttpMethod.Get ->
                if (messagesStatus.isSuccess()) json(history(path.substringAfter("/channels/").substringBefore("/"))) else problem(messagesStatus)
            path.endsWith("/pinned") -> if (pinStatus.isSuccess()) json("""{"channel_id":"c1","pinned":[]}""") else problem(pinStatus)
            path.endsWith("/pin") -> if (pinStatus.isSuccess()) respond("", pinStatus) else problem(pinStatus)
            path.startsWith("/messages/") || path.endsWith("/reactions") ->
                if (!mutationStatus.isSuccess()) problem(mutationStatus) else if (request.method == HttpMethod.Put) json(EDITED) else respond("", mutationStatus)
            else -> respond("", HttpStatusCode.NoContent)
        }
    }

    /** Uma sessão de conta sobre o motor falso, com o banco à mão para conferir o que ficou guardado. */
    private class Account(val session: AccountSession, val db: AccountDb) {
        val chat get() = session.chat
    }

    private fun TestScope.account(engine: MockEngine, userId: String = "me"): Account {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = PapoClient(engine, ServerUrl("https://papo.test"), MemoryCookieStore("jwt"))
        val resources = TestResources.inMemory(dispatcher)
        return Account(AccountSession("conta", userId, client, backgroundScope, resources, dispatcher), AccountDb(resources.driver))
    }

    private fun count(request: String) = requests.count { it == request }

    // Fontes

    @Test
    fun syncPreencheDonoCargosEntradasEMeusCargosSemTocarEmAuth() = runTest {
        channelsBody = channels(category("cat"), channel("c1"), channel("c2", entries = entries(MOD_ID, read = true)))
        usersBody = users(user("me", roles = MOD), user("u2"))
        val account = account(engine)

        account.chat.sync()
        runCurrent()

        val snapshot = account.chat.access.value
        assertSame(account.session.permissions.snapshot, account.chat.access)
        assertEquals("boss", snapshot.ownerId)
        assertTrue(snapshot.ownerKnown)
        assertEquals(setOf(MOD_ID), snapshot.roles?.keys)
        assertEquals(setOf("cat", "c1", "c2"), snapshot.grants?.keys)
        assertEquals(1, snapshot.grants?.getValue("c2")?.size)
        assertEquals(mapOf("me" to setOf(MOD_ID)), snapshot.userRoles)
        assertTrue(snapshot.usersComplete)
        assertEquals(Decision.Allowed, snapshot.channel("me", "c2", ChannelFlag.Read))
        assertEquals(Decision.Allowed, snapshot.role("me", RoleFlag.PinMessage))
        assertEquals(listOf("cat", "c1", "c2"), account.chat.visibleChannels.value.map { it.id })
        assertTrue(requests.none { it.contains("/auth") }) // regra 15: nenhuma rota de /auth
        assertEquals(1, count("GET /server"))
        assertEquals(1, count("GET /roles"))
    }

    @Test
    fun cargosComErroNaoDerrubamOSyncEMantemOSnapshot() = runTest {
        val account = account(engine)
        account.chat.runSync()
        val before = account.chat.access.value
        assertEquals(setOf(MOD_ID), before.roles?.keys)

        rolesStatus = HttpStatusCode.InternalServerError
        ownerId = "outro"
        account.chat.runSync()

        assertNull(account.chat.syncError.value)
        assertEquals(before.roles, account.chat.access.value.roles) // o catálogo velho fica
        assertEquals("outro", account.chat.access.value.ownerId) // e o resto do sync segue
        runCurrent()
        assertEquals(listOf("cat", "c1", "c2", "v1"), account.chat.channels.value.map { it.id })
    }

    @Test
    fun sincronizadaSoDepoisDeUmaSincronizacaoInteira() = runTest {
        val account = account(engine)
        assertFalse(account.chat.synced.value)

        account.chat.sync()

        assertTrue(account.chat.synced.value)
    }

    @Test
    fun sincronizacaoQueFalhaNaLeituraDosUsuariosNaoMarcaComoSincronizada() = runTest {
        usersHandler = { problem(HttpStatusCode.InternalServerError) }
        val account = account(engine)

        account.chat.runSync()

        assertNotNull(account.chat.syncError.value)
        assertFalse(account.chat.synced.value)
    }

    @Test
    fun canaisGravadosValemMesmoQuandoALeituraDosUsuariosFalha() = runTest {
        // A abertura no canal do topo espera só os canais: um 429 ou 500 nas páginas de usuários não pode impedi-la.
        usersHandler = { problem(HttpStatusCode.TooManyRequests) }
        val account = account(engine)
        assertFalse(account.chat.channelsSynced.value)

        account.chat.runSync()

        assertTrue(account.chat.channelsSynced.value)
        assertFalse(account.chat.synced.value)
    }

    @Test
    fun sincronizacaoCanceladaNoMeioNaoMarcaComoSincronizada() = runTest {
        val gate = CompletableDeferred<Unit>()
        serverGate = gate
        val account = account(engine)
        val job = launch { account.chat.runSync() }

        serverStarted.await()
        job.cancel()
        job.join()

        assertFalse(account.chat.synced.value)
    }

    @Test
    fun umaFalhaDepoisDeSincronizarNaoDesfazOSincronizada() = runTest {
        val account = account(engine)
        account.chat.runSync()

        usersHandler = { problem(HttpStatusCode.InternalServerError) }
        account.chat.runSync()

        assertNotNull(account.chat.syncError.value)
        assertTrue(account.chat.synced.value)
    }

    @Test
    fun roleAddMeuDecideAntesDeLerOsUsuarios() = runTest {
        val account = account(engine)
        account.chat.sync()
        assertEquals(Decision.Denied, account.chat.access.value.role("me", RoleFlag.PinMessage))

        usersBody = users(user("me", roles = MOD), user("u2", roles = MOD)) // o que o servidor já diz
        account.chat.handle(WsEvent.RoleAdded("me", MOD_ID))
        assertEquals(Decision.Allowed, account.chat.access.value.role("me", RoleFlag.PinMessage))

        account.chat.handle(WsEvent.RoleRemoved("me", MOD_ID))
        assertEquals(Decision.Denied, account.chat.access.value.role("me", RoleFlag.PinMessage))
    }

    @Test
    fun paginaDeUsersPedidaAntesDoEventoNaoDesfazOEvento() = runTest {
        val account = account(engine)
        account.chat.sync()
        val calls = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        // A primeira leitura sai antes do evento e volta velha; a que o evento dispara falha.
        usersHandler = {
            if (calls.getAndIncrement() == 0) {
                started.complete(Unit)
                gate.await()
                json(users(user("me"), user("u2", roles = MOD)))
            } else {
                problem(HttpStatusCode.InternalServerError)
            }
        }
        val sync = launch { account.chat.runSync() }
        started.await()

        account.chat.handle(WsEvent.RoleAdded("me", MOD_ID))
        gate.complete(Unit)
        sync.join()

        assertEquals(Decision.Allowed, account.chat.access.value.role("me", RoleFlag.PinMessage))
        assertEquals(setOf(MOD_ID), account.chat.access.value.userRoles["me"])
    }

    @Test
    fun usuariosEmDuasPaginasSeJuntamEFicamCompletos() = runTest {
        usersHandler = { request ->
            when (request.url.parameters["last_id"]) {
                null -> json("""{"users":[${user("me", at = 1)},${user("u2", roles = MOD, at = 2)}],"has_more":true}""")
                else -> json("""{"users":[${user("u3", roles = MOD, at = 3)}],"has_more":false}""")
            }
        }
        val account = account(engine)

        account.chat.sync()

        val snapshot = account.chat.access.value
        assertEquals(mapOf("u2" to setOf(MOD_ID), "u3" to setOf(MOD_ID)), snapshot.userRoles)
        assertTrue(snapshot.usersComplete)
        assertEquals(2, count("GET /users"))
    }

    @Test
    fun semOProprioUsuarioNasPaginasNaoHaCertezaENadaSeEscondePorIsso() = runTest {
        channelsBody = channels(channel("c1", entries = entries("outro", read = true)))
        usersHandler = { json("""{"users":[${user("u2", roles = MOD)}],"has_more":false}""") }
        val account = account(engine)

        account.chat.sync()
        runCurrent()

        assertFalse(account.chat.access.value.usersComplete)
        assertEquals(Decision.Unknown, account.chat.access.value.channel("me", "c1", ChannelFlag.Read))
        assertEquals(listOf("c1"), account.chat.visibleChannels.value.map { it.id })
    }

    @Test
    fun paginaSemCursorParaAntesDoFimMasOsCargosDeQuemFoiLidoValem() = runTest {
        channelsBody = channels(channel("c1", entries = entries("outro", read = true)))
        // Há mais páginas, mas o último usuário não traz o instante que serve de cursor: a leitura para ali.
        usersHandler = { json("""{"users":[{"id":"me","username":"eu","roles":[]}],"has_more":true}""") }
        val account = account(engine)

        account.chat.sync()
        runCurrent()

        assertFalse(account.chat.access.value.usersComplete)
        assertEquals(1, count("GET /users"))
        // Quem foi lido tem os cargos conhecidos (nenhum): é o suficiente para negar o que ele não lê.
        assertEquals(Decision.Denied, account.chat.access.value.channel("me", "c1", ChannelFlag.Read))
        assertEquals(emptyList(), account.chat.visibleChannels.value)
    }

    @Test
    fun maisPaginasQueOLimiteNaoDaoCertezaEParamNoLimite() = runTest {
        channelsBody = channels(channel("c1", entries = entries("outro", read = true)))
        val page = AtomicInteger()
        usersHandler = {
            val n = page.incrementAndGet()
            json("""{"users":[${user("x$n", at = n % 60)}],"has_more":true}""")
        }
        val account = account(engine)

        account.chat.sync()
        runCurrent()

        assertEquals(50, count("GET /users"))
        assertFalse(account.chat.access.value.usersComplete)
        assertEquals(listOf("c1"), account.chat.visibleChannels.value.map { it.id })
    }

    // Eventos

    @Test
    fun doisChannelUpdateJuntosFazemUmaLeituraDeCanaisEDevolvemACategoria() = runTest {
        val account = account(engine)
        account.chat.sync()
        runCurrent()
        assertEquals("cat", account.chat.channels.value.first { it.id == "c1" }.parentId)

        // O servidor manda channel_update sem parent_id ao mudar a permissão de um cargo.
        account.chat.handle(WsEvent.ChannelUpdated("c1", "c1", 1, null, null))
        account.chat.handle(WsEvent.ChannelUpdated("c1", "c1", 1, "tópico", null))
        runCurrent()
        assertNull(account.chat.channels.value.first { it.id == "c1" }.parentId) // a gravação local do evento
        assertEquals(1, count("GET /channels"))

        channelsBody = channels(category("cat"), channel("c1", entries = entries(MOD_ID, read = true)), channel("c2"), channel("v1", type = "voice"))
        account.chat.access.first { it.grants?.get("c1")?.isNotEmpty() == true }
        runCurrent()

        assertEquals("cat", account.chat.channels.value.first { it.id == "c1" }.parentId)
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(2, count("GET /channels")) // a do sync e uma só para os dois eventos
        assertEquals(1, count("GET /dms")) // só os canais são relidos
        assertEquals(1, count("GET /users"))
    }

    @Test
    fun channelCreateLeAsPermissoesDoCanalNovo() = runTest {
        val account = account(engine)
        account.chat.sync()
        assertNull(account.chat.access.value.grants?.get("c3"))

        channelsBody = channels(category("cat"), channel("c1"), channel("c3", entries = entries("outro", read = true)))
        account.chat.handle(WsEvent.ChannelCreated("c3", "c3", "text", 3, null, "cat"))
        account.chat.access.first { it.grants?.containsKey("c3") == true }
        runCurrent()

        assertEquals(1, account.chat.access.value.grants?.getValue("c3")?.size)
        // Quem foi criado só pelo evento não some porque a leitura de permissões não o lista: só o sync apaga.
        assertTrue(account.chat.channels.value.any { it.id == "c3" })
        assertEquals(2, count("GET /channels"))
    }

    @Test
    fun relerOsCanaisQueFalhaNaoMataAsProximasReleituras() = runTest {
        val account = account(engine)
        account.chat.sync()
        runCurrent()
        val served = CompletableDeferred<Unit>()
        channelsHook = served
        channelsBody = "isto não é json"

        account.chat.handle(WsEvent.ChannelUpdated("c1", "c1", 1, null, null))
        advanceTimeBy(2.seconds) // o debounce acaba e a leitura sai
        served.await()
        assertEquals(setOf("cat", "c1", "c2", "v1"), account.chat.access.value.grants?.keys) // o snapshot fica

        channelsBody = channels(category("cat"), channel("c1"), channel("c2"), channel("v1", type = "voice"))
        channelsHook = null
        account.chat.handle(WsEvent.ChannelUpdated("c1", "c1", 1, null, null))
        runCurrent()
        assertNull(account.chat.channels.value.first { it.id == "c1" }.parentId)
        account.chat.channels.first { list -> list.first { it.id == "c1" }.parentId == "cat" }
    }

    // 403

    @Test
    fun forbiddenNoPinPedeUmaReleituraENadaEReenviado() = runTest {
        val account = account(engine)
        account.chat.sync()
        runCurrent()
        pinStatus = HttpStatusCode.Forbidden
        rolesBody = ROLES_EXTRA
        channelsBody = channels(category("cat"), channel("c1"), channel("c2", entries = entries(MOD_ID, read = true)), channel("v1", type = "voice"))

        assertFailsWith<ApiException> { account.chat.setPinned("c1", "m1", true) }
        account.chat.access.first { "extra" in it.roles.orEmpty() && it.grants?.get("c2")?.isNotEmpty() == true }
        runCurrent()

        assertEquals(1, count("POST /channels/c1/messages/m1/pin")) // o app não repete sozinho
        assertEquals(2, count("GET /server"))
        assertEquals(2, count("GET /roles"))
        assertEquals(2, count("GET /channels"))

        // Outro 403 logo depois: a ação é do usuário e vai de novo, mas a releitura não.
        assertFailsWith<ApiException> { account.chat.setPinned("c1", "m1", true) }
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(2, count("POST /channels/c1/messages/m1/pin"))
        assertEquals(2, count("GET /roles"))
        assertEquals(2, count("GET /channels"))
    }

    @Test
    fun forbiddenAoEnviarPedeUmaReleituraEAMensagemFalhaComoAntes() = runTest {
        val account = account(engine)
        account.chat.sync()
        sendStatus = HttpStatusCode.Forbidden
        rolesBody = ROLES_EXTRA

        account.chat.enqueue("c1", "oi", replyTo = null, files = emptyList())
        val failed = account.chat.outbox.first { it.singleOrNull()?.status is PendingStatus.Failed }.single().status
        account.chat.access.first { "extra" in it.roles.orEmpty() }
        runCurrent()

        assertEquals(PendingStatus.Failed(SendFailure.Rejected, "sem permissão"), failed)
        assertEquals(1, count("POST /messages"))
        assertEquals(2, count("GET /roles"))
    }

    @Test
    fun umErro500NaoGastaAJanelaEUm403NaLeituraDeMensagensPedeAReleitura() = runTest {
        val account = account(engine)
        account.chat.sync()
        rolesBody = ROLES_EXTRA

        messagesStatus = HttpStatusCode.InternalServerError
        assertFailsWith<ApiException> { account.chat.refreshLatest("c1") }
        assertEquals(1, count("GET /roles")) // nada foi pedido por causa do 500

        // Se o 500 tivesse gasto a janela de dez segundos, este 403 não pediria nada e a espera abaixo não acabaria.
        messagesStatus = HttpStatusCode.Forbidden
        assertFailsWith<ApiException> { account.chat.refreshLatest("c1") }
        account.chat.access.first { "extra" in it.roles.orEmpty() }
        assertEquals(2, count("GET /roles"))
    }

    @Test
    fun forbiddenEmEdicaoExclusaoEReacaoTambemPedeAReleitura() = runTest {
        val operations = listOf<suspend (Account) -> Unit>(
            { it.chat.edit("m1", "novo") },
            { it.chat.delete("m1") },
            { it.chat.toggleReaction(assertNotNull(it.chat.storedMessage("c1", "m1")), null, "👍") },
            { it.chat.loadPinned("c1") },
        )
        for (operation in operations) {
            requests.clear()
            rolesBody = ROLES
            val account = account(engine)
            account.chat.sync()
            runCurrent()
            account.chat.handle(WsEvent.MessageCreated("m1", "c1", "u2", content = "oi", createdAt = "2026-01-01T00:00:01Z"))
            mutationStatus = HttpStatusCode.Forbidden
            pinStatus = HttpStatusCode.Forbidden
            rolesBody = ROLES_EXTRA

            assertFailsWith<ApiException> { operation(account) }
            account.chat.access.first { "extra" in it.roles.orEmpty() }

            assertEquals(2, count("GET /roles"))
        }
    }

    // Perder e recuperar a leitura

    @Test
    fun perderALeituraApagaAsMensagensGuardadasMasMantemORascunhoEEscondeOCanal() = runTest {
        channelsBody = channels(category("cat"), channel("c1"), channel("c2"))
        val account = account(engine)
        account.chat.sync()
        runCurrent()
        account.chat.handle(WsEvent.MessageCreated("m1", "c2", "u2", content = "antigo", createdAt = "2026-01-01T00:00:01Z"))
        account.chat.handle(WsEvent.MessageCreated("m2", "c1", "u2", content = "outro canal", createdAt = "2026-01-01T00:00:02Z"))
        account.session.drafts.save("c2", Draft("rascunho", replyTo = "m1"))
        account.session.drafts.flush()
        account.db.metaQueries.put("detached:c2", "1")
        runCurrent()
        assertEquals(listOf("cat", "c1", "c2"), account.chat.visibleChannels.value.map { it.id })
        assertEquals(1, account.chat.messages("c2", 10).first().size)

        // O servidor passa a deixar só o cargo `mod` ler o c2, e ainda manda a prévia da última mensagem.
        channelsBody = channels(
            category("cat"),
            channel("c1"),
            channel("c2", entries = entries(MOD_ID, read = true), lastMessage = """{"id":"m1","content":"antigo","created_at":"2026-01-01T00:00:01Z"}"""),
        )
        account.chat.handle(WsEvent.ChannelUpdated("c2", "c2", 1, null, "cat"))
        account.chat.visibleChannels.first { list -> list.none { it.id == "c2" } }
        account.chat.messages("c2", 10).first { it.isEmpty() }
        runCurrent()

        assertEquals(listOf("cat", "c1"), account.chat.visibleChannels.value.map { it.id })
        assertEquals(Draft("rascunho", replyTo = "m1"), account.session.drafts.load("c2"))
        assertNull(account.db.metaQueries.get("detached:c2").executeAsOneOrNull())
        assertNull(account.chat.channels.value.first { it.id == "c2" }.lastMessageId) // a prévia não se guarda
        assertEquals(listOf("m2"), account.chat.messages("c1", 10).first().map { it.id }) // o resto fica

        // Uma nova leitura dos canais, com o canal ainda negado, não traz a prévia de volta.
        channelsBody = channels(
            category("cat"),
            channel("c1"),
            channel("c2", entries = entries(MOD_ID, read = true), lastMessage = """{"id":"m1","content":"antigo","created_at":"2026-01-01T00:00:01Z"}"""),
            channel("c9"),
        )
        account.chat.handle(WsEvent.ChannelUpdated("c2", "c2", 1, null, "cat"))
        account.chat.channels.first { list -> list.any { it.id == "c9" } }
        assertNull(account.chat.channels.value.first { it.id == "c2" }.lastMessageId)

        // Recebendo o cargo, o canal volta na hora, o rascunho continua, e o histórico vem pela leitura do fim.
        usersBody = users(user("me", roles = MOD), user("u2", roles = MOD))
        account.chat.handle(WsEvent.RoleAdded("me", MOD_ID))
        assertTrue(account.chat.visibleChannels.first { list -> list.any { it.id == "c2" } }.isNotEmpty())
        assertEquals(Draft("rascunho", replyTo = "m1"), account.session.drafts.load("c2"))
        account.chat.refreshLatest("c2")
        assertEquals(listOf("h-c2"), account.chat.messages("c2", 10).first { it.isNotEmpty() }.map { it.id })
    }

    @Test
    fun eventosDeCargoQueNaoNegamNadaNaoApagamAsMensagens() = runTest {
        val account = account(engine)
        account.chat.sync()
        account.chat.handle(WsEvent.MessageCreated("m1", "c2", "u2", content = "oi", createdAt = "2026-01-01T00:00:01Z"))
        runCurrent()
        // Mudanças que só deixam algo desconhecido ou liberado não esvaziam o canal.
        account.chat.handle(WsEvent.RoleAdded("me", MOD_ID))
        account.chat.handle(WsEvent.RoleRemoved("me", MOD_ID))
        runCurrent()
        assertEquals(1, account.chat.messages("c2", 10).first().size)
    }

    // Contas e cancelamento

    @Test
    fun duasContasNoMesmoServidorTemSnapshotsEBancosSeparados() = runTest {
        channelsBody = channels(category("cat"), channel("c1"), channel("c2", entries = entries(MOD_ID, read = true)))
        val dona = account(engine, userId = "u2") // tem o cargo `mod`
        val outra = account(engine, userId = "me") // não tem

        dona.chat.sync()
        runCurrent()
        assertEquals(PermissionSnapshot.EMPTY, outra.chat.access.value)
        assertEquals(emptyList(), outra.chat.visibleChannels.value)
        assertNull(outra.db.metaQueries.get("permissions").executeAsOneOrNull())

        outra.chat.sync()
        runCurrent()

        assertEquals(listOf("cat", "c1", "c2"), dona.chat.visibleChannels.value.map { it.id })
        assertEquals(listOf("cat", "c1"), outra.chat.visibleChannels.value.map { it.id })

        // Mudar um cargo numa conta não mexe na memória nem no banco da outra, mesmo no mesmo servidor.
        val savedOutra = outra.db.metaQueries.get("permissions").executeAsOneOrNull()
        usersBody = users(user("me"), user("u2")) // o que o servidor já diz depois do evento
        dona.chat.handle(WsEvent.RoleRemoved("u2", MOD_ID))
        runCurrent()
        assertTrue(dona.chat.access.value.userRoles["u2"].orEmpty().isEmpty())
        assertEquals(setOf(MOD_ID), outra.chat.access.value.userRoles["u2"])
        assertEquals(savedOutra, outra.db.metaQueries.get("permissions").executeAsOneOrNull())
        assertNotEquals(savedOutra, dona.db.metaQueries.get("permissions").executeAsOneOrNull())
    }

    @Test
    fun cancelarOSyncNoMeioDaLeituraDoDonoNaoEUmErroENaoGravaNada() = runTest {
        val gate = CompletableDeferred<Unit>()
        serverGate = gate
        val account = account(engine)
        val job = launch { account.chat.runSync() }

        serverStarted.await()
        job.cancel()
        job.join()
        runCurrent()

        assertFalse(account.chat.syncing.value)
        assertNull(account.chat.syncError.value)
        assertEquals(emptyList(), account.chat.channels.value)
        assertFalse(account.chat.access.value.ownerKnown)
    }

    // Reconexão

    @Test
    fun aoReconectarOCanalNegadoNaoELidoENemPedeUmErroCerto() = runTest {
        channelsBody = channels(category("cat"), channel("c1"), channel("c2", entries = entries(MOD_ID, read = true)))
        val account = account(engine)

        account.chat.setViewing("c2")
        account.chat.onConnected()
        assertTrue(requests.none { it == "GET /channels/c2/messages" })

        account.chat.setViewing("c1")
        account.chat.onConnected()
        assertEquals(1, count("GET /channels/c1/messages"))
    }

    private companion object {
        const val MOD_ID = "mod"
        const val MOD = """[{"id":"mod","name":"Mod"}]"""
        const val ROLES = """{"roles":[{"id":"mod","name":"Mod","permissions":{"pin_message":true,"everyone_message":true}}]}"""
        const val ROLES_EXTRA =
            """{"roles":[{"id":"mod","name":"Mod","permissions":{"pin_message":true,"everyone_message":true}},{"id":"extra","permissions":{}}]}"""
        const val EDITED = """{"id":"m1","channel_id":"c1","author_id":"me","content":"novo","created_at":"2026-01-01T00:00:01Z"}"""
        const val SENT = """{"id":"s1","channel_id":"c1","author_id":"me","content":"oi","created_at":"2026-01-01T00:10:00Z"}"""

        fun history(channelId: String) =
            """{"messages":[{"id":"h-$channelId","channel_id":"$channelId","author_id":"u2","content":"do servidor","created_at":"2026-01-01T00:00:05Z"}],"has_more":false}"""

        fun entries(role: String, read: Boolean = false) =
            """[{"role_id":"$role","role_name":"$role","permissions":{"read_channel":$read}}]"""

        fun category(id: String) = """{"id":"$id","name":"$id","type":"category","position":0}"""

        fun channel(id: String, type: String = "text", entries: String = "[]", lastMessage: String = "null") =
            """{"id":"$id","name":"$id","type":"$type","position":1,"parent_id":"cat","last_message":$lastMessage,"permissions":$entries}"""

        fun channels(vararg items: String) = """{"channels":[${items.joinToString(",")}]}"""

        fun user(id: String, roles: String = "[]", at: Int = 0) =
            """{"id":"$id","username":"$id","created_at":"2026-01-01T00:00:%02dZ","roles":$roles}""".format(at)

        fun users(vararg items: String) = """{"users":[${items.joinToString(",")}],"has_more":false}"""
    }
}
