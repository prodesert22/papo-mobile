package chat.papo.mobile

import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.Message
import chat.papo.mobile.data.ws.WsEvent
import chat.papo.mobile.ui.chat.ChatViewModel
import chat.papo.mobile.ui.components.UiError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Prévia da mensagem respondida e os gestos que mexem na conversa: ir até a original, procurá-la para
 * trás no histórico, saber que foi apagada e alternar o 👍 com o toque duplo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelReplyTest {
    private lateinit var session: AccountSession
    private lateinit var server: PagedChannel

    private fun TestScope.viewModel(server: PagedChannel): ChatViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        this@ChatViewModelReplyTest.server = server
        session = TestResources.session(server.engine, this)
        return ChatViewModel(session, "c1")
    }

    /**
     * O servidor falso responde em outra thread: espera a primeira leitura do canal terminar e o pedido das
     * fixadas, que sai em paralelo com ela, chegar ao servidor. Sem isso ele pode chegar depois de o teste
     * contar os pedidos.
     */
    private suspend fun ChatViewModel.loaded() {
        state.first { it.loadedOnce }
        TestResources.waitUntil { "GET /channels/c1/pinned" in server.requests }
    }

    private suspend fun ChatViewModel.message(id: String): Message = messages.first { list -> list.any { it.id == id } }.first { it.id == id }

    private fun PagedChannel.pages(from: Int) = requests.drop(from).filter { "/channels/c1/messages?" in it && "since=" in it }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun originalJaCarregadaVaiDireto() = runTest {
        val server = PagedChannel(30).apply { replies[30] = "m10" }
        val vm = viewModel(server)
        vm.loaded()
        val reply = vm.message("m30")
        // A prévia carregada: a original está na lista e ninguém a deu como apagada.
        assertTrue(vm.messages.value.any { it.id == "m10" })
        assertTrue(vm.state.value.missingReplies.isEmpty())
        val before = server.requests.size

        vm.openReply(reply)
        assertTrue(vm.state.value.locating)
        val state = vm.state.first { it.focusId != null }

        assertEquals("m10", state.focusId)
        assertFalse(state.locating)
        assertFalse(state.targetMissing)
        assertNull(state.replyNotReached)
        assertEquals(before, server.requests.size)
    }

    @Test
    fun originalNaoCarregadaEProcuradaParaTrasEAbreAJanelaAteElaEDestaca() = runTest {
        val server = PagedChannel(400).apply { replies[400] = "m5" }
        val vm = viewModel(server)
        vm.loaded()
        val reply = vm.message("m400")
        // A prévia ainda não carregada: não está na lista nem foi dada como apagada.
        assertFalse(vm.messages.value.any { it.id == "m5" })
        assertFalse("m5" in vm.state.value.missingReplies)
        val before = server.requests.size

        vm.openReply(reply)
        assertTrue(vm.state.value.locating)
        vm.loadOlder() // enquanto procura, quem pagina o histórico é a procura
        assertFalse(vm.state.value.loadingOlder)
        val state = vm.state.first { it.focusId != null }

        assertEquals("m5", state.focusId)
        assertFalse(state.locating)
        assertNull(state.error)
        // Quatro páginas de 100, cada uma a partir da mais antiga guardada, e a janela passa a alcançar a original.
        val pages = server.pages(before)
        assertEquals(4, pages.size)
        assertTrue(pages.all { "limit=100" in it && "order=desc" in it }, pages.toString())
        assertTrue("last_id=m361" in pages.first(), pages.toString())
        assertEquals(400, vm.messages.first { it.size == 400 }.size)
        assertEquals("m5", vm.messages.value.first { it.id == "m5" }.id)
    }

    @Test
    fun originalApagadaAvisaEFicaSabidamenteApagadaSemProcurarDeNovo() = runTest {
        val server = PagedChannel(250).apply {
            replies[250] = "m100"
            deleted += 100
        }
        val vm = viewModel(server)
        vm.loaded()
        val reply = vm.message("m250")
        assertFalse("m100" in vm.state.value.missingReplies) // até aqui é só uma prévia não carregada

        vm.openReply(reply)
        val state = vm.state.first { !it.locating && it.targetMissing }

        assertEquals(setOf("m100"), state.missingReplies)
        assertNull(state.focusId)
        assertNull(state.replyNotReached)
        assertNull(state.error)

        // Dada como apagada, tocar de novo não vai ao servidor.
        val requests = server.requests.size
        vm.dismissTargetMissing()
        vm.openReply(reply)
        assertFalse(vm.state.value.locating)
        assertFalse(vm.state.value.targetMissing)
        assertEquals(requests, server.requests.size)
    }

    @Test
    fun procuraQueParaNoLimiteOferecContinuarDaMaisAntigaCarregada() = runTest {
        val server = PagedChannel(1000).apply { replies[1000] = "apagada" }
        val vm = viewModel(server)
        vm.loaded()
        val reply = vm.message("m1000")
        val before = server.requests.size

        vm.openReply(reply)
        val stopped = vm.state.first { !it.locating }

        assertEquals("apagada", stopped.replyNotReached)
        assertFalse(stopped.targetMissing)
        assertTrue(stopped.missingReplies.isEmpty()) // ainda pode existir mais para trás
        assertNull(stopped.focusId)
        assertEquals(5, server.pages(before).size)

        val resumeFrom = server.requests.size
        vm.continueReplySearch()
        assertTrue(vm.state.value.locating)
        assertNull(vm.state.value.replyNotReached) // o aviso sai enquanto procura
        val finished = vm.state.first { !it.locating }

        // Recomeça da mais antiga carregada (m461) e agora chega ao começo do canal: foi apagada.
        assertTrue("last_id=m461" in server.pages(resumeFrom).first(), server.pages(resumeFrom).toString())
        assertTrue(finished.targetMissing)
        assertEquals(setOf("apagada"), finished.missingReplies)
        assertNull(finished.replyNotReached)
    }

    @Test
    fun dispensarOAvisoEContinuarSemNadaPendenteNaoFazemNada() = runTest {
        val server = PagedChannel(1000).apply { replies[1000] = "apagada" }
        val vm = viewModel(server)
        vm.loaded()
        val requests = server.requests.size

        vm.continueReplySearch() // nada pendente
        vm.openReply(vm.message("m999")) // não é uma resposta
        assertFalse(vm.state.value.locating)
        assertEquals(requests, server.requests.size)

        vm.openReply(vm.message("m1000"))
        vm.state.first { it.replyNotReached != null }
        vm.dismissReplyNotReached()
        assertNull(vm.state.value.replyNotReached)
        val afterDismiss = server.requests.size
        vm.continueReplySearch() // o aviso foi dispensado: nada a continuar
        assertFalse(vm.state.value.locating)
        assertEquals(afterDismiss, server.requests.size)
    }

    @Test
    fun segundoToqueEnquantoProcuraNaoAbreOutraProcura() = runTest {
        val server = PagedChannel(400).apply { replies[400] = "m5" }
        val vm = viewModel(server)
        vm.loaded()
        val reply = vm.message("m400")
        val before = server.requests.size

        vm.openReply(reply)
        vm.openReply(reply)
        vm.state.first { it.focusId != null }

        assertEquals(4, server.pages(before).size) // uma só procura
    }

    @Test
    fun falhaNaProcuraMostraOErroSemDarComoApagadaEPodeTentarDeNovo() = runTest {
        val server = PagedChannel(400).apply { replies[400] = "m5" }
        val vm = viewModel(server)
        vm.loaded()
        val reply = vm.message("m400")
        server.failPages = true

        vm.openReply(reply)
        val failed = vm.state.first { !it.locating }

        assertEquals(UiError.Server("Erro"), failed.error)
        assertTrue(failed.missingReplies.isEmpty())
        assertFalse(failed.targetMissing)
        assertNull(failed.replyNotReached)
        assertNull(failed.focusId)

        vm.dismissError()
        server.failPages = false
        vm.openReply(reply)
        assertEquals("m5", vm.state.first { it.focusId != null }.focusId)
    }

    @Test
    fun naoProcuraEnquantoVoltaParaAsRecentes() = runTest {
        val server = PagedChannel(400).apply { replies[400] = "m5" }
        val vm = viewModel(server)
        vm.loaded()
        val reply = vm.message("m400")
        val before = server.requests.size

        vm.goToLatest()
        vm.openReply(reply) // a tela está trocando de trecho: o toque é ignorado
        assertFalse(vm.state.value.locating)
        vm.state.first { !it.returning }

        assertTrue(server.pages(before).isEmpty())
    }

    @Test
    fun avisoAtrasadoDoDestaqueAnteriorNaoApagaODestaqueNovo() = runTest {
        val server = PagedChannel(30).apply { replies[30] = "m10"; replies[29] = "m5" }
        val vm = viewModel(server)
        vm.loaded()

        vm.openReply(vm.message("m30"))
        vm.state.first { it.focusId == "m10" }
        vm.openReply(vm.message("m29"))
        vm.state.first { it.focusId == "m5" }

        vm.clearFocus("m10") // a tela avisa que o destaque de m10 acabou, mas o de m5 já começou
        assertEquals("m5", vm.state.value.focusId)
        vm.clearFocus("m5")
        assertNull(vm.state.value.focusId)
        vm.openReply(vm.message("m30"))
        vm.state.first { it.focusId == "m10" }
        vm.clearFocus() // sem id: apaga o que houver
        assertNull(vm.state.value.focusId)
    }

    @Test
    fun servidorQueApagaUmaMensagemDoCanalAMarcaComoApagada() = runTest {
        val vm = viewModel(PagedChannel(3))
        vm.loaded()

        vm.onMessageDeleted(WsEvent.MessageDeleted("m1", "c1"))
        vm.onMessageDeleted(WsEvent.MessageDeleted("m9", "outro-canal")) // de outra conversa: não conta

        assertEquals(setOf("m1"), vm.state.value.missingReplies)
    }

    @Test
    fun apagarAPropriaMensagemAMarcaComoApagadaParaQuemRespondeu() = runTest {
        val vm = viewModel(PagedChannel(3))
        vm.loaded()

        vm.delete(vm.message("m2"))

        assertEquals(setOf("m2"), vm.state.first { "m2" in it.missingReplies }.missingReplies)
    }

    @Test
    fun toqueDuploAlternaOPolegarEIgnoraEnquantoAReacaoEstaEmVoo() = runTest {
        val server = PagedChannel(3)
        val vm = viewModel(server)
        vm.loaded()
        val third = vm.message("m3")
        val second = vm.message("m2")
        server.holdReactions = CompletableDeferred()

        vm.quickReact(third)
        server.reactionArrived.receive()
        vm.quickReact(third) // a primeira ainda não voltou: o segundo toque é ignorado
        vm.quickReact(second) // outra mensagem não espera por esta
        server.reactionArrived.receive()
        server.holdReactions!!.complete(Unit)
        vm.messages.first { list -> list.first { it.id == "m3" }.userReactions.isNotEmpty() }
        testScheduler.advanceUntilIdle()

        assertEquals(2, server.reactions.size)
        assertTrue(server.reactions.first().let { it.startsWith("POST m3 ") && "👍" in it }, server.reactions.toString())
        assertTrue(server.reactions.last().startsWith("POST m2 "), server.reactions.toString())

        // Já reagi: o toque seguinte tira o 👍, mesmo com a linha da tela ainda segurando a mensagem de antes.
        vm.quickReact(third)
        server.reactionArrived.receive()
        vm.messages.first { list -> list.first { it.id == "m3" }.userReactions.isEmpty() }
        assertTrue(server.reactions.last().let { it.startsWith("DELETE m3 ") && "👍" in it }, server.reactions.toString())
        assertNull(vm.state.value.error)
    }

    @Test
    fun toqueDuploEmMensagemForaDaJanelaUsaAQueATelaTem() = runTest {
        val server = PagedChannel(100)
        val vm = viewModel(server)
        vm.loaded()
        val outside = vm.message("m100").copy(id = "m77") // não está na janela da conversa

        vm.quickReact(outside)
        server.reactionArrived.receive()

        assertTrue(server.reactions.single().startsWith("POST m77 "), server.reactions.toString())
    }

    @Test
    fun falhaNaReacaoMostraOErroELiberaONovoToque() = runTest {
        val server = PagedChannel(3).apply { failReactions = true }
        val vm = viewModel(server)
        vm.loaded()
        val third = vm.message("m3")

        vm.quickReact(third)
        val failed = vm.state.first { it.error != null }
        assertEquals(UiError.Server("Proibido"), failed.error)
        testScheduler.advanceUntilIdle()

        // O toque seguinte não fica preso na reação que falhou.
        vm.dismissError()
        server.failReactions = false
        vm.quickReact(third)
        vm.messages.first { list -> list.first { it.id == "m3" }.userReactions.isNotEmpty() }
        assertEquals(2, server.reactions.size)
    }

    @Test
    fun listaDeApagadasTemLimiteEEsqueceAsMaisAntigas() = runTest {
        val vm = viewModel(PagedChannel(3))
        vm.loaded()

        repeat(205) { vm.onMessageDeleted(WsEvent.MessageDeleted("x$it", "c1")) }

        val missing = vm.state.value.missingReplies
        assertEquals(200, missing.size)
        assertFalse("x4" in missing)
        assertTrue("x5" in missing && "x204" in missing)
    }
}
