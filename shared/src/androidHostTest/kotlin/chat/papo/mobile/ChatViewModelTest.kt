package chat.papo.mobile

import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.PendingStatus
import chat.papo.mobile.chat.SendFailure
import chat.papo.mobile.data.api.OutgoingFile
import chat.papo.mobile.ui.chat.ChatViewModel
import chat.papo.mobile.ui.chat.MessageTarget
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
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val bia = "0b0e7c1e-1111-2222-3333-444455556666"
    private val posted = mutableListOf<String>()
    private val edited = mutableListOf<String>()
    private var attempts = 0
    private var editAttempts = 0
    private var failEdit = false
    private var failSend = false
    private var total = 3

    /** A mensagem `n` foi criada `n` segundos depois da meia-noite. */
    private fun seconds(iso: String): Double {
        val instant = Instant.parse(iso)
        return (instant.epochSeconds - Instant.parse("2026-01-01T00:00:00Z").epochSeconds) + instant.nanosecondsOfSecond / 1e9
    }

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        val body = request.body.toByteArray().decodeToString()
        fun json(text: String, status: HttpStatusCode = HttpStatusCode.OK) =
            respond(text, status, headersOf(HttpHeaders.ContentType, if (status.value >= 400) "application/problem+json" else "application/json"))
        fun message(n: Int, content: String = "msg $n", author: String = "u2") =
            """{"id":"m$n","channel_id":"c1","author_id":"$author","content":"$content","created_at":"2026-01-01T00:%02d:%02dZ"}""".format(n / 60, n % 60)
        when {
            path == "/channels/c1/messages" -> {
                val limit = request.url.parameters["limit"]!!.toInt()
                val asc = request.url.parameters["order"] == "asc"
                val lastId = request.url.parameters["last_id"]
                val since = request.url.parameters["since"]?.let { seconds(it) }
                val matching = (1..total).filter { n ->
                    when {
                        since == null -> true
                        asc -> n > since || (n.toDouble() == since && "m$n" > lastId!!)
                        else -> n < since || (n.toDouble() == since && "m$n" < lastId!!)
                    }
                }
                val ordered = if (asc) matching else matching.reversed()
                json("""{"messages":[${ordered.take(limit).joinToString(",") { message(it) }}],"has_more":${ordered.size > limit}}""")
            }
            path == "/channels/c1/pinned" -> json("""{"channel_id":"c1","pinned":[${message(1)}]}""")
            path == "/messages" && failSend -> {
                attempts++
                json("""{"type":"t","title":"Proibido","status":403,"detail":"Sem permissão para enviar neste canal"}""", HttpStatusCode.Forbidden)
            }
            path == "/messages" -> {
                attempts++
                posted += body
                json(message(50, content = "enviada", author = "u1"), HttpStatusCode.Created)
            }
            path.startsWith("/messages/") && request.method == HttpMethod.Put && failEdit -> {
                editAttempts++
                json("""{"type":"t","title":"Proibido","status":403,"detail":"Só o autor edita"}""", HttpStatusCode.Forbidden)
            }
            path.startsWith("/messages/") && request.method == HttpMethod.Put -> {
                editAttempts++
                edited += "$path $body"
                json(message(2, content = "corrigida", author = "u1"))
            }
            else -> respond("", HttpStatusCode.NoContent)
        }
    }

    private lateinit var session: AccountSession

    private fun TestScope.viewModel(target: MessageTarget? = null): ChatViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        session = TestResources.session(engine, this)
        return ChatViewModel(session, "c1", target)
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
    fun abrirNumaMensagemAntigaMostraAVizinhancaDelaEADestaca() = runTest {
        total = 300
        val vm = viewModel(MessageTarget("m20", "2026-01-01T00:00:20Z"))
        assertTrue(vm.state.value.locating)
        vm.loadOlder() // a rolagem não pagina enquanto a mensagem é procurada
        assertFalse(vm.state.value.loadingOlder)

        val state = vm.state.first { it.focusId != null }
        assertEquals("m20", state.focusId)
        assertFalse(state.locating)
        assertFalse(state.targetMissing)
        // A lista passa a ser a vizinhança dela: as cem seguintes e o que veio antes, até o começo do canal.
        val shown = vm.messages.first { list -> list.any { it.id == "m20" } }.map { it.id }
        assertEquals((120 downTo 1).map { "m$it" }, shown)
        assertEquals(100, shown.indexOf("m20"))
        session.chat.detached.first { "c1" in it }

        // Perto do fim do trecho, a tela pede o que vem depois; o destaque ainda na tela segura o pedido.
        vm.loadNewer()
        assertFalse(vm.state.value.loadingNewer)
        vm.clearFocus()
        vm.loadNewer()
        assertTrue(vm.state.value.loadingNewer)
        assertEquals("m160", vm.messages.first { it.first().id == "m160" }.first().id)
        vm.state.first { !it.loadingNewer }
        assertEquals(160, vm.messages.first { it.size == 160 }.size) // nada do que estava à vista saiu da lista

        // Voltar às recentes troca o trecho pelo fim da conversa.
        vm.goToLatest()
        val back = vm.state.first { it.latestTick == 1 && !it.returning }
        assertNull(back.error)
        assertEquals("m300", vm.messages.first { it.firstOrNull()?.id == "m300" }.first().id)
        assertFalse(session.chat.isDetached("c1"))
    }

    @Test
    fun mensagemPedidaQueFoiApagadaAvisaSemDestacar() = runTest {
        total = 120
        // O instante dela fica entre m100 e m101, mas ela não existe mais.
        val vm = viewModel(MessageTarget("apagada", "2026-01-01T00:01:40.5Z"))

        val state = vm.state.first { !it.locating }
        assertTrue(state.targetMissing)
        assertNull(state.focusId)
        assertNull(state.error)
        assertEquals("m120", vm.messages.first { it.isNotEmpty() }.first().id) // a conversa fica no fim, como sempre
        vm.dismissTargetMissing()
        assertFalse(vm.state.value.targetMissing)
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
        // O compositor fica livre na hora e a mensagem já aparece como em saída, com a resposta que levava.
        assertTrue(cleared)
        assertNull(vm.state.value.replyTo)
        val shown = vm.pending.first { it.isNotEmpty() }.single()
        assertEquals("oi @mention(<@$bia>), viu? @biazinha não", shown.content)
        assertEquals(target.id, shown.replyToId)
        session.chat.outbox.first { it.isEmpty() }

        val body = posted.single()
        assertEquals("oi @mention(<@$bia>), viu? @biazinha não", body.field("content"))
        assertEquals(target.id, body.field("reply_to"))
        assertEquals("c1", body.field("channel_id"))
        assertEquals("m50", vm.messages.first { list -> list.any { it.id == "m50" } }.first().id)

        // A menção valia só para aquele rascunho.
        vm.submit("@bia de novo") {}
        session.chat.outbox.first { it.isEmpty() }
        assertEquals("@bia de novo", posted[1].field("content"))
    }

    @Test
    fun falhaNoEnvioDeixaATextoERespostaNaFilaSemReenviarSozinha() = runTest {
        val vm = viewModel()
        vm.loaded()
        val target = vm.messages.first { it.isNotEmpty() }.first()
        vm.startReply(target)
        failSend = true
        var cleared = false

        vm.submit("não vai") { cleared = true }
        // O compositor não guarda mais o rascunho: quem o guarda é a mensagem que ficou na fila.
        assertTrue(cleared)
        assertNull(vm.state.value.replyTo)
        val failed = vm.pending.first { it.singleOrNull()?.status is PendingStatus.Failed }.single()

        assertEquals(PendingStatus.Failed(SendFailure.Rejected, "Sem permissão para enviar neste canal"), failed.status)
        assertEquals("não vai", failed.content)
        assertEquals(target.id, failed.replyToId) // a resposta em andamento não se perde
        assertNull(vm.state.value.error)
        assertEquals(1, attempts) // um 403 não é tentado de novo

        // Reenviar é uma ação do usuário e leva o mesmo texto e a mesma resposta.
        failSend = false
        vm.retry(failed.localId)
        session.chat.outbox.first { it.isEmpty() }
        assertEquals(2, attempts)
        assertEquals("não vai", posted.single().field("content"))
        assertEquals(target.id, posted.single().field("reply_to"))
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
        vm.state.first { !it.savingEdit && edited.isNotEmpty() }

        assertEquals(listOf("/messages/m2 {\"content\":\"corrigida\"}"), edited)
        assertTrue(posted.isEmpty())
        assertTrue(cleared)
        assertNull(vm.state.value.editing)
        assertEquals("corrigida", vm.messages.first { list -> list.any { it.content == "corrigida" } }.first { it.id == "m2" }.content)
    }

    @Test
    fun falhaAoSalvarAEdicaoMantemAEdicaoEMostraOErroSemTravarOCompositor() = runTest {
        val vm = viewModel()
        vm.loaded()
        val mine = vm.messages.first { it.size == 3 }.first { it.id == "m2" }
        vm.startEdit(mine)
        failEdit = true
        var cleared = false

        vm.submit("corrigida") { cleared = true }
        assertTrue(vm.state.value.savingEdit) // editar segue síncrono: o compositor espera a resposta
        vm.submit("outra tentativa") { cleared = true } // um segundo toque enquanto salva não faz outro pedido
        val state = vm.state.first { !it.savingEdit && it.error != null }

        assertEquals(UiError.Server("Só o autor edita"), state.error)
        assertEquals("m2", state.editing?.id) // a edição continua aberta para tentar de novo
        assertFalse(cleared)
        assertEquals(1, editAttempts)
        assertTrue(vm.pending.value.isEmpty()) // edição nunca passa pela fila de envio
    }

    @Test
    fun descartarTiraAMensagemFalhadaEAFilaDeOutraConversaNaoApareceAqui() = runTest {
        val vm = viewModel()
        vm.loaded()
        failSend = true
        vm.submit("não vai") {}
        session.chat.enqueue("outra", "de outra conversa", null, emptyList())
        session.chat.outbox.first { list -> list.size == 2 && list.all { it.status is PendingStatus.Failed } }

        val mine = vm.pending.first { it.size == 1 }.single()
        assertEquals("não vai", mine.content)

        vm.withdraw(mine.localId)
        assertTrue(vm.pending.first { it.isEmpty() }.isEmpty())
        assertEquals(listOf("outra"), session.chat.outbox.value.map { it.channelId })
    }

    @Test
    fun soComAnexoEnfileiraSemTextoELimpaOsArquivosDoCompositor() = runTest {
        val vm = viewModel()
        vm.loaded()
        vm.addFiles(listOf(OutgoingFile("a.bin", "application/octet-stream", 3) { byteArrayOf(1, 2, 3) }))
        var cleared = false

        vm.submit("   ") { cleared = true }

        assertTrue(cleared)
        assertTrue(vm.state.value.files.isEmpty())
        val shown = vm.pending.first { it.isNotEmpty() }.single()
        assertEquals("", shown.content)
        assertEquals(1, shown.files.size)
        session.chat.outbox.first { it.isEmpty() }
        assertNull(posted.single().field("content")) // mensagem só de anexo não manda o campo de texto
    }

    @Test
    fun naoEnviaRascunhoVazio() = runTest {
        val vm = viewModel()
        vm.loaded()
        var cleared = false
        vm.submit("   ") { cleared = true }
        assertFalse(vm.state.value.savingEdit)
        assertFalse(cleared)
        assertTrue(vm.pending.value.isEmpty())
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
