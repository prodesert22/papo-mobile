package chat.papo.mobile.ui.components

import androidx.compose.runtime.Composable
import chat.papo.mobile.account.InvalidServerUrl
import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.chat.isDmBlockedType
import chat.papo.mobile.data.api.ApiException
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.error_dm_blocked
import chat.papo.mobile.resources.error_network
import chat.papo.mobile.resources.error_not_papo
import chat.papo.mobile.resources.error_url_credentials
import chat.papo.mobile.resources.error_url_empty
import chat.papo.mobile.resources.error_url_host
import chat.papo.mobile.resources.error_url_path
import chat.papo.mobile.resources.error_url_port
import chat.papo.mobile.resources.error_url_scheme
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import org.jetbrains.compose.resources.stringResource

/** Erro pronto para mostrar ao usuário. */
sealed interface UiError {
    /** Mensagem vinda do servidor (já em linguagem natural). */
    data class Server(val message: String) : UiError
    data class Url(val problem: ServerUrl.Problem) : UiError
    data object Network : UiError
    data object NotPapo : UiError

    /** Há um bloqueio entre as duas pessoas (qualquer um dos dois bloqueou o outro): não há conversa direta. */
    data object DmBlocked : UiError
}

fun Throwable.toUiError(): UiError = when (this) {
    is CancellationException -> throw this
    is ApiException -> if (isForbidden && isDmBlockedType(type)) UiError.DmBlocked else UiError.Server(detail ?: title ?: "HTTP $status")
    is InvalidServerUrl -> UiError.Url(problem)
    is SerializationException -> UiError.NotPapo
    else -> UiError.Network
}

@Composable
fun UiError.text(): String = when (this) {
    is UiError.Server -> message
    UiError.Network -> stringResource(Res.string.error_network)
    UiError.NotPapo -> stringResource(Res.string.error_not_papo)
    UiError.DmBlocked -> stringResource(Res.string.error_dm_blocked)
    is UiError.Url -> stringResource(
        when (problem) {
            ServerUrl.Problem.Empty -> Res.string.error_url_empty
            ServerUrl.Problem.Scheme -> Res.string.error_url_scheme
            ServerUrl.Problem.Host -> Res.string.error_url_host
            ServerUrl.Problem.Port -> Res.string.error_url_port
            ServerUrl.Problem.Credentials -> Res.string.error_url_credentials
            ServerUrl.Problem.Path -> Res.string.error_url_path
        },
    )
}
