package chat.papo.mobile

import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.data.api.MemoryCookieStore
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.data.db.account.AccountDb
import chat.papo.mobile.data.ws.WsEvent
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Embeds completos no [chat.papo.mobile.chat.ChatStore]: o que fica no banco e o que a tela lê. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatStoreEmbedsTest {
    private val engine = MockEngine { respond("", HttpStatusCode.NoContent) }

    /** Uma sessão sobre o motor falso, com o banco à mão para gravar linhas como o app de versões antigas as deixou. */
    private class Account(val session: AccountSession, val db: AccountDb)

    private fun TestScope.account(): Account {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = PapoClient(engine, ServerUrl("https://papo.test"), MemoryCookieStore("jwt"))
        val resources = TestResources.inMemory(dispatcher)
        return Account(AccountSession("conta", "u1", client, backgroundScope, resources, dispatcher), AccountDb(resources.driver))
    }

    private suspend fun Account.post() {
        session.chat.handle(WsEvent.MessageCreated("m5", "c1", "u2", content = "oi", createdAt = "2026-01-01T00:00:05Z"))
    }

    private suspend fun Account.stored() = session.chat.messages("c1", 10).first().single()

    @Test
    fun camposNovosDoEmbedSobrevivemAoEventoAoBancoEAMensagem() = runTest {
        val account = account()
        account.post()
        // O evento como o servidor o manda (openapi: `message_embeds_update`), de um webhook com o embed customizado completo.
        val event = WsEvent.parse(
            """{"type":"message_embeds_update","channel_id":"c1","message_id":"m5","embeds":[{
                "id":"e1","source_type":"custom","fetch_method":"manual","site_name":"Painel da Casa",
                "url":"https://painel.example.com/build/42","title":"Build 42 passou","description":"Tudo **verde**",
                "color":"#2ECC71",
                "author":{"name":"Robô de Build","url":"https://painel.example.com/robo","media":{"mime_type":"image/png","width":64,"height":64}},
                "thumbnail":{"mime_type":"image/png","width":1200,"height":630,"size_bytes":71770},
                "footer":{"text":"Executado às 10h"},
                "fields":[{"position":0,"name":"Ramo","value":"principal","inline":true},
                          {"position":1,"name":"Duração","value":"3 min","inline":true},
                          {"position":2,"name":"Notas","value":"sem falhas","inline":false}],
                "created_at":"2026-01-01T00:00:06Z"}]}""",
        )
        assertNotNull(event)
        account.session.chat.handle(event)

        val embed = account.stored().embeds.single()
        assertEquals("manual", embed.fetchMethod)
        assertEquals("#2ECC71", embed.color)
        assertEquals("Robô de Build", embed.author?.name)
        assertEquals("https://painel.example.com/robo", embed.author?.url)
        assertEquals(64, embed.author?.media?.width)
        assertEquals("Executado às 10h", embed.footer?.text)
        assertEquals(71770L, embed.thumbnail?.sizeBytes)
        assertEquals(listOf("Ramo", "Duração", "Notas"), embed.fields.map { it.name })
        assertEquals(listOf(true, true, false), embed.fields.map { it.inline })
        assertEquals("2026-01-01T00:00:06Z", embed.createdAt)
    }

    @Test
    fun embedGravadoAntesDosCamposNovosContinuaAparecendo() = runTest {
        val account = account()
        account.post()
        // Como a linha ficou no banco da versão anterior: só os campos que ela conhecia.
        account.db.chatQueries.setMessageEmbeds(
            """[{"id":"e0","site_name":"Exemplo","url":"https://x.test","title":"Antigo","thumbnail":{"mime_type":"image/png","width":320,"height":180}}]""",
            "m5",
        )

        val embed = account.stored().embeds.single()
        assertEquals("Antigo", embed.title)
        assertEquals(320, embed.thumbnail?.width)
        assertNull(embed.color)
        assertNull(embed.author)
        assertNull(embed.footer)
        assertTrue(embed.fields.isEmpty())
    }
}
