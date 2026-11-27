package chat.papo.mobile.data.api

import io.ktor.client.request.delete
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.plugins.onUpload
import io.ktor.client.plugins.timeout
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Endpoints de conversa: canais, DMs, usuários, mensagens, reações, pins e mídia. */
class ChatApi(private val client: PapoClient) {
    private val http get() = client.http
    private fun path(p: String) = client.server.endpoint(p)

    suspend fun channels(): List<ChannelDto> =
        http.get { url(path("/channels")) }.bodyOrThrow<ChannelsResponse>().channels

    /** Cargos do servidor com as flags. Só pede login (não é `/auth`); a lista inteira vem de uma vez, sem paginação. */
    suspend fun roles(): List<RoleDto> =
        http.get { url(path("/roles")) }.bodyOrThrow<RolesResponse>().roles

    suspend fun dms(): List<DirectConversationDto> =
        http.get { url(path("/dms")) }.bodyOrThrow<DmsResponse>().dms

    suspend fun openDm(userId: String): DirectConversationDto =
        http.post {
            url(path("/dms"))
            contentType(ContentType.Application.Json)
            setBody(OpenDmRequest(userId))
        }.bodyOrThrow()

    suspend fun closeDm(dmId: String) = http.delete { url(path("/dms/$dmId")) }.requireSuccess()

    suspend fun users(since: String? = null, lastId: String? = null): UsersPage =
        http.get {
            url(path("/users"))
            parameter("order", "asc")
            since?.let { parameter("since", it) }
            lastId?.let { parameter("last_id", it) }
        }.bodyOrThrow()

    suspend fun profile(userId: String): UserProfileDto =
        http.get { url(path("/users/$userId/profile")) }.bodyOrThrow()

    /** Atualiza apelido, frase de status e descrição. Só a própria conta. */
    suspend fun updateProfile(userId: String, request: UpdateProfileRequest) {
        http.put {
            url(path("/users/$userId"))
            contentType(ContentType.Application.Json)
            setBody(request)
        }.requireSuccess()
    }

    /** Troca o avatar (base64, até 2 MB e 512 px). O corpo é grande: tempo maior que o de uma requisição comum. */
    suspend fun updateAvatar(userId: String, request: UpdateAvatarRequest) {
        http.put {
            url(path("/users/$userId/avatar"))
            contentType(ContentType.Application.Json)
            setBody(request)
            timeout { requestTimeoutMillis = 2 * 60_000; socketTimeoutMillis = 60_000 }
        }.requireSuccess()
    }

    /** Troca o banner (até 2 MB e 2048 px); com os dois campos vazios, remove-o. */
    suspend fun updateBanner(userId: String, request: UpdateBannerRequest) {
        http.put {
            url(path("/users/$userId/banner"))
            contentType(ContentType.Application.Json)
            setBody(request)
            timeout { requestTimeoutMillis = 2 * 60_000; socketTimeoutMillis = 60_000 }
        }.requireSuccess()
    }

    /** `away`, `busy` ou null (disponível). */
    suspend fun updateStatus(userId: String, status: String?) {
        http.put {
            url(path("/users/$userId/status"))
            contentType(ContentType.Application.Json)
            setBody(UpdateStatusRequest(status))
        }.requireSuccess()
    }

    /** Usuários que a conta bloqueou. */
    suspend fun blocks(): List<UserSummaryDto> =
        http.get { url(path("/users/blocks")) }.bodyOrThrow<BlocksResponse>().users

    /** Idempotente (204). Vale nas duas direções para mensagens diretas; canais não mudam. */
    suspend fun block(userId: String) = http.post { url(path("/users/$userId/block")) }.requireSuccess()

    /** Idempotente (204). Não reabre uma DM fechada. */
    suspend fun unblock(userId: String) = http.delete { url(path("/users/$userId/block")) }.requireSuccess()

    /** Passo 1 da troca de senha: marca a conta para aceitar `PUT /users/{id}/password`. */
    suspend fun prepareOwnPasswordChange(userId: String) {
        http.post { url(path("/users/$userId/reset")) }.requireSuccess()
    }

    /** Passo 2. Encerra todas as sessões da conta, inclusive esta. */
    suspend fun changePassword(userId: String, password: String) {
        http.put {
            url(path("/users/$userId/password"))
            contentType(ContentType.Application.Json)
            setBody(ChangePasswordRequest(password))
        }.requireSuccess()
    }

