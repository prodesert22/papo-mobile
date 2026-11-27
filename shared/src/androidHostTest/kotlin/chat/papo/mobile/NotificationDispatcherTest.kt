package chat.papo.mobile

import chat.papo.mobile.chat.Channel
import chat.papo.mobile.chat.ChannelType
import chat.papo.mobile.chat.ConversationDirectory
import chat.papo.mobile.chat.ConversationKind
import chat.papo.mobile.chat.ConversationNotification
import chat.papo.mobile.chat.Dm
import chat.papo.mobile.chat.InboxState
import chat.papo.mobile.chat.InboxView
import chat.papo.mobile.chat.Mute
import chat.papo.mobile.chat.NotificationDispatcher
import chat.papo.mobile.chat.NotificationItem
import chat.papo.mobile.chat.NotificationLevel
import chat.papo.mobile.chat.NotificationPrefs
import chat.papo.mobile.chat.NotificationTexts
import chat.papo.mobile.chat.ReadMark
import chat.papo.mobile.chat.SystemNotifier
import chat.papo.mobile.data.api.AttachmentDto
import chat.papo.mobile.data.api.ExternalAuthorDto
import chat.papo.mobile.data.db.account.AccountDb
import chat.papo.mobile.data.ws.WsEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

private const val ANA = "u-ana"

