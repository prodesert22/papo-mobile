package chat.papo.mobile

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Instant

/**
 * A caixa de notificações de um usuário, paginada como o backend: ordem `(created_at, id)` decrescente, `since` com
 * `last_id` como cursor e `since` sozinho como "só as mais novas". [pageSize] é pequeno para as páginas caberem no teste.
 */
class NotificationServer(val userId: String = "u1", @Volatile var pageSize: Int = 100) {
    class Row(val id: String, val messageId: String, val channelId: String, val content: String, @Volatile var read: Boolean, val createdAt: String)

    val rows = CopyOnWriteArrayList<Row>()

    /** Cada requisição como `METODO /caminho?consulta`, na ordem em que chegou. */
    val requests = CopyOnWriteArrayList<String>()

    /** Os ids de cada `PUT read_notification` que chegou. */
    val reads = CopyOnWriteArrayList<List<String>>()

    /** O status do `PUT read_notification`: 200 marca as linhas, 404 não casou nenhuma, outro valor é falha. */
    @Volatile var readStatus = 200

    /** Se false, o 200 do `PUT` não marca nada no servidor (simula uma leitura de lista que sai atrasada). */
    @Volatile var applyReads = true

    /** Status de erro das leituras da lista enquanto não for nulo. */
    @Volatile var listStatus: Int? = null

    /** Quando não nulo, cada leitura da lista espera por ele antes de responder. */
    @Volatile var holdList: CompletableDeferred<Unit>? = null

    /** Quando não nulo, cada `PUT read_notification` espera por ele antes de responder (depois de registrado). */
    @Volatile var holdRead: CompletableDeferred<Unit>? = null

    /** Enquanto for true o `PUT read_notification` falha como uma conexão que caiu, sem resposta HTTP. */
    @Volatile var readDropped = false

    /** `GET /dms/{id}`: o corpo de cada conversa que o servidor conhece, por id; sem entrada, 404. */
    val dmBodies = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Status de erro de `GET /dms/{id}` enquanto não for nulo (ex.: 403 com `type` terminando em `/dm-blocked`). */
    @Volatile var dmStatus: Int? = null

    /** Quando não nulo, cada `GET /dms/{id}` espera por ele antes de responder. */
    @Volatile var holdDm: CompletableDeferred<Unit>? = null

    fun dmJson(id: String, userId: String = "u2", name: String = "bia") =
        """{"id":"$id","user":{"id":"$userId","username":"$name"},"created_at":"2026-01-01T00:00:00Z","unread_count":0}"""

    fun ts(n: Int) = "2026-01-01T%02d:%02d:%02dZ".format(n / 3600, n / 60 % 60, n % 60)

    /** Uma linha da caixa, criada [n] segundos depois da meia-noite. */
    fun add(id: String, n: Int, channel: String = "c1", content: String = "oi", read: Boolean = false, messageId: String = "m-$id"): Row =
        Row(id, messageId, channel, content, read, ts(n)).also { rows += it }

    fun count(prefix: String) = requests.count { it.startsWith(prefix) }

    val listRequests get() = requests.filter { it.startsWith("GET /users/$userId/notifications") }

    private fun key(row: Row) = Instant.parse(row.createdAt).toEpochMilliseconds() to row.id

    val engine = MockEngine { request ->
        val path = request.url.encodedPath
        // Os parâmetros já decodificados: o horário traz `:`, que o texto da URL escreve como `%3A`.
        val query = request.url.parameters.entries().joinToString("&") { (name, values) -> values.joinToString("&") { "$name=$it" } }
            .takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
        requests += request.method.value + " " + path + query
        fun json(text: String, status: HttpStatusCode = HttpStatusCode.OK) =
            respond(text, status, headersOf(HttpHeaders.ContentType, if (status.value >= 400) "application/problem+json" else "application/json"))
        fun problem(status: Int) = json("""{"type":"t","title":"Erro","status":$status}""", HttpStatusCode.fromValue(status))
        when {
            request.method == HttpMethod.Get && path == "/users/$userId/notifications" -> {
                holdList?.await()
                listStatus?.let { return@MockEngine problem(it) }
                val since = request.url.parameters["since"]?.let { Instant.parse(it).toEpochMilliseconds() }
                val lastId = request.url.parameters["last_id"]
                val matching = rows.filter { row ->
                    val (t, id) = key(row)
                    when {
                        since == null -> true
                        lastId == null -> t > since
                        else -> t < since || (t == since && id < lastId)
                    }
                }.sortedWith(compareByDescending<Row> { key(it).first }.thenByDescending { it.id })
                val page = matching.take(pageSize)
                val body = page.joinToString(",") {
                    """{"id":"${it.id}","message_id":"${it.messageId}","channel_id":"${it.channelId}","author_id":"u2",""" +
                        """"message_content":"${it.content}","read":${it.read},"created_at":"${it.createdAt}"}"""
                }
                json("""{"notifications":[$body],"has_more":${matching.size > pageSize}}""")
            }
            request.method == HttpMethod.Put && path == "/users/$userId/read_notification" -> {
                val ids = Regex(""""([^"]+)"""").findAll(request.body.toByteArray().decodeToString().substringAfter('['))
                    .map { it.groupValues[1] }.toList()
                reads += ids
                holdRead?.await()
                if (readDropped) throw java.io.IOException("conexão caiu")
                when (readStatus) {
                    200 -> {
                        if (applyReads) rows.filter { it.id in ids }.forEach { it.read = true }
                        json("""{"updated":${ids.size}}""")
                    }
                    else -> problem(readStatus)
                }
            }
            request.method == HttpMethod.Get && path.startsWith("/dms/") -> {
                holdDm?.await()
                val status = dmStatus
                when {
                    status == 403 -> json("""{"type":"https://papo.test/errors/dm-blocked","title":"Acesso negado","status":403}""", HttpStatusCode.Forbidden)
                    status != null -> problem(status)
                    else -> dmBodies[path.removePrefix("/dms/")]?.let { json(it) } ?: problem(404)
                }
            }
            else -> respond("", HttpStatusCode.NoContent)
        }
    }
}
