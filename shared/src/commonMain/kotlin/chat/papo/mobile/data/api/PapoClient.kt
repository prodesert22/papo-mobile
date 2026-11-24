package chat.papo.mobile.data.api

import chat.papo.mobile.account.ServerUrl
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.plugin
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.post
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.AttributeKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val PapoJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    isLenient = true
}

/** Onde fica o valor do cookie `Auth` de uma conta. */
interface AuthCookieStore {
    fun get(): String?
    suspend fun set(cookie: String?)
}

class MemoryCookieStore(private var value: String? = null) : AuthCookieStore {
    override fun get(): String? = value
    override suspend fun set(cookie: String?) {
        value = cookie
    }
}

/** Erro devolvido pelo backend (RFC 7807). */
class ApiException(
    val status: Int,
    val title: String?,
    val detail: String?,
    val type: String?,
) : Exception(detail ?: title ?: "HTTP $status") {
    val isUnauthorized get() = status == 401
    val isForbidden get() = status == 403
    val isNotFound get() = status == 404
    val isConflict get() = status == 409
}

@Serializable
private data class ProblemDetails(
    val type: String? = null,
    val title: String? = null,
    val status: Int? = null,
    val detail: String? = null,
)

/** Requisições marcadas com este atributo não disparam `/auth/refresh` ao receber 401. */
internal val NoAuthRetry = AttributeKey<Unit>("PapoNoAuthRetry")

/**
 * Cliente HTTP de um servidor. A sessão do backend vive só no cookie HttpOnly
 * `Auth`: este cliente o captura de `Set-Cookie`, reenvia em toda requisição e,
 * ao receber 401, renova a sessão em `/auth/refresh` uma única vez e repete.
 */
class PapoClient(
    engine: HttpClientEngine,
    val server: ServerUrl,
    val cookies: AuthCookieStore,
    private val onSessionExpired: suspend () -> Unit = {},
) {
    private val refreshMutex = Mutex()

    val http: HttpClient = HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) { json(PapoJson) }
        install(WebSockets)
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000
        }
    }

    init {
        http.plugin(HttpSend).intercept { request ->
            val sent = cookies.get()
            request.applyCookie(sent)
            var call = execute(request)
            capture(call.response)

            val retry = call.response.status == HttpStatusCode.Unauthorized &&
                sent != null && !request.attributes.contains(NoAuthRetry)
            if (retry && refresh(sent)) {
                runCatching { call.response.bodyAsText() }
                request.applyCookie(cookies.get())
                call = execute(request)
                capture(call.response)
            }
            call
        }
    }

    fun close() = http.close()

    /** Valor do header `Cookie` para conexões fora deste cliente (WebSocket, player de mídia). */
    fun cookieHeader(): String? = cookies.get()?.let { "$COOKIE_NAME=$it" }

    private fun HttpRequestBuilder.applyCookie(value: String?) {
        if (value != null) headers[HttpHeaders.Cookie] = "$COOKIE_NAME=$value" else headers.remove(HttpHeaders.Cookie)
    }

    private suspend fun capture(response: HttpResponse) {
        val headers = response.headers.getAll(HttpHeaders.SetCookie) ?: return
        for (raw in headers) {
            val cookie = parseAuthCookie(raw) ?: continue
            cookies.set(cookie.value.takeUnless { cookie.expired })
        }
    }

    /** Devolve true quando há um cookie novo para repetir a requisição. */
    private suspend fun refresh(staleCookie: String): Boolean = refreshMutex.withLock {
        val current = cookies.get() ?: return@withLock false
        if (current != staleCookie) return@withLock true // outra requisição já renovou

        val response = http.post {
            url(server.endpoint("/auth/refresh"))
            attributes.put(NoAuthRetry, Unit)
        }
        runCatching { response.bodyAsText() }
        when {
            response.status.isSuccess() -> cookies.get().let { it != null && it != staleCookie }
            response.status == HttpStatusCode.Unauthorized -> {
                cookies.set(null)
                onSessionExpired()
                false
            }
            else -> false
        }
    }

    companion object {
        const val COOKIE_NAME = "Auth"
    }
}

internal data class AuthCookie(val value: String, val expired: Boolean)

/** Extrai o cookie `Auth` de um header `Set-Cookie`; null se for outro cookie. */
internal fun parseAuthCookie(header: String): AuthCookie? {
    val parts = header.split(';')
    val first = parts.first().trim()
    val eq = first.indexOf('=')
    if (eq <= 0 || first.substring(0, eq).trim() != PapoClient.COOKIE_NAME) return null
    val value = first.substring(eq + 1).trim().removeSurrounding("\"")
    val maxAge = parts.drop(1)
        .map { it.trim() }
        .firstOrNull { it.startsWith("max-age=", ignoreCase = true) }
        ?.substringAfter('=')?.trim()?.toLongOrNull()
    return AuthCookie(value, expired = value.isEmpty() || (maxAge != null && maxAge <= 0))
}

suspend fun HttpResponse.toApiException(): ApiException {
    val text = runCatching { bodyAsText() }.getOrNull().orEmpty()
    val problem = runCatching { PapoJson.decodeFromString<ProblemDetails>(text) }.getOrNull()
    return ApiException(status.value, problem?.title, problem?.detail, problem?.type)
}

suspend inline fun <reified T> HttpResponse.bodyOrThrow(): T {
    if (!status.isSuccess()) throw toApiException()
    return body()
}

suspend fun HttpResponse.requireSuccess() {
    if (!status.isSuccess()) throw toApiException()
    runCatching { bodyAsText() }
}
