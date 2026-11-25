package chat.papo.mobile

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.chat.ConversationAccess
import chat.papo.mobile.chat.Draft
import chat.papo.mobile.data.api.MemoryCookieStore
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.data.db.account.AccountDb
import chat.papo.mobile.data.ws.WsEvent
import chat.papo.mobile.ui.chat.ChatViewModel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.isSuccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * O [ChatViewModel] com as permissões ligadas: o que `access` calcula, o que a conversa deixa de pedir ao servidor
 * quando a leitura é negada, o envio sem permissão e o 403. Motor HTTP simulado e banco em memória; o motor responde
 * em outra thread, então os testes esperam o efeito no estado, e quem confere que algo não foi pedido espera antes
 * uma requisição de controle, que sai depois das outras e só volta quando elas já passaram pelo motor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelPermissionsTest {
    private val requests = CopyOnWriteArrayList<String>()

    @Volatile private var rolesBody = ROLES

    @Volatile private var channelsBody = channels(channel("c1"), channel("c2"))

    @Volatile private var usersBody = users(user("me"), user("u2"))

    @Volatile private var dmsBody = """{"dms":[]}"""

    @Volatile private var mutationStatus = HttpStatusCode.NoContent

    @Volatile private var failure: Throwable? = null

    @Volatile private var pinnedStatus = HttpStatusCode.OK

    /** Segura a leitura de mensagens e a de fixadas; [messagesStarted] recebe um aviso a cada leitura de mensagens que chega ao motor. */
    @Volatile private var readGate: CompletableDeferred<Unit>? = null
    private val messagesStarted = Channel<Unit>(Channel.UNLIMITED)

    private fun MockRequestHandleScope.json(text: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
        respond(text, status, headersOf(HttpHeaders.ContentType, if (status.value >= 400) "application/problem+json" else "application/json"))

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        requests += "${request.method.value} $path"
        when {
            path == "/server" -> json("""{"id":"s","name":"papo","owner_id":"boss"}""")
            path == "/roles" -> json(rolesBody)
            path == "/channels" -> json(channelsBody)
            path == "/dms" -> json(dmsBody)
            path == "/users" -> json(usersBody)
            path == "/messages" -> json(SENT, HttpStatusCode.Created)
            path.endsWith("/messages") && request.method == HttpMethod.Get -> {
                messagesStarted.trySend(Unit)
                readGate?.await()
                json(history(path.substringAfter("/channels/").substringBefore("/")))
            }
            path.endsWith("/pinned") -> {
                readGate?.await()
                if (pinnedStatus.isSuccess()) json("""{"channel_id":"c1","pinned":[]}""") else json("""{"title":"erro","status":${pinnedStatus.value}}""", pinnedStatus)
            }
            path.startsWith("/messages/") || path.endsWith("/reactions") -> {
                failure?.let { throw it }
                when {
                    !mutationStatus.isSuccess() -> json("""{"title":"erro","status":${mutationStatus.value},"detail":"sem permissão"}""", mutationStatus)
                    request.method == HttpMethod.Put -> json(EDITED)
                    else -> respond("", mutationStatus)
                }
            }
            else -> respond("", HttpStatusCode.NoContent)
        }
    }

    private class Account(val session: AccountSession, val db: AccountDb) {
        val chat get() = session.chat
    }

    private fun TestScope.account(): Account {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val client = PapoClient(engine, ServerUrl("https://papo.test"), MemoryCookieStore("jwt"))
        val resources = TestResources.inMemory(dispatcher)
        return Account(AccountSession("conta", "me", client, backgroundScope, resources, dispatcher), AccountDb(resources.driver))
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun count(request: String) = requests.count { it == request }

    private fun ChatViewModel.type(text: String) = onDraftChange(TextFieldValue(text, TextRange(text.length)))

    /** O canal [id] passa a só deixar o cargo [role] ler; o evento faz o app reler os canais. */
    private suspend fun Account.restrict(id: String, role: String = MOD) {
        channelsBody = channels(channel("c1"), channel(id, entries = entries(role, read = true)))
        chat.handle(WsEvent.ChannelUpdated(id, id, 1, null, null))
    }

    /** `me` recebe o cargo [role]; `GET /users` já diz o mesmo, como o servidor. */
    private suspend fun Account.grant(role: String = MOD) {
        usersBody = users(user("me", roles = """[{"id":"$role","name":"$role"}]"""), user("u2"))
        chat.handle(WsEvent.RoleAdded("me", role))
    }

    /** `me` perde o cargo [role]. */
    private suspend fun Account.revoke(role: String = MOD) {
        usersBody = users(user("me"), user("u2"))
        chat.handle(WsEvent.RoleRemoved("me", role))
    }

    /** Uma requisição que sai depois das já feitas: quando ela volta, o motor já viu as anteriores. */
    private suspend fun TestScope.flush(account: Account) {
        runCurrent()
        account.chat.refreshLatest("controle")
    }

    /** A conversa "c2" aberta e carregada com a leitura permitida, que logo em seguida é negada e já saiu do banco. */
    private suspend fun TestScope.deniedConversation(): Pair<Account, ChatViewModel> {
        val account = account()
        account.chat.sync()
        runCurrent()
        val vm = ChatViewModel(account.session, "c2")
        vm.state.first { it.loadedOnce }
        vm.messages.first { it.isNotEmpty() }
        vm.type("rascunho")
        account.session.drafts.flush()
        vm.showPinned() // a lista de fixadas está aberta quando a leitura se perde
        vm.state.first { it.pinnedList != null }
        account.restrict("c2")
        vm.access.first { it.readDenied }
        vm.messages.first { it.isEmpty() }
        vm.state.first { it.pinnedList == null }
        return account to vm
    }

    // Leitura negada

    @Test
    fun perderALeituraApagaOHistoricoMantemORascunhoEACargaVoltaQuandoOAcessoVolta() = runTest {
        val (account, vm) = deniedConversation()
        flush(account)
        assertEquals(1, count("GET /channels/c2/messages"))
        assertEquals(2, count("GET /channels/c2/pinned")) // ao abrir e ao abrir a lista
        assertTrue(vm.access.value.readDenied)
        assertNull(vm.state.value.pinnedList)
        assertEquals("rascunho", vm.draft.text)
        assertEquals(Draft("rascunho"), account.session.drafts.load("c2"))

        account.grant()
        vm.access.first { !it.readDenied }
        vm.messages.first { it.isNotEmpty() }

        // O histórico volta pela leitura do fim, e as fixadas também são lidas de novo.
        assertEquals(2, count("GET /channels/c2/messages"))
        flush(account)
        assertEquals(3, count("GET /channels/c2/pinned"))
        assertEquals("rascunho", vm.draft.text)
        assertEquals(Draft("rascunho"), account.session.drafts.load("c2"))
    }

    @Test
    fun perderEVoltarALeituraNoMeioDaCargaRecomecaACargaEmVezDeEsperarAAntiga() = runTest {
        val gate = CompletableDeferred<Unit>()
        readGate = gate
        val account = account()
        account.chat.sync()
        runCurrent()
        val vm = ChatViewModel(account.session, "c2")
        messagesStarted.receive() // a primeira leitura do fim, e a das fixadas, estão no motor, presas

        account.restrict("c2")
        vm.access.first { it.readDenied }
        account.grant()
        vm.access.first { !it.readDenied }
        messagesStarted.receive() // a leitura presa foi cancelada e uma nova saiu

        gate.complete(Unit)
        vm.state.first { it.loadedOnce }
        vm.messages.first { it.isNotEmpty() }
    }

    @Test
    fun comALeituraNegadaNenhumaAcaoDoViewModelPedeMensagensFixadasOuLeitura() = runTest {
        val (account, vm) = deniedConversation()
        // Uma mensagem que chega com o canal já negado, e um trecho antigo: tudo o que habilitaria cada pedido.
        account.chat.handle(WsEvent.MessageCreated("m9", "c2", "u2", content = "chegou", createdAt = "2026-01-01T00:00:09Z"))
        account.db.metaQueries.put("detached:c2", "1")
        val stored = vm.messages.first { it.isNotEmpty() }.single()
        account.chat.detached.first { "c2" in it }
        val before = requests.size

        vm.loadOlder()
        vm.loadNewer()
        vm.goToLatest()
        vm.showPinned()
        vm.markRead(stored)
        vm.openReply(stored.copy(replyTo = "original"))
        flush(account)

        assertEquals(emptyList(), requests.drop(before).filter { it.contains("/channels/c2/") })
        val state = vm.state.value
        assertFalse(state.loadingOlder || state.loadingNewer || state.returning || state.locating)
        assertNull(state.pinnedList)
    }

    @Test
    fun abrirUmCanalSemLeituraNaoPedeNadaMantemORascunhoECarregaQuandoOAcessoVolta() = runTest {
        channelsBody = channels(channel("c1"), channel("c2", entries = entries(MOD, read = true)))
        val account = account()
        account.chat.sync()
        runCurrent()
        account.session.drafts.save("c2", Draft("do outro dia"))
        account.session.drafts.flush()

        val vm = ChatViewModel(account.session, "c2")
        assertTrue(vm.access.value.readDenied) // já nasce calculado
        flush(account)

        assertEquals(0, count("GET /channels/c2/messages"))
        assertEquals(0, count("GET /channels/c2/pinned"))
        assertFalse(vm.state.value.loadedOnce)
        assertEquals("do outro dia", vm.draft.text)

        account.grant()
        vm.state.first { it.loadedOnce }
        vm.messages.first { it.isNotEmpty() }

        assertEquals(1, count("GET /channels/c2/messages"))
        assertEquals("do outro dia", vm.draft.text)
    }

    @Test
    fun falharAoLerAsFixadasNaoImpedeAConversaDeCarregarNemMostraErro() = runTest {
        pinnedStatus = HttpStatusCode.InternalServerError
        val account = account()
        account.chat.sync()
        runCurrent()

        val vm = ChatViewModel(account.session, "c1")
        vm.state.first { it.loadedOnce }
        vm.messages.first { it.isNotEmpty() }
        flush(account)

        assertEquals(1, count("GET /channels/c1/pinned"))
        assertNull(vm.state.value.error)
    }

    @Test
    fun comALeituraLiberadaMarcarComoLidaAvisaOServidor() = runTest {
        val account = account()
        account.chat.sync()
        runCurrent()
        val vm = ChatViewModel(account.session, "c1")
        vm.state.first { it.loadedOnce }
        val message = vm.messages.first { it.isNotEmpty() }.first()

        vm.markRead(message)
        flush(account)

        assertEquals(1, count("PUT /channels/c1/read"))
    }

    // Envio

    @Test
    fun semPermissaoDeEnvioSubmitNaoEnfileiraMasEditarAPropriaMensagemFunciona() = runTest {
        channelsBody = channels(channel("c1", entries = entries(READERS, read = true)))
        usersBody = users(user("me", roles = """[{"id":"$READERS","name":"Leitores"}]"""))
        val account = account()
        account.chat.sync()
        runCurrent()
        val vm = ChatViewModel(account.session, "c1")
        vm.state.first { it.loadedOnce }
        val mine = vm.messages.first { list -> list.any { it.authorId == "me" } }.first { it.authorId == "me" }
        assertFalse(vm.access.value.send)
        assertTrue(vm.access.value.removeOwnReaction)

        vm.type("oi")
        vm.submit()
        flush(account)

        assertEquals(emptyList(), account.chat.outbox.value)
        assertEquals(0, count("POST /messages"))
        assertEquals("oi", vm.draft.text) // o texto fica no compositor

        // Editar a própria mensagem segue valendo: o servidor não olha o envio para isso.
        vm.startEdit(mine)
        vm.type("corrigida")
        vm.submit()
        vm.state.first { it.editing == null && !it.savingEdit }
        assertEquals(1, count("PUT /messages/${mine.id}"))
        assertEquals("oi", vm.draft.text) // o rascunho de antes da edição volta

        // Com a permissão de volta, o mesmo texto vai para a fila.
        channelsBody = channels(channel("c1", entries = entries(READERS, read = true, send = true)))
        account.chat.handle(WsEvent.ChannelUpdated("c1", "c1", 1, null, null))
        vm.access.first { it.send }
        vm.submit()
        account.chat.outbox.first { it.isEmpty() }
        assertEquals(1, count("POST /messages"))
    }

    // O que a conta pode

    @Test
    fun oAcessoSegueOsEventosDeCargoEADmTemAPropriaRegra() = runTest {
        dmsBody = """{"dms":[{"id":"d1","user":{"id":"u2","username":"u2"}}]}"""
        val account = account()
        account.chat.sync()
        runCurrent()
        val channel = ChatViewModel(account.session, "c1")
        val dm = ChatViewModel(account.session, "d1")

        // Sem cargo nenhum: não fixa nem avisa todos no canal, mas a conversa direta fixa.
        channel.access.first { !it.pin }
        assertFalse(channel.access.value.mentionEveryone)
        assertTrue(channel.access.value.joinVoice)
        assertTrue(dm.access.value.pin)
        assertFalse(dm.access.value.joinVoice)
        assertFalse(dm.access.value.mentionEveryone)

        account.grant()
        channel.access.first { it.pin && it.mentionEveryone }

        account.revoke()
        channel.access.first { !it.pin && !it.mentionEveryone }
        assertTrue(dm.access.value.pin)
    }

    @Test
    fun antesDeSaberOAcessoDaContaTudoSeOferece() = runTest {
        val account = account()
        val vm = ChatViewModel(account.session, "c1") // sem sincronizar: nada se sabe das permissões

        assertEquals(ConversationAccess.Unrestricted, vm.access.value)
    }

    // 403

    @Test
    fun forbiddenNaReacaoPedeUmaSoReleituraDasPermissoes() = runTest {
        val account = account()
        account.chat.sync()
        runCurrent()
        val vm = ChatViewModel(account.session, "c1")
        vm.state.first { it.loadedOnce }
        val message = vm.messages.first { it.isNotEmpty() }.first()
        mutationStatus = HttpStatusCode.Forbidden
        rolesBody = ROLES_EXTRA

        vm.react(message, null, "👍")
        vm.state.first { it.error != null }
        account.chat.access.first { "extra" in it.roles.orEmpty() }
        flush(account)

        assertEquals(1, count("POST /channels/c1/messages/${message.id}/reactions")) // nada é reenviado
        assertEquals(2, count("GET /roles")) // a do sync e uma só, mesmo com a loja e o view model pedindo
        assertEquals(2, count("GET /server"))
        assertFalse(account.session.permissions.allowForbiddenRefresh()) // a janela já foi usada
    }

    @Test
    fun errosQueNaoSaoForbiddenNaoPedemReleitura() = runTest {
        val account = account()
        account.chat.sync()
        runCurrent()
        val vm = ChatViewModel(account.session, "c1")
        vm.state.first { it.loadedOnce }
        val message = vm.messages.first { it.isNotEmpty() }.first()

        mutationStatus = HttpStatusCode.InternalServerError
        vm.react(message, null, "👍")
        vm.state.first { it.error != null }
        vm.dismissError()

        failure = IOException("sem rede")
        vm.react(message, null, "👍")
        vm.state.first { it.error != null }

        flush(account)
        assertEquals(1, count("GET /roles"))
        assertTrue(account.session.permissions.allowForbiddenRefresh()) // a janela nem foi tocada
    }

    private companion object {
        const val MOD = "mod"
        const val READERS = "leitores"
        const val ROLES =
            """{"roles":[{"id":"mod","name":"Mod","permissions":{"pin_message":true,"everyone_message":true}},{"id":"leitores","name":"Leitores","permissions":{}}]}"""
        const val ROLES_EXTRA =
            """{"roles":[{"id":"mod","name":"Mod","permissions":{"pin_message":true,"everyone_message":true}},{"id":"leitores","permissions":{}},{"id":"extra","permissions":{}}]}"""
        const val EDITED = """{"id":"mine-c1","channel_id":"c1","author_id":"me","content":"corrigida","created_at":"2026-01-01T00:00:06Z"}"""
        const val SENT = """{"id":"s1","channel_id":"c1","author_id":"me","content":"oi","created_at":"2026-01-01T00:10:00Z"}"""

        fun history(channelId: String) =
            """{"messages":[
                {"id":"h-$channelId","channel_id":"$channelId","author_id":"u2","content":"do servidor","created_at":"2026-01-01T00:00:05Z"},
                {"id":"mine-$channelId","channel_id":"$channelId","author_id":"me","content":"minha","created_at":"2026-01-01T00:00:06Z"}],
                "has_more":true}"""

        fun entries(role: String, read: Boolean = false, send: Boolean = false) =
            """[{"role_id":"$role","role_name":"$role","permissions":{"read_channel":$read,"send_messages":$send}}]"""

        fun channel(id: String, entries: String = "[]") =
            """{"id":"$id","name":"$id","type":"text","position":1,"permissions":$entries}"""

        fun channels(vararg items: String) = """{"channels":[${items.joinToString(",")}]}"""

        fun user(id: String, roles: String = "[]") =
            """{"id":"$id","username":"$id","created_at":"2026-01-01T00:00:00Z","roles":$roles}"""

        fun users(vararg items: String) = """{"users":[${items.joinToString(",")}],"has_more":false}"""
    }
}
