package chat.papo.mobile.data.api

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CredentialsRequest(val username: String, val password: String)

@Serializable
data class LoginServerRequest(@SerialName("server_password") val serverPassword: String)

@Serializable
data class LoginUser(val id: String, val username: String)

@Serializable
data class LoginResponse(
    val user: LoginUser? = null,
    @SerialName("connection_violation") val connectionViolation: Boolean = false,
)

@Serializable
data class RoleSummaryDto(val id: String, val name: String = "", val color: String? = null)

@Serializable
data class NotificationsConfig(
    val enabled: Boolean = true,
    val messagePreview: Boolean = true,
    val sound: Boolean = true,
    val mentions: Boolean = true,
)

@Serializable
data class DisplayConfig(
    val fontSize: String = "medium",
    val messageDensity: String = "normal",
    val showTimestamps: Boolean = true,
    val showAvatars: Boolean = true,
)

@Serializable
data class UserConfig(
    val theme: String = "system",
    val notifications: NotificationsConfig = NotificationsConfig(),
    val display: DisplayConfig = DisplayConfig(),
)

@Serializable
data class WhoamiSettings(val version: Int = 0, val config: UserConfig = UserConfig())

@Serializable
data class WhoamiResponse(
    val id: String,
    val username: String,
    val nickname: String? = null,
    @SerialName("avatar_blob") val avatarBlob: String? = null,
    @SerialName("avatar_format") val avatarFormat: String? = null,
    val status: String? = null,
    @SerialName("status_message") val statusMessage: String? = null,
    val typing: String? = null,
    val roles: List<RoleSummaryDto> = emptyList(),
    val settings: WhoamiSettings = WhoamiSettings(),
    @SerialName("connection_violation") val connectionViolation: Boolean = false,
)

@Serializable
data class ConnectionDto(
    val id: String,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
data class RefreshResponse(val connection: ConnectionDto? = null)

@Serializable
data class ConnectedDevicesResponse(val connections: List<ConnectionDto> = emptyList())

@Serializable
data class DropConnectionRequest(@SerialName("connection_id") val connectionId: String)

@Serializable
data class DropConnectionResponse(val dropped: Int = 0)

@Serializable
data class ServerDto(
    val id: String? = null,
    val name: String = "",
    @SerialName("icon_blob") val iconBlob: String? = null,
    @SerialName("icon_format") val iconFormat: String? = null,
    @SerialName("owner_id") val ownerId: String? = null,
    val public: Boolean = false,
    @SerialName("owner_username") val ownerUsername: String? = null,
    @SerialName("channel_count") val channelCount: Int = 0,
    @SerialName("member_count") val memberCount: Int = 0,
)

class AuthApi(private val client: PapoClient) {
    private val http get() = client.http
    private fun path(p: String) = client.server.endpoint(p)

    suspend fun health() {
        http.get { url(path("/health")); attributes.put(NoAuthRetry, Unit) }.requireSuccess()
    }

    /** Público: funciona sem sessão. */
    suspend fun server(): ServerDto =
        http.get { url(path("/server")); attributes.put(NoAuthRetry, Unit) }.bodyOrThrow()

    /** Autorização temporária (30 min) exigida por servidores não públicos antes de login/registro. */
    suspend fun loginServer(serverPassword: String) {
        http.post {
            url(path("/auth/login_server"))
            attributes.put(NoAuthRetry, Unit)
            contentType(ContentType.Application.Json)
            setBody(LoginServerRequest(serverPassword))
        }.requireSuccess()
    }

    suspend fun login(username: String, password: String): LoginResponse =
        http.post {
            url(path("/auth/login"))
            attributes.put(NoAuthRetry, Unit)
            contentType(ContentType.Application.Json)
            setBody(CredentialsRequest(username, password))
        }.bodyOrThrow()

    suspend fun register(username: String, password: String) {
        http.post {
            url(path("/auth/register"))
            attributes.put(NoAuthRetry, Unit)
            contentType(ContentType.Application.Json)
            setBody(CredentialsRequest(username, password))
        }.requireSuccess()
    }

    suspend fun whoami(): WhoamiResponse = http.get { url(path("/auth/whoami")) }.bodyOrThrow()

    suspend fun logout() {
        http.post { url(path("/auth/logout")); attributes.put(NoAuthRetry, Unit) }.requireSuccess()
    }

    suspend fun refresh(): RefreshResponse =
        http.post { url(path("/auth/refresh")); attributes.put(NoAuthRetry, Unit) }.bodyOrThrow()

    suspend fun connectedDevices(): List<ConnectionDto> =
        http.get { url(path("/auth/connected_devices")) }.bodyOrThrow<ConnectedDevicesResponse>().connections

    /** [connectionId] é o UUID da conexão ou "ALL". */
    suspend fun dropConnection(connectionId: String): Int =
        http.post {
            url(path("/auth/drop_connection"))
            contentType(ContentType.Application.Json)
            setBody(DropConnectionRequest(connectionId))
        }.bodyOrThrow<DropConnectionResponse>().dropped
}