/**
 * A decisão de notificar o sistema, sobre uma caixa e um notificador falsos e o relógio virtual. Nada aqui usa a rede:
 * a caixa falsa diz quando uma leitura "começou" e "terminou", que é o que o despachante espera dela.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationDispatcherTest {
    private val me = "abcdef01-1111-4111-8111-111111111111"
    private val ana = ANA
    private val mention = "oi @mention(<@$me>) tudo bem?"
    private val window = NotificationDispatcher.ANNOUNCE_WINDOW + 1.milliseconds

    private sealed interface Call {
        data class Post(val notification: ConversationNotification) : Call
        data class Cancel(val accountId: String, val conversationId: String) : Call
    }

    /** O notificador de mentira: guarda o que foi pedido, na ordem. */
    private class FakeNotifier : SystemNotifier {
        var granted = true
        val calls = ArrayList<Call>()
        val posts: List<ConversationNotification> get() = calls.filterIsInstance<Call.Post>().map { it.notification }

        override fun isPermissionGranted(): Boolean = granted
        override suspend fun post(notification: ConversationNotification) {
            calls += Call.Post(notification)
        }

        override fun cancelConversation(accountId: String, conversationId: String) {
            calls += Call.Cancel(accountId, conversationId)
        }

        override fun cancelAccount(accountId: String) = Unit
        override fun removeAccount(accountId: String) = Unit
    }

    /** A caixa de mentira: as linhas e os contadores de leitura andam por quem chama. */
    private class FakeInbox : InboxView {
        val box = MutableStateFlow(InboxState(loaded = true))
        val started = MutableStateFlow(0L)
        val finished = MutableStateFlow(0L)
        override val state: StateFlow<InboxState> = box
        override val readsStarted: StateFlow<Long> = started
        override val readsFinished: StateFlow<Long> = finished

        /** Uma leitura inteira que traz [rows] (as já lidas ficam como estão). */
        fun read(vararg rows: NotificationItem) {
            started.value += 1
            box.value = box.value.copy(items = rows.toList())
            finished.value += 1
        }
    }

    private class Directory : ConversationDirectory {
        val channels = HashMap<String, Channel>()
        val dms = HashMap<String, Dm>()
        val names = HashMap<String, String>()

        /** O canal que faz a consulta falhar, como uma plataforma ou um conteúdo que dá erro. */
        var failOn: String? = null
        override fun channel(id: String): Channel? {
            check(id != failOn) { "falha de mentira" }
            return channels[id]
        }
        override fun dm(id: String): Dm? = dms[id]
        override fun userName(id: String): String? = names[id]
    }

    private class Fixture(scope: TestScope, db: AccountDb, val accountId: String, val me: String, val notifier: FakeNotifier) {
        val events = MutableSharedFlow<WsEvent>(extraBufferCapacity = 64)
        val marks = MutableSharedFlow<ReadMark>(extraBufferCapacity = 64)
        val inbox = FakeInbox()
        val directory = Directory()
        val viewing = MutableStateFlow<String?>(null)
        var unread = HashMap<String, Int>()
        var label = "Servidor · @eu"
        val prefs = NotificationPrefs(db.metaQueries, scope.backgroundScope, StandardTestDispatcher(scope.testScheduler)) {
            Instant.fromEpochMilliseconds(1_700_000_000_000) + scope.testScheduler.currentTime.milliseconds
        }
        val dispatcher = NotificationDispatcher(
            accountId = accountId,
            userId = me,
            accountLabel = { label },
            events = events,
            readMarks = marks,
            inbox = inbox,
            prefs = prefs,
            directory = directory,
            viewing = viewing,
            mentionCount = { unread[it] ?: 0 },
            notifier = notifier,
            texts = NotificationTexts(attachment = "Anexo", unknownUser = "usuário", emptyMessage = "Nova mensagem"),
            scope = scope.backgroundScope,
            timeSource = scope.testScheduler.timeSource,
        )

        init {
            directory.channels["c1"] = Channel("c1", "geral", ChannelType.Text, 0, null, null, null, null, null, null, null, null)
            directory.dms["d1"] = Dm("d1", ANA, null, null, null, 0)
            directory.names[ANA] = "Ana"
        }

        fun send(event: WsEvent) {
            events.tryEmit(event)
        }
    }

    private fun TestScope.fixture(accountId: String = "conta-a", notifier: FakeNotifier = FakeNotifier(), me: String = this@NotificationDispatcherTest.me): Fixture {
        val f = Fixture(this, AccountDb(TestResources.driver()), accountId, me, notifier)
        f.dispatcher.start()
        runCurrent()
        return f
    }

    private fun message(
        id: String,
        channel: String = "c1",
        author: String? = ana,
        content: String? = "oi",
        attachments: List<AttachmentDto> = emptyList(),
        at: String = "2026-01-01T10:00:00Z",
        external: ExternalAuthorDto? = null,
    ) = WsEvent.MessageCreated(id, channel, author, null, external, content, null, attachments, at)

    private fun announce(messageId: String) = WsEvent.NewNotification("n-$messageId", ana, messageId, "oi")

    private fun row(messageId: String, channel: String = "c1", content: String = "oi", read: Boolean = false) = NotificationItem(
        "n-$messageId", messageId, channel, ana, null, null, content, read, "2026-01-01T10:00:01Z",
    )

    // Menção

    @Test
    fun mencaoNoTextoNotificaNaHoraSemEsperar() = runTest {
        val f = fixture()

        f.send(message("m1", content = mention))
        runCurrent()

        val post = f.notifier.posts.single()
        assertEquals("conta-a", post.accountId)
        assertEquals("c1", post.conversationId)
        assertEquals(ConversationKind.Channel, post.kind)
        assertEquals("geral", post.title)
        assertEquals("Servidor · @eu", post.accountLabel)
        assertEquals(1, post.mentionCount)
        assertEquals("Ana", post.messages.single().author)
        assertEquals("oi @usuário tudo bem?", post.messages.single().text)
        assertEquals(false, post.target.approximate)
        assertEquals("m1", post.target.messageId)
    }

    @Test
    fun mencaoNotificaComOCanalSilenciadoEComONivelDoServidorEmSoMencoes() = runTest {
        val f = fixture()
        f.prefs.updateChannel("c1") { it.copy(mute = Mute(null)) }
        f.prefs.updateServer { it.copy(mute = Mute(null)) }

        f.send(message("m1", content = mention))
        runCurrent()

        assertEquals(1, f.notifier.posts.size)
    }

    @Test
    fun mencaoComONivelNadaNaoNotificaENemEspera() = runTest {
        val f = fixture()
        f.prefs.updateServer { it.copy(sync = false, level = NotificationLevel.Nothing) }

        f.send(message("m1", content = mention))
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()

        assertTrue(f.notifier.calls.isEmpty())
    }

    @Test
    fun mencaoSuprimidaNaoNotifica() = runTest {
        val f = fixture()
        f.prefs.updateServer { it.copy(suppressMentions = true) }

        f.send(message("m1", content = mention))
        runCurrent()

        assertTrue(f.notifier.posts.isEmpty())
    }

    @Test
    fun mencaoAOutraPessoaNaoEMencaoMinha() = runTest {
        val f = fixture()

        f.send(message("m1", content = "oi @mention(<@12345678-1111-4111-8111-111111111111>)"))
        runCurrent()
        advanceTimeBy(window)
        runCurrent()

        assertTrue(f.notifier.posts.isEmpty())
    }

    // Resposta e @everyone, pelo que o servidor diz na linha da caixa

    @Test
    fun respostaPelaLinhaDaCaixaNotificaEGuardaOInstanteDaLinhaParaAbrirAMensagem() = runTest {
        val f = fixture()

        f.send(message("m1", content = "concordo"))
        f.send(announce("m1"))
        runCurrent()
        assertTrue(f.notifier.posts.isEmpty(), "ainda espera a leitura da caixa")
        f.inbox.read(row("m1", content = "concordo"))
        runCurrent()

        val post = f.notifier.posts.single()
        assertEquals(1, post.mentionCount)
        assertEquals(true, post.target.approximate)
        assertEquals("2026-01-01T10:00:01Z", post.target.createdAt)
    }

    @Test
    fun everyoneNotificaMasSuprimidoNao() = runTest {
        val f = fixture()
        f.send(message("m1", content = "aviso @everyone"))
        f.send(announce("m1"))
        runCurrent()
        f.inbox.read(row("m1", content = "aviso @everyone"))
        runCurrent()
        assertEquals(1, f.notifier.posts.size)

        f.prefs.updateServer { it.copy(suppressEveryone = true) }
        f.send(message("m2", content = "aviso @everyone"))
        f.send(announce("m2"))
        runCurrent()
        f.inbox.read(row("m2", content = "aviso @everyone"), row("m1", content = "aviso @everyone"))
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(1, f.notifier.posts.size, "o segundo @everyone foi suprimido")
    }

    @Test
    fun linhaJaPresenteNaCaixaDecideSemEsperar() = runTest {
        val f = fixture()
        f.inbox.read(row("m1", content = "concordo"))

        f.send(message("m1", content = "concordo"))
        runCurrent()

        assertEquals(1, f.notifier.posts.size)
    }

    // Mensagem comum

    @Test
    fun mensagemComumNaoNotificaComSoMencoes() = runTest {
        val f = fixture()

        f.send(message("m1"))
        runCurrent()
        advanceTimeBy(window)
        runCurrent()

        assertTrue(f.notifier.posts.isEmpty())
    }

    @Test
    fun mensagemComumNotificaComNivelTodasSoDoAparelhoDepoisDaJanela() = runTest {
        val f = fixture()
        f.prefs.updateChannel("c1") { it.copy(sync = false, level = NotificationLevel.All) }

        f.send(message("m1"))
        runCurrent()
        advanceTimeBy(NotificationDispatcher.ANNOUNCE_WINDOW - 100.milliseconds)
        runCurrent()
        assertTrue(f.notifier.posts.isEmpty(), "dentro da janela ainda espera o new_notification")
        advanceTimeBy(200.milliseconds)
        runCurrent()

        val post = f.notifier.posts.single()
        assertEquals(0, post.mentionCount, "mensagem comum não conta como menção")
        assertEquals(false, post.target.approximate)
    }

    @Test
    fun mensagemComumComCanalSilenciadoNaoNotificaMesmoComTodas() = runTest {
        val f = fixture()
        f.prefs.updateChannel("c1") { it.copy(sync = false, level = NotificationLevel.All, mute = Mute(null)) }

        f.send(message("m1"))
        runCurrent()
        advanceTimeBy(window)
        runCurrent()

        assertTrue(f.notifier.posts.isEmpty())
    }

    @Test
    fun newNotificationEfemeroSemLinhaViraMensagemComum() = runTest {
        val f = fixture()
        f.prefs.updateChannel("c1") { it.copy(sync = false, level = NotificationLevel.All) }

        f.send(message("m1"))
        f.send(announce("m1"))
        runCurrent()
        f.inbox.read() // a leitura que veio depois do evento não tem a linha: o id era efêmero
        runCurrent()

        val post = f.notifier.posts.single()
        assertEquals(0, post.mentionCount)
    }

    @Test
    fun newNotificationEfemeroSemLinhaNaoNotificaComSoMencoes() = runTest {
        val f = fixture()

        f.send(message("m1"))
        f.send(announce("m1"))
        runCurrent()
        f.inbox.read()
        runCurrent()
        advanceTimeBy(window)
        runCurrent()

        assertTrue(f.notifier.posts.isEmpty())
    }

    @Test
    fun leituraDaCaixaQueNuncaTerminaTemLimiteEAMensagemViraComum() = runTest {
        val f = fixture()
        f.prefs.updateChannel("c1") { it.copy(sync = false, level = NotificationLevel.All) }

        f.send(message("m1"))
        f.send(announce("m1"))
        runCurrent()
        advanceTimeBy(NotificationDispatcher.INBOX_READ_WAIT - 1.milliseconds)
        runCurrent()
        assertTrue(f.notifier.posts.isEmpty())
        advanceTimeBy(2.milliseconds)
        runCurrent()

        assertEquals(1, f.notifier.posts.size)
    }

    @Test
    fun leituraQueJaEstavaEmAndamentoNaoServeParaOEvento() = runTest {
        val f = fixture()
        f.prefs.updateChannel("c1") { it.copy(sync = false, level = NotificationLevel.All) }
        f.inbox.started.value = 1 // uma leitura começou antes do evento

        f.send(message("m1", content = "concordo"))
        f.send(announce("m1"))
        runCurrent()
        f.inbox.finished.value = 1 // ela termina sem a linha, que o servidor criou depois de a pedir
        runCurrent()
        assertTrue(f.notifier.posts.isEmpty(), "espera a leitura seguinte")
        f.inbox.read(row("m1", content = "concordo")) // começa a 2, termina a 2
        runCurrent()

        assertEquals(1, f.notifier.posts.single().mentionCount)
    }

    // Quem não notifica

    @Test
    fun minhaMensagemNaoNotifica() = runTest {
        val f = fixture()

        f.send(message("m1", author = me, content = mention))
        runCurrent()
        advanceTimeBy(window)
        runCurrent()

        assertTrue(f.notifier.calls.isEmpty())
    }

    @Test
    fun conversaAbertaNaoNotifica() = runTest {
        val f = fixture()
        f.viewing.value = "c1"
        runCurrent()
        f.notifier.calls.clear()

        f.send(message("m1", content = mention))
        runCurrent()

        assertTrue(f.notifier.posts.isEmpty())
    }

    @Test
    fun conversaAbertaDuranteAEsperaCancelaANotificacao() = runTest {
        val f = fixture()
        f.send(message("m1", content = "concordo"))
        f.send(announce("m1"))
        runCurrent()

        f.viewing.value = "c1"
        runCurrent()
        f.inbox.read(row("m1", content = "concordo"))
        runCurrent()

        assertTrue(f.notifier.posts.isEmpty())
    }

    @Test
    fun canalQueAContaNaoLeNaoNotifica() = runTest {
        val f = fixture()
        f.directory.channels.remove("c1")

        f.send(message("m1", content = mention))
        runCurrent()

        assertTrue(f.notifier.posts.isEmpty())
    }

    @Test
    fun semPermissaoDoSistemaNaoNotificaENemEspera() = runTest {
        val f = fixture()
        f.notifier.granted = false

        f.send(message("m1", content = mention))
        runCurrent()

        assertTrue(f.notifier.posts.isEmpty())
    }

    @Test
    fun mensagemApagadaDuranteAEsperaNaoNotifica() = runTest {
        val f = fixture()
        f.send(message("m1", content = "concordo"))
        f.send(announce("m1"))
        runCurrent()

        f.send(WsEvent.MessageDeleted("m1", "c1"))
        runCurrent()
        f.inbox.read(row("m1", content = "concordo"))
        runCurrent()

        assertTrue(f.notifier.posts.isEmpty())
    }

    // Conversa direta

    @Test
    fun mensagemDiretaNotificaNaHoraComONomeDaPessoaNoTitulo() = runTest {
        val f = fixture()
        f.unread["d1"] = 2

        f.send(message("m1", channel = "d1"))
        runCurrent()

        val post = f.notifier.posts.single()
        assertEquals(ConversationKind.Dm, post.kind)
        assertEquals("Ana", post.title)
        assertEquals(2, post.mentionCount)
    }

    @Test
    fun conversaDiretaSilenciadaNaoNotifica() = runTest {
        val f = fixture()
        f.prefs.updateDm("d1") { it.copy(mute = Mute(null)) }

        f.send(message("m1", channel = "d1"))
        runCurrent()

        assertTrue(f.notifier.posts.isEmpty())
    }

    @Test
    fun silenciarOServidorNaoCalaAConversaDireta() = runTest {
        val f = fixture()
        f.prefs.updateServer { it.copy(sync = false, mute = Mute(null), level = NotificationLevel.Nothing) }

        f.send(message("m1", channel = "d1"))
        runCurrent()

        assertEquals(1, f.notifier.posts.size)
    }

    @Test
    fun conversaDiretaAbertaNaoNotifica() = runTest {
        val f = fixture()
        f.viewing.value = "d1"
        runCurrent()

        f.send(message("m1", channel = "d1"))
        runCurrent()

        assertTrue(f.notifier.posts.isEmpty())
    }

    @Test
    fun conversaDiretaQueSoApareceDepoisDaMensagemNotifica() = runTest {
        // Uma conversa direta fechada volta com o `dm_update`, que o servidor manda depois do `message`.
        val f = fixture()

        f.send(message("m1", channel = "d3"))
        runCurrent()
        assertTrue(f.notifier.posts.isEmpty())

        advanceTimeBy(300)
        f.directory.dms["d3"] = Dm("d3", ANA, null, null, null, 0)
        advanceTimeBy(200)
        runCurrent()

        assertEquals(ConversationKind.Dm, f.notifier.posts.single().kind)
    }

    @Test
    fun conversaQueNuncaApareceNaoNotifica() = runTest {
        val f = fixture()

        f.send(message("m1", channel = "x9"))
        advanceTimeBy(5_000)
        runCurrent()

        assertTrue(f.notifier.posts.isEmpty())
    }

    @Test
    fun conversaDiretaDeUsuarioDesconhecidoUsaONomeGenerico() = runTest {
        val f = fixture()
        f.directory.dms["d2"] = Dm("d2", "u-novo", null, null, null, 0)

        f.send(message("m1", channel = "d2", author = "u-novo"))
        runCurrent()

        assertEquals("usuário", f.notifier.posts.single().title)
    }

    // Texto

    @Test
    fun mensagemSoComAnexoViraAnexoENuncaMostraONomeDoArquivo() = runTest {
        val f = fixture()
        val sensivel = AttachmentDto(id = "a1", fileName = "segredo.png", mimeType = "image/png", moderationStatus = "sensitive")

        f.send(message("m1", channel = "d1", content = null, attachments = listOf(sensivel)))
        runCurrent()

        assertEquals("Anexo", f.notifier.posts.single().messages.single().text)
    }

    @Test
    fun mencaoNoTextoViraONomeDeExibicaoEOMarkdownSai() = runTest {
        val f = fixture()
        f.directory.names[me] = "Eu Mesmo"

        f.send(message("m1", content = "**oi** @mention(<@$me>), veja [isto](https://exemplo.test)"))
        runCurrent()

        assertEquals("oi @Eu Mesmo, veja isto", f.notifier.posts.single().messages.single().text)
    }

    @Test
    fun autorDeWebhookUsaONomeExterno() = runTest {
        val f = fixture()

        f.send(message("m1", channel = "d1", author = null, external = ExternalAuthorDto(name = "Robô")))
        runCurrent()

        assertEquals("Robô", f.notifier.posts.single().messages.single().author)
    }

    @Test
    fun umaFalhaNaDecisaoNaoDerrubaAsMensagensSeguintes() = runTest {
        val f = fixture()
        f.directory.channels["c2"] = Channel("c2", "outro", ChannelType.Text, 0, null, null, null, null, null, null, null, null)
        f.directory.failOn = "c2"

        f.send(message("m1", channel = "c2", content = mention))
        runCurrent()
        f.send(message("m2", channel = "c1", content = mention))
        runCurrent()

        assertEquals(listOf("c1"), f.notifier.posts.map { it.conversationId })
    }

    @Test
    fun asLinhasSaemNaOrdemDeEnvioMesmoQuandoADecisaoFoiOutra() = runTest {
        val f = fixture()
        f.prefs.updateChannel("c1") { it.copy(sync = false, level = NotificationLevel.All) }

        // A primeira espera a janela da caixa; a segunda é uma menção no texto e decide na hora.
        f.send(message("m1", content = "primeira", at = "2026-01-01T10:00:00Z"))
        f.send(message("m2", content = mention, at = "2026-01-01T10:00:01Z"))
        runCurrent()
        advanceTimeBy(window)
        runCurrent()
        advanceTimeBy(NotificationDispatcher.MIN_UPDATE_INTERVAL)
        runCurrent()

        val last = f.notifier.posts.last()
        assertEquals(listOf("primeira", "oi @usuário tudo bem?"), last.messages.map { it.text })
        assertEquals("m2", last.target.messageId)
    }

    // Atualização: várias mensagens, no máximo uma troca por segundo

    @Test
    fun duasMensagensNoMesmoSegundoViramUmaTrocaSoComAsDuas() = runTest {
        val f = fixture()

        f.send(message("m1", channel = "d1", content = "um"))
        runCurrent()
        advanceTimeBy(300.milliseconds)
        f.send(message("m2", channel = "d1", content = "dois", at = "2026-01-01T10:00:02Z"))
        runCurrent()
        assertEquals(1, f.notifier.posts.size, "a troca espera o intervalo")
        advanceTimeBy(NotificationDispatcher.MIN_UPDATE_INTERVAL)
        runCurrent()

        assertEquals(2, f.notifier.posts.size)
        assertEquals(listOf("um", "dois"), f.notifier.posts.last().messages.map { it.text })
        assertEquals("m2", f.notifier.posts.last().target.messageId)
    }

    @Test
    fun aNotificacaoLevaSoAsUltimasMensagens() = runTest {
        val f = fixture()

        for (n in 1..7) {
            f.send(message("m$n", channel = "d1", content = "msg $n"))
            runCurrent()
            advanceTimeBy(NotificationDispatcher.MIN_UPDATE_INTERVAL)
            runCurrent()
        }

        assertEquals((3..7).map { "msg $it" }, f.notifier.posts.last().messages.map { it.text })
        assertEquals(7, f.notifier.posts.last().mentionCount)
    }

    @Test
    fun oRotuloDaContaVaiComACadaTroca() = runTest {
        val f = fixture()
        f.send(message("m1", channel = "d1"))
        runCurrent()

        f.label = "Outro nome · @eu"
        advanceTimeBy(NotificationDispatcher.MIN_UPDATE_INTERVAL)
        f.send(message("m2", channel = "d1"))
        runCurrent()

        assertEquals("Outro nome · @eu", f.notifier.posts.last().accountLabel)
    }

    @Test
    fun amesmaMensagemDuasVezesNaoRepeteALinha() = runTest {
        val f = fixture()

        f.send(message("m1", channel = "d1"))
        runCurrent()
        advanceTimeBy(NotificationDispatcher.MIN_UPDATE_INTERVAL)
        f.send(message("m1", channel = "d1"))
        runCurrent()
        advanceTimeBy(NotificationDispatcher.MIN_UPDATE_INTERVAL)
        runCurrent()

        assertEquals(1, f.notifier.posts.size)
        assertEquals(1, f.notifier.posts.single().messages.size)
    }

    // Tirar ou trocar a notificação

    @Test
    fun abrirAConversaTiraANotificacaoMesmoSemNadaGuardado() = runTest {
        val f = fixture()

        f.viewing.value = "c1"
        runCurrent()

        assertEquals(listOf<Call>(Call.Cancel("conta-a", "c1")), f.notifier.calls)
    }

    @Test
    fun leituraAteAUltimaMensagemTiraANotificacao() = runTest {
        val f = fixture()
        f.send(message("m1", channel = "d1"))
        runCurrent()

        f.marks.tryEmit(ReadMark("d1", "m1", "2026-01-01T10:00:00Z"))
        runCurrent()

        assertEquals(Call.Cancel("conta-a", "d1"), f.notifier.calls.last())
    }

    @Test
    fun leituraDeUmaMensagemMaisAntigaTrocaANotificacaoSoComAsNovas() = runTest {
        val f = fixture()
        f.send(message("m1", channel = "d1", content = "velha", at = "2026-01-01T10:00:00Z"))
        runCurrent()
        advanceTimeBy(NotificationDispatcher.MIN_UPDATE_INTERVAL)
        f.send(message("m2", channel = "d1", content = "nova", at = "2026-01-01T10:05:00Z"))
        runCurrent()
        advanceTimeBy(NotificationDispatcher.MIN_UPDATE_INTERVAL)
        runCurrent()

        f.marks.tryEmit(ReadMark("d1", "m1", "2026-01-01T10:00:00Z"))
        runCurrent()
        advanceTimeBy(NotificationDispatcher.MIN_UPDATE_INTERVAL)
        runCurrent()

        val last = f.notifier.posts.last()
        assertEquals(listOf("nova"), last.messages.map { it.text })
        assertFalse(f.notifier.calls.any { it is Call.Cancel })
    }

    @Test
    fun leituraDeCanalSemNadaGuardadoDestaSessaoTiraANotificacaoAntiga() = runTest {
        val f = fixture()

        f.marks.tryEmit(ReadMark("c1", "m9", null))
        runCurrent()

        assertEquals(listOf<Call>(Call.Cancel("conta-a", "c1")), f.notifier.calls)
    }

    @Test
    fun leituraSemInstanteSoCobreAPropriaMensagem() = runTest {
        val f = fixture()
        f.send(message("m1", channel = "d1", content = "um"))
        runCurrent()
        advanceTimeBy(NotificationDispatcher.MIN_UPDATE_INTERVAL)
        f.send(message("m2", channel = "d1", content = "dois", at = "2026-01-01T10:05:00Z"))
        runCurrent()
        advanceTimeBy(NotificationDispatcher.MIN_UPDATE_INTERVAL)
        runCurrent()

        f.marks.tryEmit(ReadMark("d1", "m2", null))
        runCurrent()
        advanceTimeBy(NotificationDispatcher.MIN_UPDATE_INTERVAL)
        runCurrent()

        assertEquals(listOf("um"), f.notifier.posts.last().messages.map { it.text })
    }

    @Test
    fun apagarAMensagemTiraANotificacaoQuandoEraAUnica() = runTest {
        val f = fixture()
        f.send(message("m1", channel = "d1"))
        runCurrent()

        f.send(WsEvent.MessageDeleted("m1", "d1"))
        runCurrent()

        assertEquals(Call.Cancel("conta-a", "d1"), f.notifier.calls.last())
    }

    @Test
    fun apagarUmaMensagemDeOutraConversaNaoMexe() = runTest {
        val f = fixture()
        f.send(message("m1", channel = "d1"))
        runCurrent()
        val before = f.notifier.calls.size

        f.send(WsEvent.MessageDeleted("m1", "c1"))
        f.send(WsEvent.MessageDeleted("m-outra", "d1"))
        runCurrent()
        advanceTimeBy(NotificationDispatcher.MIN_UPDATE_INTERVAL)
        runCurrent()

        assertEquals(before, f.notifier.calls.size)
    }

    @Test
    fun caixaMarcadaComoLidaTiraANotificacao() = runTest {
        val f = fixture()
        f.send(message("m1", content = "concordo"))
        f.send(announce("m1"))
        runCurrent()
        f.inbox.read(row("m1", content = "concordo"))
        runCurrent()
        assertEquals(1, f.notifier.posts.size)

        f.inbox.box.value = f.inbox.box.value.copy(items = listOf(row("m1", content = "concordo", read = true)))
        runCurrent()

        assertEquals(Call.Cancel("conta-a", "c1"), f.notifier.calls.last())
    }

    @Test
    fun caixaLidaDeOutraMensagemNaoMexeNaNotificacao() = runTest {
        val f = fixture()
        f.send(message("m1", channel = "d1"))
        runCurrent()
        val before = f.notifier.calls.size

        f.inbox.box.value = f.inbox.box.value.copy(items = listOf(row("m-outra", channel = "d1", read = true)))
        runCurrent()

        assertEquals(before, f.notifier.calls.size)
    }

    @Test
    fun cancelamentoDepoisDeUmaTrocaPendenteNaoDeixaANotificacaoVoltar() = runTest {
        val f = fixture()
        f.send(message("m1", channel = "d1"))
        runCurrent()
        f.send(message("m2", channel = "d1", at = "2026-01-01T10:01:00Z"))
        runCurrent() // a troca da segunda ficou para daqui a 1 s

        f.viewing.value = "d1"
        runCurrent()
        advanceTimeBy(NotificationDispatcher.MIN_UPDATE_INTERVAL * 3)
        runCurrent()

        assertEquals(1, f.notifier.posts.size)
        assertEquals(Call.Cancel("conta-a", "d1"), f.notifier.calls.last())
    }

    // Contas separadas

    @Test
    fun duasContasDoMesmoServidorFicamSeparadas() = runTest {
        val notifier = FakeNotifier()
        val a = fixture("conta-a", notifier)
        val b = fixture("conta-b", notifier, me = "fedcba98-2222-4222-8222-222222222222")

        // O mesmo id de canal nas duas: só a conta que recebeu o evento notifica.
        a.send(message("m1", content = mention))
        runCurrent()
        assertEquals(listOf("conta-a"), notifier.posts.map { it.accountId })

        // A menção a uma conta não é menção à outra.
        b.send(message("m2", content = mention))
        runCurrent()
        advanceTimeBy(window)
        runCurrent()
        assertEquals(listOf("conta-a"), notifier.posts.map { it.accountId })

        // Abrir o canal numa conta só tira a notificação dela.
        a.viewing.value = "c1"
        runCurrent()
        assertEquals(Call.Cancel("conta-a", "c1"), notifier.calls.last())
        assertTrue(notifier.calls.none { it is Call.Cancel && it.accountId == "conta-b" })
    }

    @Test
    fun comecarDuasVezesNaoDuplicaAsNotificacoes() = runTest {
        val f = fixture()
        f.dispatcher.start()
        runCurrent()

        f.send(message("m1", channel = "d1"))
        runCurrent()

        assertEquals(1, f.notifier.posts.size)
    }
}
