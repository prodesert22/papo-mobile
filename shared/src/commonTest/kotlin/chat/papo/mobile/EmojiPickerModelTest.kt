package chat.papo.mobile

import chat.papo.mobile.chat.EmojiPickerModel
import chat.papo.mobile.chat.EmojiRef
import chat.papo.mobile.chat.PickerCell
import chat.papo.mobile.chat.PickerSection
import chat.papo.mobile.chat.ServerEmoji
import chat.papo.mobile.chat.ServerEmojis
import chat.papo.mobile.chat.UnicodeEmojiCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmojiPickerModelTest {
    private val unicode = UnicodeEmojiCatalog.parse(
        listOf(
            "😀\t0\trosto risonho\tgrinning face\tfeliz\tgrinning",
            "👍\t1\tpolegar para cima\tthumbs up\tjoia\t+1|thumbsup",
            "🐶\t2\trosto de cachorro\tdog face\tcão|cachorro\tdog",
            "🇧🇷\t8\tbandeira: Brasil\tflag: Brazil\tpaís\tflag_br",
        ).joinToString("\n"),
    )
    private val parrot = ServerEmoji("id1", "party_parrot", "GIF")
    private val ok = ServerEmoji("id2", "ok_hand", "PNG")
    private val server = ServerEmojis(list = listOf(ok, parrot), complete = true)

    private fun sections(cells: List<PickerCell>) = cells.filterIsInstance<PickerCell.Header>().map { it.section }

    @Test
    fun semBuscaMostraRecentesServidorEOsGruposQueTemEmoji() {
        val cells = EmojiPickerModel.cells("", unicode, server, listOf(EmojiRef.Unicode("👍")))
        assertEquals(
            listOf(PickerSection.Recent, PickerSection.Server, PickerSection.Smileys, PickerSection.People, PickerSection.Animals, PickerSection.Flags),
            sections(cells),
        )
    }

    @Test
    fun semRecentesNemServidorNaoHaCabecalhoVazio() {
        // Servidor sem emoji nenhum: a leitura terminou e a lista veio vazia.
        val cells = EmojiPickerModel.cells("", unicode, ServerEmojis(complete = true), emptyList())
        assertEquals(listOf(PickerSection.Smileys, PickerSection.People, PickerSection.Animals, PickerSection.Flags), sections(cells))
    }

    @Test
    fun recenteQueNaoExisteMaisFicaDeFora() {
        val cells = EmojiPickerModel.cells(
            "",
            unicode,
            server,
            listOf(EmojiRef.Custom("apagado"), EmojiRef.Unicode("🦄"), EmojiRef.Custom("id1"), EmojiRef.Unicode("👍")),
        )
        val recent = cells.filter { (it as? PickerCell.Unicode)?.section == PickerSection.Recent || (it as? PickerCell.Server)?.section == PickerSection.Recent }
        assertEquals(listOf("s:Recent:id1", "u:Recent:👍"), recent.map { it.key })
    }

    @Test
    fun chavesNuncaSeRepetem() {
        // O mesmo emoji aparece em Recentes e no grupo; a grade exige chave única.
        val cells = EmojiPickerModel.cells("", unicode, server, listOf(EmojiRef.Unicode("👍"), EmojiRef.Custom("id1")))
        assertEquals(cells.size, cells.map { it.key }.toSet().size)
    }

    @Test
    fun buscaAchaDoServidorEUnicodeSemAcento() {
        val cells = EmojiPickerModel.cells("PARROT", unicode, server, emptyList())
        assertEquals(listOf(PickerSection.ServerResults), sections(cells))
        assertEquals(listOf("s:ServerResults:id1"), cells.drop(1).map { it.key })

        val polegar = EmojiPickerModel.cells("polegar", unicode, server, emptyList())
        assertEquals(listOf(PickerSection.UnicodeResults), sections(polegar))
        assertEquals("u:UnicodeResults:👍", polegar[1].key)
    }

    @Test
    fun buscaDoServidorColocaQuemComecaComOTextoPrimeiro() {
        val list = listOf(ServerEmoji("a", "my_ok", null), ServerEmoji("b", "ok_hand", null), ServerEmoji("c", "zok", null))
        val cells = EmojiPickerModel.cells(":ok:", unicode, ServerEmojis(list = list, complete = true), emptyList())
        assertEquals(listOf("b", "a", "c"), cells.filterIsInstance<PickerCell.Server>().map { it.emoji.id })
    }

    @Test
    fun buscaSemResultadoNaoDevolveNada() {
        assertTrue(EmojiPickerModel.cells("zzzzzz", unicode, server, emptyList()).isEmpty())
    }

    @Test
    fun buscaSoDeEspacosOuDoisPontosVoltaAoNavegar() {
        // Em branco é "sem busca": mostra tudo. Só dois-pontos não casa nada.
        assertTrue(EmojiPickerModel.cells("   ", unicode, server, emptyList()).isNotEmpty())
        assertTrue(EmojiPickerModel.cells("::", unicode, server, emptyList()).isEmpty())
    }

    @Test
    fun reacoesRapidasPoemOsRecentesNaFrenteEOsPadroesDepois() {
        val quick = EmojiPickerModel.quickReactions(listOf(EmojiRef.Unicode("🔥"), EmojiRef.Custom("id1")), server)
        assertEquals(6, quick.size)
        assertEquals(listOf(EmojiRef.Unicode("🔥"), EmojiRef.Custom("id1")), quick.take(2))
        assertEquals(EmojiRef.Unicode("👍"), quick[2])
    }

    @Test
    fun reacoesRapidasNaoRepetemNemTrazemEmojiDoServidorQueSumiu() {
        val quick = EmojiPickerModel.quickReactions(
            listOf(EmojiRef.Custom("apagado"), EmojiRef.Unicode("😂"), EmojiRef.Unicode("😂")),
            server,
        )
        assertEquals(6, quick.size)
        assertEquals(quick.size, quick.toSet().size)
        assertEquals(EmojiRef.Unicode("😂"), quick.first())
        assertTrue(EmojiRef.Custom("apagado") !in quick)
    }

    @Test
    fun reacoesRapidasSemRecentesSaoOsPadroes() {
        val quick = EmojiPickerModel.quickReactions(emptyList(), ServerEmojis())
        assertEquals(EmojiPickerModel.DEFAULT_REACTIONS.map { EmojiRef.Unicode(it) }, quick)
    }

    @Test
    fun reacoesRapidasRespeitamOLimite() {
        val many = (1..10).map { EmojiRef.Unicode("e$it") }
        assertEquals(many.take(6), EmojiPickerModel.quickReactions(many, ServerEmojis()))
    }

    @Test
    fun primeiraLeituraMostraLugaresReservadosEmVezDaListaParcial() {
        val growing = ServerEmojis(list = listOf(parrot), loading = true, complete = false)
        val cells = EmojiPickerModel.cells("", unicode, growing, emptyList())
        val serverCells = cells.dropWhile { it != PickerCell.Header(PickerSection.Server) }.drop(1)
            .takeWhile { it !is PickerCell.Header }
        assertEquals(EmojiPickerModel.SERVER_PLACEHOLDERS, serverCells.size)
        assertTrue(serverCells.all { it is PickerCell.Placeholder })
        assertEquals(cells.size, cells.map { it.key }.toSet().size)
        // E a busca também não usa a lista pela metade.
        assertTrue(EmojiPickerModel.cells("parrot", unicode, growing, emptyList()).isEmpty())
    }

    @Test
    fun releituraComListaGuardadaMostraAListaEnquantoCarrega() {
        val refreshing = ServerEmojis(list = listOf(parrot, ok), loading = true, complete = true)
        val cells = EmojiPickerModel.cells("", unicode, refreshing, emptyList())
        assertEquals(2, cells.count { it is PickerCell.Server })
        assertTrue(cells.none { it is PickerCell.Placeholder })
    }

    @Test
    fun falhaNaPrimeiraLeituraMostraOQueChegouSemLugaresReservados() {
        val failed = ServerEmojis(list = listOf(parrot), failed = true)
        val cells = EmojiPickerModel.cells("", unicode, failed, emptyList())
        assertEquals(1, cells.count { it is PickerCell.Server })
        assertTrue(cells.none { it is PickerCell.Placeholder })
    }
}
