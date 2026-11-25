package chat.papo.mobile

import chat.papo.mobile.chat.Channel
import chat.papo.mobile.chat.ChannelFlag
import chat.papo.mobile.chat.ChannelGrant
import chat.papo.mobile.chat.ChannelType
import chat.papo.mobile.chat.ConversationAccess
import chat.papo.mobile.chat.Decision
import chat.papo.mobile.chat.PermissionSnapshot
import chat.papo.mobile.chat.RoleFlag
import chat.papo.mobile.chat.RoleFlags
import chat.papo.mobile.chat.authorCanEveryone
import chat.papo.mobile.chat.conversationAccess
import chat.papo.mobile.chat.visibleChannels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A regra de permissões do servidor (ver `Permissions.kt`), uma linha por fato: dono, canal aberto, união
 * de cargos, cada fonte desconhecida, tabela da DM, anexo, `@everyone` e o filtro de canais.
 */
class PermissionsTest {
    private val me = "me"
    private val boss = "boss"

    private fun flags(vararg flag: RoleFlag) = RoleFlags(flag.toSet())

    private fun grant(role: String, read: Boolean = false, send: Boolean = false, delete: Boolean = false, voice: Boolean = false) =
        ChannelGrant(role, read, send, delete, voice)

    /** Tudo conhecido: o dono é [boss], e o usuário [me] tem os cargos de [mine]. */
    private fun known(
        roles: Map<String, RoleFlags> = emptyMap(),
        mine: Set<String> = emptySet(),
        grants: Map<String, List<ChannelGrant>> = emptyMap(),
        owner: String? = boss,
    ) = PermissionSnapshot(
        ownerId = owner,
        ownerKnown = true,
        roles = roles,
        userRoles = mapOf(me to mine),
        usersComplete = true,
        grants = grants,
    )

    private fun channel(id: String, type: ChannelType = ChannelType.Text, parent: String? = null) = Channel(
        id = id,
        name = id,
        type = type,
        position = 0,
        topic = null,
        parentId = parent,
        lastMessageId = null,
        lastMessageAt = null,
        lastMessageText = null,
        lastMessageAuthor = null,
        lastReadMessage = null,
        notification = null,
    )

    // role()

    @Test
    fun donoTemTodasAsFlagsDeCargoMesmoSemSaberOResto() {
        val snapshot = PermissionSnapshot(ownerId = me, ownerKnown = true)
        RoleFlag.entries.forEach { assertEquals(Decision.Allowed, snapshot.role(me, it)) }
    }

    @Test
    fun donoNaoConhecidoNaoValeComoDono() {
        val snapshot = PermissionSnapshot(ownerId = me, ownerKnown = false)
        assertEquals(Decision.Unknown, snapshot.role(me, RoleFlag.PinMessage))
    }

    @Test
    fun semCatalogoDeCargosODesconhecidoSeOferece() {
        val snapshot = known().copy(roles = null)
        assertEquals(Decision.Unknown, snapshot.role(me, RoleFlag.PinMessage))
    }

    @Test
    fun cargosDoUsuarioNaoLidosSaoDesconhecidosMasPaginasCompletasDizemSemCargos() {
        val partial = known(roles = mapOf("r" to flags(RoleFlag.PinMessage))).copy(userRoles = emptyMap(), usersComplete = false)
        assertEquals(Decision.Unknown, partial.role(me, RoleFlag.PinMessage))

        val complete = partial.copy(usersComplete = true)
        assertEquals(Decision.Denied, complete.role(me, RoleFlag.PinMessage))
    }

    @Test
    fun cargoComAFlagConcede() {
        val snapshot = known(roles = mapOf("mod" to flags(RoleFlag.PinMessage)), mine = setOf("mod"))
        assertEquals(Decision.Allowed, snapshot.role(me, RoleFlag.PinMessage))
        assertEquals(Decision.Denied, snapshot.role(me, RoleFlag.SendAttachment))
    }

