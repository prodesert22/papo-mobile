package chat.papo.mobile

import chat.papo.mobile.ui.chat.ChatViewModel
import chat.papo.mobile.ui.components.UiError
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val bia = "0b0e7c1e-1111-2222-3333-444455556666"
    private val posted = mutableListOf<String>()
    private val edited = mutableListOf<String>()
    private var failSend = false
    private var total = 3

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        val body = request.body.toByteArray().decodeToString()
        fun json(text: String, status: HttpStatusCode = HttpStatusCode.OK) =
            respond(text, status, headersOf(HttpHeaders.ContentType, if (status.value >= 400) "application/problem+json" else "application/json"))
        fun message(n: Int, content: String = "msg $n", author: String = "u2") =
            """{"id":"m$n","channel_id":"c1","author_id":"$author","content":"$content","created_at":"2026-01-01T00:%02d:%02dZ"}""".format(n / 60, n % 60)
        when {
            path == "/channels/c1/messages" -> {
                val before = request.url.parameters["last_id"]?.removePrefix("m")?.toInt() ?: (total + 1)
                val limit = request.url.parameters["limit"]!!.toInt()
                val ids = (before - 1 downTo maxOf(1, before - limit)).toList()
                json("""{"messages":[${ids.joinToString(",") { message(it) }}],"has_more":${(ids.lastOrNull() ?: 1) > 1}}""")
            }
            path == "/channels/c1/pinned" -> json("""{"channel_id":"c1","pinned":[${message(1)}]}""")
            path == "/messages" && failSend ->
                json("""{"type":"t","title":"Proibido","status":403,"detail":"Sem permissão para enviar neste canal"}""", HttpStatusCode.Forbidden)
            path == "/messages" -> {
                posted += body
                json(message(50, content = "enviada", author = "u1"), HttpStatusCode.Created)
            }
            path.startsWith("/messages/") && request.method == HttpMethod.Put -> {
                edited += "$path $body"
                json(message(2, content = "corrigida", author = "u1"))
            }
            else -> respond("", HttpStatusCode.NoContent)
        }
    }

    private fun TestScope.viewModel(): ChatViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return ChatViewModel(TestResources.session(engine, this), "c1")
    }

    /** O motor HTTP falso responde em outra thread: espera o estado chegar onde o teste precisa. */
    private suspend fun ChatViewModel.loaded() {
        state.first { it.loadedOnce }
    }

    private fun String.field(name: String): String? =
        Regex("""name="?$name"?[^\r\n]*\r\n(?:[^\r\n]+\r\n)*\r\n([^\r]*)""").find(this)?.groupValues?.get(1)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun abrirCarregaOHistoricoEAsFixadas() = runTest {
        val vm = viewModel()
        vm.loaded()
        assertEquals(listOf("m3", "m2", "m1"), vm.messages.first { it.size == 3 }.map { it.id })
        assertTrue(vm.state.value.reachedStart) // três mensagens cabem numa página: não há mais o que buscar

        vm.showPinned()
        assertEquals(listOf("m1"), vm.state.first { it.pinnedList != null }.pinnedList!!.map { it.id })
        vm.hidePinned()
        assertNull(vm.state.value.pinnedList)
    }

    @Test
    fun enviarCodificaAMencaoEscolhidaELevaAResposta() = runTest {
        val vm = viewModel()
        vm.loaded()
        val target = vm.messages.first { it.isNotEmpty() }.first()
        vm.startReply(target)
        vm.pickMention("bia", bia)
        var cleared = false

        vm.submit("oi @bia, viu? @biazinha não") { cleared = true }
        vm.state.first { !it.sending && posted.isNotEmpty() }

        val body = posted.single()
        assertEquals("oi @mention(<@$bia>), viu? @biazinha não", body.field("content"))
        assertEquals(target.id, body.field("reply_to"))
        assertEquals("c1", body.field("channel_id"))
        assertTrue(cleared)
        assertNull(vm.state.value.replyTo)
        assertEquals("m50", vm.messages.first { list -> list.any { it.id == "m50" } }.first().id)

        // A menção valia só para aquele rascunho.
        vm.submit("@bia de novo") {}
        vm.state.first { !it.sending && posted.size == 2 }
        assertEquals("@bia de novo", posted[1].field("content"))
    }

    @Test
    fun falhaNoEnvioMantemORascunhoEOContexto() = runTest {
        val vm = viewModel()
        vm.loaded()
        val target = vm.messages.first { it.isNotEmpty() }.first()
        vm.startReply(target)
        failSend = true
        var cleared = false

        vm.submit("não vai") { cleared = true }
        val state = vm.state.first { !it.sending && it.error != null }

        assertEquals(UiError.Server("Sem permissão para enviar neste canal"), state.error)
        assertFalse(cleared)
        assertEquals(target.id, state.replyTo?.id) // a resposta em andamento não se perde
        vm.dismissError()
        assertNull(vm.state.value.error)
    }

    @Test
    fun editarSalvaPorPutENaoCriaMensagem() = runTest {
        val vm = viewModel()
        vm.loaded()
        val mine = vm.messages.first { it.size == 3 }.first { it.id == "m2" }
        vm.startReply(mine)
        vm.startEdit(mine)
        assertNull(vm.state.value.replyTo) // editar e responder não convivem
        var cleared = false

        vm.submit("corrigida") { cleared = true }
        vm.state.first { !it.sending && edited.isNotEmpty() }

        assertEquals(listOf("/messages/m2 {\"content\":\"corrigida\"}"), edited)
        assertTrue(posted.isEmpty())
        assertTrue(cleared)
        assertNull(vm.state.value.editing)
        assertEquals("corrigida", vm.messages.first { list -> list.any { it.content == "corrigida" } }.first { it.id == "m2" }.content)
    }

    @Test
    fun naoEnviaRascunhoVazio() = runTest {
        val vm = viewModel()
        vm.loaded()
        var cleared = false
        vm.submit("   ") { cleared = true }
        assertFalse(vm.state.value.sending)
        assertFalse(cleared)
        assertTrue(posted.isEmpty())
    }

    @Test
    fun historicoLongoCarregaAosPoucosAteOComeco() = runTest {
        total = 130
        val vm = viewModel()
        vm.loaded()
        assertEquals(40, vm.messages.first { it.size == 40 }.size)
        assertFalse(vm.state.value.reachedStart)

        var rounds = 0
        while (!vm.state.value.reachedStart && rounds++ < 10) {
            vm.loadOlder()
            vm.state.first { !it.loadingOlder }
        }
        assertTrue(vm.state.value.reachedStart)
        val all = vm.messages.first { it.size == 130 }
        assertEquals("m130", all.first().id)
        assertEquals("m1", all.last().id)
    }
}
