package chat.papo.mobile

import chat.papo.mobile.chat.Channel
import chat.papo.mobile.chat.ChannelType
import chat.papo.mobile.chat.Dm
import chat.papo.mobile.chat.OpenConversation
import chat.papo.mobile.chat.SwitchEntry
import chat.papo.mobile.chat.SwitchKind
import chat.papo.mobile.chat.SwitchSource
import chat.papo.mobile.chat.User
import chat.papo.mobile.chat.filterSwitchEntries
import chat.papo.mobile.chat.switchEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuickSwitchTest {
    private val me = "abcdef01-1111-1111-1111-111111111111"
    private val other = "22222222-2222-2222-2222-222222222222"

    /** Menção no formato que o backend guarda; é o que chega em `lastMessageText`. */
    private fun mention(id: String) = "oi @mention(<@$id>), vem ver"

    private fun channel(
        id: String,
        name: String = id,
        type: ChannelType = ChannelType.Text,
        at: String? = null,
        unread: Boolean = false,
        text: String? = null,
        notification: String? = null,
    ) = Channel(
        id = id,
        name = name,
        type = type,
        position = 0,
        topic = null,
        parentId = null,
        lastMessageId = if (at != null || unread) "m-$id" else null,
        lastMessageAt = at,
        lastMessageText = text,
        lastMessageAuthor = null,
        lastReadMessage = if (unread) "antiga" else "m-$id",
        notification = notification,
    )

    private fun dm(id: String, userId: String, unread: Int = 0, at: String? = null) =
        Dm(id, userId, lastMessageId = null, lastMessageText = null, lastMessageAt = at, unreadCount = unread)

    private fun user(id: String, username: String, nickname: String? = null) =
        User(id, username, nickname, null, null, banned = false, roleColor = null, avatarVersion = 0)

    private fun source(
        accountId: String = "conta-1",
        label: String = "Casa",
        myUserId: String = me,
        channels: List<Channel> = emptyList(),
        dms: List<Dm> = emptyList(),
        users: List<User> = emptyList(),
    ) = SwitchSource(accountId, label, myUserId, channels, dms, users.associateBy { it.id })

    private fun names(entries: List<SwitchEntry>) = entries.map { it.name }

    // Ordem

    @Test
    fun mencoesVemAntesDasNaoLidasQueVemAntesDasLidas() {
        val entries = switchEntries(
            listOf(
                source(
                    channels = listOf(
                        channel("lida", at = "2026-01-01T12:00:00Z"),
                        channel("nao-lida", at = "2026-01-01T11:00:00Z", unread = true, text = "bom dia"),
                        channel("com-mencao", at = "2026-01-01T09:00:00Z", unread = true, text = mention(me)),
                    ),
                ),
            ),
        )

        assertEquals(listOf("com-mencao", "nao-lida", "lida"), names(entries))
        assertEquals(listOf(true, false, false), entries.map { it.mention })
        assertEquals(listOf(true, true, false), entries.map { it.unread })
    }

    @Test
    fun dentroDoMesmoGrupoVemAAtividadeMaisRecentePrimeiro() {
        val entries = switchEntries(
            listOf(
                source(
                    channels = listOf(
                        channel("velha", at = "2026-01-01T08:00:00Z"),
                        channel("nova", at = "2026-01-01T10:00:00Z"),
                        channel("meio", at = "2026-01-01T09:00:00Z"),
                    ),
                ),
            ),
        )

        assertEquals(listOf("nova", "meio", "velha"), names(entries))
    }

    @Test
    fun aAtividadeSeCompararPeloInstanteENaoPeloTexto() {
        // Como texto, "08:00-03:00" vem antes de "09:00Z" e "10:00+02:00" vem depois; como instantes é o contrário
        // (11:00Z e 08:00Z).
        val entries = switchEntries(
            listOf(
                source(
                    channels = listOf(
                        channel("cedo-no-fuso", at = "2026-01-01T10:00:00+02:00"),
                        channel("em-utc", at = "2026-01-01T09:00:00Z"),
                        channel("tarde-no-fuso", at = "2026-01-01T08:00:00-03:00"),
                        channel("fracao", at = "2026-01-01T08:59:59.999999Z"),
                    ),
                ),
            ),
        )

        assertEquals(listOf("tarde-no-fuso", "em-utc", "fracao", "cedo-no-fuso"), names(entries))
    }

    @Test
    fun semAtividadeConhecidaVemDepoisEDesempataPeloNomeSemAcento() {
        val same = "2026-01-01T10:00:00Z"
        val channels = listOf(
            channel("sem-data-b", name = "Zeta"),
            channel("sem-data-a", name = "Álamo"),
            channel("data-ilegivel", name = "Ilegivel", at = "ontem"),
            channel("bola", name = "Bola", at = same),
            channel("agua", name = "Água", at = same),
            channel("cafe", name = "café", at = same),
            channel("sem-data-c", name = "Beta"),
        )
        // Empatados na atividade, "Água" < "Bola" < "café" ignorando acento; sem data, "Álamo" < "Beta" < "Ilegivel" < "Zeta".
        val expected = listOf("Água", "Bola", "café", "Álamo", "Beta", "Ilegivel", "Zeta")

        assertEquals(expected, names(switchEntries(listOf(source(channels = channels)))))
        // O resultado não depende da ordem de entrada: com ela invertida, quem não tem data é comparado com quem tem nos dois sentidos.
        assertEquals(expected, names(switchEntries(listOf(source(channels = channels.reversed())))))
    }

    @Test
    fun nomesIguaisDeContasDiferentesMantemAOrdemDasContas() {
        val entries = switchEntries(
            listOf(
                source(accountId = "a", label = "Um", channels = listOf(channel("c1", name = "geral"))),
                source(accountId = "b", label = "Dois", channels = listOf(channel("c2", name = "geral"))),
            ),
        )

        assertEquals(listOf("a", "b"), entries.map { it.accountId })
    }

    // Não lido e menção

    @Test
    fun canalSilenciadoNaoContaComoNaoLidoNemMencao() {
        val entries = switchEntries(
            listOf(
                source(
                    channels = listOf(
                        channel("mudo", unread = true, text = mention(me), notification = "off"),
                        channel("so-mencoes", unread = true, text = "oi", notification = "only_mentions"),
                        channel("todas", unread = true, text = "oi", notification = "all"),
                        channel("padrao", unread = true, text = "oi", notification = null),
                    ),
                ),
            ),
        )

        val byName = entries.associateBy { it.name }
        assertFalse(byName.getValue("mudo").unread)
        assertFalse(byName.getValue("mudo").mention)
        assertTrue(byName.getValue("so-mencoes").unread)
        assertTrue(byName.getValue("todas").unread)
        assertTrue(byName.getValue("padrao").unread)
        // O silenciado cai para o fim, junto das lidas.
        assertEquals("mudo", entries.last().name)
    }

    @Test
    fun mencaoSoValeEmCanalNaoLidoECitandoOProprioUsuario() {
        val entries = switchEntries(
            listOf(
                source(
                    channels = listOf(
                        channel("lido-com-mencao", at = "2026-01-01T10:00:00Z", text = mention(me)),
                        channel("de-outro", unread = true, text = mention(other)),
                        channel("sem-mencao", unread = true, text = "sem ninguem"),
                        channel("sem-texto", unread = true, text = null),
                        channel("maiuscula", unread = true, text = mention(me.uppercase())),
                        // Só a segunda menção é do usuário: vale qualquer uma do texto, não só a primeira.
                        channel("varias", unread = true, text = mention(other) + " e " + mention(me)),
                    ),
                ),
            ),
        )

        val byName = entries.associateBy { it.name }
        assertFalse(byName.getValue("lido-com-mencao").mention)
        assertFalse(byName.getValue("de-outro").mention)
        assertFalse(byName.getValue("sem-mencao").mention)
        assertFalse(byName.getValue("sem-texto").mention)
        assertTrue(byName.getValue("maiuscula").mention)
        assertTrue(byName.getValue("varias").mention)
    }

    @Test
    fun conversaDiretaComMensagensNaoLidasEMencaoEUmaSemNaoLidasNao() {
        val entries = switchEntries(
            listOf(
                source(
                    channels = listOf(channel("canal", unread = true, text = "oi")),
                    dms = listOf(dm("d1", other, unread = 3, at = "2026-01-01T10:00:00Z"), dm("d2", "u3", unread = 0)),
                    users = listOf(user(other, "bia", nickname = "Bia B."), user("u3", "caio")),
                ),
            ),
        )

        assertEquals(listOf("Bia B.", "canal", "caio"), names(entries))
        val bia = entries.first()
        assertTrue(bia.mention && bia.unread)
        assertEquals(3, bia.unreadCount)
        assertEquals(SwitchKind.Dm, bia.kind)
        assertFalse(entries.last().unread || entries.last().mention)
    }

    @Test
    fun conversaDiretaMostraONomeDeExibicaoEUsuarioDesconhecidoViraInterrogacao() {
        val entries = switchEntries(
            listOf(
                source(
                    dms = listOf(dm("d1", other), dm("d2", "u3"), dm("d3", "sumiu")),
                    users = listOf(user(other, "bia", nickname = "Bia B."), user("u3", "caio", nickname = "  ")),
                ),
            ),
        )

        val byId = entries.associateBy { it.channelId }
        assertEquals("Bia B.", byId.getValue("d1").name)
        assertEquals("bia", byId.getValue("d1").username)
        assertEquals("caio", byId.getValue("d2").name) // apelido em branco cai para o usuário
        assertEquals("?", byId.getValue("d3").name)
        assertNull(byId.getValue("d3").username)
    }

    // O que fica de fora ou entra

    @Test
    fun categoriasFicamDeForaECanaisDeVozEntramComOTipoCerto() {
        val entries = switchEntries(
            listOf(
                source(
                    channels = listOf(
                        channel("cat", name = "Geral", type = ChannelType.Category),
                        channel("texto", type = ChannelType.Text),
                        channel("voz", type = ChannelType.Voice),
                    ),
                ),
            ),
        )

        assertEquals(setOf("texto", "voz"), names(entries).toSet())
        assertEquals(SwitchKind.Voice, entries.first { it.name == "voz" }.kind)
        assertEquals(SwitchKind.Text, entries.first { it.name == "texto" }.kind)
    }

    @Test
    fun aConversaAbertaSaiMasSoNaContaCerta() {
        val sources = listOf(
            source(accountId = "a", label = "Um", channels = listOf(channel("c1", name = "geral"), channel("c2", name = "outro"))),
            // O mesmo id de canal num servidor diferente é outra conversa (regra 4).
            source(accountId = "b", label = "Dois", channels = listOf(channel("c1", name = "geral"))),
        )

        val entries = switchEntries(sources, open = OpenConversation("a", "c1"))

        assertEquals(listOf("a" to "c2", "b" to "c1"), entries.map { it.accountId to it.channelId }.sortedBy { it.first })
        assertEquals(3, switchEntries(sources).size)
        assertEquals(3, switchEntries(sources, open = OpenConversation("c", "c1")).size)
        assertEquals(3, switchEntries(sources, open = OpenConversation("a", "d1")).size)
    }

    @Test
    fun conversaDiretaAbertaTambemSai() {
        val entries = switchEntries(
            listOf(source(dms = listOf(dm("d1", other), dm("d2", "u3")), users = listOf(user(other, "bia"), user("u3", "caio")))),
            open = OpenConversation("conta-1", "d1"),
        )

        assertEquals(listOf("caio"), names(entries))
    }

    @Test
    fun semContasOuSemConversasNaoHaEntradas() {
        assertEquals(emptyList(), switchEntries(emptyList()))
        assertEquals(emptyList(), switchEntries(listOf(source())))
    }

    // Contas

    @Test
    fun cadaEntradaLevaOIdEORotuloDaPropriaContaEOMesmoIdEmDoisServidoresSaoDuasEntradas() {
        val entries = switchEntries(
            listOf(
                source(accountId = "um", label = "Casa", channels = listOf(channel("c1", name = "geral"))),
                source(accountId = "dois", label = "Trabalho", channels = listOf(channel("c1", name = "geral"))),
            ),
        )

        assertEquals(2, entries.size)
        assertEquals(setOf("um" to "Casa", "dois" to "Trabalho"), entries.map { it.accountId to it.accountLabel }.toSet())
        assertEquals(setOf("c1"), entries.map { it.channelId }.toSet())
        assertNotEquals(entries[0].key, entries[1].key)
        assertEquals(2, entries.map { it.key }.toSet().size)
    }

    @Test
    fun aMencaoUsaOUsuarioDaContaDaEntradaENaoODeOutraConta() {
        val entries = switchEntries(
            listOf(
                source(accountId = "um", myUserId = me, channels = listOf(channel("c1", name = "do-um", unread = true, text = mention(other)))),
                source(accountId = "dois", myUserId = other, channels = listOf(channel("c2", name = "do-dois", unread = true, text = mention(other)))),
            ),
        )

        val byName = entries.associateBy { it.name }
        assertFalse(byName.getValue("do-um").mention) // cita o usuário da outra conta, não o desta
        assertTrue(byName.getValue("do-dois").mention)
        assertEquals("do-dois", entries.first().name)
    }

    @Test
    fun conversasDeContasDiferentesSeOrdenamJuntasPelaAtividade() {
        val entries = switchEntries(
            listOf(
                source(accountId = "um", channels = listOf(channel("a", at = "2026-01-01T08:00:00Z"), channel("b", at = "2026-01-01T10:00:00Z"))),
                source(accountId = "dois", channels = listOf(channel("c", at = "2026-01-01T09:00:00Z"))),
            ),
        )

        assertEquals(listOf("b" to "um", "c" to "dois", "a" to "um"), entries.map { it.name to it.accountId })
    }

    // Filtro

    private fun filterFixture() = switchEntries(
        listOf(
            source(
                accountId = "casa",
                label = "Casa",
                channels = listOf(
                    channel("c1", name = "Água", at = "2026-01-01T10:00:00Z"),
                    channel("c2", name = "geral", unread = true, text = "oi"),
                    channel("c3", name = "design-geral", at = "2026-01-01T11:00:00Z"),
                ),
                dms = listOf(dm("d1", other)),
                users = listOf(user(other, "joao.silva", nickname = "João")),
            ),
            source(
                accountId = "trabalho",
                label = "Trabalho",
                channels = listOf(channel("c1", name = "agua", at = "2026-01-01T09:00:00Z"), channel("c4", name = "financeiro")),
            ),
        ),
    )

    @Test
    fun consultaVaziaOuEmBrancoDevolveTudoNaMesmaOrdem() {
        val all = filterFixture()

        assertEquals(all, filterSwitchEntries(all, ""))
        assertEquals(all, filterSwitchEntries(all, "   "))
    }

    @Test
    fun filtraPeloNomeSemDiferenciarAcentoNemMaiuscula() {
        val all = filterFixture()

        val result = filterSwitchEntries(all, "AGUA")

        assertEquals(setOf("casa" to "c1", "trabalho" to "c1"), result.map { it.accountId to it.channelId }.toSet())
        assertEquals(2, result.size)
        assertEquals(listOf("joao.silva"), filterSwitchEntries(all, "joao").mapNotNull { it.username })
        assertEquals(listOf("João"), names(filterSwitchEntries(all, "joão")))
    }

    @Test
    fun filtraPeloUsuarioDeUmaConversaDireta() {
        val result = filterSwitchEntries(filterFixture(), "silva")

        assertEquals(listOf("João"), names(result))
    }

    @Test
    fun filtraPeloRotuloDaContaEPoeOsNomesQueCasamAntes() {
        val all = filterFixture()

        // "trab" não está em nenhum nome: sobram as conversas da conta "Trabalho", na ordem da lista.
        val byAccount = filterSwitchEntries(all, "trab")
        assertEquals(setOf("trabalho"), byAccount.map { it.accountId }.toSet())
        assertEquals(all.filter { it.accountId == "trabalho" }, byAccount)

        // "casa" também aparece no nome de um canal: ele vem antes das conversas que só casam pela conta.
        val withName = switchEntries(
            listOf(
                source(accountId = "casa", label = "Casa", channels = listOf(channel("c1", name = "geral", at = "2026-01-01T12:00:00Z"))),
                source(accountId = "outra", label = "Outra", channels = listOf(channel("c2", name = "casamento"))),
            ),
        )
        assertEquals(listOf("casamento", "geral"), names(filterSwitchEntries(withName, "casa")))
    }

    @Test
    fun consultaSemCorrespondenciaNaoDevolveNada() {
        assertEquals(emptyList(), filterSwitchEntries(filterFixture(), "zzzz"))
        assertEquals(emptyList(), filterSwitchEntries(emptyList(), "geral"))
    }

    @Test
    fun oFiltroOrdenaPelaQualidadeDoCasamentoEDepoisPelaOrdemDaLista() {
        val all = filterFixture()

        // "geral" (igual) vem antes de "design-geral" (só uma palavra começa com ela), mesmo com a segunda mais recente.
        val result = filterSwitchEntries(all, "geral")

        assertEquals(listOf("geral", "design-geral"), names(result))
    }

    @Test
    fun oFiltroNaoMisturaContasEMantemOIdDeCadaEntrada() {
        val result = filterSwitchEntries(filterFixture(), "agua")

        for (entry in result) {
            val expectedLabel = if (entry.accountId == "casa") "Casa" else "Trabalho"
            assertEquals(expectedLabel, entry.accountLabel)
        }
    }
}