    @Test
    fun flagsDeCargoSaoOuEntreOsCargosDoUsuario() {
        val snapshot = known(
            roles = mapOf("a" to flags(RoleFlag.PinMessage), "b" to flags(RoleFlag.EveryoneMessage), "c" to flags()),
            mine = setOf("c", "a", "b"),
        )
        assertEquals(Decision.Allowed, snapshot.role(me, RoleFlag.PinMessage))
        assertEquals(Decision.Allowed, snapshot.role(me, RoleFlag.EveryoneMessage))
        assertEquals(Decision.Denied, snapshot.role(me, RoleFlag.BanMembers))
    }

    @Test
    fun cargoSemDefinicaoNaoNegaMasNaoImpedeQuemConcede() {
        val roles = mapOf("a" to flags(RoleFlag.PinMessage), "b" to flags())
        // "novo" acabou de ser criado: o catálogo ainda não o tem.
        val snapshot = known(roles = roles, mine = setOf("novo", "b"))
        assertEquals(Decision.Unknown, snapshot.role(me, RoleFlag.PinMessage))

        val withGrant = known(roles = roles, mine = setOf("novo", "a"))
        assertEquals(Decision.Allowed, withGrant.role(me, RoleFlag.PinMessage))
    }

    @Test
    fun semOCargoComAFlagEOComDonoDesconhecidoNaoSeNega() {
        val snapshot = known(roles = mapOf("b" to flags()), mine = setOf("b")).copy(ownerKnown = false)
        assertEquals(Decision.Unknown, snapshot.role(me, RoleFlag.PinMessage))
    }

    @Test
    fun servidorSemDonoNegaParaQuemNaoTemOCargo() {
        val snapshot = known(roles = mapOf("b" to flags()), mine = setOf("b"), owner = null)
        assertEquals(Decision.Denied, snapshot.role(me, RoleFlag.PinMessage))
    }

    // channel()

    @Test
    fun canalSemEntradasConhecidasEDesconhecido() {
        assertEquals(Decision.Unknown, known().copy(grants = null).channel(me, "c", ChannelFlag.Read))
        assertEquals(Decision.Unknown, known(grants = mapOf("outro" to emptyList())).channel(me, "c", ChannelFlag.Read))
    }

    @Test
    fun donoTemTudoNoCanalInclusiveExcluirEmCanalAberto() {
        val snapshot = known(owner = me, grants = mapOf("aberto" to emptyList(), "fechado" to listOf(grant("r"))))
        ChannelFlag.entries.forEach {
            assertEquals(Decision.Allowed, snapshot.channel(me, "aberto", it))
            assertEquals(Decision.Allowed, snapshot.channel(me, "fechado", it))
        }
    }

    @Test
    fun canalAbertoELivreParaLerEnviarEVozMasNaoParaExcluir() {
        val snapshot = known(grants = mapOf("aberto" to emptyList()))
        assertEquals(Decision.Allowed, snapshot.channel(me, "aberto", ChannelFlag.Read))
        assertEquals(Decision.Allowed, snapshot.channel(me, "aberto", ChannelFlag.Send))
        assertEquals(Decision.Allowed, snapshot.channel(me, "aberto", ChannelFlag.Voice))
        assertEquals(Decision.Denied, snapshot.channel(me, "aberto", ChannelFlag.Delete))
    }

    @Test
    fun canalAbertoNaoDependeDeSaberODonoMasExcluirDepende() {
        val snapshot = known(grants = mapOf("aberto" to emptyList())).copy(ownerKnown = false, userRoles = emptyMap(), usersComplete = false)
        assertEquals(Decision.Allowed, snapshot.channel(me, "aberto", ChannelFlag.Read))
        assertEquals(Decision.Unknown, snapshot.channel(me, "aberto", ChannelFlag.Delete))
    }

