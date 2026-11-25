package chat.papo.mobile

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Instant

/**
 * Servidor falso com o canal `c1` e as mensagens m1..m[total], paginado como o backend: cursor
 * `since` + `last_id` nas duas direções. A mensagem `n` foi criada `n` segundos depois da meia-noite.
 */
class PagedChannel(var total: Int) {
    /** Cada requisição como `METODO /caminho?consulta`, na ordem em que chegou. */
    val requests = CopyOnWriteArrayList<String>()

    /** A mensagem `n` responde à mensagem com este id (que pode já não existir). */
    val replies = ConcurrentHashMap<Int, String>()

    /** Mensagens que o servidor já não tem: somem das páginas sem mudar o número das outras. */
    val deleted: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /** Cada reação enviada, como `METODO id corpo`. */
    val reactions = CopyOnWriteArrayList<String>()

    /** As leituras com cursor (página para trás ou para frente) respondem 500 enquanto for true. */
    @Volatile var failPages = false

    /** Os pedidos de reação respondem 403 enquanto for true. */
    @Volatile var failReactions = false

    /** Quando não nulo, cada pedido de reação espera por ele antes de responder. */
    @Volatile var holdReactions: CompletableDeferred<Unit>? = null

    /** Um aviso para cada pedido de reação que chegou, antes de ele esperar por [holdReactions]. */
    val reactionArrived = Channel<String>(Channel.UNLIMITED)

    /** Um aviso para cada leitura de página com cursor que chegou, antes de ela esperar por [holdPages]. */
    val pageArrived = Channel<String>(Channel.UNLIMITED)

    /** Quando não nulo, cada leitura de página com cursor espera por ele antes de responder. */
    @Volatile var holdPages: CompletableDeferred<Unit>? = null

    fun ts(n: Int) = "2026-01-01T00:%02d:%02dZ".format(n / 60, n % 60)

    private fun seconds(iso: String): Double {
        val instant = Instant.parse(iso)
        return (instant.epochSeconds - Instant.parse("2026-01-01T00:00:00Z").epochSeconds) + instant.nanosecondsOfSecond / 1e9
    }

    private fun message(n: Int, content: String = "msg $n", author: String = "u2") =
        """{"id":"m$n","channel_id":"c1","author_id":"$author","content":"$content","created_at":"${ts(n)}"""" +
            (replies[n]?.let { ""","reply_to":"$it"""" } ?: "") + "}"

    val engine = MockEngine { request ->
        val path = request.url.encodedPath
        val query = request.url.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
        requests += request.method.value + " " + path + query
        fun json(text: String, status: HttpStatusCode = HttpStatusCode.OK) =
            respond(text, status, headersOf(HttpHeaders.ContentType, if (status.value >= 400) "application/problem+json" else "application/json"))
        when {
            path == "/channels/c1/messages" -> {
                val since = request.url.parameters["since"]?.let { seconds(it) }
                if (since != null) {
                    pageArrived.trySend(query)
                    holdPages?.await()
                    if (failPages) return@MockEngine json("""{"type":"t","title":"Erro","status":500}""", HttpStatusCode.InternalServerError)
                }
                val limit = request.url.parameters["limit"]!!.toInt()
                val asc = request.url.parameters["order"] == "asc"
                val lastId = request.url.parameters["last_id"]
                val matching = (1..total).filter { n ->
                    n !in deleted && when {
                        since == null -> true
                        asc -> n > since || (n.toDouble() == since && "m$n" > lastId!!)
                        else -> n < since || (n.toDouble() == since && "m$n" < lastId!!)
                    }
                }
                val ordered = if (asc) matching else matching.reversed()
                json("""{"messages":[${ordered.take(limit).joinToString(",") { message(it) }}],"has_more":${ordered.size > limit}}""")
            }
            path == "/channels/c1/pinned" -> json("""{"channel_id":"c1","pinned":[]}""")
            Regex("/channels/c1/messages/m\\d+/reactions").matches(path) -> {
                val id = path.substringAfter("/messages/").substringBefore('/')
                val entry = "${request.method.value} $id ${request.body.toByteArray().decodeToString()}"
                reactions += entry
                reactionArrived.trySend(entry)
                holdReactions?.await()
                if (failReactions) json("""{"type":"t","title":"Proibido","status":403}""", HttpStatusCode.Forbidden) else respond("", HttpStatusCode.NoContent)
            }
            else -> respond("", HttpStatusCode.NoContent)
        }
    }
}
