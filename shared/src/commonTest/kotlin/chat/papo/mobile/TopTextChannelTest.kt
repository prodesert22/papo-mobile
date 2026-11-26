package chat.papo.mobile

import chat.papo.mobile.chat.Channel
import chat.papo.mobile.chat.ChannelGrant
import chat.papo.mobile.chat.ChannelType
import chat.papo.mobile.chat.PermissionSnapshot
import chat.papo.mobile.chat.groupChannels
import chat.papo.mobile.chat.topTextChannel
import chat.papo.mobile.chat.visibleChannels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** O canal de texto mais ao topo da lista: o primeiro de texto na ordem em que a lista de canais os mostra. */
class TopTextChannelTest {
    private val me = "usuario-ana"

    private fun channel(id: String, type: ChannelType = ChannelType.Text, parent: String? = null, position: Int = 0, name: String = id) = Channel(
        id = id,
        name = name,
        type = type,
        position = position,
        topic = null,
        parentId = parent,
        lastMessageId = null,
        lastMessageAt = null,
        lastMessageText = null,
        lastMessageAuthor = null,
        lastReadMessage = null,
        notification = null,
    )

    /** Os canais na ordem dada, com a posição de cada um igual ao índice. */
    private fun ordered(vararg items: Channel) = items.mapIndexed { index, item -> item.copy(position = index) }

    private fun top(channels: List<Channel>) = topTextChannel(channels)?.id

    @Test
    fun semCanaisNaoHaCanalDoTopo() {
        assertNull(topTextChannel(emptyList()))
    }

    @Test
    fun oPrimeiroCanalDeTextoDaListaEOdoTopo() {
        assertEquals("avisos", top(ordered(channel("avisos"), channel("geral"))))
    }

    @Test
    fun aOrdemEPelaPosicaoENaoPelaOrdemEmQueOsCanaisChegam() {
        val channels = listOf(channel("geral", position = 2), channel("avisos", position = 1), channel("musica", position = 3))

        assertEquals("avisos", top(channels))
    }

    @Test
    fun comAMesmaPosicaoDesempataPeloNome() {
        val channels = listOf(channel("c2", name = "papo"), channel("c1", name = "geral"))

        assertEquals("c1", top(channels))
    }

    @Test
    fun canalDeVozNoTopoNaoContaEOPrimeiroDeTextoVem() {
        assertEquals("geral", top(ordered(channel("sala", ChannelType.Voice), channel("geral"))))
    }

    @Test
    fun soCanaisDeVozOuCategoriasNaoDaoCanalDoTopo() {
        val channels = ordered(channel("Audio", ChannelType.Category), channel("sala", ChannelType.Voice, parent = "Audio"))

        assertNull(top(channels))
    }

    @Test
    fun categoriaSemTextoNoTopoPassaParaOTextoDaSeguinte() {
        val channels = ordered(
            channel("Audio", ChannelType.Category),
            channel("sala", ChannelType.Voice, parent = "Audio"),
            channel("Texto", ChannelType.Category),
            channel("geral", parent = "Texto"),
            channel("papo", parent = "Texto"),
        )

        assertEquals("geral", top(channels))
    }

    @Test
    fun canalSoltoAntesDaPrimeiraCategoriaVemAntesDosDela() {
        val channels = ordered(channel("boas-vindas"), channel("Texto", ChannelType.Category), channel("geral", parent = "Texto"))

        assertEquals("boas-vindas", top(channels))
    }

    @Test
    fun valeAOrdemDaListaDeCategoriasENaoAMenorPosicaoDoCanal() {
        // `fundo` é filho de `Segunda` mas tem a menor posição de todas. A lista mostra `Primeira` antes, com `abertura`.
        val channels = listOf(
            channel("fundo", parent = "Segunda", position = 0),
            channel("Primeira", ChannelType.Category, position = 1),
            channel("abertura", parent = "Primeira", position = 2),
            channel("Segunda", ChannelType.Category, position = 3),
        )

        assertEquals("abertura", top(channels))
        assertEquals(listOf("abertura", "fundo"), groupChannels(channels).flatMap { group -> group.channels.map { it.id } })
    }

    @Test
    fun oCanalDoTopoSaiDaMesmaOrdemQueAListaUsa() {
        val channels = ordered(
            channel("Voz", ChannelType.Category),
            channel("sala", ChannelType.Voice),
            channel("Texto", ChannelType.Category),
            channel("geral"),
        )

        assertEquals(topTextChannel(channels), groupChannels(channels).flatMap { it.channels }.first { it.type == ChannelType.Text })
    }

    @Test
    fun canalQueAContaNaoPodeLerNaoContaEOProximoVemDepoisDoFiltroDaLista() {
        val channels = ordered(channel("diretoria"), channel("geral"))
        // `diretoria` só dá leitura a um cargo que a conta não tem; `geral` não tem entradas, e está aberto.
        val snapshot = PermissionSnapshot(
            ownerId = "dono",
            ownerKnown = true,
            roles = emptyMap(),
            userRoles = mapOf(me to emptySet()),
            usersComplete = true,
            grants = mapOf(
                "diretoria" to listOf(ChannelGrant("cargo-diretoria", read = true, send = true, delete = false, voice = false)),
                "geral" to emptyList(),
            ),
        )

        assertEquals("geral", top(visibleChannels(channels, snapshot, me)))
    }

    @Test
    fun comOsCanaisTodosNegadosNaoHaCanalDoTopo() {
        val channels = ordered(channel("diretoria"))
        val snapshot = PermissionSnapshot(
            ownerId = "dono",
            ownerKnown = true,
            roles = emptyMap(),
            userRoles = mapOf(me to emptySet()),
            usersComplete = true,
            grants = mapOf("diretoria" to listOf(ChannelGrant("cargo-diretoria", read = true, send = true, delete = false, voice = false))),
        )

        assertNull(top(visibleChannels(channels, snapshot, me)))
    }
}
