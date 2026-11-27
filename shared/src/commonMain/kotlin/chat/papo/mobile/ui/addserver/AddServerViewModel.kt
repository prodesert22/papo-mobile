package chat.papo.mobile.ui.addserver

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.papo.mobile.account.AccountManager
import chat.papo.mobile.account.AddAccountResult
import chat.papo.mobile.account.ServerProbe
import chat.papo.mobile.chat.PasswordCheck
import chat.papo.mobile.chat.checkNewPassword
import chat.papo.mobile.chat.parseResetLink
import chat.papo.mobile.chat.redactSecrets
import chat.papo.mobile.core.MutationResult
import chat.papo.mobile.core.classifyMutation
import chat.papo.mobile.data.api.ApiException
import chat.papo.mobile.ui.components.UiError
import chat.papo.mobile.ui.components.toUiError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AddServerStep { Address, InsecureWarning, ServerPassword, Credentials, ResetPassword }

enum class CredentialsMode { Login, Register }

/** O que a redefinição por link deixou para a tela dizer. Nenhuma é repetida sozinha. */
sealed interface ResetOutcome {
    /** O texto colado não é um link de redefinição nem um token. */
    data object InvalidLink : ResetOutcome

    /** A senha nova é fraca ou as duas não batem; nada foi ao servidor. */
    data class Check(val check: PasswordCheck) : ResetOutcome

    /** O link é de outro endereço: a tela mostra os dois, e só um segundo toque envia. */
    data class HostDiffers(val linkHost: String, val serverHost: String) : ResetOutcome

    /** 410: o link é inválido, expirou ou já foi usado. */
    data object Gone : ResetOutcome

    /** Outra falha (limite de taxa, recusa com o motivo, sem conexão, resposta incerta). */
    data class Failed(val result: MutationResult) : ResetOutcome
}

data class AddServerState(
    val step: AddServerStep = AddServerStep.Address,
    val address: String = "",
    val probe: ServerProbe? = null,
    val serverPassword: String = "",
    val username: String = "",
    val password: String = "",
    val mode: CredentialsMode = CredentialsMode.Login,
    val busy: Boolean = false,
    val error: UiError? = null,
    val done: AddAccountResult? = null,
    /** O link de redefinição e a senha nova, só neste estado: nunca num estado salvo, log ou mensagem. */
    val resetLink: String = "",
    val newPassword: String = "",
    val newPasswordRepeat: String = "",
    val resetOutcome: ResetOutcome? = null,
    /** O endereço de link que a tela já mostrou ao usuário; o envio com ele só vale depois disso. */
    val resetHostShown: String? = null,
    /** A senha acabou de ser redefinida: a etapa de entrar avisa e espera a senha nova. */
    val passwordWasReset: Boolean = false,
)

