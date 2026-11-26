package chat.papo.mobile

import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.ui.chat.ChatViewModel
import chat.papo.mobile.ui.chat.MessageTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Levar a conversa a uma mensagem que já está nela (a barra de áudio, quando o áudio é da conversa aberta). */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelShowMessageTest {
    private lateinit var session: AccountSession
    private lateinit var server: PagedChannel

    private fun TestScope.viewModel(server: PagedChannel): ChatViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        this@ChatViewModelShowMessageTest.server = server
        session = TestResources.session(server.engine, this)
        return ChatViewModel(session, "c1")
    }

    /** Espera a primeira leitura do canal e o pedido das fixadas, que sai em paralelo, para contar os pedidos depois. */
    private suspend fun ChatViewModel.loaded() {
        state.first { it.loadedOnce }
        TestResources.waitUntil { "GET /channels/c1/pinned" in server.requests }
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun mensagemJaCarregadaRolaAteElaSemPedirNadaAoServidor() = runTest {
        val vm = viewModel(PagedChannel(30))
        vm.loaded()
        val before = server.requests.size

        vm.showMessage(MessageTarget("m10", server.ts(10)))
        assertTrue(vm.state.value.locating)
        val state = vm.state.first { it.focusId != null }

        assertEquals("m10", state.focusId)
        assertFalse(state.locating)
        assertFalse(state.targetMissing)
        assertEquals(before, server.requests.size)
    }

    @Test
    fun mensagemQueOServidorJaNaoTemAvisaQueSumiu() = runTest {
        val vm = viewModel(PagedChannel(30))
        vm.loaded()

        vm.showMessage(MessageTarget("m999", server.ts(59)))
        val state = vm.state.first { !it.locating && it.targetMissing }

        assertNull(state.focusId)
        assertEquals(setOf("m999"), state.missingReplies)
    }

    @Test
    fun comUmaProcuraEmAndamentaUmSegundoPedidoNaoFazNada() = runTest {
        val vm = viewModel(PagedChannel(30))
        vm.loaded()
        val before = server.requests.size

        vm.showMessage(MessageTarget("m999", server.ts(59)))
        vm.showMessage(MessageTarget("m998", server.ts(58)))
        val state = vm.state.first { !it.locating && it.targetMissing }

        // Só a primeira procura saiu: a segunda não pediu a página e a mensagem dela nem foi dada como sumida.
        assertEquals(setOf("m999"), state.missingReplies)
        assertEquals(1, server.requests.drop(before).count { "order=asc" in it })
    }
}
