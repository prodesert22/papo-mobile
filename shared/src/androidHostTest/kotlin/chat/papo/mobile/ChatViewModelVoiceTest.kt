package chat.papo.mobile

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.data.api.MemoryCookieStore
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.media.VoiceFormat
import chat.papo.mobile.media.VoiceRecording
import chat.papo.mobile.media.VoiceRecordingState
import chat.papo.mobile.ui.chat.ChatViewModel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Enviar a gravação de voz pelo [ChatViewModel]: ela sai como uma mensagem só dela, gasta a resposta e deixa o texto e os
 * arquivos do compositor como estavam; sem permissão, editando, ou sendo de outra conversa, ela fica na revisão.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelVoiceTest {
    private val posted = CopyOnWriteArrayList<String>()

    /** Quem é o dono do servidor: dono pode tudo; nos testes de permissão negada é outra pessoa. */
    @Volatile private var ownerId = "me"

    @Volatile private var channelsBody = channels(channel("c1"), channel("outra"))

    @Volatile private var usersBody = """{"users":[{"id":"me","username":"me"},{"id":"u2","username":"u2"}],"has_more":false}"""

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        fun json(text: String, status: HttpStatusCode = HttpStatusCode.OK) =
            respond(text, status, headersOf(HttpHeaders.ContentType, "application/json"))
        when {
            path == "/server" -> json("""{"id":"s","name":"papo","owner_id":"$ownerId"}""")
            path == "/roles" -> json("""{"roles":[{"id":"leitores","name":"Leitores","permissions":{}}]}""")
            path == "/channels" -> json(channelsBody)
            path == "/dms" -> json("""{"dms":[]}""")
            path == "/users" -> json(usersBody)
            path == "/messages" && request.method == HttpMethod.Post -> {
                posted += request.body.toByteArray().decodeToString()
                json("""{"id":"s1","channel_id":"c1","author_id":"me","content":"","created_at":"2026-01-01T00:10:00Z"}""", HttpStatusCode.Created)
            }
            path.endsWith("/messages") ->
                json("""{"messages":[{"id":"m1","channel_id":"c1","author_id":"u2","content":"oi","created_at":"2026-01-01T00:00:01Z"}],"has_more":false}""")
            else -> respond("", HttpStatusCode.NoContent)
        }
    }

    private class Voice(test: TestScope) {
        val recorder = FakeVoiceRecorder(VoiceFormat("audio/webm", "webm", 32_000))
        val voice = VoiceRecording(
            recorder = recorder,
            players = { clip, listener -> FakeVoicePlayer(clip, listener) },
            scope = test.backgroundScope,
            io = StandardTestDispatcher(test.testScheduler),
            callActive = MutableStateFlow(false),
            pauseOtherAudio = {},
            wallClockMs = { 1_700_000_000_000L },
            timeSource = test.testScheduler.timeSource,
        )
        val state get() = voice.state.value
    }

    private class Chat(val session: AccountSession, val vm: ChatViewModel, val store: ViewModelStore, val voice: Voice)

    private suspend fun TestScope.chat(): Chat {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val client = PapoClient(engine, ServerUrl("https://papo.test"), MemoryCookieStore("jwt"))
        val session = AccountSession("conta", "me", client, backgroundScope, TestResources.inMemory(dispatcher), dispatcher)
        session.chat.sync()
        runCurrent()
        val voice = Voice(this)
        val store = ViewModelStore()
        val vm = ViewModelProvider.create(store, viewModelFactory { initializer { ChatViewModel(session, "c1", voice = voice.voice) } })[ChatViewModel::class]
        vm.state.first { it.loadedOnce }
        vm.messages.first { it.isNotEmpty() }
        return Chat(session, vm, store, voice)
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun ChatViewModel.type(text: String) = onDraftChange(TextFieldValue(text, TextRange(text.length)))

    private fun String.field(name: String): String? =
        Regex("""name="?$name"?[^\r\n]*\r\n(?:[^\r\n]+\r\n)*\r\n([^\r]*)""").find(this)?.groupValues?.get(1)

    /** Grava um segundo para [owner] e para: a gravação fica na revisão. */
    private fun TestScope.review(voice: Voice, owner: String) {
        voice.voice.start(owner)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        voice.voice.stop()
        runCurrent()
        assertIs<VoiceRecordingState.Review>(voice.state)
    }

    // Enviar

    @Test
    fun enviarMandaOArquivoDeVozSemTextoGastaARespostaEDeixaTextoEArquivosNoCompositor() = runTest {
        val chat = chat()
        val vm = chat.vm
        vm.type("rascunho")
        vm.addFiles(listOf(bytesFile("foto.png", "image/png", byteArrayOf(1))))
        vm.startReply(vm.messages.value.first())
        review(chat.voice, vm.voiceOwner)

        vm.sendVoice()
        chat.session.chat.outbox.first { it.isEmpty() }

        val body = posted.single()
        assertEquals("m1", body.field("reply_to"))
        assertEquals("c1", body.field("channel_id"))
        assertTrue(Regex("""filename="audio_1700000000000\.webm"""").containsMatchIn(body), body)
        assertTrue("rascunho" !in body && "foto.png" !in body, "só a gravação deveria sair")
        // A resposta foi gasta; o texto e o arquivo escolhido ficam para a próxima mensagem.
        assertNull(vm.state.value.replyTo)
        assertEquals("rascunho", vm.draft.text)
        assertEquals(listOf("foto.png"), vm.state.value.files.map { it.name })
        assertEquals(VoiceRecordingState.Idle, chat.voice.state)
    }

    @Test
    fun enviarSemRespostaNaoMandaReplyTo() = runTest {
        val chat = chat()
        review(chat.voice, chat.vm.voiceOwner)

        chat.vm.sendVoice()
        chat.session.chat.outbox.first { it.isEmpty() }

        assertNull(posted.single().field("reply_to"))
    }

    @Test
    fun enviarSemGravacaoNaoMandaNada() = runTest {
        val chat = chat()

        chat.vm.sendVoice()
        runCurrent()

        assertEquals(emptyList(), chat.session.chat.outbox.value)
        assertTrue(posted.isEmpty())
    }

    @Test
    fun enviarSemGravadorNaPlataformaNaoMandaNada() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val client = PapoClient(engine, ServerUrl("https://papo.test"), MemoryCookieStore("jwt"))
        val session = AccountSession("conta", "me", client, backgroundScope, TestResources.inMemory(dispatcher), dispatcher)
        val vm = ChatViewModel(session, "c1")

        vm.sendVoice()
        runCurrent()

        assertTrue(posted.isEmpty())
    }

    @Test
    fun aGravacaoDeOutraConversaNaoEEnviadaNemPerdida() = runTest {
        val chat = chat()
        review(chat.voice, "conta/outra")

        chat.vm.sendVoice()
        runCurrent()

        assertTrue(posted.isEmpty())
        assertEquals("conta/outra", (chat.voice.state as VoiceRecordingState.Review).owner)
    }

    @Test
    fun oDonoDaGravacaoECadaContaECadaCanal() = runTest {
        val chat = chat()

        assertEquals("conta/c1", chat.vm.voiceOwner)
    }

    // Quando não envia

    @Test
    fun editandoAGravacaoComecadaDepoisFicaNaRevisaoSemEnviar() = runTest {
        val chat = chat()
        val vm = chat.vm
        vm.startEdit(vm.messages.value.first())
        review(chat.voice, vm.voiceOwner)

        vm.sendVoice()
        runCurrent()

        assertTrue(posted.isEmpty())
        assertIs<VoiceRecordingState.Review>(chat.voice.state)
    }

    @Test
    fun comecarAEditarDescartaAGravacaoDaConversa() = runTest {
        val chat = chat()
        review(chat.voice, chat.vm.voiceOwner)

        chat.vm.startEdit(chat.vm.messages.value.first())

        assertEquals(VoiceRecordingState.Idle, chat.voice.state)
    }

    @Test
    fun comecarAEditarNaoMexeNaGravacaoDeOutraConversa() = runTest {
        val chat = chat()
        review(chat.voice, "conta/outra")

        chat.vm.startEdit(chat.vm.messages.value.first())

        assertIs<VoiceRecordingState.Review>(chat.voice.state)
    }

    @Test
    fun semPermissaoDeEnviarAGravacaoFicaNaRevisao() = runTest {
        ownerId = "boss"
        channelsBody = channels(channel("c1", entries(READERS, read = true, send = false)))
        usersBody = users(READERS)
        val chat = chat()
        assertFalse(chat.vm.access.value.send)
        review(chat.voice, chat.vm.voiceOwner)

        chat.vm.sendVoice()
        runCurrent()

        assertTrue(posted.isEmpty())
        assertIs<VoiceRecordingState.Review>(chat.voice.state)
    }

    @Test
    fun podendoEnviarMasNaoAnexarAGravacaoFicaNaRevisao() = runTest {
        ownerId = "boss"
        channelsBody = channels(channel("c1", entries(READERS, read = true, send = true)))
        usersBody = users(READERS)
        val chat = chat()
        assertTrue(chat.vm.access.value.send)
        assertFalse(chat.vm.access.value.attach)
        review(chat.voice, chat.vm.voiceOwner)

        chat.vm.sendVoice()
        runCurrent()

        assertTrue(posted.isEmpty())
        assertIs<VoiceRecordingState.Review>(chat.voice.state)
    }

    // Sair

    @Test
    fun liberarAConversaDescartaAGravacaoDela() = runTest {
        val chat = chat()
        review(chat.voice, chat.vm.voiceOwner)

        chat.store.clear()

        assertEquals(VoiceRecordingState.Idle, chat.voice.state)
    }

    @Test
    fun liberarAConversaDeixaAGravacaoDeOutraConversa() = runTest {
        val chat = chat()
        review(chat.voice, "conta/outra")

        chat.store.clear()

        assertIs<VoiceRecordingState.Review>(chat.voice.state)
    }

    private companion object {
        const val READERS = "leitores"

        fun entries(role: String, read: Boolean, send: Boolean) =
            """[{"role_id":"$role","role_name":"$role","permissions":{"read_channel":$read,"send_messages":$send}}]"""

        fun channel(id: String, entries: String = "[]") =
            """{"id":"$id","name":"$id","type":"text","position":1,"permissions":$entries}"""

        fun channels(vararg items: String) = """{"channels":[${items.joinToString(",")}]}"""

        fun users(role: String) =
            """{"users":[{"id":"me","username":"me","created_at":"2026-01-01T00:00:00Z","roles":[{"id":"$role","name":"$role"}]},{"id":"u2","username":"u2"}],"has_more":false}"""
    }
}
