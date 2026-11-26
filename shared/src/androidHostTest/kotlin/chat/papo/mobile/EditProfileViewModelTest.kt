package chat.papo.mobile

import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.avatarMediaName
import chat.papo.mobile.core.MutationResult
import chat.papo.mobile.data.api.OutgoingFile
import chat.papo.mobile.media.ImageReducer
import chat.papo.mobile.media.ImageReduction
import chat.papo.mobile.media.ImageRefusal
import chat.papo.mobile.media.ProfileDraft
import chat.papo.mobile.media.ProfileField
import chat.papo.mobile.media.ProfileImageKind
import chat.papo.mobile.media.ReducedImage
import chat.papo.mobile.ui.components.UiError
import chat.papo.mobile.ui.profile.BannerChange
import chat.papo.mobile.ui.profile.EditLoad
import chat.papo.mobile.ui.profile.EditProfileState
import chat.papo.mobile.ui.profile.EditProfileViewModel
import chat.papo.mobile.ui.profile.SaveStep
import chat.papo.mobile.ui.profile.StepOutcome
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A edição do próprio perfil sobre o motor HTTP falso: o que cada "Salvar" manda e em que ordem, o que cada resposta vira
 * e a garantia de que nenhum pedido é repetido sozinho.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditProfileViewModelTest {
    private class Server {
        /** Cada pedido como "MÉTODO caminho", na ordem em que chegou. */
        val requests = CopyOnWriteArrayList<String>()

        /** O corpo de cada PUT, por caminho, na ordem em que chegou. */
        val bodies = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()

        /** O status de resposta de cada PUT, por caminho (200 se não consta). */
        val putStatus = ConcurrentHashMap<String, HttpStatusCode>()

        /** Um PUT a este caminho espera o portão abrir antes de responder. */
        val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

        @Volatile var profileStatus = HttpStatusCode.OK
        @Volatile var offline = false
        @Volatile var nickname = "Ana Lima"
        @Volatile var statusMessage = "Em reunião"
        @Volatile var description = "Desenho telas."
        @Volatile var typing: String? = "digitando…"
        @Volatile var banner: String? = "a".repeat(64)

        fun puts(path: String) = bodies[path].orEmpty().toList()

        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            requests += "${request.method.value} $path"
            if (request.method == HttpMethod.Put) bodies.getOrPut(path) { CopyOnWriteArrayList() } += request.body.toByteArray().decodeToString()
            if (offline) throw IOException("sem rede")
            fun json(text: String, status: HttpStatusCode = HttpStatusCode.OK) =
                respond(text, status, headersOf(HttpHeaders.ContentType, if (status.isSuccess()) "application/json" else "application/problem+json"))
            when {
                request.method == HttpMethod.Get && path == "/users/u1/profile" ->
                    if (profileStatus.isSuccess()) {
                        json(
                            """{"id":"u1","username":"ana","nickname":"$nickname","description":"$description","status_message":"$statusMessage",""" +
                                """"typing":${typing?.let { "\"$it\"" }},"banner_media":${banner?.let { "\"$it\"" }},"created_at":"2026-01-04T10:30:00Z","roles":[]}""",
                        )
                    } else {
                        json("""{"type":"t","title":"Erro","status":${profileStatus.value},"detail":"falhou ${profileStatus.value}"}""", profileStatus)
                    }
                request.method == HttpMethod.Put -> {
                    gates[path]?.await()
                    val status = putStatus[path] ?: HttpStatusCode.OK
                    if (status.isSuccess()) json("""{"message":"ok"}""") else json("""{"type":"t","title":"Erro","status":${status.value},"detail":"recusou ${status.value}"}""", status)
                }
                path == "/channels" -> json("""{"channels":[]}""")
                path == "/dms" -> json("""{"dms":[]}""")
                path == "/users" -> json("""{"users":[{"id":"u1","username":"ana"},{"id":"u2","username":"bia"}],"has_more":false}""")
                else -> respond("", HttpStatusCode.NoContent)
            }
        }
    }

    /** Um redutor que devolve o que o teste manda e anota o que recebeu. */
    private class FakeReducer(var next: ImageReduction) : ImageReducer {
        val calls = CopyOnWriteArrayList<Pair<Int, ProfileImageKind>>()

        override suspend fun reduce(source: ByteArray, kind: ProfileImageKind): ImageReduction {
            calls += source.size to kind
            return next
        }
    }

    private val pngBytes = byteArrayOf(1, 2, 3, 4, 5)
    private val webpBytes = byteArrayOf(9, 8, 7)

    private fun png() = ImageReduction.Ready(ReducedImage(pngBytes, "image/png", 512, 512, animated = false))

    private fun webp() = ImageReduction.Ready(ReducedImage(webpBytes, "image/webp", 1536, 512, animated = false))

    private fun TestScope.session(server: Server, userId: String = "u1"): AccountSession {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return TestResources.session(server.engine, this, userId)
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(server: Server, reducer: ImageReducer = FakeReducer(png())) =
        EditProfileViewModel(session(server), reducer, io = Dispatchers.Unconfined)

    private suspend fun EditProfileViewModel.ready(): EditProfileState = state.first { it.load !is EditLoad.Loading }

    private suspend fun EditProfileViewModel.finished(): EditProfileState = state.first { !it.saving && it.outcomes.isNotEmpty() }

    private suspend fun EditProfileViewModel.picked(kind: ProfileImageKind, file: OutgoingFile = bytesFile("f.jpg", "image/jpeg", byteArrayOf(0))) {
        pick(kind, file)
        state.first { it.reducing == null && (it.avatar != null || it.banner != BannerChange.Keep || it.refusals.isNotEmpty()) }
    }

    // Abrir

    @Test
    fun abreLendoOPerfilDoServidorEComecaSemNadaParaSalvar() = runTest {
        val server = Server()
        val vm = viewModel(server)

        val state = vm.ready()

        assertEquals(ProfileDraft("Ana Lima", "Em reunião", "Desenho telas.", "digitando…"), state.draft)
        assertEquals(state.draft, state.saved)
        assertTrue(state.hasBanner)
        assertFalse(state.textChanged)
        assertFalse(state.canSave)
        // Lê do servidor, sem usar o que a sessão tinha guardado: a descrição não tem evento.
        assertEquals(listOf("GET /users/u1/profile"), server.requests.toList())
    }

    @Test
    fun falhaAoLerMostraOErroEOutraLeituraRecomecaOsTextos() = runTest {
        val server = Server().also { it.profileStatus = HttpStatusCode.InternalServerError }
        val vm = viewModel(server)
        assertEquals(EditLoad.Failed(UiError.Server("falhou 500")), vm.ready().load)
        runCurrent()
        assertEquals(1, server.requests.size)

        server.profileStatus = HttpStatusCode.OK
        vm.reload()
        assertEquals(EditLoad.Loading, vm.state.value.load)
        val state = vm.ready()

        assertIs<EditLoad.Ready>(state.load)
        assertEquals("Ana Lima", state.draft.nickname)
        assertEquals(2, server.requests.size)
    }

    // Salvar o texto

    @Test
    fun salvarSoOTextoMandaUmPutComOsTresCamposObrigatoriosESemAFraseDeDigitacao() = runTest {
        val server = Server()
        val vm = viewModel(server)
        vm.ready()

        vm.edit(ProfileField.Nickname, "  Ana L.  ")
        assertTrue(vm.state.value.canSave)
        server.nickname = "Ana L." // o servidor guarda o que recebeu; a leitura depois de salvar o traz de volta
        vm.save()
        val done = vm.finished()

        assertEquals("""{"nickname":"Ana L.","status":"Em reunião","description":"Desenho telas."}""", server.puts("/users/u1").single())
        assertEquals(StepOutcome.Saved, done.outcomes[SaveStep.Text])
        assertFalse(done.canSave)
        // Nenhum outro PUT, e o perfil é lido de novo depois (um GET), não reenviado.
        assertEquals(listOf("GET /users/u1/profile", "PUT /users/u1", "GET /users/u1/profile"), server.requests.toList())
    }

    @Test
    fun seALeituraDepoisDeSalvarFalharOQueFoiSalvoContinuaSalvo() = runTest {
        val server = Server()
        val vm = viewModel(server)
        vm.ready()
        vm.edit(ProfileField.Nickname, "Ana L.")

        server.profileStatus = HttpStatusCode.InternalServerError
        vm.save()
        val done = vm.finished()

        assertEquals(StepOutcome.Saved, done.outcomes[SaveStep.Text])
        assertEquals("Ana L.", done.saved.nickname)
        assertIs<EditLoad.Ready>(done.load)
        assertFalse(done.canSave)
        assertEquals(listOf("GET /users/u1/profile", "PUT /users/u1", "GET /users/u1/profile"), server.requests.toList())
    }

    @Test
    fun aFraseDeDigitacaoSoVaiQuandoMudou() = runTest {
        val server = Server()
        val vm = viewModel(server)
        vm.ready()

        vm.edit(ProfileField.Typing, "pensando…")
        vm.save()
        vm.finished()

        assertEquals(
            """{"nickname":"Ana Lima","status":"Em reunião","description":"Desenho telas.","typing":"pensando…"}""",
            server.puts("/users/u1").single(),
        )
    }

    @Test
    fun textoAcimaDoLimiteNaoSalvaENadaVaiAoServidor() = runTest {
        val server = Server()
        val vm = viewModel(server)
        vm.ready()

        vm.edit(ProfileField.Nickname, "a".repeat(33))
        assertEquals(listOf(ProfileField.Nickname), vm.state.value.overLimit)
        assertFalse(vm.state.value.canSave)
        vm.save()
        runCurrent()

        assertEquals(listOf("GET /users/u1/profile"), server.requests.toList())
        assertTrue(vm.state.value.outcomes.isEmpty())
    }

    @Test
    fun voltarAoTextoOriginalApagaAMudancaEDesligaOSalvar() = runTest {
        val vm = viewModel(Server())
        vm.ready()

        vm.edit(ProfileField.Description, "outra")
        assertTrue(vm.state.value.canSave)
        vm.edit(ProfileField.Description, "Desenho telas. ")

        assertFalse(vm.state.value.textChanged)
        assertFalse(vm.state.value.canSave)
    }

    // Avatar e banner

    @Test
    fun salvarSoOAvatarMandaOBase64EOFormatoSemMexerNoTexto() = runTest {
        val server = Server()
        val reducer = FakeReducer(png())
        val vm = viewModel(server, reducer)
        vm.ready()

        vm.picked(ProfileImageKind.Avatar, bytesFile("foto.jpg", "image/jpeg", ByteArray(100)))
        assertEquals(listOf(100 to ProfileImageKind.Avatar), reducer.calls.toList())
        assertTrue(vm.state.value.canSave)
        vm.save()
        val done = vm.finished()

        assertEquals("""{"avatar":"${Base64.encode(pngBytes)}","avatar_format":"PNG"}""", server.puts("/users/u1/avatar").single())
        assertEquals(StepOutcome.Saved, done.outcomes[SaveStep.Avatar])
        assertNull(done.outcomes[SaveStep.Text])
        assertNull(done.avatar)
        assertTrue(server.puts("/users/u1").isEmpty())
    }

    @Test
    fun bannerNovoVaiComOFormatoDoRedutor() = runTest {
        val server = Server()
        val vm = viewModel(server, FakeReducer(webp()))
        vm.ready()

        vm.picked(ProfileImageKind.Banner)
        vm.save()
        val done = vm.finished()

        assertEquals("""{"banner":"${Base64.encode(webpBytes)}","banner_format":"WEBP"}""", server.puts("/users/u1/banner").single())
        assertEquals(BannerChange.Keep, done.banner)
    }

    @Test
    fun removerOBannerMandaOsDoisCamposVaziosEOPerfilPassaASemBanner() = runTest {
        val server = Server()
        val vm = viewModel(server)
        assertTrue(vm.ready().hasBanner)

        vm.removeBanner()
        assertEquals(BannerChange.Remove, vm.state.value.banner)
        assertTrue(vm.state.value.canSave)
        server.banner = null
        vm.save()
        val done = vm.finished()

        assertEquals("""{"banner":"","banner_format":""}""", server.puts("/users/u1/banner").single())
        assertFalse(done.hasBanner)
        assertEquals(BannerChange.Keep, done.banner)
    }

    @Test
    fun osPedidosSaemNaOrdemTextoAvatarBannerEOResultadoDeCadaUmFicaNoEstado() = runTest {
        val server = Server()
        val vm = viewModel(server)
        vm.ready()
        vm.edit(ProfileField.StatusMessage, "Livre")
        vm.picked(ProfileImageKind.Banner)
        vm.picked(ProfileImageKind.Avatar)

        vm.save()
        val done = vm.finished()

        assertEquals(
            listOf("GET /users/u1/profile", "PUT /users/u1", "PUT /users/u1/avatar", "PUT /users/u1/banner", "GET /users/u1/profile"),
            server.requests.toList(),
        )
        assertEquals(mapOf(SaveStep.Text to StepOutcome.Saved, SaveStep.Avatar to StepOutcome.Saved, SaveStep.Banner to StepOutcome.Saved), done.outcomes)
    }

    @Test
    fun oAvatarNovoApareceNaHoraComAVersaoSubidaEAImagemNoCache() = runTest {
        val server = Server()
        val session = session(server)
        session.chat.sync()
        assertEquals(0L, session.chat.users.first { "u1" in it }.getValue("u1").avatarVersion)
        val vm = EditProfileViewModel(session, FakeReducer(png()), io = Dispatchers.Unconfined)
        vm.ready()

        vm.picked(ProfileImageKind.Avatar)
        vm.save()
        vm.finished()

        assertEquals(1L, session.chat.users.first { it["u1"]?.avatarVersion == 1L }.getValue("u1").avatarVersion)
        assertContentEquals(pngBytes, session.media.peek(avatarMediaName("u1", 1)))
    }

    // Falhas

    @Test
    fun recusaDoServidorMostraODetalheEOsOutrosPedidosAindaSaem() = runTest {
        val server = Server().also { it.putStatus["/users/u1"] = HttpStatusCode.BadRequest }
        val vm = viewModel(server)
        vm.ready()
        vm.edit(ProfileField.Nickname, "Novo")
        vm.picked(ProfileImageKind.Avatar)

        vm.save()
        val done = vm.finished()

        assertEquals(StepOutcome.Failed(MutationResult.Rejected("recusou 400", "t")), done.outcomes[SaveStep.Text])
        assertEquals(StepOutcome.Saved, done.outcomes[SaveStep.Avatar])
        // O texto continua à espera de o usuário corrigir e salvar de novo.
        assertTrue(done.textChanged)
        assertTrue(done.canSave)
        assertEquals(1, server.puts("/users/u1").size)
    }

    @Test
    fun erro5xxNoTextoEIncertoMandaUmPutSoEOsProximosNaoSaem() = runTest {
        val server = Server().also { it.putStatus["/users/u1"] = HttpStatusCode.InternalServerError }
        val vm = viewModel(server)
        vm.ready()
        vm.edit(ProfileField.Nickname, "Novo")
        vm.picked(ProfileImageKind.Avatar)
        vm.picked(ProfileImageKind.Banner)

        vm.save()
        val done = vm.finished()
        runCurrent()

        assertEquals(StepOutcome.Failed(MutationResult.Uncertain), done.outcomes[SaveStep.Text])
        assertEquals(StepOutcome.Skipped, done.outcomes[SaveStep.Avatar])
        assertEquals(StepOutcome.Skipped, done.outcomes[SaveStep.Banner])
        assertEquals(1, server.puts("/users/u1").size)
        assertTrue(server.puts("/users/u1/avatar").isEmpty())
        assertTrue(server.puts("/users/u1/banner").isEmpty())
        // Nada foi salvo: não há leitura extra do perfil.
        assertEquals(listOf("GET /users/u1/profile", "PUT /users/u1"), server.requests.toList())
    }

    @Test
    fun limiteDeTaxaMandaUmPutEPedeParaEsperar() = runTest {
        val server = Server().also { it.putStatus["/users/u1/avatar"] = HttpStatusCode.TooManyRequests }
        val vm = viewModel(server)
        vm.ready()
        vm.picked(ProfileImageKind.Avatar)
        vm.picked(ProfileImageKind.Banner)

        vm.save()
        val done = vm.finished()
        runCurrent()

        assertEquals(StepOutcome.Failed(MutationResult.RateLimited), done.outcomes[SaveStep.Avatar])
        assertEquals(StepOutcome.Skipped, done.outcomes[SaveStep.Banner])
        assertEquals(1, server.puts("/users/u1/avatar").size)
        assertTrue(server.puts("/users/u1/banner").isEmpty())
        // O avatar continua escolhido: tentar de novo é decisão do usuário.
        assertTrue(done.avatar != null && done.canSave)
    }

    @Test
    fun semRedeDepoisDeLerOPerfilFicaIncertoENadaSeRepete() = runTest {
        val server = Server()
        val vm = viewModel(server)
        vm.ready()
        vm.edit(ProfileField.Nickname, "Novo")

        server.offline = true
        vm.save()
        val done = vm.finished()
        runCurrent()

        assertEquals(StepOutcome.Failed(MutationResult.Uncertain), done.outcomes[SaveStep.Text])
        assertEquals(1, server.puts("/users/u1").size)
    }

    @Test
    fun recarregarDepoisDeUmaRespostaIncertaLeOPerfilEMostraOQueOServidorGuardou() = runTest {
        val server = Server().also { it.putStatus["/users/u1"] = HttpStatusCode.InternalServerError }
        val vm = viewModel(server)
        vm.ready()
        vm.edit(ProfileField.Nickname, "Novo Nome")
        vm.save()
        vm.finished()

        // O servidor tinha aplicado a mudança antes de falhar.
        server.nickname = "Novo Nome"
        vm.reload()
        val state = vm.ready()

        assertEquals("Novo Nome", state.draft.nickname)
        assertFalse(state.textChanged)
        assertTrue(state.outcomes.isEmpty())
        assertEquals(listOf("GET /users/u1/profile", "PUT /users/u1", "GET /users/u1/profile"), server.requests.toList())
    }

    @Test
    fun enquantoSalvaOBotaoEsperaENadaMaisMudaNemSaiDeNovo() = runTest {
        val server = Server().also { it.gates["/users/u1"] = CompletableDeferred() }
        val vm = viewModel(server)
        vm.ready()
        vm.edit(ProfileField.Nickname, "Novo")

        vm.save()
        TestResources.waitUntil { server.puts("/users/u1").isNotEmpty() }
        runCurrent()
        assertTrue(vm.state.value.saving)
        assertEquals(StepOutcome.Sending, vm.state.value.outcomes[SaveStep.Text])
        assertFalse(vm.state.value.canSave)
        vm.save()
        vm.edit(ProfileField.Nickname, "Outro")
        vm.removeBanner()
        vm.discardBanner()
        vm.discardAvatar()
        vm.pick(ProfileImageKind.Avatar, bytesFile("f.jpg", "image/jpeg", byteArrayOf(0)))
        vm.reload()
        runCurrent()
        assertEquals("Novo", vm.state.value.draft.nickname)
        assertNull(vm.state.value.avatar)
        assertNull(vm.state.value.reducing)
        assertEquals(BannerChange.Keep, vm.state.value.banner)

        server.gates.getValue("/users/u1").complete(Unit)
        vm.finished()

        assertEquals(1, server.puts("/users/u1").size)
    }

    // Escolher a imagem

    @Test
    fun imagemRecusadaGuardaOMotivoESeEscolherOutraLimpaOMotivo() = runTest {
        val server = Server()
        val reducer = FakeReducer(ImageReduction.Refused(ImageRefusal.AnimationTooLarge))
        val vm = viewModel(server, reducer)
        vm.ready()

        vm.picked(ProfileImageKind.Avatar)
        assertEquals(ImageRefusal.AnimationTooLarge, vm.state.value.refusals[ProfileImageKind.Avatar])
        assertNull(vm.state.value.avatar)
        assertFalse(vm.state.value.canSave)

        reducer.next = png()
        vm.picked(ProfileImageKind.Avatar)
        assertNull(vm.state.value.refusals[ProfileImageKind.Avatar])
        assertTrue(vm.state.value.avatar != null)
    }

    @Test
    fun arquivoGrandeDemaisNemChegaAoRedutor() = runTest {
        val reducer = FakeReducer(png())
        val vm = viewModel(Server(), reducer)
        vm.ready()

        vm.picked(ProfileImageKind.Banner, sizedFile("enorme.png", 40L * 1024 * 1024, "image/png"))

        assertEquals(ImageRefusal.SourceTooLarge, vm.state.value.refusals[ProfileImageKind.Banner])
        assertTrue(reducer.calls.isEmpty())
    }

    @Test
    fun arquivoQueOProvedorNaoAbreViraIlegivel() = runTest {
        val reducer = FakeReducer(png())
        val vm = viewModel(Server(), reducer)
        vm.ready()
        val broken = OutgoingFile("x.png", "image/png", 10, open = { throw IOException("sumiu") })

        vm.picked(ProfileImageKind.Avatar, broken)

        assertEquals(ImageRefusal.Unreadable, vm.state.value.refusals[ProfileImageKind.Avatar])
        assertTrue(reducer.calls.isEmpty())
    }

    @Test
    fun formatoQueOServidorNaoAceitaERecusadoAntesDoEnvio() = runTest {
        val heic = ImageReduction.Ready(ReducedImage(pngBytes, "image/heic", 10, 10, animated = false))
        val vm = viewModel(Server(), FakeReducer(heic))
        vm.ready()

        vm.picked(ProfileImageKind.Avatar)

        assertEquals(ImageRefusal.NotAnImage, vm.state.value.refusals[ProfileImageKind.Avatar])
        assertNull(vm.state.value.avatar)
    }

    @Test
    fun descartarAvatarEBannerTiraAEscolhaEOQueEstavaSalvoSeguraOSalvar() = runTest {
        val vm = viewModel(Server())
        vm.ready()
        vm.picked(ProfileImageKind.Avatar)
        vm.picked(ProfileImageKind.Banner)
        assertTrue(vm.state.value.canSave)

        vm.discardAvatar()
        vm.discardBanner()

        assertNull(vm.state.value.avatar)
        assertEquals(BannerChange.Keep, vm.state.value.banner)
        assertFalse(vm.state.value.canSave)

        vm.removeBanner()
        vm.discardBanner()
        assertEquals(BannerChange.Keep, vm.state.value.banner)
    }

    @Test
    fun salvarSemNadaMudadoNaoFazNada() = runTest {
        val server = Server()
        val vm = viewModel(server)
        vm.ready()

        vm.save()
        runCurrent()

        assertEquals(listOf("GET /users/u1/profile"), server.requests.toList())
        assertFalse(vm.state.value.saving)
    }

    @Test
    fun enquantoAImagemEPreparadaOSalvarEsperaEEscolherOutraTrocaAAnterior() = runTest {
        val gate = CompletableDeferred<Unit>()
        val slow = object : ImageReducer {
            override suspend fun reduce(source: ByteArray, kind: ProfileImageKind): ImageReduction {
                gate.await()
                return png()
            }
        }
        val vm = viewModel(Server(), slow)
        vm.ready()
        vm.edit(ProfileField.Nickname, "Novo")

        vm.pick(ProfileImageKind.Avatar, bytesFile("a.jpg", "image/jpeg", byteArrayOf(1)))
        runCurrent()
        assertEquals(ProfileImageKind.Avatar, vm.state.value.reducing)
        assertFalse(vm.state.value.canSave)
        vm.pick(ProfileImageKind.Avatar, bytesFile("b.jpg", "image/jpeg", byteArrayOf(2)))
        runCurrent()
        gate.complete(Unit)
        vm.state.first { it.reducing == null }

        assertTrue(vm.state.value.canSave)
        assertTrue(vm.state.value.avatar != null)
    }
}
