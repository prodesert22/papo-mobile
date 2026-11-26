package chat.papo.mobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import chat.papo.mobile.ui.chat.MessageTarget
import chat.papo.mobile.ui.home.ParkedChat
import chat.papo.mobile.ui.home.ParkedChatStores
import chat.papo.mobile.ui.home.ParkedChats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ParkedChatStoresTest {
    /** Um ViewModel que diz se foi liberado, no lugar do `ChatViewModel`, que precisa de uma sessão inteira. */
    private class Probe : ViewModel() {
        var cleared = false

        override fun onCleared() {
            cleared = true
        }
    }

    private val factory = viewModelFactory { initializer { Probe() } }

    private fun ParkedChatStores.probe(key: String): Probe = ViewModelProvider.create(owner(key).viewModelStore, factory)[Probe::class]

    private val geral = ParkedChat("canal-geral")
    private val sessaoAna = Any()
    private val sessaoBia = Any()

    @Test
    fun amesmaChaveDaOMesmoViewModel() {
        val stores = ParkedChatStores()

        assertSame(stores.probe("a"), stores.probe("a"))
    }

    @Test
    fun chavesDiferentesTemViewModelsDiferentes() {
        val stores = ParkedChatStores()

        assertTrue(stores.probe("a") !== stores.probe("b"))
    }

    @Test
    fun soSaiOArmazemDaConversaQueDeixouDeSerAUltima() {
        val stores = ParkedChatStores()
        val fica = stores.probe("fica")
        val sai = stores.probe("sai")

        stores.retain(setOf("fica"))

        assertTrue(sai.cleared)
        assertFalse(fica.cleared)
        assertSame(fica, stores.probe("fica"))
    }

    @Test
    fun conversaLiberadaVoltaComViewModelNovo() {
        val stores = ParkedChatStores()
        val antes = stores.probe("a")

        stores.retain(emptySet())

        assertTrue(antes.cleared)
        assertTrue(antes !== stores.probe("a"))
    }

    @Test
    fun semNadaParaLiberarTudoFica() {
        val stores = ParkedChatStores()
        val a = stores.probe("a")

        stores.retain(setOf("a", "b"))

        assertFalse(a.cleared)
    }

    @Test
    fun travarLiberaTodasAsConversas() {
        val stores = ParkedChatStores()
        val a = stores.probe("a")
        val b = stores.probe("b")

        stores.clear()

        assertTrue(a.cleared)
        assertTrue(b.cleared)
    }

    @Test
    fun chaveDeUmaContaNaoServeParaOutra() {
        // Duas contas do mesmo servidor abrindo o mesmo canal: cada uma tem o ViewModel e a sessão dela.
        val ana = ParkedChatStores.keyOf("conta-ana", geral, sessaoAna)
        val bia = ParkedChatStores.keyOf("conta-bia", geral, sessaoBia)

        assertNotEquals(ana, bia)
    }

    @Test
    fun sessaoNovaDaMesmaContaTemChaveNova() {
        val antes = ParkedChatStores.keyOf("conta-ana", geral, sessaoAna)
        val depois = ParkedChatStores.keyOf("conta-ana", geral, Any())

        assertNotEquals(antes, depois)
    }

    @Test
    fun mensagemPedidaMudaAChaveDaConversa() {
        val semMensagem = ParkedChatStores.keyOf("conta-ana", geral, sessaoAna)
        val comMensagem = ParkedChatStores.keyOf("conta-ana", ParkedChat("canal-geral", MessageTarget("m1", "2026-01-05T10:00:00Z")), sessaoAna)

        assertNotEquals(semMensagem, comMensagem)
    }

    @Test
    fun conversasVivasSaoAUltimaDeCadaContaComSessao() {
        val chats = ParkedChats.Empty.opened("conta-ana", geral).opened("conta-bia", ParkedChat("canal-cafe")).opened("conta-removida", geral)
        val sessions = mapOf("conta-ana" to sessaoAna, "conta-bia" to sessaoBia, "conta-sem-conversa" to Any())

        val live = ParkedChatStores.liveKeys(chats, sessions)

        assertEquals(
            setOf(
                ParkedChatStores.keyOf("conta-ana", geral, sessaoAna),
                ParkedChatStores.keyOf("conta-bia", ParkedChat("canal-cafe"), sessaoBia),
            ),
            live,
        )
    }

    @Test
    fun semConversasNaoHaChavesVivas() {
        assertTrue(ParkedChatStores.liveKeys(ParkedChats.Empty, mapOf("conta-ana" to sessaoAna)).isEmpty())
    }
}
