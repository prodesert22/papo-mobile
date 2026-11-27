package chat.papo.mobile

import chat.papo.mobile.chat.MessageRetention
import chat.papo.mobile.data.api.AttachmentDto
import chat.papo.mobile.data.api.EmbedDto
import chat.papo.mobile.data.api.PapoJson
import chat.papo.mobile.data.db.account.AccountDb
import chat.papo.mobile.media.MediaCache
import chat.papo.mobile.media.attachmentOriginalKey
import chat.papo.mobile.media.attachmentThumbKey
import chat.papo.mobile.media.embedAuthorKey
import chat.papo.mobile.media.embedImageKey
import chat.papo.mobile.media.posterKey
import chat.papo.mobile.platform.JcaCrypto
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A retenção de mensagens: as 3 conversas abertas por último guardam as 120 mensagens mais novas, com a mídia delas;
 * o resto sai do aparelho. Roda sobre o banco em memória e um cache de mídia em disco, sem rede.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageRetentionTest {
    private val driver = TestResources.driver()
    private val db = AccountDb(driver)
    private val q = db.chatQueries
    private val crypto = JcaCrypto()
    private val mediaKey = crypto.randomBytes(32)
    private val mediaDir = TestResources.tempDir()
    private val bytes = "imagem".encodeToByteArray()

    private var sharedCache: MediaCache? = null

    /** O cache de mídia da conta: o mesmo que a retenção varre, como na sessão, onde há uma instância só. */
    private fun TestScope.cache(): MediaCache =
        sharedCache ?: MediaCache(mediaDir, mediaKey, crypto, StandardTestDispatcher(testScheduler)).also { sharedCache = it }

    private fun TestScope.retention(withMedia: Boolean = true): MessageRetention =
        MessageRetention(db, StandardTestDispatcher(testScheduler), if (withMedia) cache() else null)

    private fun <T> list(serializer: KSerializer<T>, items: List<T>) = PapoJson.encodeToString(ListSerializer(serializer), items)

    /** Grava a mensagem [id] em [channel], no instante [us]; [attachments] e [embeds] já em JSON. */
    private fun seed(channel: String, id: String, us: Long, attachments: String = "[]", embeds: String = "[]") = q.upsertMessage(
        id = id, channel_id = channel, author_id = "u2", content = "texto $id", created_at = "2026-01-01T00:00:00Z", created_us = us,
        edited_at = null, reply_to = null, webhook_id = null, external_author = null, attachments = attachments, embeds = embeds,
        reactions = "[]", user_reactions = "[]",
    )

    /** [count] mensagens de [channel], ids `<canal>-000`, `<canal>-001`..., do instante igual ao número. */
    private fun seedMany(channel: String, count: Int) = repeat(count) { seed(channel, "$channel-%03d".format(it), it.toLong()) }

    private fun stored(channel: String): List<String> = q.selectMessages(channel, 10_000).executeAsList().map { it.id }

    private fun channelsWithMessages(): Set<String> = q.selectMessageChannels().executeAsList().toSet()

    private fun recentSaved(): String? = db.metaQueries.get(MessageRetention.RECENT_KEY).executeAsOneOrNull()

    // Constantes

    @Test
    fun osLimitesSaoOsDoPedido() {
        assertEquals(3, MessageRetention.MAX_CONVERSATIONS)
        assertEquals(120L, MessageRetention.MESSAGES_PER_CONVERSATION)
    }

    // Recência

    @Test
    fun aMaisRecenteVaiParaOComecoEACapEDeTres() = runTest(timeout = 5.seconds) {
        val retention = retention()
        assertTrue(retention.recentConversations().isEmpty())

        listOf("a", "b", "c").forEach { retention.noteOpened(it) }
        assertEquals(listOf("c", "b", "a"), retention.recentConversations())

        retention.noteOpened("d") // a quarta empurra a mais antiga para fora
        assertEquals(listOf("d", "c", "b"), retention.recentConversations())
    }

    @Test
    fun abrirDeNovoUmaDaListaSoMudaAOrdemSemRepetir() = runTest(timeout = 5.seconds) {
        val retention = retention()
        listOf("a", "b", "c").forEach { retention.noteOpened(it) }

        retention.noteOpened("a")
        assertEquals(listOf("a", "c", "b"), retention.recentConversations())

        retention.noteOpened("a") // já é a primeira: nada muda
        assertEquals(listOf("a", "c", "b"), retention.recentConversations())
    }

    @Test
    fun asRecentesSobrevivemAReiniciarEFicamNoBancoDaConta() = runTest(timeout = 5.seconds) {
        retention().apply { listOf("a", "b", "c", "d").forEach { noteOpened(it) } }

        // Outra instância sobre o mesmo banco, como depois de o processo morrer.
        assertEquals(listOf("d", "c", "b"), retention().recentConversations())
        assertEquals("d,c,b", recentSaved())
    }

    @Test
    fun cadaContaTemAsSuasRecentes() = runTest(timeout = 5.seconds) {
        retention().noteOpened("a")
        val outraConta = MessageRetention(AccountDb(TestResources.driver()), StandardTestDispatcher(testScheduler))

        assertTrue(outraConta.recentConversations().isEmpty())
    }

    @Test
    fun valorGuardadoComRepeticaoVaziosOuDemaisEArrumadoAoLer() = runTest(timeout = 5.seconds) {
        db.metaQueries.put(MessageRetention.RECENT_KEY, ",a,,b,a,c,d,e")

        assertEquals(listOf("a", "b", "c"), retention().recentConversations())
    }

    @Test
    fun semNadaGuardadoNaoHaRecentes() = runTest(timeout = 5.seconds) {
        db.metaQueries.put(MessageRetention.RECENT_KEY, "")

        assertTrue(retention().recentConversations().isEmpty())
    }

    // Quem guarda mensagens novas

    @Test
    fun guardaMensagemNovaQuemEstaEntreAsRecentesOuTemTelaAberta() = runTest(timeout = 5.seconds) {
        val retention = retention()
        retention.noteOpened("recente")
        retention.markOpen("aberta")
        retention.recentConversations()

        assertTrue(retention.keeps("recente"))
        assertTrue(retention.keeps("aberta"))
        assertFalse(retention.keeps("outra"))
    }

    // Poda

    @Test
    fun podaMantemAsCentoEVinteMaisNovasEDescartaAsMaisAntigas() = runTest(timeout = 5.seconds) {
        val retention = retention()
        retention.noteOpened("c1")
        seedMany("c1", 125)

        val touched = retention.prune()

        assertEquals((124 downTo 5).map { "c1-%03d".format(it) }, stored("c1"))
        assertEquals(setOf("c1"), touched)
    }

    @Test
    fun noEmpateDeInstanteSaiPrimeiroOQueTemOIdMenor() = runTest(timeout = 5.seconds) {
        val retention = retention()
        retention.noteOpened("c1")
        // Quatro mensagens por instante: o corte (5 de fora) cai no meio do segundo grupo.
        val all = (0 until 125).map { "c1-%03d".format(it) to (it / 4).toLong() }
        all.forEach { (id, us) -> seed("c1", id, us) }

        retention.prune()

        val expected = all.sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenByDescending { it.first }).take(120).map { it.first }
        assertEquals(expected, stored("c1")) // a mesma ordem de selectMessages
        assertTrue("c1-004" !in stored("c1") && "c1-005" in stored("c1"))
    }

    @Test
    fun comCentoEVinteNadaSaiEComCentoEUmSaiUma() = runTest(timeout = 5.seconds) {
        val retention = retention()
        retention.noteOpened("c1")
        retention.noteOpened("c2")
        seedMany("c1", 120)
        seedMany("c2", 121)

        val touched = retention.prune()

        assertEquals(120, stored("c1").size)
        assertEquals(120, stored("c2").size)
        assertFalse("c2-000" in stored("c2"))
        assertEquals(setOf("c2"), touched)
    }

    @Test
    fun conversaForaDasRecentesPerdeTodasAsMensagens() = runTest(timeout = 5.seconds) {
        val retention = retention()
        retention.noteOpened("c1")
        seedMany("c1", 5)
        seedMany("velha", 5)
        seedMany("dm", 3)

        val touched = retention.prune()

        assertEquals(setOf("c1"), channelsWithMessages())
        assertEquals(setOf("velha", "dm"), touched)
    }

    @Test
    fun semNenhumaRecenteTudoSai() = runTest(timeout = 5.seconds) {
        seedMany("c1", 5)

        retention().prune()

        assertTrue(channelsWithMessages().isEmpty())
    }

    @Test
    fun abrirUmaQuartaConversaTiraAsMensagensDaMaisAntiga() = runTest(timeout = 5.seconds) {
        val retention = retention()
        listOf("a", "b", "c").forEach { retention.noteOpened(it); seedMany(it, 3) }
        retention.prune()
        assertEquals(setOf("a", "b", "c"), channelsWithMessages())

        retention.noteOpened("d")
        seedMany("d", 3)
        val touched = retention.prune()

        assertEquals(setOf("b", "c", "d"), channelsWithMessages())
        assertEquals(setOf("a"), touched)
    }

    @Test
    fun podarDeNovoNaoMudaNada() = runTest(timeout = 5.seconds) {
        val retention = retention()
        retention.noteOpened("c1")
        seedMany("c1", 130)
        retention.prune()
        val before = stored("c1")

        assertTrue(retention.prune().isEmpty())
        assertEquals(before, stored("c1"))
    }

    // Conversa aberta

    @Test
    fun conversaAbertaNaoEPodadaEDepoisDeFecharSim() = runTest(timeout = 5.seconds) {
        val retention = retention()
        retention.markOpen("c1") // aberta, mas fora das recentes
        seedMany("c1", 200)

        assertTrue(retention.prune().isEmpty())
        assertEquals(200, stored("c1").size)

        retention.markClosed("c1")
        assertEquals(setOf("c1"), retention.prune())
        assertTrue(stored("c1").isEmpty())
    }

    @Test
    fun conversaRecenteMasAbertaNaoEAparadaAteFechar() = runTest(timeout = 5.seconds) {
        val retention = retention()
        retention.noteOpened("c1")
        retention.markOpen("c1")
        seedMany("c1", 200)

        assertTrue(retention.prune().isEmpty())
        assertEquals(200, stored("c1").size)

        retention.markClosed("c1")
        retention.prune()
        assertEquals(120, stored("c1").size)
    }

    @Test
    fun duasTelasDaMesmaConversaSoLiberamQuandoAsDuasFecham() = runTest(timeout = 5.seconds) {
        val retention = retention()
        retention.markOpen("c1")
        retention.markOpen("c1")
        seedMany("c1", 5)

        retention.markClosed("c1")
        assertTrue(retention.prune().isEmpty()) // ainda há uma tela
        assertEquals(5, stored("c1").size)

        retention.markClosed("c1")
        assertEquals(setOf("c1"), retention.prune())
    }

    @Test
    fun fecharUmaTelaQueNaoEstavaAbertaNaoQuebraAContagem() = runTest(timeout = 5.seconds) {
        val retention = retention()
        retention.markClosed("c1") // sem efeito
        retention.markOpen("c1")
        seedMany("c1", 5)

        assertTrue(retention.prune().isEmpty()) // a contagem não ficou negativa nem zerada
    }

    @Test
    fun duasConversasAbertasAoMesmoTempoSaoPoupadasAindaQueNaoSejamRecentes() = runTest(timeout = 5.seconds) {
        val retention = retention()
        listOf("r1", "r2", "r3").forEach { retention.noteOpened(it) }
        retention.markOpen("pilha1")
        retention.markOpen("pilha2")
        listOf("r1", "r2", "r3", "pilha1", "pilha2", "fechada").forEach { seedMany(it, 2) }

        retention.prune()

        assertEquals(setOf("r1", "r2", "r3", "pilha1", "pilha2"), channelsWithMessages())
    }

    // Trecho antigo

    @Test
    fun trechoAntigoDeConversaFechadaEDescartadoComAMarca() = runTest(timeout = 5.seconds) {
        val retention = retention()
        retention.noteOpened("c1")
        seedMany("c1", 10)
        db.metaQueries.put("detached:c1", "1")

        val touched = retention.prune()

        assertTrue(stored("c1").isEmpty())
        assertNull(db.metaQueries.get("detached:c1").executeAsOneOrNull())
        assertEquals(setOf("c1"), touched)
    }

    @Test
    fun trechoAntigoDeConversaAbertaFicaComAMarca() = runTest(timeout = 5.seconds) {
        val retention = retention()
        retention.markOpen("c1")
        seedMany("c1", 10)
        db.metaQueries.put("detached:c1", "1")

        retention.prune()

        assertEquals(10, stored("c1").size)
        assertNotNull(db.metaQueries.get("detached:c1").executeAsOneOrNull())
    }

    @Test
    fun marcaDeConversaForaDasRecentesSaiMesmoSemMensagens() = runTest(timeout = 5.seconds) {
        db.metaQueries.put("detached:velha", "1")

        retention().prune()

        assertNull(db.metaQueries.get("detached:velha").executeAsOneOrNull())
    }

    @Test
    fun marcaDeUmaConversaNaoApagaAMarcaDeOutra() = runTest(timeout = 5.seconds) {
        val retention = retention()
        retention.markOpen("aberta")
        db.metaQueries.put("detached:aberta", "1")
        db.metaQueries.put("detached:velha", "1")

        retention.prune()

        assertEquals(listOf("detached:aberta"), db.metaQueries.keysWithPrefix("detached:").executeAsList())
    }

    // Corte logo depois de gravar

    @Test
    fun cortarLogoDepoisDeGravarSoValeParaConversaSemTelaEAcimaDoLimite() = runTest(timeout = 5.seconds) {
        val retention = retention()
        retention.noteOpened("c1")
        seedMany("c1", 121)
        retention.markOpen("c1")
        assertFalse(retention.trimIfClosed("c1")) // com tela, nada se corta
        assertEquals(121, stored("c1").size)

        retention.markClosed("c1")
        assertTrue(retention.trimIfClosed("c1"))
        assertEquals(120, stored("c1").size)
        assertFalse(retention.trimIfClosed("c1")) // no limite: nada a cortar
    }

    // Só mensagens

    @Test
    fun aPodaNaoTocaNasConversasNemNosRascunhosNemNasConfiguracoes() = runTest(timeout = 5.seconds) {
        q.upsertChannel("velha", "geral", "text", 1, null, null, "m9", "2026-01-01T00:00:09Z", "oi", "bia", "m5", "{\"level\":\"all\"}")
        q.upsertDm("dm", "u2", "2026-01-01T00:00:00Z", "m8", "2026-01-01T00:00:08Z", "ola", "bia", "m3", 4)
        db.metaQueries.put("draft:velha", "rascunho")
        seedMany("velha", 5)
        seedMany("dm", 5)

        retention().prune()

        assertTrue(channelsWithMessages().isEmpty())
        val channel = q.selectChannel("velha").executeAsOne()
        assertEquals(listOf("m9", "oi", "bia", "m5", "{\"level\":\"all\"}"), listOf(channel.last_message_id, channel.last_message_text, channel.last_message_author, channel.last_read_message, channel.notification_settings))
        val dm = q.selectDm("dm").executeAsOne()
        assertEquals(listOf("m8", "ola", "m3", 4L), listOf(dm.last_message_id, dm.last_message_text, dm.last_read_message, dm.unread_count))
        assertEquals("rascunho", db.metaQueries.get("draft:velha").executeAsOne())
    }

    // Mídia

    private fun attachments(vararg ids: String) = list(AttachmentDto.serializer(), ids.map { AttachmentDto(it, "image/png", "$it.png", 3) })

    private fun embeds(vararg ids: String) = list(EmbedDto.serializer(), ids.map { EmbedDto(it, title = "t") })

    private fun mediaOf(attachment: String) = listOf(attachmentThumbKey(attachment), attachmentOriginalKey(attachment), posterKey(attachment))

    private fun embedMediaOf(embed: String) = listOf(embedImageKey(embed), embedAuthorKey(embed))

    @Test
    fun aMidiaDasMensagensQueFicamFicaEADasQueSaemSai() = runTest(timeout = 5.seconds) {
        val retention = retention()
        val cache = cache()
        retention.noteOpened("c1")
        // 121 mensagens: sai a mais antiga (c1-000), com os anexos e o embed dela; a de outra conversa sai inteira.
        seed("c1", "c1-000", 0, attachments("a-cortada"), embeds("e-cortado"))
        (1..119).forEach { seed("c1", "c1-%03d".format(it), it.toLong()) }
        seed("c1", "novo", 1000, attachments("a-novo"), embeds("e-novo"))
        seed("velha", "x", 1, attachments("a-velha"), embeds("e-velho"))
        val kept = mediaOf("a-novo") + embedMediaOf("e-novo")
        val dropped = mediaOf("a-cortada") + embedMediaOf("e-cortado") + mediaOf("a-velha") + embedMediaOf("e-velho")
        (kept + dropped).forEach { cache.put(it, bytes) }

        retention.prune()

        assertEquals(120, stored("c1").size)
        kept.forEach { assertContentEquals(bytes, cache.peek(it), it) }
        dropped.forEach { assertNull(cache.peek(it), it) }
    }

    @Test
    fun aPodaNaoApagaAvataresEmojisBannersNemAvataresDeWebhook() = runTest(timeout = 5.seconds) {
        val cache = cache()
        val others = listOf("avatar/u1/2", "emoji/e1", "media/sha", "webhook-avatar/w1")
        others.forEach { cache.put(it, bytes) }
        cache.put(attachmentThumbKey("a1"), bytes)

        retention().prune()

        others.forEach { assertContentEquals(bytes, cache.peek(it), it) }
        assertNull(cache.peek(attachmentThumbKey("a1")))
    }

    @Test
    fun mensagemComJsonIlegivelNaoImpedeAVarredura() = runTest(timeout = 5.seconds) {
        val retention = retention()
        val cache = cache()
        retention.markOpen("c1")
        seed("c1", "m1", 1, attachments = "isto não é json", embeds = "[]")
        seed("c1", "m2", 2, attachments("a2"))
        cache.put(attachmentThumbKey("a2"), bytes)
        cache.put(attachmentThumbKey("lixo"), bytes)

        retention.prune()

        assertContentEquals(bytes, cache.peek(attachmentThumbKey("a2")))
        assertNull(cache.peek(attachmentThumbKey("lixo")))
    }

    @Test
    fun semCacheDeMidiaAPodaSoMexeNasLinhas() = runTest(timeout = 5.seconds) {
        val retention = retention(withMedia = false)
        retention.noteOpened("c1")
        seedMany("c1", 125)
        seedMany("velha", 2)

        assertEquals(setOf("c1", "velha"), retention.prune())
        assertEquals(120, stored("c1").size)
    }

    // Migração da pasta de mídia

    private fun migratedMark() = db.metaQueries.get(MessageRetention.MEDIA_LAYOUT_KEY).executeAsOneOrNull()

    @Test
    fun aMigracaoMantemAvataresEEmojisConhecidosEApagaOResto() = runTest(timeout = 5.seconds) {
        val cache = cache()
        q.upsertUser("u1", "ana", null, null, null, null, false, null)
        q.upsertUser("u2", "bia", null, null, null, null, false, null)
        q.bumpAvatar("u2")
        q.bumpAvatar("u2") // versão 2: a de uma versão anterior já não vale
        db.metaQueries.put("emoji.server", """[{"id":"e1","name":"um","format":"png"}]""")
        val keep = listOf("avatar/u1/0", "avatar/u2/2", "emoji/e1")
        val gone = listOf("avatar/u2/1", "avatar/desconhecido/0", "emoji/e2", "media/banner", "webhook-avatar/w1", "att-thumb/antigo", "att/antigo", "embed/antigo")
        (keep + gone).forEach { cache.put(it, bytes) }
        cache.put(attachmentThumbKey("a1"), bytes) // a área nova não é tocada pela migração
        seed("c1", "m1", 1, attachments("a1"))

        retention().apply { markOpen("c1") }.startup()

        keep.forEach { assertContentEquals(bytes, cache.peek(it), it) }
        gone.forEach { assertNull(cache.peek(it), it) }
        assertContentEquals(bytes, cache.peek(attachmentThumbKey("a1")))
        assertEquals("2", migratedMark())
    }

    @Test
    fun aMigracaoSoRodaUmaVez() = runTest(timeout = 5.seconds) {
        val cache = cache()
        retention().startup()
        assertEquals("2", migratedMark())

        cache.put("media/banner", bytes) // depois da migração: é do formato novo e fica
        retention().startup()

        assertContentEquals(bytes, cache.peek("media/banner"))
    }

    @Test
    fun listaDeEmojisIlegivelNaoImpedeAMigracao() = runTest(timeout = 5.seconds) {
        val cache = cache()
        db.metaQueries.put("emoji.server", "não é json")
        cache.put("emoji/e1", bytes)

        retention().startup()

        assertNull(cache.peek("emoji/e1")) // sem lista não há como saber; o catálogo o traz de volta na próxima leitura
        assertEquals("2", migratedMark())
    }

    @Test
    fun migracaoCanceladaNaoGravaAMarcaERodaDeNovo() = runTest(timeout = 5.seconds) {
        val retention = retention()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { retention.startup() }
        job.cancel() // suspensa na primeira leitura do banco
        job.join()

        assertTrue(job.isCancelled)
        assertNull(migratedMark())

        retention().startup()
        assertEquals("2", migratedMark())
    }

    @Test
    fun aberturaDaContaPodaDepoisDeMigrar() = runTest(timeout = 5.seconds) {
        val retention = retention()
        retention.noteOpened("c1")
        seedMany("c1", 130)
        seedMany("velha", 4)

        assertEquals(setOf("c1", "velha"), retention.startup())

        assertEquals(120, stored("c1").size)
        assertEquals(setOf("c1"), channelsWithMessages())
    }

    // Cancelamento

    @Test
    fun podaCanceladaPropagaOCancelamentoENaoApagaNada() = runTest(timeout = 5.seconds) {
        val retention = retention()
        val cache = cache()
        seedMany("velha", 5)
        cache.put(attachmentThumbKey("a1"), bytes)

        val job = launch(start = CoroutineStart.UNDISPATCHED) { retention.prune() }
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(5, stored("velha").size)
        assertContentEquals(bytes, cache.peek(attachmentThumbKey("a1")))
        // E a poda seguinte não ficou travada pela cancelada.
        assertEquals(setOf("velha"), retention.prune())
    }

    @Test
    fun arquivosDaAreaDasMensagensFicamNaSubpastaDoCache() = runTest(timeout = 5.seconds) {
        cache().put(attachmentThumbKey("a1"), bytes)

        assertEquals(1, File(mediaDir, MediaCache.MESSAGE_AREA).listFiles().orEmpty().size)
    }
}
