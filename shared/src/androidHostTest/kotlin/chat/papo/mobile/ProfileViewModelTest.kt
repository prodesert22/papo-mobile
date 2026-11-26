package chat.papo.mobile

import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.ui.components.UiError
import chat.papo.mobile.ui.profile.ProfileLoad
import chat.papo.mobile.ui.profile.ProfileUiState
import chat.papo.mobile.ui.profile.ProfileViewModel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.isSuccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A tela de perfil sobre o motor HTTP falso: ler, não achar, falhar, tentar de novo e abrir a conversa direta. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {
    /** Um servidor Papo com um perfil e a abertura de conversa direta; [profileStatus] e [dmStatus] mudam as respostas. */
    private class Server(@Volatile var nickname: String = "Bia Borges") {
        val requests = CopyOnWriteArrayList<String>()
        val bodies = CopyOnWriteArrayList<String>()
        @Volatile var profileStatus = HttpStatusCode.OK
        @Volatile var dmStatus = HttpStatusCode.OK
        @Volatile var offline = false
        @Volatile var dmGate: CompletableDeferred<Unit>? = null
        @Volatile var profileGate: CompletableDeferred<Unit>? = null

        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            requests += "${request.method.value} $path"
            if (request.method == HttpMethod.Post) bodies += request.body.toByteArray().decodeToString()
            if (offline) throw IOException("sem rede")
            fun json(text: String, status: HttpStatusCode) =
                respond(text, status, headersOf(HttpHeaders.ContentType, if (status.isSuccess()) "application/json" else "application/problem+json"))
            fun problem(status: HttpStatusCode) = json("""{"type":"t","title":"Erro","status":${status.value},"detail":"falhou ${status.value}"}""", status)
            when {
                request.method == HttpMethod.Get && path.endsWith("/profile") -> {
                    profileGate?.await()
                    if (profileStatus.isSuccess()) {
                        json(
                            """{"id":"u2","username":"bia","nickname":"$nickname","description":"Mexo com infra","status_message":"Revisando",""" +
                                """"created_at":"2026-01-04T10:30:00Z","roles":[{"id":"r1","name":"Admin","color":"#E86CA5"}]}""",
                            profileStatus,
                        )
                    } else {
                        problem(profileStatus)
                    }
                }
                request.method == HttpMethod.Post && path == "/dms" -> {
                    dmGate?.await()
                    if (dmStatus.isSuccess()) {
                        json("""{"id":"dm1","user":{"id":"u2","username":"bia"},"created_at":"2026-01-01T00:00:00Z"}""", dmStatus)
                    } else {
                        problem(dmStatus)
                    }
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
    }

    private fun TestScope.session(server: Server, userId: String = "u1"): AccountSession {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return TestResources.session(server.engine, this, userId)
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** O motor HTTP falso responde em outra thread: espera a leitura terminar. */
    private suspend fun ProfileViewModel.settled(): ProfileUiState = state.first { it.load !is ProfileLoad.Loading }

    @Test
    fun abreCarregandoELeOPerfilUmaVez() = runTest {
        val server = Server()
        val vm = ProfileViewModel(session(server), "u2")
        assertEquals(ProfileLoad.Loading, vm.state.value.load)

        val loaded = assertIs<ProfileLoad.Loaded>(vm.settled().load)

        assertEquals("Bia Borges", loaded.profile.displayName)
        assertEquals("Mexo com infra", loaded.profile.description)
        assertEquals(listOf("Admin"), loaded.profile.roles.map { it.name })
        assertEquals(listOf("GET /users/u2/profile"), server.requests.toList())
    }

    @Test
    fun abrirOMesmoPerfilDeNovoNaSessaoNaoVoltaAoServidor() = runTest {
        val server = Server()
        val session = session(server)
        ProfileViewModel(session, "u2").settled()

        val again = ProfileViewModel(session, "u2")

        assertIs<ProfileLoad.Loaded>(again.settled().load)
        assertEquals(1, server.requests.size)
    }

    @Test
    fun mostrarOCacheDepoisDeInvalidarTrazOPerfilNovoSemPassarPorCarregando() = runTest {
        val server = Server()
        val session = session(server)
        val vm = ProfileViewModel(session, "u2")
        vm.settled()

        session.profiles.invalidate("u2")
        server.nickname = "Bia Souza"
        vm.showCached()
        assertIs<ProfileLoad.Loaded>(vm.state.value.load)
        val shown = vm.state.first { (it.load as? ProfileLoad.Loaded)?.profile?.nickname == "Bia Souza" }

        assertIs<ProfileLoad.Loaded>(shown.load)
        assertEquals(2, server.requests.size)
    }

    @Test
    fun mostrarOCacheDentroDaValidadeNaoVoltaAoServidor() = runTest {
        val server = Server()
        val vm = ProfileViewModel(session(server), "u2")
        vm.settled()

        vm.showCached()
        runCurrent()

        assertEquals(1, server.requests.size)
    }

    @Test
    fun mostrarOCacheSemPerfilNaTelaOuComFalhaNaoMudaNada() = runTest {
        val server = Server().also { it.profileStatus = HttpStatusCode.InternalServerError }
        val session = session(server)
        val vm = ProfileViewModel(session, "u2")
        val failed = vm.settled().load
        vm.showCached()
        runCurrent()
        assertEquals(failed, vm.state.value.load)
        assertEquals(1, server.requests.size)

        server.profileStatus = HttpStatusCode.OK
        vm.retry()
        val loaded = vm.settled().load
        session.profiles.invalidate("u2")
        server.profileStatus = HttpStatusCode.InternalServerError
        vm.showCached()
        TestResources.waitUntil { server.requests.size == 3 }
        runCurrent()

        // A leitura nova falhou: o perfil que estava na tela continua.
        assertEquals(loaded, vm.state.value.load)
    }

    @Test
    fun usuarioInexistenteViraNaoEncontrado() = runTest {
        val server = Server().also { it.profileStatus = HttpStatusCode.NotFound }
        val vm = ProfileViewModel(session(server), "u2")

        assertEquals(ProfileLoad.NotFound, vm.settled().load)
        assertEquals(1, server.requests.size)
    }

    @Test
    fun erroDoServidorMostraOMotivoENadaSeRepeteSozinho() = runTest {
        val server = Server().also { it.profileStatus = HttpStatusCode.InternalServerError }
        val vm = ProfileViewModel(session(server), "u2")

        val failed = assertIs<ProfileLoad.Failed>(vm.settled().load)
        runCurrent()

        assertEquals(UiError.Server("falhou 500"), failed.error)
        assertEquals(1, server.requests.size)
    }

    @Test
    fun semRedeViraErroDeRede() = runTest {
        val server = Server().also { it.offline = true }
        val vm = ProfileViewModel(session(server), "u2")

        assertEquals(ProfileLoad.Failed(UiError.Network), vm.settled().load)
    }

    @Test
    fun tentarDeNovoLeDoServidorEMostraOPerfil() = runTest {
        val server = Server().also { it.profileStatus = HttpStatusCode.InternalServerError }
        val vm = ProfileViewModel(session(server), "u2")
        assertIs<ProfileLoad.Failed>(vm.settled().load)

        server.profileStatus = HttpStatusCode.OK
        vm.retry()
        assertEquals(ProfileLoad.Loading, vm.state.value.load)

        assertIs<ProfileLoad.Loaded>(vm.settled().load)
        assertEquals(2, server.requests.size)
    }

    @Test
    fun tentarDeNovoEnquantoLeNaoMostraErroDeCancelamentoEOPerfilChega() = runTest {
        val server = Server().also { it.profileGate = CompletableDeferred() }
        val vm = ProfileViewModel(session(server), "u2")
        TestResources.waitUntil { server.requests.isNotEmpty() }
        runCurrent()

        vm.retry()
        runCurrent()
        server.profileGate!!.complete(Unit)

        assertIs<ProfileLoad.Loaded>(vm.settled().load)
        // As duas leituras dividiram o pedido que já estava em andamento.
        assertEquals(1, server.requests.size)
    }

    @Test
    fun abrirConversaChamaOCallbackComOIdEPedeUmaVez() = runTest {
        val server = Server()
        val vm = ProfileViewModel(session(server), "u2")
        vm.settled()
        var opened: String? = null

        vm.openChat { opened = it }
        assertTrue(vm.state.value.opening)
        vm.state.first { !it.opening }

        assertEquals("dm1", opened)
        assertNull(vm.state.value.openError)
        assertEquals(listOf("""{"user_id":"u2"}"""), server.bodies.toList())
        assertEquals(1, server.requests.count { it == "POST /dms" })
    }

    @Test
    fun enquantoAConversaAbreOBotaoNaoPedeDeNovo() = runTest {
        val server = Server().also { it.dmGate = CompletableDeferred() }
        val vm = ProfileViewModel(session(server), "u2")
        vm.settled()
        val opened = mutableListOf<String>()

        vm.openChat { opened += it }
        TestResources.waitUntil { server.requests.contains("POST /dms") }
        vm.openChat { opened += it }
        vm.openChat { opened += it }
        runCurrent()
        server.dmGate!!.complete(Unit)
        vm.state.first { !it.opening }

        assertEquals(listOf("dm1"), opened)
        assertEquals(1, server.requests.count { it == "POST /dms" })
    }

    @Test
    fun falhaAoAbrirAConversaApareceNoLugarENaoSeRepete() = runTest {
        val server = Server().also { it.dmStatus = HttpStatusCode.Forbidden }
        val vm = ProfileViewModel(session(server), "u2")
        vm.settled()
        var opened = false

        vm.openChat { opened = true }
        val state = vm.state.first { !it.opening }
        runCurrent()

        assertEquals(UiError.Server("falhou 403"), state.openError)
        assertFalse(opened)
        // O perfil continua na tela.
        assertIs<ProfileLoad.Loaded>(state.load)
        assertEquals(1, server.requests.count { it == "POST /dms" })
    }

    @Test
    fun novaTentativaDepoisDeFalharLimpaOErroEAbreAConversa() = runTest {
        val server = Server().also { it.dmStatus = HttpStatusCode.InternalServerError }
        val vm = ProfileViewModel(session(server), "u2")
        vm.settled()
        vm.openChat { }
        vm.state.first { !it.opening && it.openError != null }

        server.dmStatus = HttpStatusCode.OK
        var opened: String? = null
        vm.openChat { opened = it }
        assertNull(vm.state.value.openError)
        vm.state.first { !it.opening }

        assertEquals("dm1", opened)
        assertEquals(2, server.requests.count { it == "POST /dms" })
    }

    @Test
    fun noProprioPerfilNaoHaConversaParaAbrir() = runTest {
        val server = Server()
        val vm = ProfileViewModel(session(server, userId = "u2"), "u2")
        vm.settled()
        var opened = false

        vm.openChat { opened = true }
        runCurrent()

        assertFalse(opened)
        assertFalse(vm.state.value.opening)
        assertEquals(0, server.requests.count { it == "POST /dms" })
    }

    @Test
    fun duasSessoesNaoCompartilhamOPerfil() = runTest {
        val first = Server(nickname = "Bia Borges")
        val second = Server(nickname = "Bia do Outro Servidor")
        val firstSession = session(first)
        val secondSession = session(second)

        val a = assertIs<ProfileLoad.Loaded>(ProfileViewModel(firstSession, "u2").settled().load)
        val b = assertIs<ProfileLoad.Loaded>(ProfileViewModel(secondSession, "u2").settled().load)

        assertEquals("Bia Borges", a.profile.displayName)
        assertEquals("Bia do Outro Servidor", b.profile.displayName)
        assertEquals(1, first.requests.size)
        assertEquals(1, second.requests.size)
    }
}