    @Test
    fun cadaFlagDeCanalSoVemDaPropriaFlagDaEntrada() {
        val grants = mapOf(
            "c" to listOf(
                grant("ler", read = true),
                grant("enviar", send = true),
                grant("excluir", delete = true),
                grant("voz", voice = true),
            ),
        )
        ChannelFlag.entries.forEach { flag ->
            val role = when (flag) {
                ChannelFlag.Read -> "ler"
                ChannelFlag.Send -> "enviar"
                ChannelFlag.Delete -> "excluir"
                ChannelFlag.Voice -> "voz"
            }
            val snapshot = known(mine = setOf(role), grants = grants)
            ChannelFlag.entries.forEach { asked ->
                val expected = if (asked == flag) Decision.Allowed else Decision.Denied
                assertEquals(expected, snapshot.channel(me, "c", asked), "cargo $role pedindo $asked")
            }
        }
    }

    @Test
    fun entradasDeCargosDoUsuarioSeUnem() {
        val grants = mapOf("c" to listOf(grant("a", read = true), grant("b", send = true), grant("outro", delete = true)))
        val snapshot = known(mine = setOf("a", "b"), grants = grants)
        assertEquals(Decision.Allowed, snapshot.channel(me, "c", ChannelFlag.Read))
        assertEquals(Decision.Allowed, snapshot.channel(me, "c", ChannelFlag.Send))
        // A entrada que concede excluir é de um cargo que o usuário não tem.
        assertEquals(Decision.Denied, snapshot.channel(me, "c", ChannelFlag.Delete))
    }

    @Test
    fun semCargoNenhumCanalComEntradasNega() {
        val snapshot = known(grants = mapOf("c" to listOf(grant("a", read = true))))
        assertEquals(Decision.Denied, snapshot.channel(me, "c", ChannelFlag.Read))
    }

    @Test
    fun canalComEntradasSemSaberOsCargosDoUsuarioEDesconhecido() {
        val snapshot = known(grants = mapOf("c" to listOf(grant("a", read = true)))).copy(userRoles = emptyMap(), usersComplete = false)
        assertEquals(Decision.Unknown, snapshot.channel(me, "c", ChannelFlag.Read))
    }

    @Test
    fun canalComEntradasSemSaberODonoNaoSeNega() {
        val snapshot = known(mine = setOf("b"), grants = mapOf("c" to listOf(grant("a", read = true)))).copy(ownerKnown = false)
        assertEquals(Decision.Unknown, snapshot.channel(me, "c", ChannelFlag.Read))
        // Mas o que o cargo do usuário concede vale sem saber o dono.
        val granted = snapshot.copy(userRoles = mapOf(me to setOf("a")))
        assertEquals(Decision.Allowed, granted.channel(me, "c", ChannelFlag.Read))
    }

    // conversationAccess()

    @Test
    fun semSnapshotUmCanalOferecaTudoMenosEveryoneEExcluirAlheia() {
        val access = conversationAccess(PermissionSnapshot.EMPTY, me, "c", isDm = false)
        assertEquals(ConversationAccess.Unrestricted, access)
        assertFalse(access.deleteOthers)
        assertFalse(access.mentionEveryone)
        assertFalse(access.readDenied)
    }

    @Test
    fun emDmTudoSimMenosExcluirAlheiaVozEEveryone() {
        val denied = known(grants = mapOf("d" to listOf(grant("a", read = true))))
        for (snapshot in listOf(PermissionSnapshot.EMPTY, denied, known(owner = me))) {
            val access = conversationAccess(snapshot, me, "d", isDm = true)
            assertEquals(
                ConversationAccess(
                    read = true,
                    send = true,
                    attach = true,
                    react = true,
                    removeOwnReaction = true,
                    pin = true,
                    deleteOthers = false,
                    joinVoice = false,
                    mentionEveryone = false,
                    readDenied = false,
                ),
                access,
            )
        }
    }

    @Test
    fun donoPodeTudoNoCanalInclusiveExcluirAlheiaEEveryone() {
        val snapshot = known(owner = me, grants = mapOf("c" to listOf(grant("r"))))
        assertEquals(
            ConversationAccess(
                read = true,
                send = true,
                attach = true,
                react = true,
                removeOwnReaction = true,
                pin = true,
                deleteOthers = true,
                joinVoice = true,
                mentionEveryone = true,
                readDenied = false,
            ),
            conversationAccess(snapshot, me, "c", isDm = false),
        )
    }

