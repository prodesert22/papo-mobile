package chat.papo.mobile

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.chat.Draft
import chat.papo.mobile.chat.Drafts
import chat.papo.mobile.data.api.MemoryCookieStore
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.data.db.account.AccountDb
import chat.papo.mobile.data.db.account.MetaQueries
import chat.papo.mobile.ui.chat.ChatViewModel
import chat.papo.mobile.ui.chat.DraftEmojis
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Rascunho por conversa no [ChatViewModel], e a menção digitada à mão que vira menção no corpo enviado. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelDraftsTest {
    private val bia = "0b0e7c1e-1111-2222-3333-444455556666"
    private val joao = "0b0e7c1e-aaaa-bbbb-cccc-ddddeeeeffff"
    private val posted = mutableListOf<String>()
    private val edited = mutableListOf<String>()

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        val body = request.body.toByteArray().decodeToString()
        fun json(text: String, status: HttpStatusCode = HttpStatusCode.OK) =
            respond(text, status, headersOf(HttpHeaders.ContentType, "application/json"))
        fun message(n: Int, content: String = "msg $n", channel: String = "c1", author: String = "u2") =
            """{"id":"m$n","channel_id":"$channel","author_id":"$author","content":"$content","created_at":"2026-01-01T00:00:%02dZ"}""".format(n)
        when {
            path == "/channels" -> json(
                """{"channels":[{"id":"c1","name":"papo","type":"text","position":1},{"id":"c2","name":"outro","type":"text","position":2}]}""",
            )
            path == "/dms" -> json("""{"dms":[]}""")
            path == "/users" -> json(
                """{"users":[{"id":"u1","username":"ana"},{"id":"$bia","username":"bia"},
                    {"id":"$joao","username":"joao","nickname":"João Silva"}],"has_more":false}""",
            )
            path == "/channels/c1/messages" -> json("""{"messages":[${(3 downTo 1).joinToString(",") { message(it) }}],"has_more":false}""")
            path == "/messages" && request.method == HttpMethod.Post -> {
                posted += body
                json(message(50, content = "enviada", channel = body.field("channel_id") ?: "c1", author = "u1"), HttpStatusCode.Created)
            }
            path.startsWith("/messages/") && request.method == HttpMethod.Put -> {
                edited += "$path $body"
                json(message(2, content = "corrigida", author = "u1"))
            }
            else -> json("""{"messages":[],"has_more":false}""")
        }
    }

    private lateinit var meta: MetaQueries

    private fun TestScope.session(io: CoroutineDispatcher = StandardTestDispatcher(testScheduler)): AccountSession {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val resources = TestResources.inMemory(io)
        meta = AccountDb(resources.driver).metaQueries
        val client = PapoClient(engine, ServerUrl("https://papo.test"), MemoryCookieStore("jwt"))
        return AccountSession("conta", "u1", client, backgroundScope, resources, io)
    }

    private suspend fun ChatViewModel.loaded() {
        state.first { it.loadedOnce }
        messages.first { it.size == 3 }
    }

    /** O usuário escreve [text] no compositor. */
    private fun ChatViewModel.type(text: String) = onDraftChange(TextFieldValue(text, TextRange(text.length)))

    private fun stored(channelId: String): Draft? = Drafts.decode(meta.get(Drafts.key(channelId)).executeAsOneOrNull())

    private fun String.field(name: String): String? =
        Regex("""name="?$name"?[^\r\n]*\r\n(?:[^\r\n]+\r\n)*\r\n([^\r]*)""").find(this)?.groupValues?.get(1)

    private fun mention(id: String) = "@mention(<@$id>)"

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    // Menção digitada à mão

    @Test
    fun nomeDigitadoAMaoViraMencaoNoCorpoEnviado() = runTest {
        val session = session()
        session.chat.sync()
        runCurrent()
        val vm = ChatViewModel(session, "c1")
        vm.loaded()

        vm.type("oi @joao, e @JOAO. @bia! a@bia.com @fulano `@bia`")
        vm.submit()
        session.chat.outbox.first { it.isEmpty() }

        assertEquals(
            "oi ${mention(joao)}, e ${mention(joao)}. ${mention(bia)}! a@bia.com @fulano `@bia`",
            posted.single().field("content"),
        )
    }

    @Test
    fun nomeEscolhidoNoAutocompletarSegueValendo() = runTest {
        val session = session()
        val vm = ChatViewModel(session, "c1") // sem sincronizar: o banco não tem usuário nenhum
        vm.loaded()
        vm.pickMention("bia", bia)

        vm.type("oi @bia.")
        vm.submit()
        session.chat.outbox.first { it.isEmpty() }

        assertEquals("oi ${mention(bia)}.", posted.single().field("content"))
    }

    // Emoji do servidor no compositor: o campo mostra a imagem, o texto do rascunho segue sendo `:nome:`

    @Test
    fun emojiDoServidorSegueComoNomeNoRascunhoGuardadoENoCorpoEnviado() = runTest {
        val session = session()
        val vm = ChatViewModel(session, "c1")
        vm.loaded()
        val emojis = DraftEmojis(mapOf("party" to "e1", "ok" to "e2"))

        vm.onDraftChange(emojis.settle(vm.draft, TextFieldValue("oi :party::ok:", TextRange(14))))
        session.drafts.flush()
        runCurrent()
        assertEquals(Draft("oi :party::ok:"), stored("c1"))

        vm.submit()
        session.chat.outbox.first { it.isEmpty() }
        assertEquals("oi :party::ok:", posted.single().field("content"))
    }

    @Test
    fun apagarLogoDepoisDoEmojiTiraOEmojiInteiroDoCorpoEnviado() = runTest {
        val session = session()
        val vm = ChatViewModel(session, "c1")
        vm.loaded()
        val emojis = DraftEmojis(mapOf("party" to "e1", "ok" to "e2"))
        vm.onDraftChange(TextFieldValue("oi :party::ok:", TextRange(14)))

        // O teclado apagou o `:` do fim; o compositor entrega o texto acertado ao view model.
        vm.onDraftChange(emojis.settle(vm.draft, TextFieldValue("oi :party::ok", TextRange(13))))
        vm.submit()
        session.chat.outbox.first { it.isEmpty() }

        assertEquals("oi :party:", posted.single().field("content"))
    }

    // Rascunho

    @Test
    fun rascunhoEGuardadoDepoisDoAtrasoERestauradoComARespostaAoReabrir() = runTest {
        val session = session()
        val vm = ChatViewModel(session, "c1")
        vm.loaded()
        val target = vm.messages.first { it.size == 3 }.first { it.id == "m2" }
        vm.startReply(target)
        vm.type("meu rascunho")

        advanceTimeBy(Drafts.DEBOUNCE - 1.milliseconds)
        runCurrent()
        assertNull(stored("c1")) // ainda dentro do atraso
        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(Draft("meu rascunho", "m2"), stored("c1"))

        // A conversa é aberta de novo (outro view model, como depois de sair e voltar ou de matar o app).
        val reopened = ChatViewModel(session, "c1")
        runCurrent()
        assertEquals("meu rascunho", reopened.draft.text)
        assertEquals(TextRange("meu rascunho".length), reopened.draft.selection)
        assertEquals("m2", reopened.state.value.replyTo?.id)
    }

    @Test
    fun flushGravaOQueAindaEstavaNoAtrasoAntesDeSairDaTela() = runTest {
        val session = session()
        val vm = ChatViewModel(session, "c1")
        vm.type("quase perdi")
        session.drafts.flush()
        runCurrent()

        assertEquals(Draft("quase perdi"), stored("c1"))
        val reopened = ChatViewModel(session, "c1")
        runCurrent()
        assertEquals("quase perdi", reopened.draft.text)
    }

    @Test
    fun cadaConversaGuardaOSeuRascunho() = runTest {
        val session = session()
        val first = ChatViewModel(session, "c1")
        val second = ChatViewModel(session, "c2")
        first.type("do c1")
        second.type("do c2")
        session.drafts.flush()
        runCurrent()

        assertEquals(Draft("do c1"), stored("c1"))
        assertEquals(Draft("do c2"), stored("c2"))
        val reopened = ChatViewModel(session, "c2")
        runCurrent()
        assertEquals("do c2", reopened.draft.text)
        assertEquals("", ChatViewModel(session, "outra").also { runCurrent() }.draft.text)
    }

    @Test
    fun respostaApagadaESoTextoMalformadoEIgnorados() = runTest {
        val session = session()
        meta.put(Drafts.key("c1"), Drafts.encode(Draft("texto que fica", "mensagem-que-sumiu")))
        meta.put(Drafts.key("c2"), "isto não é um rascunho")

        val vm = ChatViewModel(session, "c1")
        val broken = ChatViewModel(session, "c2")
        runCurrent()

        assertEquals("texto que fica", vm.draft.text)
        assertNull(vm.state.value.replyTo) // a mensagem não está mais guardada: a resposta é descartada
        assertEquals("", broken.draft.text)
        assertNull(broken.state.value.error)
    }

    @Test
    fun respostaDeOutroCanalNaoEMostrada() = runTest {
        val session = session()
        val vm = ChatViewModel(session, "c1")
        vm.loaded()
        // O rascunho de c2 aponta para m2, que é de c1.
        meta.put(Drafts.key("c2"), Drafts.encode(Draft("de c2", "m2")))
        val other = ChatViewModel(session, "c2")
        runCurrent()
        assertEquals("de c2", other.draft.text)
        assertNull(other.state.value.replyTo)
    }

    @Test
    fun restaurarNaoPisaNoQueOUsuarioJaEscreveuEnquantoOBancoRespondia() = runTest {
        val gate = GateDispatcher(StandardTestDispatcher(testScheduler))
        val session = session(gate)
        val first = ChatViewModel(session, "c1")
        first.loaded()
        val m3 = first.messages.first { it.size == 3 }.first { it.id == "m3" }
        meta.put(Drafts.key("c1"), Drafts.encode(Draft("velho", "m1")))

        gate.closed = true // o banco ainda não respondeu
        val vm = ChatViewModel(session, "c1")
        runCurrent()
        vm.type("novo")
        vm.startReply(m3)
        gate.open()
        runCurrent()

        assertEquals("novo", vm.draft.text)
        assertEquals("m3", vm.state.value.replyTo?.id)
    }

    @Test
    fun textoApagadoAntesDoBancoResponderTambemNaoVoltaDoRascunho() = runTest {
        val gate = GateDispatcher(StandardTestDispatcher(testScheduler))
        val session = session(gate)
        meta.put(Drafts.key("c1"), Drafts.encode(Draft("velho")))

        gate.closed = true
        val vm = ChatViewModel(session, "c1")
        runCurrent()
        vm.type("a")
        vm.type("") // digitou e apagou tudo
        gate.open()
        runCurrent()

        assertEquals("", vm.draft.text)
    }

    // Edição

    @Test
    fun editarSuspendeAGravacaoECancelarOuTerminarVoltaAoRascunho() = runTest {
        val session = session()
        val vm = ChatViewModel(session, "c1")
        vm.loaded()
        val messages = vm.messages.first { it.size == 3 }
        val reply = messages.first { it.id == "m3" }
        val mine = messages.first { it.id == "m2" }
        vm.startReply(reply)
        vm.type("rascunho de antes")
        advanceTimeBy(Drafts.DEBOUNCE)
        runCurrent()
        assertEquals(Draft("rascunho de antes", "m3"), stored("c1"))

        vm.startEdit(mine)
        assertEquals("msg 2", vm.draft.text)
        assertNull(vm.state.value.replyTo)
        vm.type("texto da edição")
        advanceTimeBy(Drafts.DEBOUNCE * 2)
        runCurrent()
        assertEquals(Draft("rascunho de antes", "m3"), stored("c1")) // o texto da edição nunca vira rascunho

        vm.cancelContext()
        assertNull(vm.state.value.editing)
        assertEquals("rascunho de antes", vm.draft.text)
        assertEquals("m3", vm.state.value.replyTo?.id)

        // De novo, agora salvando a edição.
        vm.startEdit(mine)
        vm.type("corrigida")
        vm.submit()
        vm.state.first { !it.savingEdit && it.editing == null }
        assertEquals(listOf("/messages/m2 {\"content\":\"corrigida\"}"), edited)
        assertEquals("rascunho de antes", vm.draft.text)
        assertEquals("m3", vm.state.value.replyTo?.id)
        advanceTimeBy(Drafts.DEBOUNCE * 2)
        runCurrent()
        assertEquals(Draft("rascunho de antes", "m3"), stored("c1"))
        assertTrue(posted.isEmpty())
    }

    @Test
    fun editarUmaSegundaMensagemSeguidaVoltaAoMesmoRascunhoDeAntes() = runTest {
        val session = session()
        val vm = ChatViewModel(session, "c1")
        vm.loaded()
        val messages = vm.messages.first { it.size == 3 }
        vm.type("meu rascunho")

        vm.startEdit(messages.first { it.id == "m1" })
        vm.startEdit(messages.first { it.id == "m2" })
        assertEquals("msg 2", vm.draft.text)
        vm.cancelContext()

        assertEquals("meu rascunho", vm.draft.text)
    }

    @Test
    fun responderNoMeioDeUmaEdicaoEncerraAEdicaoSemMandarOTextoDelaComoResposta() = runTest {
        val session = session()
        val vm = ChatViewModel(session, "c1")
        vm.loaded()
        val messages = vm.messages.first { it.size == 3 }
        vm.type("meu rascunho")
        vm.startEdit(messages.first { it.id == "m1" })

        vm.startReply(messages.first { it.id == "m3" })

        assertNull(vm.state.value.editing)
        assertEquals("meu rascunho", vm.draft.text)
        assertEquals("m3", vm.state.value.replyTo?.id)
    }

    @Test
    fun cancelarARespostaTiraEstaDoRascunhoGuardado() = runTest {
        val session = session()
        val vm = ChatViewModel(session, "c1")
        vm.loaded()
        vm.type("texto")
        vm.startReply(vm.messages.first { it.size == 3 }.first { it.id == "m2" })
        advanceTimeBy(Drafts.DEBOUNCE)
        runCurrent()
        assertEquals(Draft("texto", "m2"), stored("c1"))

        vm.cancelContext()
        advanceTimeBy(Drafts.DEBOUNCE)
        runCurrent()

        assertNull(vm.state.value.replyTo)
        assertEquals("texto", vm.draft.text)
        assertEquals(Draft("texto"), stored("c1"))
    }

    // Enviar

    @Test
    fun enfileirarLimpaOCompositorEORascunhoGuardadoNaHora() = runTest {
        val session = session()
        val vm = ChatViewModel(session, "c1")
        vm.loaded()
        vm.type("vai embora")
        advanceTimeBy(Drafts.DEBOUNCE)
        runCurrent()
        assertEquals(Draft("vai embora"), stored("c1"))

        vm.submit()
        runCurrent() // sem esperar o atraso: se o app fechasse agora o texto enviado não pode voltar como rascunho

        assertEquals("", vm.draft.text)
        assertNull(meta.get(Drafts.key("c1")).executeAsOneOrNull())
        session.chat.outbox.first { it.isEmpty() }
        val reopened = ChatViewModel(session, "c1")
        runCurrent()
        assertEquals("", reopened.draft.text)
    }

    /** Segura o que vai para o banco até [open]: mostra o que acontece enquanto a leitura do rascunho ainda não voltou. */
    private class GateDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        var closed = false
        private val held = mutableListOf<Pair<CoroutineContext, Runnable>>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (closed) held += context to block else delegate.dispatch(context, block)
        }

        fun open() {
            closed = false
            held.forEach { (context, block) -> delegate.dispatch(context, block) }
            held.clear()
        }
    }
}
