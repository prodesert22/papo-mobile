package chat.papo.mobile

import chat.papo.mobile.data.api.ApiException
import chat.papo.mobile.data.api.DirectConversationDto
import chat.papo.mobile.data.api.UserSummaryDto
import chat.papo.mobile.data.ws.WsEvent
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Fechar uma conversa direta: some da lista, volta com um `dm_update`, e a falha deixa a linha onde estava. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatStoreCloseDmTest {
    private val requests = mutableListOf<String>()
    private var closeStatus = HttpStatusCode.NoContent
    private var closeGate: CompletableDeferred<Unit>? = null
    private val closeStarted = CompletableDeferred<Unit>()

    private val engine = MockEngine { request ->
        requests += "${request.method.value} ${request.url.encodedPath}"
        when {
            request.url.encodedPath.startsWith("/dms/") -> {
                closeStarted.complete(Unit)
                closeGate?.await()
                if (closeStatus.value < 400) {
                    respond("", closeStatus)
                } else {
                    respond(
                        """{"title":"erro","detail":"recusado","status":${closeStatus.value}}""",
                        closeStatus,
                        headersOf(HttpHeaders.ContentType, "application/problem+json"),
                    )
                }
            }
            else -> respond("", HttpStatusCode.NoContent)
        }
    }

    private val dm = DirectConversationDto("d1", UserSummaryDto("u9", "novato", nickname = "Novato"), "2026-01-01T00:00:00Z", unreadCount = 2)

    @Test
    fun fecharApagaALinhaEUmDmUpdateDepoisATrazDeVolta() = runTest {
        val chat = TestResources.session(engine, this).chat
        chat.handle(WsEvent.DmUpdated(dm))
        runCurrent()
        assertEquals(listOf("d1"), chat.dms.value.map { it.id })

        chat.closeDm("d1")
        runCurrent()
        assertTrue(chat.dms.value.isEmpty())

        chat.handle(WsEvent.DmUpdated(dm.copy(unreadCount = 3)))
        runCurrent()
        assertEquals(3, chat.dms.value.single().unreadCount)
        assertEquals(listOf("DELETE /dms/d1"), requests)
    }

    @Test
    fun fecharUmaNaoMexeNasOutras() = runTest {
        val chat = TestResources.session(engine, this).chat
        chat.handle(WsEvent.DmUpdated(dm))
        chat.handle(WsEvent.DmUpdated(DirectConversationDto("d2", UserSummaryDto("u8", "outra"), "2026-01-01T00:00:00Z")))
        runCurrent()

        chat.closeDm("d1")
        runCurrent()

        assertEquals(listOf("d2"), chat.dms.value.map { it.id })
    }

    @Test
    fun umaRecusaDoServidorMantemALinha() = runTest {
        closeStatus = HttpStatusCode.InternalServerError
        val chat = TestResources.session(engine, this).chat
        chat.handle(WsEvent.DmUpdated(dm))
        runCurrent()

        val failure = assertFailsWith<ApiException> { chat.closeDm("d1") }
        runCurrent()

        assertEquals(500, failure.status)
        assertEquals(listOf("d1"), chat.dms.value.map { it.id })
        assertEquals(listOf("DELETE /dms/d1"), requests)
    }

    @Test
    fun um429NaoApagaALinhaENaoEhRepetido() = runTest {
        closeStatus = HttpStatusCode.TooManyRequests
        val chat = TestResources.session(engine, this).chat
        chat.handle(WsEvent.DmUpdated(dm))
        runCurrent()

        val failure = assertFailsWith<ApiException> { chat.closeDm("d1") }
        runCurrent()

        assertEquals(429, failure.status)
        assertEquals(listOf("d1"), chat.dms.value.map { it.id })
        assertEquals(listOf("DELETE /dms/d1"), requests)
    }

    @Test
    fun conversaQueOServidorNaoConheceDa404ESegueNaLista() = runTest {
        closeStatus = HttpStatusCode.NotFound
        val chat = TestResources.session(engine, this).chat
        chat.handle(WsEvent.DmUpdated(dm))
        runCurrent()

        val failure = assertFailsWith<ApiException> { chat.closeDm("d1") }

        assertTrue(failure.isNotFound)
        assertEquals(listOf("d1"), chat.dms.value.map { it.id })
    }

    @Test
    fun fecharCanceladoNoMeioNaoApagaALinha() = runTest {
        closeGate = CompletableDeferred()
        val chat = TestResources.session(engine, this).chat
        chat.handle(WsEvent.DmUpdated(dm))
        runCurrent()
        val job = launch { chat.closeDm("d1") }

        TestResources.waitUntil { closeStarted.isCompleted }
        job.cancel()
        job.join()
        runCurrent()

        assertTrue(job.isCancelled)
        assertEquals(listOf("d1"), chat.dms.value.map { it.id })
    }
}
