package chat.papo.mobile.ui.addserver

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.papo.mobile.account.AccountManager
import chat.papo.mobile.account.AddAccountResult
import chat.papo.mobile.account.ServerProbe
import chat.papo.mobile.ui.components.UiError
import chat.papo.mobile.ui.components.toUiError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AddServerStep { Address, InsecureWarning, ServerPassword, Credentials }

enum class CredentialsMode { Login, Register }

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
        discardPending()
        _state.update { it.copy(step = AddServerStep.Address, probe = null, error = null, serverPassword = "", password = "") }
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