    @Test
    fun quemNaoTemNadaNumCanalFechadoNaoPodeNada() {
        val snapshot = known(grants = mapOf("c" to listOf(grant("a", read = true, send = true))))
        assertEquals(
            ConversationAccess(
                read = false,
                send = false,
                attach = false,
                react = false,
                removeOwnReaction = false,
                pin = false,
                deleteOthers = false,
                joinVoice = false,
                mentionEveryone = false,
                readDenied = true,
            ),
            conversationAccess(snapshot, me, "c", isDm = false),
        )
    }

    @Test
    fun canalSomenteLeituraLeETiraAPropriaReacaoMasNaoEnvia() {
        val snapshot = known(mine = setOf("a"), grants = mapOf("c" to listOf(grant("a", read = true))))
        val access = conversationAccess(snapshot, me, "c", isDm = false)
        assertTrue(access.read)
        assertTrue(access.removeOwnReaction)
        assertFalse(access.send)
        assertFalse(access.react)
        assertFalse(access.attach)
        assertFalse(access.readDenied)
    }

    @Test
    fun anexoPedeEnvioESendAttachmentDeCargo() {
        val roles = mapOf("anexo" to flags(RoleFlag.SendAttachment), "nada" to flags())
        val grants = mapOf("c" to listOf(grant("anexo", read = true, send = true), grant("nada", read = true, send = true)))

        val sendOnly = known(roles = roles, mine = setOf("nada"), grants = grants)
        assertTrue(conversationAccess(sendOnly, me, "c", isDm = false).send)
        assertFalse(conversationAccess(sendOnly, me, "c", isDm = false).attach)

        val both = known(roles = roles, mine = setOf("anexo"), grants = grants)
        assertTrue(conversationAccess(both, me, "c", isDm = false).attach)

        // O cargo permite anexo, mas o canal não deixa o cargo enviar: sem envio não há anexo.
        val noSend = known(roles = roles, mine = setOf("anexo"), grants = mapOf("c" to listOf(grant("anexo", read = true))))
        assertFalse(conversationAccess(noSend, me, "c", isDm = false).attach)
    }

    @Test
    fun fixarEEveryoneVemDoCargoEExcluirAlheiaEVozVemDoCanal() {
        val roles = mapOf("mod" to flags(RoleFlag.PinMessage, RoleFlag.EveryoneMessage))
        val grants = mapOf("c" to listOf(grant("mod", read = true, delete = true, voice = true)))
        val access = conversationAccess(known(roles = roles, mine = setOf("mod"), grants = grants), me, "c", isDm = false)
        assertTrue(access.pin)
        assertTrue(access.mentionEveryone)
        assertTrue(access.deleteOthers)
        assertTrue(access.joinVoice)

        val plain = conversationAccess(known(roles = mapOf("mod" to flags()), mine = setOf("mod"), grants = grants), me, "c", isDm = false)
        assertFalse(plain.pin)
        assertFalse(plain.mentionEveryone)
    }

    @Test
    fun canalAbertoLiberaVozMasNaoExcluirAlheiaNemFixar() {
        val snapshot = known(roles = mapOf("r" to flags()), mine = setOf("r"), grants = mapOf("c" to emptyList()))
        val access = conversationAccess(snapshot, me, "c", isDm = false)
        assertTrue(access.read && access.send && access.react && access.joinVoice)
        assertFalse(access.deleteOthers)
        assertFalse(access.pin)
    }

    @Test
    fun desconhecidoOferecoMasEveryoneExcluirAlheiaELeituraNegadaExigemCerteza() {
        // Nada se sabe do canal nem dos cargos: o app se comporta como antes das permissões.
        val unknown = PermissionSnapshot(ownerId = boss, ownerKnown = true)
        val access = conversationAccess(unknown, me, "c", isDm = false)
        assertEquals(ConversationAccess.Unrestricted, access)
        assertTrue(access.send && access.attach && access.react && access.pin && access.joinVoice)
    }

