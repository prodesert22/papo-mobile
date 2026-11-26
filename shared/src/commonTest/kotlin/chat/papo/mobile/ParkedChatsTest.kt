package chat.papo.mobile

import chat.papo.mobile.ui.chat.MessageTarget
import chat.papo.mobile.ui.home.ParkedChat
import chat.papo.mobile.ui.home.ParkedChats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ParkedChatsTest {
    private val contaAna = "conta-ana"
    private val contaBia = "conta-bia"
    private val geral = ParkedChat("canal-geral")
    private val cafe = ParkedChat("canal-cafe", MessageTarget("msg-7", "2026-01-05T10:00:00Z"))

    @Test
    fun semConversaAbertaNaoHaNadaEstacionado() {
        assertNull(ParkedChats.Empty[contaAna])
        assertTrue(ParkedChats.Empty.entries.isEmpty())
    }

    @Test
    fun cadaContaGuardaAUltimaConversaDela() {
        val chats = ParkedChats.Empty.opened(contaAna, geral).opened(contaBia, cafe)

        assertEquals(geral, chats[contaAna])
        assertEquals(cafe, chats[contaBia])
    }

    @Test
    fun abrirOutraConversaNaContaSubstituiAAnterior() {
        val chats = ParkedChats.Empty.opened(contaAna, geral).opened(contaAna, cafe)

        assertEquals(cafe, chats[contaAna])
        assertEquals(1, chats.entries.size)
    }

    @Test
    fun abrirANaoAlteraOutraContaNemAMesmaConversaDeOutraConta() {
        // O mesmo id de canal em duas contas (o mesmo servidor com dois usuários) são conversas de contas diferentes.
        val chats = ParkedChats.Empty.opened(contaAna, geral)

        assertNull(chats[contaBia])
        assertEquals(geral, chats.opened(contaBia, ParkedChat("outro")).get(contaAna))
    }

    @Test
    fun abrirAConversaQueJaEstaDevolveOMesmoValor() {
        val chats = ParkedChats.Empty.opened(contaAna, geral)

        assertSame(chats, chats.opened(contaAna, geral))
    }

    @Test
    fun contaQueSaiLevaAConversaELaDasOutrasFica() {
        val chats = ParkedChats.Empty.opened(contaAna, geral).opened(contaBia, cafe)

        val depois = chats.retaining(setOf(contaBia))

        assertNull(depois[contaAna])
        assertEquals(cafe, depois[contaBia])
    }

    @Test
    fun manterTodasAsContasDevolveOMesmoValor() {
        val chats = ParkedChats.Empty.opened(contaAna, geral).opened(contaBia, cafe)

        assertSame(chats, chats.retaining(setOf(contaAna, contaBia, "conta-sem-conversa")))
    }

    @Test
    fun manterNenhumaContaEsvaziaAsConversas() {
        val chats = ParkedChats.Empty.opened(contaAna, geral)

        assertTrue(chats.retaining(emptySet()).entries.isEmpty())
    }

    @Test
    fun estadoSalvoVoltaIgual() {
        val chats = ParkedChats.Empty.opened(contaAna, geral).opened(contaBia, cafe)

        val restored = ParkedChats.decode(chats.encode())

        assertEquals(chats, restored)
        assertEquals(cafe.target, restored[contaBia]?.target)
        assertNull(restored[contaAna]?.target)
    }

    @Test
    fun estadoSalvoVazioVoltaVazio() {
        assertEquals(ParkedChats.Empty, ParkedChats.decode(emptyList()))
        assertEquals(emptyList(), ParkedChats.Empty.encode())
    }

    @Test
    fun estadoSalvoIncompletoEDescartado() {
        // Sobram dois campos do último grupo: ele não vale, e o primeiro continua valendo.
        val saved = ParkedChats.Empty.opened(contaAna, geral).encode() + listOf(contaBia, "canal-x")

        val restored = ParkedChats.decode(saved)

        assertEquals(geral, restored[contaAna])
        assertNull(restored[contaBia])
    }

    @Test
    fun estadoSalvoSemContaOuSemCanalEDescartado() {
        val saved = listOf("", "canal", "", "", contaAna, "", "", "")

        assertEquals(ParkedChats.Empty, ParkedChats.decode(saved))
    }

    @Test
    fun mensagemPedidaSoValeComIdEInstanteJuntos() {
        val semInstante = listOf(contaAna, "canal-geral", "msg-1", "")
        val semId = listOf(contaBia, "canal-cafe", "", "2026-01-05T10:00:00Z")

        val restored = ParkedChats.decode(semInstante + semId)

        assertEquals(geral, restored[contaAna])
        assertEquals(ParkedChat("canal-cafe"), restored[contaBia])
    }
}
