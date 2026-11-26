package chat.papo.mobile

import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.data.api.ApiException
import chat.papo.mobile.data.api.ChatApi
import chat.papo.mobile.data.api.MemoryCookieStore
import chat.papo.mobile.data.api.PapoClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Os pedidos de perfil, de banner e de avatar de webhook: o caminho, o método e o que cada resposta vira. */
class ProfileApiTest {
    private class Seen(val method: HttpMethod, val path: String)

    private fun api(seen: MutableList<Seen>, reply: (String) -> Pair<HttpStatusCode, ByteArray>): ChatApi {
        val engine = MockEngine { request ->
            seen += Seen(request.method, request.url.encodedPath)
            val (status, body) = reply(request.url.encodedPath)
            val type = if (status.value >= 400) "application/problem+json" else "application/json"
            respond(body, status, headersOf(HttpHeaders.ContentType, type))
        }
        return ChatApi(PapoClient(engine, ServerUrl("https://papo.test"), MemoryCookieStore("jwt")))
    }

    private fun problem(status: HttpStatusCode) =
        status to """{"type":"t","title":"Erro","status":${status.value},"detail":"falhou"}""".encodeToByteArray()

    @Test
    fun perfilPedeOCaminhoDoUsuarioELeOsCampos() = runTest {
        val seen = mutableListOf<Seen>()
        val api = api(seen) {
            HttpStatusCode.OK to (
                """{"id":"u2","username":"bia","nickname":"Bia Borges","avatar_blob":"AQID","avatar_format":"PNG",""" +
                    """"banner_media":"abc123","description":"Oi","status":"busy","status_message":"Revisando","typing":null,""" +
                    """"status_updated_at":"2026-01-01T00:00:00Z","created_at":"2026-01-04T10:30:00Z",""" +
                    """"roles":[{"id":"r1","name":"Admin","color":"#E86CA5"},{"id":"r2","name":"Leitores"}]}"""
                ).encodeToByteArray()
        }

        val profile = api.profile("u2")

        assertEquals(listOf(HttpMethod.Get to "/users/u2/profile"), seen.map { it.method to it.path })
        assertEquals("Bia Borges", profile.nickname)
        assertEquals("AQID", profile.avatarBlob)
        assertEquals("abc123", profile.bannerMedia)
        assertEquals("Oi", profile.description)
        assertEquals("Revisando", profile.statusMessage)
        assertEquals(listOf("Admin", "Leitores"), profile.roles.map { it.name })
        assertNull(profile.roles.last().color)
    }

    @Test
    fun perfilSemAvatarNemBannerNemListaDeCargosNaoQuebra() = runTest {
        val api = api(mutableListOf()) { HttpStatusCode.OK to """{"id":"u3","username":"caio","banner_media":null,"roles":null}""".encodeToByteArray() }

        val profile = api.profile("u3")

        assertNull(profile.avatarBlob)
        assertNull(profile.bannerMedia)
        assertNull(profile.description)
        assertTrue(profile.roles.isEmpty())
    }

    @Test
    fun perfilInexistenteEErroDoServidorViramApiException() = runTest {
        var status = HttpStatusCode.NotFound
        val api = api(mutableListOf()) { problem(status) }

        assertTrue(assertFailsWith<ApiException> { api.profile("u9") }.isNotFound)
        status = HttpStatusCode.InternalServerError
        assertEquals(500, assertFailsWith<ApiException> { api.profile("u9") }.status)
    }

    @Test
    fun bannerPedeAMidiaPeloShaEDevolveOsBytes() = runTest {
        val seen = mutableListOf<Seen>()
        val api = api(seen) { HttpStatusCode.OK to byteArrayOf(7, 8, 9) }

        assertContentEquals(byteArrayOf(7, 8, 9), api.media("abc123"))

        assertEquals(listOf(HttpMethod.Get to "/media/abc123"), seen.map { it.method to it.path })
    }

    @Test
    fun bannerQueNaoExisteViraApiException() = runTest {
        val api = api(mutableListOf()) { problem(HttpStatusCode.NotFound) }

        assertTrue(assertFailsWith<ApiException> { api.media("abc123") }.isNotFound)
    }

    @Test
    fun avatarDeWebhookPedeOCaminhoPublicoEOAusenteENulo() = runTest {
        val seen = mutableListOf<Seen>()
        var status = HttpStatusCode.OK
        val api = api(seen) { if (status == HttpStatusCode.OK) status to byteArrayOf(1, 2, 3) else problem(status) }

        assertContentEquals(byteArrayOf(1, 2, 3), api.webhookAvatar("w1"))
        status = HttpStatusCode.NotFound
        assertNull(api.webhookAvatar("w1"))
        status = HttpStatusCode.TooManyRequests
        assertEquals(429, assertFailsWith<ApiException> { api.webhookAvatar("w1") }.status)

        assertEquals(List(3) { HttpMethod.Get to "/webhooks/w1/avatar" }, seen.map { it.method to it.path })
    }
}
