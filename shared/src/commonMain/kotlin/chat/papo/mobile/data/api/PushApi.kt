package chat.papo.mobile.data.api

import io.ktor.client.request.delete
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** O corpo de `PUT` e `DELETE /users/me/push-device`. O `DELETE` só usa o `token`, mas leva o mesmo corpo (OpenAPI). */
@Serializable
data class PushDeviceRequest(
    val token: String,
    val platform: String,
    @SerialName("device_name") val deviceName: String,
    val provider: String,
)

/** Registro do aparelho para receber push (`/users/me/push-device`). Cada chamada é uma só tentativa: quem chama decide. */
class PushApi(private val client: PapoClient) {
    private val http get() = client.http
    private fun path(p: String) = client.server.endpoint(p)

    private fun body(token: String, deviceName: String) = PushDeviceRequest(token, PLATFORM, deviceName, PROVIDER)

    /** Registra ou atualiza o aparelho do usuário da sessão (upsert por token). 204; 400 campo inválido; 401. */
    suspend fun register(token: String, deviceName: String) {
        http.put {
            url(path("/users/me/push-device"))
            contentType(ContentType.Application.Json)
            setBody(body(token, deviceName))
        }.requireSuccess()
    }

    /**
     * Tira o aparelho do usuário da sessão. 204; 404 se o token não é dele (nunca registrado, ou já passou a outra conta
     * do mesmo servidor); 401. Sem renovar a sessão num 401: quem tira o registro está saindo, e as rotas de autenticação têm limite.
     */
    suspend fun unregister(token: String, deviceName: String) {
        http.delete {
            url(path("/users/me/push-device"))
            attributes.put(NoAuthRetry, Unit)
            contentType(ContentType.Application.Json)
            setBody(body(token, deviceName))
        }.requireSuccess()
    }

    private companion object {
        const val PLATFORM = "android"
        const val PROVIDER = "fcm"
    }
}
