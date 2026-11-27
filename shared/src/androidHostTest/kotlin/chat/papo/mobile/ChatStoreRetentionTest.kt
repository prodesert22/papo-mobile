package chat.papo.mobile

import androidx.lifecycle.ViewModelStore
import app.cash.sqldelight.db.SqlDriver
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.data.api.MemoryCookieStore
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.data.db.account.AccountDb
import chat.papo.mobile.data.ws.WsEvent
import chat.papo.mobile.ui.chat.ChatViewModel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A retenção de mensagens vista pelo [chat.papo.mobile.chat.ChatStore]: quando a poda roda, o que o tempo real grava e
 * as telas vivas. As regras de cada passo da poda estão em [MessageRetentionTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatStoreRetentionTest {
    private var historyIds = listOf(1, 2, 3)
    private var historyHasMore = false

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        fun json(text: String, status: HttpStatusCode = HttpStatusCode.OK) =
            respond(text, status, headersOf(HttpHeaders.ContentType, "application/json"))
        fun message(channel: String, n: Int) =
            """{"id":"$channel-h$n","channel_id":"$channel","author_id":"u2","content":"hist $n","created_at":"2026-02-01T00:%02d:%02dZ"}""".format(n / 60, n % 60)
        when {
            path == "/channels" -> json(
                """{"channels":[
                    {"id":"c1","name":"um","type":"text","position":1,"last_message":{"id":"p1","content":"previa um","created_at":"2026-01-01T00:00:01Z"},"last_read_message":"p0"},
                    {"id":"c2","name":"dois","type":"text","position":2},
                    {"id":"c3","name":"tres","type":"text","position":3},
                    {"id":"c4","name":"quatro","type":"text","position":4}]}""",
            )
            path == "/dms" -> json("""{"dms":[{"id":"d1","user":{"id":"u2","username":"bia"},"created_at":"2026-01-01T00:00:00Z","unread_count":0}]}""")
            path == "/users" -> json("""{"users":[{"id":"u1","username":"ana"},{"id":"u2","username":"bia"}],"has_more":false}""")
            path.startsWith("/channels/") && path.endsWith("/messages") -> {
                val channel = path.removePrefix("/channels/").removeSuffix("/messages")
                json("""{"messages":[${historyIds.reversed().joinToString(",") { message(channel, it) }}],"has_more":$historyHasMore}""")
            }
            path == "/messages" -> json(
                """{"id":"enviada","channel_id":"c3","author_id":"u1","content":"oi","created_at":"2026-03-01T00:00:00Z"}""",
                HttpStatusCode.Created,
            )
            else -> respond("", HttpStatusCode.NoContent)
        }
    }

    private class Account(val session: AccountSession, val db: AccountDb, val driver: SqlDriver)

    private fun TestScope.account(): Account {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = PapoClient(engine, ServerUrl("https://papo.test"), MemoryCookieStore("jwt"))
        val resources = TestResources.inMemory(dispatcher)
        return Account(AccountSession("conta", "u1", client, backgroundScope, resources, dispatcher), AccountDb(resources.driver), resources.driver)
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun Account.seed(channel: String, count: Int, from: Int = 0) = repeat(count) {
        val n = from + it
        db.chatQueries.upsertMessage(
            id = "$channel-%03d".format(n), channel_id = channel, author_id = "u2", content = "texto $n", created_at = "2026-01-01T00:00:00Z",
            created_us = n.toLong(), edited_at = null, reply_to = null, webhook_id = null, external_author = null, attachments = "[]",
            embeds = "[]", reactions = "[]", user_reactions = "[]",
        )
    }

    private fun Account.stored(channel: String): List<String> = db.chatQueries.selectMessages(channel, 10_000).executeAsList().map { it.id }

    private fun created(id: String, channel: String, us: Int) =
        WsEvent.MessageCreated(id, channel, "u2", content = "nova $id", createdAt = "2026-06-01T00:%02d:%02dZ".format(us / 60, us % 60))

    /** O usuário abriu e fechou a conversa: ela é a mais recente, e sem tela. */
    private fun TestScope.visit(account: Account, channel: String) {
        account.session.chat.conversationOpened(channel)
        account.session.chat.conversationClosed(channel)
        runCurrent()
    }

    // Tempo real

    @Test
    fun mensagemDeConversaForaDasRecentesNaoEGuardadaMasAPreviaEONaoLidoAtualizam() = runTest {
        val account = account()
        val chat = account.session.chat
        chat.sync()
        runCurrent()

        chat.handle(created("n1", "c1", 5))
        chat.handle(created("n2", "d1", 6))
        runCurrent()

        assertTrue(account.stored("c1").isEmpty())
        assertTrue(account.stored("d1").isEmpty())
        assertEquals("n1", chat.channels.value.first { it.id == "c1" }.lastMessageId)
        assertEquals("nova n1", chat.channels.value.first { it.id == "c1" }.lastMessageText)
        assertEquals(1, chat.dms.value.single().unreadCount)
    }

    @Test
    fun conversaRecenteSemTelaGuardaAMensagemEFicaEmCentoEVinte() = runTest {
        val account = account()
        val chat = account.session.chat
        visit(account, "c1")
        account.seed("c1", 120)

        chat.handle(created("n1", "c1", 5))

        val ids = account.stored("c1")
        assertEquals(120, ids.size)
        assertEquals("n1", ids.first()) // a nova entrou...
        assertFalse("c1-000" in ids) // ...e a mais antiga saiu
    }

    @Test
    fun cortarPeloTempoRealFazAConversaVoltarAPedirOHistorico() = runTest {
        val account = account()
        val chat = account.session.chat
        historyIds = listOf(1)
        historyHasMore = false
        chat.conversationOpened("c1")
        runCurrent()
        chat.refreshLatest("c1") // um trecho que acaba no começo do histórico: hasOlder passa a ser falso
        assertFalse(chat.hasOlder("c1"))
        chat.conversationClosed("c1")
        runCurrent()
        account.seed("c1", 120, from = 0)

        chat.handle(created("n1", "c1", 5)) // 121 guardadas: corta

        assertTrue(chat.hasOlder("c1"))
    }

    @Test
    fun conversaComTelaNaoECortadaPeloTempoReal() = runTest {
        val account = account()
        val chat = account.session.chat
        chat.conversationOpened("c1")
        runCurrent()
        account.seed("c1", 120)

        chat.handle(created("n1", "c1", 5))

        assertEquals(121, account.stored("c1").size)
    }

    @Test
    fun conversaAbertaForaDasRecentesGuardaAMensagemDoTempoReal() = runTest {
        val account = account()
        val chat = account.session.chat
        listOf("c1", "c2", "c3", "c4").forEach(chat::conversationOpened) // c1 sai das 3 mais recentes, mas a tela segue viva
        runCurrent()

        chat.handle(created("n1", "c1", 5))

        assertEquals(listOf("n1"), account.stored("c1"))
    }

    @Test
    fun mensagemRepetidaDoTempoRealNaoDuplicaNemCortaDemais() = runTest {
        val account = account()
        val chat = account.session.chat
        visit(account, "c1")
        account.seed("c1", 119)

        chat.handle(created("n1", "c1", 5))
        chat.handle(created("n1", "c1", 5))

        assertEquals(120, account.stored("c1").size)
    }

    // Abrir e fechar

    @Test
    fun abrirAQuartaConversaApagaSoAsMensagensDaMaisAntigaENaoAsLinhasDela() = runTest {
        val account = account()
        val chat = account.session.chat
        chat.sync()
        listOf("c1", "c2", "c3").forEach { visit(account, it) }
        listOf("c1", "c2", "c3").forEach { account.seed(it, 3) }
        val previewBefore = chat.channels.value.first { it.id == "c1" }

        chat.conversationOpened("c4")
        runCurrent()

        assertTrue(account.stored("c1").isEmpty())
        assertEquals(3, account.stored("c2").size)
        assertEquals(3, account.stored("c3").size)
        assertEquals(previewBefore, chat.channels.value.first { it.id == "c1" }) // prévia, não lido e leitura continuam
        assertEquals("previa um", chat.channels.value.first { it.id == "c1" }.lastMessageText)
    }

    @Test
    fun fecharATelaDeUmaConversaQueJaNaoEraRecentePodaEla() = runTest {
        val account = account()
        val chat = account.session.chat
        listOf("c1", "c2", "c3", "c4").forEach(chat::conversationOpened)
        runCurrent()
        account.seed("c1", 5)
        account.seed("c2", 5)

        chat.conversationClosed("c2") // recente: só corta se passar de 120
        runCurrent()
        assertEquals(5, account.stored("c2").size)
        assertEquals(5, account.stored("c1").size) // com tela

        chat.conversationClosed("c1") // não é recente: sai tudo
        runCurrent()
        assertTrue(account.stored("c1").isEmpty())
    }

    @Test
    fun fecharUmaDasDuasTelasDaMesmaConversaNaoAPoda() = runTest {
        val account = account()
        val chat = account.session.chat
        chat.conversationOpened("c1")
        chat.conversationOpened("c1")
        listOf("c2", "c3", "c4").forEach(chat::conversationOpened)
        runCurrent()
        account.seed("c1", 5)

        chat.conversationClosed("c1")
        runCurrent()
        assertEquals(5, account.stored("c1").size)

        chat.conversationClosed("c1")
        runCurrent()
        assertTrue(account.stored("c1").isEmpty())
    }

    @Test
    fun conversaAbertaNaoSofreNadaMesmoQuandoOutraTelaFechaOuAbre() = runTest {
        val account = account()
        val chat = account.session.chat
        chat.conversationOpened("c1")
        runCurrent()
        account.seed("c1", 200)

        listOf("c2", "c3", "c4").forEach { visit(account, it) } // c1 sai das recentes
        runCurrent()

        assertEquals(200, account.stored("c1").size)
    }

    @Test
    fun podaDeUmaConversaFazOHasOlderDelaDeixarDeDizerQueOComecoFoiAlcancado() = runTest {
        val account = account()
        val chat = account.session.chat
        historyIds = listOf(1, 2)
        historyHasMore = false
        chat.conversationOpened("c1")
        runCurrent()
        chat.refreshLatest("c1")
        assertFalse(chat.hasOlder("c1"))

        chat.conversationClosed("c1") // c1 é recente: sem passar de 120, nada muda
        runCurrent()
        assertFalse(chat.hasOlder("c1"))

        listOf("c2", "c3", "c4").forEach { visit(account, it) } // c1 sai das recentes e perde tudo
        assertTrue(chat.hasOlder("c1"))
        assertTrue(account.stored("c1").isEmpty())
    }

    // Abertura da conta

    @Test
    fun iniciarASessaoPodaOQueUmaVersaoAntigaOuUmProcessoMortoDeixou() = runTest {
        val account = account()
        val chat = account.session.chat
        visit(account, "c1")
        account.seed("c1", 130)
        account.seed("antiga", 10)
        account.db.metaQueries.put("detached:antiga", "1")

        chat.start()
        runCurrent()

        assertEquals(120, account.stored("c1").size)
        assertTrue(account.stored("antiga").isEmpty())
        assertNull(account.db.metaQueries.get("detached:antiga").executeAsOneOrNull())
    }

    @Test
    fun falhaNaPodaNaoDerrubaOAppENemImpedeAbrirAConversa() = runTest {
        val account = account()
        val chat = account.session.chat
        runCurrent() // o resto da sessão termina de ler o banco antes de ele fechar
        account.driver.close() // toda poda falha

        chat.conversationOpened("c1")
        chat.conversationClosed("c1")
        runCurrent()

        // A falha fica em segundo plano: nenhuma exceção escapa do escopo da sessão, que segue de pé.
        assertTrue(account.session.scope.isActive)
    }

    // Telas vivas

    @Test
    fun conversasDeDuasTelasVivasSoSaemDoAparelhoQuandoAsTelasSaem() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val account = account()
        val owners = listOf("c1", "c2", "c3", "c4").associateWith { ViewModelStore() }
        val models = owners.mapValues { (channel, owner) -> ChatViewModel(account.session, channel).also { owner.put("chat-$channel", it) } }
        models.values.forEach { it.state.first { state -> state.loadedOnce } }
        runCurrent()

        // Quatro telas vivas: c1 não é mais das 3 recentes, e mesmo assim as quatro guardam o que leram.
        assertEquals(3, account.stored("c1").size)
        assertEquals(3, account.stored("c4").size)

        owners.getValue("c1").clear() // a tela de c1 sai
        runCurrent()
        assertTrue(account.stored("c1").isEmpty())
        assertEquals(3, account.stored("c2").size)
        assertEquals(3, account.stored("c3").size)
        assertEquals(3, account.stored("c4").size)

        owners.getValue("c4").clear() // c4 é recente: fica
        runCurrent()
        assertEquals(3, account.stored("c4").size)
        assertEquals(listOf("c4", "c3", "c2"), recents(account))
    }

    private fun recents(account: Account): List<String> =
        account.db.metaQueries.get("retention.recent").executeAsOne().split(",")

    // Fila de envio

    @Test
    fun enviarParaConversaForaDasRecentesNaoGuardaAMensagemMasAFilaTermina() = runTest {
        val account = account()
        val chat = account.session.chat
        chat.sync()
        runCurrent()

        chat.enqueue("c3", "oi", null, emptyList())
        chat.outbox.first { it.isEmpty() }
        runCurrent()

        assertTrue(account.stored("c3").isEmpty())
        assertEquals("enviada", chat.channels.value.first { it.id == "c3" }.lastMessageId)
    }

    @Test
    fun enviarParaConversaComTelaGuardaAMensagemEAFilaTermina() = runTest {
        val account = account()
        val chat = account.session.chat
        chat.sync()
        chat.conversationOpened("c3")
        runCurrent()

        chat.enqueue("c3", "oi", null, emptyList())
        chat.outbox.first { it.isEmpty() }

        assertEquals(listOf("enviada"), account.stored("c3"))
    }
}
