package chat.papo.mobile

import chat.papo.mobile.chat.Channel
import chat.papo.mobile.chat.ChannelType
import chat.papo.mobile.ui.home.StartupInput
import chat.papo.mobile.ui.home.StartupOpening
import chat.papo.mobile.ui.home.StartupStep
import chat.papo.mobile.ui.home.step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Quando a abertura automática abre o canal do topo: uma vez por abertura do app, e só se quem usa ainda não mexeu. */
class StartupOpeningTest {
    private val ana = "conta-ana"
    private val bia = "conta-bia"

    private fun channel(id: String, type: ChannelType = ChannelType.Text, position: Int = 0) = Channel(
        id = id,
        name = id,
        type = type,
        position = position,
        topic = null,
        parentId = null,
        lastMessageId = null,
        lastMessageAt = null,
        lastMessageText = null,
        lastMessageAuthor = null,
        lastReadMessage = null,
        notification = null,
    )

    private val avisos = channel("canal-avisos", position = 1)
    private val geral = channel("canal-geral", position = 2)
    private val sala = channel("canal-sala", ChannelType.Voice, position = 0)

    /** Tudo pronto para abrir: a conta de Ana, sincronizada, sem conversa estacionada, na tela principal. */
    private fun input(
        accountId: String? = ana,
        sessionExpired: Boolean = false,
        hasParkedChat: Boolean = false,
        onHome: Boolean = true,
        synced: Boolean = true,
        channels: List<Channel> = listOf(geral, avisos, sala),
    ) = StartupInput(accountId, sessionExpired, hasParkedChat, onHome, synced, channels)

    private val done = StartupStep(StartupOpening.Done)
    private val waiting = StartupStep(StartupOpening.Waiting(ana))

    // Fresh

    @Test
    fun abreOCanalDeTextoMaisAoTopoComAListaPronta() {
        assertEquals(StartupStep(StartupOpening.Done, open = "canal-avisos"), StartupOpening.Fresh.step(input()))
    }

    @Test
    fun canalDeVozNaFrenteNaoContaEAbreOPrimeiroDeTexto() {
        val step = StartupOpening.Fresh.step(input(channels = listOf(sala, geral)))

        assertEquals("canal-geral", step.open)
    }

    @Test
    fun listaAindaNaoSincronizadaEspera() {
        assertEquals(waiting, StartupOpening.Fresh.step(input(synced = false, channels = emptyList())))
    }

    @Test
    fun listaDoBancoDeOutraSessaoNaoBastaEsperaASincronizacao() {
        assertEquals(waiting, StartupOpening.Fresh.step(input(synced = false, channels = listOf(avisos))))
    }

    @Test
    fun semContaNaoHaOQueAbrir() {
        assertEquals(done, StartupOpening.Fresh.step(input(accountId = null)))
    }

    @Test
    fun sessaoEncerradaFicaNaListaSemEsperarNada() {
        assertEquals(done, StartupOpening.Fresh.step(input(sessionExpired = true, synced = false)))
    }

    @Test
    fun contaComConversaEstacionadaNaoAbre() {
        assertEquals(done, StartupOpening.Fresh.step(input(hasParkedChat = true)))
    }

    @Test
    fun comOutraTelaPorCimaDaPrincipalNaoAbre() {
        assertEquals(done, StartupOpening.Fresh.step(input(onHome = false)))
    }

    @Test
    fun sincronizadaSemNenhumCanalFicaNaLista() {
        assertEquals(done, StartupOpening.Fresh.step(input(channels = emptyList())))
    }

    @Test
    fun sincronizadaSoComCanaisDeVozFicaNaLista() {
        assertEquals(done, StartupOpening.Fresh.step(input(channels = listOf(sala))))
    }

    // Waiting

    @Test
    fun esperandoContinuaEsperandoAteASincronizacao() {
        assertEquals(waiting, StartupOpening.Waiting(ana).step(input(synced = false)))
    }

    @Test
    fun esperandoAbreQuandoAListaChega() {
        assertEquals(StartupStep(StartupOpening.Done, open = "canal-avisos"), StartupOpening.Waiting(ana).step(input()))
    }

    @Test
    fun esperandoNaoAbreSeAListaChegaSemCanalDeTexto() {
        assertEquals(done, StartupOpening.Waiting(ana).step(input(channels = listOf(sala))))
    }

    @Test
    fun trocarDeContaNoTrilhoEncerraAEsperaENaoAbreOCanalDaOutra() {
        // A conta de Bia está sincronizada e tem canais, mas o app foi aberto na de Ana.
        assertEquals(done, StartupOpening.Waiting(ana).step(input(accountId = bia)))
    }

    @Test
    fun esperandoAContaDeixaDeExistirEncerra() {
        assertEquals(done, StartupOpening.Waiting(ana).step(input(accountId = null)))
    }

    @Test
    fun quemAbreUmaConversaDuranteAEsperaNaoVeOutraPorCima() {
        assertEquals(done, StartupOpening.Waiting(ana).step(input(hasParkedChat = true)))
    }

    @Test
    fun sessaoQueEncerraDuranteAEsperaFicaNaLista() {
        assertEquals(done, StartupOpening.Waiting(ana).step(input(sessionExpired = true)))
    }

    @Test
    fun sairDaTelaPrincipalDuranteAEsperaEncerra() {
        assertEquals(done, StartupOpening.Waiting(ana).step(input(onHome = false)))
    }

    // Done

    @Test
    fun resolvidaNuncaMaisAbreMesmoComTudoPronto() {
        assertEquals(done, StartupOpening.Done.step(input()))
    }

    @Test
    fun abreUmaVezSoPorAbertura() {
        val first = StartupOpening.Fresh.step(input())
        assertEquals("canal-avisos", first.open)

        val second = first.next.step(input())
        assertNull(second.open)
        assertEquals(StartupOpening.Done, second.next)
    }

    @Test
    fun esperaAListaAbreQuandoEstaChegaENaoReabre() {
        var state: StartupOpening = StartupOpening.Fresh
        val opened = mutableListOf<String>()
        fun see(input: StartupInput) {
            val step = state.step(input)
            state = step.next
            step.open?.let(opened::add)
        }

        see(input(synced = false, channels = emptyList()))
        see(input(synced = false, channels = listOf(geral)))
        assertEquals(emptyList(), opened)
        see(input())
        see(input(channels = listOf(geral)))
        see(input(accountId = bia, channels = listOf(geral)))

        assertEquals(listOf("canal-avisos"), opened)
    }

    // O estado salvo

    @Test
    fun cadaEstadoVoltaIgualDoEstadoSalvo() {
        listOf(StartupOpening.Fresh, StartupOpening.Waiting(ana), StartupOpening.Waiting("conta:com:dois-pontos"), StartupOpening.Done)
            .forEach { assertEquals(it, StartupOpening.decode(it.encode())) }
    }

    @Test
    fun estadoSalvoQueFaltaOuEstragadoValeResolvidaEntaoNaoAbreNada() {
        listOf(null, "", "waiting:", "waiting", "fresh!", "lixo").forEach {
            assertEquals(StartupOpening.Done, StartupOpening.decode(it), "decode($it)")
        }
    }
}
