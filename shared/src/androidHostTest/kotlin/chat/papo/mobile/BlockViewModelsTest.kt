package chat.papo.mobile

import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.core.MutationResult
import chat.papo.mobile.ui.components.UiError
import chat.papo.mobile.ui.profile.BlockActionViewModel
import chat.papo.mobile.ui.settings.BlockedUsersViewModel
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertIs
import kotlin.test.assertNull

/** As telas de bloqueio sobre o motor HTTP falso: a lista, desbloquear e o botão do perfil. */
@OptIn(ExperimentalCoroutinesApi::class)
class BlockViewModelsTest {
    private fun TestScope.session(server: BlockServer): AccountSession {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return TestResources.session(server.engine, this)
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun abrirATelaLeAListaUmaVez() = runTest {
        val server = BlockServer(initial = listOf("u2"))
        val session = session(server)
        val vm = BlockedUsersViewModel(session.blocks)
        assertEquals(true, vm.state.value.loading)

        val settled = vm.state.first { !it.loading }

        assertNull(settled.loadError)
        assertEquals(setOf("u2"), session.blocks.blocked.value)
        assertEquals(1, server.count("GET /users/blocks"))
    }

    @Test
    fun leituraComErroMostraOErroEReloadLeDeNovoSoQuandoPedido() = runTest {
        val server = BlockServer(initial = listOf("u2")).also { it.listStatus = HttpStatusCode.InternalServerError }
        val session = session(server)
        val vm = BlockedUsersViewModel(session.blocks)

        val failed = vm.state.first { !it.loading }
        assertEquals(UiError.Server("falhou 500"), failed.loadError)
        assertEquals(1, server.count("GET /users/blocks"))

        server.listStatus = HttpStatusCode.OK
        vm.reload()
        val ok = vm.state.first { !it.loading && it.loadError == null }

        assertNull(ok.loadError)
        assertEquals(setOf("u2"), session.blocks.blocked.value)
        assertEquals(2, server.count("GET /users/blocks"))
    }

    @Test
    fun desbloquearTiraDaListaESemFalha() = runTest {
        val server = BlockServer(initial = listOf("u2", "u3"))
        val session = session(server)
        val vm = BlockedUsersViewModel(session.blocks)
        vm.state.first { !it.loading }

        vm.unblock("u2")
        val settled = vm.state.first { it.unblocking == null && session.blocks.blocked.value == setOf("u3") }

        assertNull(settled.failure)
        assertEquals(1, server.count("DELETE /users/u2/block"))
    }

    @Test
    fun desbloquearComLimiteDeTaxaGuardaAFalhaEMantemAPessoa() = runTest {
        val server = BlockServer(initial = listOf("u2"))
        val session = session(server)
        val vm = BlockedUsersViewModel(session.blocks)
        vm.state.first { !it.loading }
        server.blockStatus = HttpStatusCode.TooManyRequests

        vm.unblock("u2")
        val settled = vm.state.first { it.failure != null }

        assertEquals(MutationResult.RateLimited, settled.failure)
        assertEquals(setOf("u2"), session.blocks.blocked.value)
        assertEquals(1, server.count("DELETE /users/u2/block"))
    }

    @Test
    fun umDesbloqueioPorVezEOutroPedidoEnquantoEsperaEIgnorado() = runTest {
        val gate = CompletableDeferred<Unit>()
        val server = BlockServer(initial = listOf("u2", "u3"))
        val session = session(server)
        val vm = BlockedUsersViewModel(session.blocks)
        vm.state.first { !it.loading }
        server.gate = gate

        vm.unblock("u2")
        vm.unblock("u3") // ignorado: o primeiro ainda espera
        TestResources.waitUntil { server.count("DELETE /users/u2/block") == 1 }
        gate.complete(Unit)
        vm.state.first { it.unblocking == null && session.blocks.blocked.value == setOf("u3") }

        assertEquals(0, server.count("DELETE /users/u3/block"))
    }

    @Test
    fun bloquearPeloPerfilEntraNaListaESemFalha() = runTest {
        val server = BlockServer()
        val session = session(server)
        val vm = BlockActionViewModel(session.blocks, "u2")

        vm.block()
        val settled = vm.state.first { !it.busy && session.blocks.blocked.value.isNotEmpty() }

        assertNull(settled.failure)
        assertEquals(setOf("u2"), session.blocks.blocked.value)
    }

    @Test
    fun bloquearPeloPerfilRecusadoMostraODetalhe() = runTest {
        val server = BlockServer().also { it.blockStatus = HttpStatusCode.BadRequest }
        val session = session(server)
        val vm = BlockActionViewModel(session.blocks, "u2")

        vm.block()
        val settled = vm.state.first { !it.busy && it.failure != null }

        assertEquals("falhou 400", assertIs<MutationResult.Rejected>(settled.failure).detail)
        assertEquals(emptySet(), session.blocks.blocked.value)
        assertEquals(1, server.count("POST /users/u2/block"))
    }

    @Test
    fun desbloquearPeloPerfilSaiDaLista() = runTest {
        val server = BlockServer(initial = listOf("u2"))
        val session = session(server)
        session.blocks.refresh()
        val vm = BlockActionViewModel(session.blocks, "u2")

        vm.unblock()
        vm.state.first { !it.busy && session.blocks.blocked.value.isEmpty() }

        assertEquals(1, server.count("DELETE /users/u2/block"))
    }
}