class AddServerViewModel(
    private val accounts: AccountManager,
    initialAddress: String?,
    initialUsername: String?,
) : ViewModel() {
    private val _state = MutableStateFlow(AddServerState(address = initialAddress.orEmpty(), username = initialUsername.orEmpty()))
    val state: StateFlow<AddServerState> = _state.asStateFlow()

    private var pending: AccountManager.PendingAccount? = null

    init {
        // Reentrar numa conta existente: o endereço já é conhecido, então pula a digitação.
        if (!initialAddress.isNullOrBlank()) findServer()
    }

    fun setAddress(value: String) = _state.update { it.copy(address = value, error = null) }
    fun setServerPassword(value: String) = _state.update { it.copy(serverPassword = value, error = null) }
    fun setUsername(value: String) = _state.update { it.copy(username = value, error = null) }
    fun setPassword(value: String) = _state.update { it.copy(password = value, error = null) }
    fun setMode(value: CredentialsMode) = _state.update { it.copy(mode = value, error = null) }

    fun openReset() = _state.update {
        if (it.step != AddServerStep.Credentials || it.busy) it else it.copy(step = AddServerStep.ResetPassword, error = null, passwordWasReset = false)
    }

    fun setResetLink(value: String) = _state.update { it.copy(resetLink = value, resetOutcome = null) }
    fun setNewPassword(value: String) = _state.update { it.copy(newPassword = value, resetOutcome = null) }
    fun setNewPasswordRepeat(value: String) = _state.update { it.copy(newPasswordRepeat = value, resetOutcome = null) }

    /**
     * Manda o token do link e a senha nova a `POST /auth/password_reset` do servidor desta entrada. Primeiro confere
     * tudo o que dá para conferir sem rede; um link de outro endereço pede um segundo toque. As rotas de autenticação
     * são limitadas por endereço, então nada é repetido sozinho.
     */
    fun submitReset() {
        val s = _state.value
        if (s.busy || s.step != AddServerStep.ResetPassword) return
        val probe = s.probe ?: return
        val link = parseResetLink(s.resetLink, probe.url)
        if (link == null) {
            _state.update { it.copy(resetOutcome = ResetOutcome.InvalidLink) }
            return
        }
        val check = checkNewPassword(s.newPassword, s.newPasswordRepeat)
        if (check != PasswordCheck.Ok) {
            _state.update { it.copy(resetOutcome = ResetOutcome.Check(check)) }
            return
        }
        if (link.otherHost != null && link.otherHost != s.resetHostShown) {
            _state.update { it.copy(resetOutcome = ResetOutcome.HostDiffers(link.otherHost, probe.url.host), resetHostShown = link.otherHost) }
            return
        }
        _state.update { it.copy(busy = true, resetOutcome = null) }
        viewModelScope.launch {
            val failure = sendReset(link.token, s.newPassword)
            _state.update { current ->
                val cleared = current.copy(busy = false, newPassword = "", newPasswordRepeat = "")
                when (failure) {
                    null -> cleared.copy(
                        step = AddServerStep.Credentials,
                        resetLink = "",
                        resetOutcome = null,
                        resetHostShown = null,
                        passwordWasReset = true,
                        mode = CredentialsMode.Login,
                        password = "",
                    )
                    // O link se gastou, ou pode ter se gastado: colar de novo é com o usuário. Nos outros casos ele ainda vale.
                    ResetOutcome.Gone, ResetOutcome.Failed(MutationResult.Uncertain) -> cleared.copy(resetLink = "", resetOutcome = failure)
                    else -> cleared.copy(resetOutcome = failure)
                }
            }
        }
    }

    /** Null quando o servidor aceitou. O token e a senha não saem em nenhum texto que venha do servidor. */
    private suspend fun sendReset(token: String, password: String): ResetOutcome? = try {
        pending().resetPassword(token, password)
        null
    } catch (e: ApiException) {
        if (e.status == 410) ResetOutcome.Gone else ResetOutcome.Failed(redacted(classifyMutation(e), token, password))
    } catch (e: Throwable) {
        ResetOutcome.Failed(classifyMutation(e)) // relança o cancelamento
    }

    private fun redacted(result: MutationResult, vararg secrets: String): MutationResult =
        if (result is MutationResult.Rejected) result.copy(detail = result.detail?.let { redactSecrets(it, *secrets) }) else result

    fun findServer() = run {
        val probe = accounts.probe(_state.value.address)
        discardPending()
        _state.update {
            it.copy(
                probe = probe,
                address = probe.url.display,
                step = if (probe.url.isSecure) nextStepAfterAddress(probe) else AddServerStep.InsecureWarning,
            )
        }
    }

    fun acceptInsecure() {
        val probe = _state.value.probe ?: return
        _state.update { it.copy(step = nextStepAfterAddress(probe)) }
    }

    fun submitServerPassword() = run {
        pending().submitServerPassword(_state.value.serverPassword)
        _state.update { it.copy(step = AddServerStep.Credentials, serverPassword = "") }
    }

    fun submitCredentials() = run {
        val s = _state.value
        _state.update { it.copy(passwordWasReset = false) }
        val result = when (s.mode) {
            CredentialsMode.Login -> pending().login(s.username, s.password)
            CredentialsMode.Register -> pending().register(s.username, s.password)
        }
        pending = null
        _state.update { it.copy(done = result, password = "") }
    }

    /** Volta um passo; devolve false quando já está no primeiro. */
    fun back(): Boolean {
        val s = _state.value
        if (s.busy) return true
        if (s.step == AddServerStep.Address) return false
        if (s.step == AddServerStep.ResetPassword) {
            // Volta a entrar no mesmo servidor, sem reabrir o login nem pedir a senha do servidor de novo.
            _state.update { it.copy(step = AddServerStep.Credentials, resetLink = "", newPassword = "", newPasswordRepeat = "", resetOutcome = null, resetHostShown = null) }
            return true
        }
        discardPending()
        _state.update { it.copy(step = AddServerStep.Address, probe = null, error = null, serverPassword = "", password = "", resetLink = "", newPassword = "", newPasswordRepeat = "", resetOutcome = null, resetHostShown = null, passwordWasReset = false) }
        return true
    }

    override fun onCleared() = discardPending()

    private fun nextStepAfterAddress(probe: ServerProbe) =
        if (probe.info.public) AddServerStep.Credentials else AddServerStep.ServerPassword

    private fun pending(): AccountManager.PendingAccount =
        pending ?: accounts.begin(checkNotNull(_state.value.probe)).also { pending = it }

    private fun discardPending() {
        pending?.cancel()
        pending = null
    }

    private fun run(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: Throwable) {
                _state.update { it.copy(error = e.toUiError()) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}
