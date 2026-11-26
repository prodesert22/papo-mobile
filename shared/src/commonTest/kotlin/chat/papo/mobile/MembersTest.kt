package chat.papo.mobile

import chat.papo.mobile.chat.MemberListing
import chat.papo.mobile.chat.MemberStatus
import chat.papo.mobile.chat.Presence
import chat.papo.mobile.chat.User
import chat.papo.mobile.chat.groupMembers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MembersTest {
    private val me = "u-me"

    private fun user(
        id: String,
        username: String,
        nickname: String? = null,
        banned: Boolean = false,
        statusMessage: String? = null,
    ) = User(id, username, nickname, statusMessage, null, banned, roleColor = null, avatarVersion = 0)

    private fun status(value: String, message: String? = null) = Presence(value, message)

    private fun grouped(users: List<User>, presence: Map<String, Presence> = emptyMap(), query: String = ""): MemberListing.Grouped =
        groupMembers(users, presence, query, me) as MemberListing.Grouped

    private fun found(users: List<User>, query: String, presence: Map<String, Presence> = emptyMap()): MemberListing.Found =
        groupMembers(users, presence, query, me) as MemberListing.Found

    private fun MemberListing.Grouped.names(status: MemberStatus) =
        sections.single { it.status == status }.members.map { it.user.displayName }

    // Seções

    @Test
    fun agrupaPorEstadoNaOrdemDisponivelAusenteOcupadoOffline() {
        val users = listOf(user("1", "off"), user("2", "busy"), user("3", "away"), user("4", "on"))
        val presence = mapOf("1" to status("offline"), "2" to status("busy"), "3" to status("away"), "4" to status("online"))

        val listing = grouped(users, presence)

        assertEquals(listOf(MemberStatus.Online, MemberStatus.Away, MemberStatus.Busy, MemberStatus.Offline), listing.sections.map { it.status })
        assertEquals(listOf("on"), listing.names(MemberStatus.Online))
        assertEquals(listOf("away"), listing.names(MemberStatus.Away))
        assertEquals(listOf("busy"), listing.names(MemberStatus.Busy))
        assertEquals(listOf("off"), listing.names(MemberStatus.Offline))
    }

    @Test
    fun secoesVaziasSaemDaLista() {
        val users = listOf(user("1", "ana"), user("2", "bia"))

        val listing = grouped(users, mapOf("1" to status("online"), "2" to status("busy")))

        assertEquals(listOf(MemberStatus.Online, MemberStatus.Busy), listing.sections.map { it.status })
    }

    @Test
    fun cadaSecaoSabeQuantosMembrosTem() {
        val users = listOf(user("1", "ana"), user("2", "bia"), user("3", "caio"))

        val listing = grouped(users, mapOf("1" to status("online"), "2" to status("online"), "3" to status("away")))

        assertEquals(listOf(2, 1), listing.sections.map { it.count })
        assertEquals(3, listing.total)
    }

    @Test
    fun semUsuariosNaoHaSecaoNemTotal() {
        val listing = grouped(emptyList())

        assertTrue(listing.sections.isEmpty())
        assertEquals(0, listing.total)
    }

    @Test
    fun quemNaoTemPresencaAnunciadaEstaOffline() {
        val listing = grouped(listOf(user("1", "ana")), presence = mapOf("outro" to status("online")))

        assertEquals(listOf("ana"), listing.names(MemberStatus.Offline))
        assertNull(listing.sections.single().members.single().presence)
    }

    @Test
    fun estadoDesconhecidoContaComoOffline() {
        val listing = grouped(listOf(user("1", "ana"), user("2", "bia")), mapOf("1" to status("invisivel"), "2" to status("offline")))

        assertEquals(listOf(MemberStatus.Offline), listing.sections.map { it.status })
        assertEquals(2, listing.sections.single().count)
    }

    @Test
    fun usuariosBanidosFicamDeForaDaListaEDoTotal() {
        val users = listOf(user("1", "ana"), user("2", "bia", banned = true))

        val listing = grouped(users, mapOf("2" to status("online")))

        assertEquals(listOf(MemberStatus.Offline), listing.sections.map { it.status })
        assertEquals(listOf("ana"), listing.names(MemberStatus.Offline))
        assertEquals(1, listing.total)
    }

    @Test
    fun cadaUsuarioApareceEmUmaSecaoSoEOsIdsNaoSeRepetem() {
        val users = (1..6).map { user("$it", "user$it") }
        val presence = mapOf("1" to status("online"), "2" to status("away"), "3" to status("busy"), "4" to status("online"))

        val ids = grouped(users, presence).sections.flatMap { section -> section.members.map { it.id } }

        assertEquals(users.map { it.id }.toSet(), ids.toSet())
        assertEquals(users.size, ids.size)
    }

    // Ordem

    @Test
    fun ordenaPeloNomeDeExibicaoSemDiferenciarAcentoNemMaiusculas() {
        val users = listOf(user("1", "caio", "Caio"), user("2", "bruno"), user("3", "agata", "Ágata"), user("4", "ana", "ana"))

        val names = grouped(users).names(MemberStatus.Offline)

        assertEquals(listOf("Ágata", "ana", "bruno", "Caio"), names)
    }

    @Test
    fun usaOApelidoNoLugarDoUsuarioQuandoHa() {
        val users = listOf(user("1", "zeca", "Ana Lima"), user("2", "bia"))

        assertEquals(listOf("Ana Lima", "bia"), grouped(users).names(MemberStatus.Offline))
    }

    @Test
    fun nomesIguaisDesempatamPeloUsuarioEDepoisPeloId() {
        val users = listOf(
            user("c", "zeta", "Lia"),
            user("b", "alfa", "lia"),
            user("a", "alfa", "LIA"),
        )

        val ids = grouped(users).sections.single().members.map { it.id }

        assertEquals(listOf("a", "b", "c"), ids)
    }

    @Test
    fun aOrdemDeEntradaNaoMudaOResultado() {
        val users = listOf(user("1", "ana"), user("2", "bia"), user("3", "caio"), user("4", "Bia", "bia"))

        val forward = grouped(users).sections
        val backward = grouped(users.reversed()).sections

        assertEquals(forward, backward)
    }

    // Quem usa a conta

    @Test
    fun quemUsaAContaFicaIdentificavelNaLista() {
        val users = listOf(user(me, "lia"), user("1", "ana"))

        val members = grouped(users).sections.single().members

        assertEquals(listOf("ana" to false, "lia" to true), members.map { it.user.username to it.isMe })
    }

    @Test
    fun quemUsaAContaTambemFicaIdentificavelNaBusca() {
        val result = found(listOf(user(me, "lia"), user("1", "liana")), "lia")

        assertEquals(listOf("lia" to true, "liana" to false), result.members.map { it.user.username to it.isMe })
    }

    // Mensagem de status

    @Test
    fun amensagemDaPresencaAoVivoVemAntesDaDoPerfil() {
        val member = grouped(listOf(user("1", "ana", statusMessage = "no perfil")), mapOf("1" to status("online", "ao vivo")))
            .sections.single().members.single()

        assertEquals("ao vivo", member.statusMessage)
    }

    @Test
    fun semMensagemNaPresencaValeADoPerfil() {
        val member = grouped(listOf(user("1", "ana", statusMessage = "no perfil")), mapOf("1" to status("online")))
            .sections.single().members.single()

        assertEquals("no perfil", member.statusMessage)
    }

    @Test
    fun mensagemEmBrancoNaoContaComoMensagem() {
        val member = grouped(listOf(user("1", "ana", statusMessage = "  ")), mapOf("1" to status("online", "")))
            .sections.single().members.single()

        assertNull(member.statusMessage)
    }

    // Busca

    @Test
    fun comBuscaOResultadoVemSemSecoesEComOTotalDeMembros() {
        val users = listOf(user("1", "ana"), user("2", "bia"), user("3", "caio", banned = true))

        val result = found(users, "a")

        assertEquals(listOf("ana", "bia"), result.members.map { it.user.username })
        assertEquals(2, result.total)
    }

    @Test
    fun buscaSemAcentoNemMaiusculasAchaONomeAcentuado() {
        val result = found(listOf(user("1", "agata", "Ágata Dias"), user("2", "bia")), "AGATA")

        assertEquals(listOf("1"), result.members.map { it.id })
    }

    @Test
    fun buscaPeloUsuarioComOuSemArroba() {
        val users = listOf(user("1", "lia.moraes", "Lia"), user("2", "caio", "Caio Reis"))

        assertEquals(listOf("2"), found(users, "@caio").members.map { it.id })
        assertEquals(listOf("2"), found(users, "caio").members.map { it.id })
        assertEquals(listOf("1"), found(users, "@lia.m").members.map { it.id })
    }

    @Test
    fun buscaOrdenaPorRelevanciaEDepoisPorNome() {
        val users = listOf(user("1", "x1", "Ana Clara"), user("2", "x2", "Joana"), user("3", "x3", "Ana"), user("4", "x4", "Anabela"))

        val names = found(users, "ana").members.map { it.user.displayName }

        assertEquals(listOf("Ana", "Ana Clara", "Anabela", "Joana"), names)
    }

    @Test
    fun buscaSemResultadoDevolveListaVazia() {
        val result = found(listOf(user("1", "ana")), "zzz")

        assertTrue(result.members.isEmpty())
        assertEquals(1, result.total)
    }

    @Test
    fun buscaEmBrancoNaoAtivaOFiltro() {
        val listing = groupMembers(listOf(user("1", "ana")), emptyMap(), "   ", me)

        assertTrue(listing is MemberListing.Grouped)
    }

    @Test
    fun buscaNaoMostraBanidos() {
        val result = found(listOf(user("1", "ana", banned = true), user("2", "anita")), "an")

        assertEquals(listOf("2"), result.members.map { it.id })
    }

    @Test
    fun cadaResultadoDaBuscaTrazOEstadoDeQuemFoiAchado() {
        val users = listOf(user("1", "ana"), user("2", "anita"), user("3", "anabela"))
        val presence = mapOf("1" to status("busy"), "2" to status("away"))

        val statuses = found(users, "an", presence).members.associate { it.user.username to it.status }

        assertEquals(mapOf("ana" to MemberStatus.Busy, "anabela" to MemberStatus.Offline, "anita" to MemberStatus.Away), statuses)
    }
}
