package chat.papo.mobile

import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.data.api.ApiException
import chat.papo.mobile.data.api.AttachmentDto
import chat.papo.mobile.data.api.MemoryCookieStore
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.data.db.account.AccountDb
import chat.papo.mobile.data.ws.WsEvent
import chat.papo.mobile.media.guardGone
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Anexos bloqueados pela moderação no [chat.papo.mobile.chat.ChatStore]: o que fica no banco e o que o 410 do servidor faz. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatStoreModerationTest {
    private val requests = CopyOnWriteArrayList<String>()

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        requests += "${request.method.value} $path"
        fun problem(status: HttpStatusCode, title: String) = respond(
            """{"type":"x","title":"$title","status":${status.value},"detail":"d"}""",
            status,
            headersOf(HttpHeaders.ContentType, "application/problem+json"),
        )
        when (path) {
            "/attachments/a1/thumbnail", "/attachments/a1" -> problem(HttpStatusCode.Gone, "Recurso indisponível")
            "/attachments/a2/thumbnail" -> problem(HttpStatusCode.InternalServerError, "Erro interno")
            "/attachments/a3/thumbnail" -> respond(byteArrayOf(7, 8, 9), HttpStatusCode.OK)
            else -> respond("", HttpStatusCode.NoContent)
        }
    }

    /** Uma sessão sobre o motor falso, com o banco à mão para conferir o que ficou guardado. */
    private class Account(val session: AccountSession, val db: AccountDb)

    private fun TestScope.account(): Account {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = PapoClient(engine, ServerUrl("https://papo.test"), MemoryCookieStore("jwt"))
        val resources = TestResources.inMemory(dispatcher)
        val session = AccountSession("conta", "u1", client, backgroundScope, resources, dispatcher)
        session.chat.conversationOpened("c1") // só uma conversa aberta guarda mensagens do tempo real
        return Account(session, AccountDb(resources.driver))
    }

    private fun attachment(id: String, status: String?) =
        AttachmentDto(id, "image/png", "$id.png", 10, thumbnailId = id, moderationStatus = status)

    private suspend fun Account.post(vararg attachments: AttachmentDto) {
        session.chat.handle(
            WsEvent.MessageCreated("m5", "c1", "u2", content = "oi", createdAt = "2026-01-01T00:00:05Z", attachments = attachments.toList()),
        )
    }

    private suspend fun Account.stored() = session.chat.messages("c1", 10).first().single()

    private fun Account.storedAttachments() = db.chatQueries.selectMessage("m5").executeAsOne().attachments

    @Test
    fun markBlockedGravaBlockedSoNoAnexoCertoESemPedirNada() = runTest {
        val account = account()
        account.post(attachment("a1", "pending"), attachment("a2", "clean"))

        account.session.chat.markBlocked("m5", "a1")

        assertEquals(listOf("blocked", "clean"), account.stored().attachments.map { it.moderationStatus })
        assertTrue(requests.isEmpty(), "gravar o bloqueio não pede nada ao servidor: $requests")
    }

    @Test
    fun markBlockedSemMudancaNaoReescreveALinha() = runTest {
        val account = account()
        account.post(attachment("a1", "blocked"), attachment("a2", "clean"))
        val before = account.storedAttachments()

        account.session.chat.markBlocked("m5", "a1") // já bloqueado
        account.session.chat.markBlocked("m5", "inexistente") // anexo que a mensagem não tem
        account.session.chat.markBlocked("desconhecida", "a1") // mensagem que não está guardada

        assertEquals(before, account.storedAttachments())
    }

    @Test
    fun respostaDeModeracaoPeloWebSocketUsaOMesmoCaminho() = runTest {
        val account = account()
        account.post(attachment("a1", "pending"))

        account.session.chat.handle(WsEvent.AttachmentModeration("c1", "m5", "a1", "sensitive"))
        assertTrue(account.stored().attachments.single().isSensitive)
        account.session.chat.handle(WsEvent.AttachmentModeration("c1", "m5", "a1", "blocked"))
        assertEquals("blocked", account.stored().attachments.single().moderationStatus)
    }

    // guardGone

    @Test
    fun resposta410GravaBlockedAvisaATelaERepassaOErroSemNovoPedido() = runTest {
        val account = account()
        account.post(attachment("a1", "pending"))
        var warned = 0

        val failure = assertFailsWith<ApiException> {
            account.session.guardGone("m5", "a1", onGone = { warned++ }) { account.session.api.attachmentThumbnail("a1") }
        }

        assertEquals(410, failure.status)
        assertEquals(1, warned)
        assertEquals("blocked", account.stored().attachments.single().moderationStatus)
        assertEquals(listOf("GET /attachments/a1/thumbnail"), requests.toList())
    }

    @Test
    fun resposta410DoOriginalTambemBloqueia() = runTest {
        val account = account()
        account.post(attachment("a1", "clean"))

        assertFailsWith<ApiException> { account.session.guardGone("m5", "a1") { account.session.api.attachment("a1") } }

        assertEquals("blocked", account.stored().attachments.single().moderationStatus)
    }

    @Test
    fun outroErroDoServidorNaoBloqueiaENemAvisaATela() = runTest {
        val account = account()
        account.post(attachment("a2", "clean"))
        var warned = 0

        val failure = assertFailsWith<ApiException> {
            account.session.guardGone("m5", "a2", onGone = { warned++ }) { account.session.api.attachmentThumbnail("a2") }
        }

        assertEquals(500, failure.status)
        assertEquals(0, warned)
        assertEquals("clean", account.stored().attachments.single().moderationStatus)
    }

    @Test
    fun respostaBoaDevolveOsBytesSemMexerNoAnexo() = runTest {
        val account = account()
        account.post(attachment("a3", "pending"))

        val bytes = account.session.guardGone("m5", "a3") { account.session.api.attachmentThumbnail("a3") }

        assertEquals(listOf<Byte>(7, 8, 9), bytes.toList())
        assertEquals("pending", account.stored().attachments.single().moderationStatus)
    }

    @Test
    fun cancelamentoPassaSemBloquearNada() = runTest {
        val account = account()
        account.post(attachment("a1", "clean"))

        assertFailsWith<CancellationException> { account.session.guardGone<Unit>("m5", "a1") { throw CancellationException("saiu da tela") } }

        assertEquals("clean", account.stored().attachments.single().moderationStatus)
    }
}