    /** Bytes do avatar, ou null se o usuário não tem um. */
    suspend fun avatar(userId: String): ByteArray? = bytesOrNull("/users/$userId/avatar")

    /** Bytes do avatar persistente de um webhook (rota pública, sem cookie), ou null se ele não tem um. */
    suspend fun webhookAvatar(webhookId: String): ByteArray? = bytesOrNull("/webhooks/$webhookId/avatar")

    suspend fun messages(
        channelId: String,
        since: String? = null,
        lastId: String? = null,
        order: String = "desc",
        limit: Int = 30,
    ): MessageListResponse =
        http.get {
            url(path("/channels/$channelId/messages"))
            parameter("order", order)
            parameter("limit", limit)
            since?.let { parameter("since", it) }
            lastId?.let { parameter("last_id", it) }
        }.bodyOrThrow()

    /**
     * Envia uma mensagem. Os arquivos vão do [OutgoingFile.open] direto para o corpo, em fluxo, sem virar `ByteArray`;
     * o fluxo é aberto de novo se o cliente repete o pedido depois de renovar a sessão. [onProgress] recebe os bytes
     * do corpo já entregues ao motor HTTP e o total (null se o motor não o conhece: algum arquivo sem tamanho faz o
     * corpo sair em pedaços); quando o total é alcançado, o servidor pode ter recebido tudo. Sai do zero de novo se
     * o cliente repete o pedido. Lança [FileUnavailableException] se um arquivo não abre ou falha na leitura.
     */
    suspend fun sendMessage(
        channelId: String,
        content: String,
        replyTo: String?,
        files: List<OutgoingFile>,
        onProgress: ((sent: Long, total: Long?) -> Unit)? = null,
    ): MessageDto {
        val opened = OpenedFiles()
        try {
            return http.submitFormWithBinaryData(
                url = path("/messages"),
                formData = formData {
                    append("channel_id", channelId)
                    if (content.isNotEmpty()) append("content", content)
                    replyTo?.let { append("reply_to", it) }
                    files.forEach { file ->
                        append(
                            "attachments",
                            ChannelProvider(file.size) { opened.open(file) },
                            Headers.build {
                                append(HttpHeaders.ContentType, file.mimeType)
                                append(HttpHeaders.ContentDisposition, "filename=\"${file.name.replace("\"", "")}\"")
                            },
                        )
                    }
                },
            ) {
                // Envio de anexos pode demorar bem mais que uma requisição comum.
                timeout { requestTimeoutMillis = 10 * 60_000; socketTimeoutMillis = 120_000 }
                if (onProgress != null) onUpload { sent, total -> onProgress(sent, total) }
            }.bodyOrThrow()
        } catch (e: Throwable) {
            // Quem cancelou o envio segue cancelando. O motor pode embrulhar o que o arquivo lançou (o OkHttp o
            // esconde numa IOException), então a falha do arquivo vale mais que o que chegou aqui.
            currentCoroutineContext().ensureActive()
            throw opened.failure ?: e.fileFailure() ?: e
        } finally {
            opened.closeAll()
        }
    }

    suspend fun editMessage(messageId: String, content: String): MessageDto =
        http.put {
            url(path("/messages/$messageId"))
            contentType(ContentType.Application.Json)
            setBody(UpdateMessageRequest(content))
        }.bodyOrThrow()

    suspend fun deleteMessage(messageId: String) = http.delete { url(path("/messages/$messageId")) }.requireSuccess()

    suspend fun markRead(channelId: String, messageId: String) =
        http.put {
            url(path("/channels/$channelId/read"))
            contentType(ContentType.Application.Json)
            setBody(MarkReadRequest(messageId))
        }.requireSuccess()

    suspend fun pin(channelId: String, messageId: String) =
        http.post { url(path("/channels/$channelId/messages/$messageId/pin")) }.requireSuccess()

    suspend fun unpin(channelId: String, messageId: String) =
        http.delete { url(path("/channels/$channelId/messages/$messageId/pin")) }.requireSuccess()

    suspend fun pinned(channelId: String): List<MessageDto> =
        http.get { url(path("/channels/$channelId/pinned")) }.bodyOrThrow<PinnedMessagesResponse>().pinned

    suspend fun react(channelId: String, messageId: String, reaction: ReactionRequest) =
        http.post {
            url(path("/channels/$channelId/messages/$messageId/reactions"))
            contentType(ContentType.Application.Json)
            setBody(reaction)
        }.requireSuccess()

    suspend fun unreact(channelId: String, messageId: String, reaction: ReactionRequest) =
        http.delete {
            url(path("/channels/$channelId/messages/$messageId/reactions"))
            contentType(ContentType.Application.Json)
            setBody(reaction)
        }.requireSuccess()

    /**
     * Uma página de quem reagiu, da reação mais nova à mais antiga: o servidor manda até 100 por página, sem
     * `limit` nem filtro por emoji. A página seguinte leva o cursor da anterior, [since] e [lastId], que vão
     * juntos ou nenhum: `since` sozinho, mesmo em `desc`, o servidor lê como "criado depois" e volta ao começo.
     */
    suspend fun reactions(channelId: String, messageId: String, since: String? = null, lastId: String? = null): ReactionListDto =
        http.get {
            url(path("/channels/$channelId/messages/$messageId/reactions"))
            parameter("order", "desc")
            if (since != null && lastId != null) {
                parameter("since", since)
                parameter("last_id", lastId)
            }
        }.bodyOrThrow()

    /**
     * Busca mensagens no servidor, até 100 por página. A página seguinte leva o
     * `created_at` e o `id` do último resultado; o servidor recusa o cursor pela metade.
     */
    suspend fun search(request: SearchRequest, since: String? = null, lastId: String? = null): SearchResponse =
        http.post {
            url(path("/search"))
            if (since != null && lastId != null) {
                parameter("since", since)
                parameter("last_id", lastId)
            }
            contentType(ContentType.Application.Json)
            setBody(request)
        }.bodyOrThrow()

    suspend fun emojis(since: String? = null, lastId: String? = null): EmojisPage =
        http.get {
            url(path("/emojis"))
            parameter("order", "asc")
            since?.let { parameter("since", it) }
            lastId?.let { parameter("last_id", it) }
        }.bodyOrThrow()

    suspend fun iceServers(): List<IceServerDto> =
        http.get { url(path("/voice/ice-servers")) }.bodyOrThrow<IceServersResponse>().iceServers

    suspend fun attachment(fileId: String): ByteArray = bytes("/attachments/$fileId")

    suspend fun attachmentThumbnail(fileId: String): ByteArray = bytes("/attachments/$fileId/thumbnail")

    suspend fun media(sha: String): ByteArray = bytes("/media/$sha")

    /** `GET /embeds/{id}`: a imagem do embed e o ícone do autor, em base64, num pedido só. */
    suspend fun embed(embedId: String): EmbedWithImageDto =
        http.get { url(path("/embeds/$embedId")) }.bodyOrThrow()

    fun attachmentUrl(fileId: String): String = path("/attachments/$fileId")

    private suspend fun bytes(p: String): ByteArray {
        val response = http.get { url(path(p)) }
        if (!response.status.isSuccess()) throw response.toApiException()
        return response.readRawBytes()
    }

    private suspend fun bytesOrNull(p: String): ByteArray? {
        val response = http.get { url(path(p)) }
        return when {
            response.status.isSuccess() -> response.readRawBytes()
            response.status.value == 404 -> null
            else -> throw response.toApiException()
        }
    }
}