    // authorCanEveryone()

    @Test
    fun autorComEveryoneMessageOuDonoPodeUsarEveryone() {
        val roles = mapOf("mod" to flags(RoleFlag.EveryoneMessage), "nada" to flags())
        val snapshot = known(roles = roles, mine = setOf("mod")).copy(userRoles = mapOf(me to setOf("mod"), "x" to setOf("nada")))
        assertTrue(authorCanEveryone(snapshot, me, null))
        assertFalse(authorCanEveryone(snapshot, "x", null))
        assertTrue(authorCanEveryone(snapshot, boss, null)) // o dono, sem cargo nenhum
    }

    @Test
    fun everyoneNuncaVemDeWebhookNemDeAutorAusenteEDesconhecidoNaoVale() {
        val roles = mapOf("mod" to flags(RoleFlag.EveryoneMessage))
        val snapshot = known(roles = roles, mine = setOf("mod"))
        assertFalse(authorCanEveryone(snapshot, me, webhookId = "w1"))
        assertFalse(authorCanEveryone(snapshot, null, null))
        assertFalse(authorCanEveryone(snapshot, "", null))
        assertFalse(authorCanEveryone(PermissionSnapshot.EMPTY, me, null))
        assertFalse(authorCanEveryone(snapshot, boss, webhookId = "w1")) // nem o dono por webhook
    }

    // visibleChannels()

    @Test
    fun canalDeTextoSomeSoSeALeituraFoiNegada() {
        val channels = listOf(channel("aberto"), channel("fechado"), channel("novo"))
        val snapshot = known(
            grants = mapOf("aberto" to emptyList(), "fechado" to listOf(grant("a", read = true))),
        )
        // "novo" não está nas entradas conhecidas: desconhecido aparece.
        assertEquals(listOf("aberto", "novo"), visibleChannels(channels, snapshot, me).map { it.id })
    }

    @Test
    fun canalDeVozAparecePorLeituraOuPorVoz() {
        val channels = listOf(channel("so-leitura", ChannelType.Voice), channel("so-voz", ChannelType.Voice), channel("nada", ChannelType.Voice))
        val snapshot = known(
            mine = setOf("r"),
            grants = mapOf(
                "so-leitura" to listOf(grant("r", read = true)),
                "so-voz" to listOf(grant("r", voice = true)),
                "nada" to listOf(grant("r", send = true)),
            ),
        )
        assertEquals(listOf("so-leitura", "so-voz"), visibleChannels(channels, snapshot, me).map { it.id })
    }

    @Test
    fun categoriaEsvaziadaPeloFiltroSomeMasACategoriaVaziaDeOrigemFica() {
        val channels = listOf(
            channel("cat-oculta", ChannelType.Category),
            channel("secreto", parent = "cat-oculta"),
            channel("cat-mista", ChannelType.Category),
            channel("secreto2", parent = "cat-mista"),
            channel("geral", parent = "cat-mista"),
            channel("cat-vazia", ChannelType.Category),
            channel("solto"),
        )
        val snapshot = known(
            grants = mapOf(
                "secreto" to listOf(grant("a", read = true)),
                "secreto2" to listOf(grant("a", read = true)),
                "geral" to emptyList(),
                "solto" to emptyList(),
            ),
        )
        assertEquals(
            listOf("cat-mista", "geral", "cat-vazia", "solto"),
            visibleChannels(channels, snapshot, me).map { it.id },
        )
    }

    @Test
    fun semSnapshotTodosOsCanaisAparecemNaMesmaOrdem() {
        val channels = listOf(channel("cat", ChannelType.Category), channel("a", parent = "cat"), channel("v", ChannelType.Voice, "cat"))
        assertEquals(channels, visibleChannels(channels, PermissionSnapshot.EMPTY, me))
        assertEquals(emptyList(), visibleChannels(emptyList(), PermissionSnapshot.EMPTY, me))
    }
}
