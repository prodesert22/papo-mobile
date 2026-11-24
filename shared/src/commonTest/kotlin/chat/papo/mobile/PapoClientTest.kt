package chat.papo.mobile

import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.data.api.ApiException
import chat.papo.mobile.data.api.AuthApi
import chat.papo.mobile.data.api.MemoryCookieStore
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.data.api.parseAuthCookie
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PapoClientTest {
    private val server = ServerUrl("https://papo.test")
    private val whoami = """{"id":"u1","username":"ana"}"""
    private val problem = """{"type":"about:blank","title":"Não autorizado","status":401,"detail":"Token expirado"}"""

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK, cookie: String? = null): HttpResponseData {
        val contentType = if (status.value >= 400) "application/problem+json" else "application/json"
        val headers = if (cookie != null) {
            headersOf(HttpHeaders.ContentType to listOf(contentType), HttpHeaders.SetCookie to listOf(cookie))
        } else {
            headersOf(HttpHeaders.ContentType, contentType)
        }
        return respond(body, status, headers)
    }

    private fun HttpRequestData.cookie() = headers[HttpHeaders.Cookie]

    @Test
    fun parseDoCookieAuth() {
        assertEquals("abc.def", parseAuthCookie("Auth=abc.def; Path=/; HttpOnly; Secure; SameSite=Strict")?.value)
        assertEquals(false, parseAuthCookie("Auth=abc; Max-Age=1800")?.expired)
        assertEquals(true, parseAuthCookie("Auth=; Path=/; Max-Age=0")?.expired)
        assertEquals(true, parseAuthCookie("Auth=abc; Max-Age=0")?.expired)
        assertNull(parseAuthCookie("Other=1; Path=/"))
        assertNull(parseAuthCookie("AuthX=1"))
    }

    @Test
    fun loginGuardaOCookieEEnviaNasProximasRequisicoes() = runTest {
        val seen = mutableListOf<String?>()
        val engine = MockEngine { request ->
            seen += request.cookie()
            when (request.url.encodedPath) {
                "/auth/login" -> json("""{"user":{"id":"u1","username":"ana"}}""", cookie = "Auth=jwt1; Path=/; HttpOnly")
                else -> json(whoami)
            }
        }
        val cookies = MemoryCookieStore()
        val api = AuthApi(PapoClient(engine, server, cookies))

        api.login("ana", "Senha@123")
        assertEquals("jwt1", cookies.get())
        assertEquals("ana", api.whoami().username)
        assertEquals(listOf(null, "Auth=jwt1"), seen)
    }

    @Test
    fun em401RenovaASessaoUmaVezERepete() = runTest {
        val paths = mutableListOf<String>()
        val engine = MockEngine { request ->
            paths += "${request.url.encodedPath} ${request.cookie()}"
            when {
                request.url.encodedPath == "/auth/refresh" ->
                    json("""{"connection":{"id":"c1"}}""", cookie = "Auth=new; Path=/")
                request.cookie() == "Auth=new" -> json(whoami)
                else -> json(problem, HttpStatusCode.Unauthorized)
            }
        }
        val cookies = MemoryCookieStore("old")
        var expired = false
        val api = AuthApi(PapoClient(engine, server, cookies) { expired = true })

        assertEquals("u1", api.whoami().id)
        assertEquals("new", cookies.get())
        assertEquals(listOf("/auth/whoami Auth=old", "/auth/refresh Auth=old", "/auth/whoami Auth=new"), paths)
        assertTrue(!expired)
    }

    @Test
    fun requisicoesConcorrentesCompartilhamUmUnicoRefresh() = runTest {
        var refreshes = 0
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath == "/auth/refresh" -> {
                    refreshes++
                    delay(50)
                    json("{}", cookie = "Auth=new; Path=/")
                }
                request.cookie() == "Auth=new" -> json(whoami)
                else -> json(problem, HttpStatusCode.Unauthorized)
            }
        }
        val api = AuthApi(PapoClient(engine, server, MemoryCookieStore("old")))

        val results = List(8) { async { api.whoami().id } }.awaitAll()
        assertEquals(List(8) { "u1" }, results)
        assertEquals(1, refreshes)
    }

    @Test
    fun refreshNegadoEncerraASessao() = runTest {
        val engine = MockEngine { json(problem, HttpStatusCode.Unauthorized) }
        val cookies = MemoryCookieStore("old")
        var expired = 0
        val api = AuthApi(PapoClient(engine, server, cookies) { expired++ })

        val error = assertFailsWith<ApiException> { api.whoami() }
        assertEquals(401, error.status)
        assertEquals("Token expirado", error.message)
        assertNull(cookies.get())
        assertEquals(1, expired)
    }

    @Test
    fun credenciaisErradasNaoDisparamRefresh() = runTest {
        val paths = mutableListOf<String>()
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            json(problem, HttpStatusCode.Unauthorized)
        }
        // Cookie temporário de /auth/login_server presente: mesmo assim o 401 do login é definitivo.
        val api = AuthApi(PapoClient(engine, server, MemoryCookieStore("temp")))

        assertFailsWith<ApiException> { api.login("ana", "errada") }
        assertEquals(listOf("/auth/login"), paths)
    }

    @Test
    fun logoutLimpaOCookie() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.NoContent, headersOf(HttpHeaders.SetCookie, "Auth=; Path=/; Max-Age=0")) }
        val cookies = MemoryCookieStore("jwt")
        AuthApi(PapoClient(engine, server, cookies)).logout()
        assertNull(cookies.get())
    }

    @Test
    fun erroDoBackendViraApiExceptionComDetalhe() = runTest {
        val engine = MockEngine {
            json("""{"type":"t","title":"Conflito","status":409,"detail":"Username já existe"}""", HttpStatusCode.Conflict)
        }
        val error = assertFailsWith<ApiException> {
            AuthApi(PapoClient(engine, server, MemoryCookieStore())).register("ana", "Senha@123")
        }
        assertTrue(error.isConflict)
        assertEquals("Username já existe", error.detail)
    }
}
