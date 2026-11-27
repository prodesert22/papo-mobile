package chat.papo.mobile

import chat.papo.mobile.chat.Dm
import chat.papo.mobile.chat.MessagePosition
import chat.papo.mobile.chat.NotificationItem
import chat.papo.mobile.chat.ReadMark
import chat.papo.mobile.chat.TriggerKind
import chat.papo.mobile.chat.badgeText
import chat.papo.mobile.chat.classify
import chat.papo.mobile.chat.markReadUpTo
import chat.papo.mobile.chat.mentionCounts
import chat.papo.mobile.chat.mergePage
import chat.papo.mobile.chat.readBatches
import chat.papo.mobile.chat.toItem
import chat.papo.mobile.data.api.NotificationDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationsTest {
    private val me = "abcdef01-1111-4111-8111-111111111111"
    private val other = "22222222-2222-4222-8222-222222222222"

    private fun item(
        id: String,
        at: String = "2026-01-01T00:00:00Z",
        channel: String = "c1",
        message: String = "m-$id",
        content: String = "",
        read: Boolean = false,
    ) = NotificationItem(id, message, channel, authorId = other, webhookId = null, externalAuthor = null, content = content, read = read, createdAt = at)

    private fun at(second: Int, millis: Int = 0) =
        "2026-01-01T00:00:${second.toString().padStart(2, '0')}.${millis.toString().padStart(3, '0')}Z"

    private fun dm(id: String, unread: Int) =
        Dm(id, userId = other, lastMessageId = null, lastMessageText = null, lastMessageAt = null, unreadCount = unread)

    // classify

    @Test
    fun conversaDiretaSempreEDm() {
        assertEquals(TriggerKind.Dm, classify(item("n", content = "oi"), me, isDm = true))
        // Mesmo com menção e @everyone no texto: o servidor cria linha para toda mensagem de DM.
        assertEquals(TriggerKind.Dm, classify(item("n", content = "@mention(<@$me>) @everyone"), me, isDm = true))
    }

    @Test
    fun mencaoDiretaAMimEMencao() {
        assertEquals(TriggerKind.Mention, classify(item("n", content = "oi @mention(<@$me>), tudo bem?"), me, isDm = false))
    }

    @Test
    fun mencaoVenceEveryoneNaMesmaMensagem() {
        assertEquals(TriggerKind.Mention, classify(item("n", content = "@everyone e @mention(<@$me>)"), me, isDm = false))
    }

    @Test
    fun mencaoAOutraPessoaMaisEveryoneEEveryone() {
        assertEquals(TriggerKind.Everyone, classify(item("n", content = "@mention(<@$other>) @everyone"), me, isDm = false))
    }

    @Test
    fun mencaoAOutraPessoaSemEveryoneEResposta() {
        // A linha existe e não é DM nem menção a mim: só pode ser resposta a uma mensagem minha.
        assertEquals(TriggerKind.Reply, classify(item("n", content = "@mention(<@$other>) concordo"), me, isDm = false))
        assertEquals(TriggerKind.Reply, classify(item("n", content = ""), me, isDm = false))
    }

    @Test
    fun umaMencaoEntreVariasAchaAMinha() {
        val content = "@mention(<@$other>) e @mention(<@$me>)"
        assertEquals(TriggerKind.Mention, classify(item("n", content = content), me, isDm = false))
    }

    @Test
    fun mencaoSegueOFormatoDoServidor() {
        // Sem diferenciar maiúsculas no `@mention`, como o `(?i)` do servidor.
        assertEquals(TriggerKind.Mention, classify(item("n", content = "@MENTION(<@$me>)"), me, isDm = false))
        // `<@id>` sozinho não dispara nada no servidor.
        assertEquals(TriggerKind.Reply, classify(item("n", content = "oi <@$me>"), me, isDm = false))
        // O id é comparado como está, como o servidor faz: maiúsculas no id não casam com o usuário.
        assertEquals(TriggerKind.Reply, classify(item("n", content = "@mention(<@${me.uppercase()}>)"), me, isDm = false))
        // O id tem de ter a forma de um UUID.
        assertEquals(TriggerKind.Reply, classify(item("n", content = "@mention(<@abc>)"), "abc", isDm = false))
    }

    @Test
    fun everyoneSegueOLimiteDePalavraDoServidor() {
        fun kind(text: String) = classify(item("n", content = text), me, isDm = false)
        assertEquals(TriggerKind.Everyone, kind("@everyone"))
        assertEquals(TriggerKind.Everyone, kind("atenção @EVERYONE!"))
        assertEquals(TriggerKind.Everyone, kind("x@everyone")) // o servidor casa em qualquer lugar
        assertEquals(TriggerKind.Everyone, kind("@everyoneé")) // `é` não é letra para o `\b` do servidor
        assertEquals(TriggerKind.Everyone, kind("a@b.com @everyone")) // vários `@`: o primeiro não vale, o segundo sim
        assertEquals(TriggerKind.Reply, kind("@everyones"))
        assertEquals(TriggerKind.Reply, kind("@everyone_x"))
        assertEquals(TriggerKind.Reply, kind("@every one"))
        assertEquals(TriggerKind.Reply, kind("termina com @"))
    }

    @Test
    fun mencaoDepoisDoCorteDe512NaoEVista() {
        val content = "a".repeat(512)
        // O servidor corta em 512; a menção que ficou de fora não chega, e a linha cai em Reply.
        assertEquals(TriggerKind.Reply, classify(item("n", content = content), me, isDm = false))
    }

    // Item

    @Test
    fun oInstanteDaLinhaVemEmMicrossegundosENuloSeIlegivel() {
        assertEquals(1_767_225_600_000_000L, item("n", at = "2026-01-01T00:00:00Z").createdAtUs)
        assertEquals(1_767_225_600_000_001L, item("n", at = "2026-01-01T00:00:00.000001Z").createdAtUs)
        assertEquals(item("n", at = "2026-01-01T00:00:00Z").createdAtUs, item("n", at = "2025-12-31T21:00:00-03:00").createdAtUs)
        assertNull(item("n", at = "ontem").createdAtUs)
    }

    @Test
    fun odtoViraALinhaComTodosOsCampos() {
        val dto = NotificationDto(
            id = "n1", messageId = "m1", channelId = "c1", authorId = "u2", webhookId = "w1", messageContent = "oi", read = true,
            createdAt = "2026-01-01T00:00:00Z",
        )
        val item = dto.toItem()
        assertEquals(listOf("n1", "m1", "c1", "u2", "w1", "oi", true, "2026-01-01T00:00:00Z"),
            listOf(item.id, item.messageId, item.channelId, item.authorId, item.webhookId, item.content, item.read, item.createdAt))
        assertNull(item.externalAuthor)
    }

    // mergePage

    @Test
    fun mergeDeListasVaziasEVazio() {
        assertEquals(emptyList(), mergePage(emptyList(), emptyList()))
    }

    @Test
    fun mergeSemRepetirIdEDaMaisNovaParaAMaisAntiga() {
        val old = listOf(item("a", at(1)), item("c", at(3)))
        val page = listOf(item("d", at(4)), item("c", at(3)), item("b", at(2)))

        assertEquals(listOf("d", "c", "b", "a"), mergePage(old, page).map { it.id })
    }

    @Test
    fun mergeComEmpateNoInstanteOrdenaPeloIdDecrescente() {
        val merged = mergePage(listOf(item("a", at(1))), listOf(item("c", at(1)), item("b", at(1))))
        assertEquals(listOf("c", "b", "a"), merged.map { it.id })
    }

    @Test
    fun mergeComparaOInstanteENaoOTexto() {
        // 10:00 em -03:00 é 13:00Z: depois de 12:00Z, mesmo com o texto "menor".
        val early = item("a", "2026-01-01T12:00:00Z")
        val late = item("b", "2026-01-01T10:00:00-03:00")
        assertEquals(listOf("b", "a"), mergePage(listOf(early), listOf(late)).map { it.id })
    }

    @Test
    fun mergeColocaPorUltimoOQueTemInstanteIlegivel() {
        val merged = mergePage(listOf(item("x", "ontem")), listOf(item("a", at(1))))
        assertEquals(listOf("a", "x"), merged.map { it.id })
    }

    @Test
    fun mergeUsaALinhaNovaParaOMesmoId() {
        val merged = mergePage(listOf(item("a", at(1), content = "velho")), listOf(item("a", at(1), content = "editado")))
        assertEquals("editado", merged.single().content)
    }

    @Test
    fun mergeNaoDesfazALeitura() {
        val old = listOf(item("a", at(1), read = true), item("b", at(2), read = false))
        val page = listOf(item("a", at(1), read = false), item("b", at(2), read = true))

        val merged = mergePage(old, page).associateBy { it.id }

        assertTrue(merged.getValue("a").read) // cópia atrasada que ainda a mostra não lida
        assertTrue(merged.getValue("b").read) // lida no servidor
    }

    @Test
    fun mergeMantemLidaQuandoAsDuasCopiasSaoLidas() {
        val merged = mergePage(listOf(item("a", read = true)), listOf(item("a", read = true)))
        assertTrue(merged.single().read)
    }

    @Test
    fun mergeRepeteIdNaPropriaPaginaSemDuplicar() {
        assertEquals(1, mergePage(emptyList(), listOf(item("a"), item("a"))).size)
    }

    // readBatches

    @Test
    fun blocosDeLeituraDeMil() {
        assertEquals(emptyList(), readBatches(emptyList()))
        assertEquals(listOf(listOf("n0")), readBatches(listOf("n0")))
        assertEquals(listOf(1000), readBatches(List(1000) { "n$it" }).map { it.size })
        assertEquals(listOf(1000, 1), readBatches(List(1001) { "n$it" }).map { it.size })
        assertEquals(listOf(1000, 1000, 500), readBatches(List(2500) { "n$it" }).map { it.size })
    }

    @Test
    fun blocosDeLeituraNaoRepetemId() {
        assertEquals(listOf(listOf("a", "b")), readBatches(listOf("a", "b", "a", "b")))
    }

    // markReadUpTo

    private val positions = mapOf(
        "m1" to MessagePosition(100, "m1"),
        "m2" to MessagePosition(200, "m2"),
        "m3" to MessagePosition(300, "m3"),
        "m3b" to MessagePosition(300, "m3b"),
    )

    @Test
    fun marcaAteAMensagemPelaPosicaoQuandoAsDuasEstaoGuardadas() {
        val items = listOf(item("n3", message = "m3"), item("n2", message = "m2"), item("n1", message = "m1"))

        val marked = markReadUpTo(items, ReadMark("c1", "m2", "x"), positions::get)

        assertEquals(listOf("n3" to false, "n2" to true, "n1" to true), marked.map { it.id to it.read })
    }

    @Test
    fun empateNoInstanteDecidePeloIdComoOServidor() {
        val items = listOf(item("nb", message = "m3b"), item("na", message = "m3"))

        val marked = markReadUpTo(items, ReadMark("c1", "m3", null), positions::get)

        assertEquals(listOf("nb" to false, "na" to true), marked.map { it.id to it.read })
    }

    @Test
    fun aPosicaoVenceOInstanteDaNotificacao() {
        // A notificação saiu depois da marca no relógio, mas a mensagem dela é anterior: o servidor a marca.
        val items = listOf(item("n1", at = at(30), message = "m1"))
        val marked = markReadUpTo(items, ReadMark("c1", "m2", at(5)), positions::get)
        assertTrue(marked.single().read)
    }

    @Test
    fun ignoraOQueJaEstaLidoEOutrosCanais() {
        val items = listOf(item("lida", message = "m1", read = true), item("outro", message = "m1", channel = "c2"))

        val marked = markReadUpTo(items, ReadMark("c1", "m3", null), positions::get)

        assertEquals(items, marked)
    }

    @Test
    fun semListaNadaMuda() {
        assertEquals(emptyList(), markReadUpTo(emptyList(), ReadMark("c1", "m1", null), positions::get))
    }

    @Test
    fun aLinhaDaPropriaMensagemDaMarcaContaMesmoSemPosicoes() {
        val items = listOf(item("n1", at = at(1, 3), message = "mx"))
        // O instante da notificação (T + 3 ms) é depois do da mensagem (T): só a regra do id a pega.
        val marked = markReadUpTo(items, ReadMark("c1", "mx", at(1)), { null })
        assertTrue(marked.single().read)
    }

    @Test
    fun semPosicoesComparaOInstanteDaNotificacaoComODaMarca() {
        val items = listOf(
            item("antes", at = at(5), message = "ma"),
            item("igual", at = at(10), message = "mb"),
            item("depois", at = at(10, 3), message = "mc"),
        )

        val marked = markReadUpTo(items, ReadMark("c1", "mx", at(10)), { null })

        assertEquals(listOf("antes" to true, "igual" to true, "depois" to false), marked.map { it.id to it.read })
    }

    @Test
    fun comUmaSoPosicaoGuardadaVoltaAoInstante() {
        val items = listOf(item("n1", at = at(5), message = "m1"), item("n2", at = at(50), message = "m2"))

        // A marca está guardada e a mensagem de n2 não: sem as duas posições, vale o instante.
        val onlyMark = markReadUpTo(items, ReadMark("c1", "m3", at(10)), { id -> positions[id].takeIf { id == "m3" } })
        assertEquals(listOf(true, false), onlyMark.map { it.read })

        // As mensagens das linhas estão guardadas e a da marca não.
        val onlyItems = markReadUpTo(items, ReadMark("c1", "mx", at(10)), { id -> positions[id] })
        assertEquals(listOf(true, false), onlyItems.map { it.read })
    }

    @Test
    fun semOInstanteDaMarcaSoAPropriaMensagemConta() {
        val items = listOf(item("n1", at = at(1), message = "ma"), item("n2", at = at(2), message = "mx"))

        val noInstant = markReadUpTo(items, ReadMark("c1", "mx", null), { null })
        val unreadable = markReadUpTo(items, ReadMark("c1", "mx", "ontem"), { null })

        assertEquals(listOf(false, true), noInstant.map { it.read })
        assertEquals(listOf(false, true), unreadable.map { it.read })
    }

    @Test
    fun linhaComInstanteIlegivelNuncaEMarcadaPeloInstante() {
        val items = listOf(item("n1", at = "ontem", message = "ma"))
        assertFalse(markReadUpTo(items, ReadMark("c1", "mx", at(10)), { null }).single().read)
    }

    @Test
    fun posicaoDeMensagemOrdenaPorInstanteEDepoisId() {
        assertTrue(MessagePosition(1, "z") < MessagePosition(2, "a"))
        assertTrue(MessagePosition(2, "a") < MessagePosition(2, "b"))
        assertEquals(0, MessagePosition(2, "a").compareTo(MessagePosition(2, "a")))
    }

    // mentionCounts

    @Test
    fun semLinhasNemConversasNadaConta() {
        val counts = mentionCounts(emptyList(), emptyList(), me, capped = false) { _, _ -> true }
        assertEquals(emptyMap(), counts.perChannel)
        assertEquals(emptyMap(), counts.perDm)
        assertEquals(0, counts.total)
        assertFalse(counts.capped)
    }

    @Test
    fun contaAsLinhasNaoLidasPorCanalEPassaOMotivoAoPredicado() {
        val items = listOf(
            item("n1", channel = "c1", content = "@mention(<@$me>)"),
            item("n2", channel = "c1", content = "@everyone"),
            item("n3", channel = "c2", content = "resposta"),
            item("n4", channel = "c2", content = "@mention(<@$me>)", read = true),
        )
        val asked = mutableListOf<Pair<String, TriggerKind>>()

        val counts = mentionCounts(items, emptyList(), me, capped = false) { channel, kind ->
            asked += channel to kind
            true
        }

        assertEquals(mapOf("c1" to 2, "c2" to 1), counts.perChannel)
        assertEquals(3, counts.total)
        // A linha lida nem chega ao predicado.
        assertEquals(listOf("c1" to TriggerKind.Mention, "c1" to TriggerKind.Everyone, "c2" to TriggerKind.Reply), asked)
    }

    @Test
    fun oPredicadoSuprimeUmTipoOuUmCanal() {
        val items = listOf(
            item("n1", channel = "c1", content = "@mention(<@$me>)"),
            item("n2", channel = "c1", content = "@everyone"),
            item("n3", channel = "c2", content = "@everyone"),
        )

        val counts = mentionCounts(items, emptyList(), me, capped = false) { channel, kind ->
            kind != TriggerKind.Everyone || channel == "c2"
        }

        assertEquals(mapOf("c1" to 1, "c2" to 1), counts.perChannel)
        assertEquals(2, counts.total)
    }

    @Test
    fun canalSemNadaAContarNaoApareceNoMapa() {
        val items = listOf(item("n1", channel = "c1", content = "@everyone"))
        val counts = mentionCounts(items, emptyList(), me, capped = false) { _, _ -> false }
        assertEquals(emptyMap(), counts.perChannel)
    }

    @Test
    fun conversaDiretaContaPeloUnreadCountEIgnoraAsLinhas() {
        val items = listOf(
            item("n1", channel = "d1", content = "oi"),
            item("n2", channel = "d1", content = "oi de novo"),
            item("n3", channel = "c1", content = "@everyone"),
        )
        val dms = listOf(dm("d1", unread = 5), dm("d2", unread = 0))
        val asked = mutableListOf<String>()

        val counts = mentionCounts(items, dms, me, capped = false) { channel, _ ->
            asked += channel
            true
        }

        assertEquals(mapOf("d1" to 5), counts.perDm) // sem o d2, que não tem não lidas
        assertEquals(mapOf("c1" to 1), counts.perChannel)
        assertEquals(6, counts.total)
        assertEquals(listOf("c1"), asked) // as linhas da DM não passam pelo predicado
    }

    @Test
    fun oCortePassaDaCaixaParaAContagem() {
        assertTrue(mentionCounts(emptyList(), emptyList(), me, capped = true) { _, _ -> true }.capped)
    }

    // badgeText

    @Test
    fun seloSemMencoesNaoApareceNemComCorte() {
        assertNull(badgeText(0, capped = false))
        assertNull(badgeText(-1, capped = false))
        assertNull(badgeText(0, capped = true))
    }

    @Test
    fun seloMostraOsNumeros() {
        assertEquals("1", badgeText(1, capped = false))
        assertEquals("99", badgeText(99, capped = false))
    }

    @Test
    fun seloPassaDe99ViraNoventaENoveMais() {
        assertEquals("99+", badgeText(100, capped = false))
        assertEquals("99+", badgeText(150, capped = true))
        assertEquals("99+", badgeText(99, capped = true))
    }

    @Test
    fun seloComACaixaLidaSoEmParteAvisaQueHaMais() {
        assertEquals("5+", badgeText(5, capped = true))
    }
}