/**
 * Abre os arquivos de um envio, que o motor HTTP lê na thread dele, e é quem os fecha: uma leitura interrompida
 * (cancelamento, rede que caiu) não fecha o fluxo do provedor. Guarda a falha do primeiro arquivo que não abriu.
 */
private class OpenedFiles {
    @Volatile var failure: FileUnavailableException? = null
        private set

    @Volatile private var channels: List<ByteReadChannel> = emptyList()

    fun open(file: OutgoingFile): ByteReadChannel {
        val channel = try {
            file.open()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw FileUnavailableException(file.name, e).also { failure = failure ?: it }
        }
        channels += channel
        return channel
    }

    /** Fecha o que ficou aberto; um fluxo já lido até o fim ignora o pedido. */
    fun closeAll() {
        val open = channels
        channels = emptyList()
        for (channel in open) {
            try {
                channel.cancel()
            } catch (e: Exception) {
                // O fluxo está sendo descartado: uma falha ao fechá-lo não muda o resultado do envio.
            }
        }
    }
}

private fun Throwable.fileFailure(): FileUnavailableException? =
    generateSequence(this) { it.cause }.take(MAX_CAUSE_DEPTH).filterIsInstance<FileUnavailableException>().firstOrNull()

private const val MAX_CAUSE_DEPTH = 10
