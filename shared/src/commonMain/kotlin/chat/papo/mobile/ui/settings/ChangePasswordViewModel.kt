package chat.papo.mobile.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.PasswordCheck
import chat.papo.mobile.chat.checkNewPassword
import chat.papo.mobile.chat.redactSecrets
import chat.papo.mobile.core.MutationResult
import chat.papo.mobile.core.runMutation
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Em que passo a troca parou, para a tela dizer o que fazer. */
enum class PasswordStep {
    /** `POST /users/{eu}/reset`: ainda não mudou nada na senha. */
    Prepare,

    /** `PUT /users/{eu}/password`: o servidor pode ter trocado a senha mesmo sem responder. */
    Change,
}

sealed interface ChangePasswordOutcome {
    /** A senha nova não passou na conferência local; nada foi ao servidor. */
    data class Invalid(val check: PasswordCheck) : ChangePasswordOutcome

    /** O servidor não aceitou ou não respondeu direito. [result] nunca é [MutationResult.Done]. */
    data class Failed(val step: PasswordStep, val result: MutationResult) : ChangePasswordOutcome

    /** A senha mudou e a sessão desta conta acabou; a tela volta à principal, que leva ao "Entrar de novo". */
    data object Changed : ChangePasswordOutcome
}

data class ChangePasswordState(
    val busy: Boolean = false,
    val outcome: ChangePasswordOutcome? = null,
)

/**
 * A troca da senha da própria conta. O servidor não confere a senha atual: pede primeiro `POST /users/{eu}/reset` e só
 * com 200 manda `PUT /users/{eu}/password`. A senha nova só passa por [submit]; não fica no estado deste modelo nem
 * em nenhuma mensagem. Nada é repetido sozinho: depois de uma falha, quem decide é o usuário.
 *
 * A senha nova encerra todas as sessões da conta, inclusive esta; com 200, [endSession] marca a conta como expirada.
 */
class ChangePasswordViewModel(
    private val session: AccountSession,
    private val endSession: suspend () -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(ChangePasswordState())
    val state: StateFlow<ChangePasswordState> = _state.asStateFlow()

    /** Mensagens esperando para sair: trocar a senha acaba com a sessão em que elas iriam. */
    val queued: Int get() = session.chat.outbox.value.size

    fun submit(password: String, repeat: String) {
        if (_state.value.busy) return
        val check = checkNewPassword(password, repeat)
        if (check != PasswordCheck.Ok) {
            _state.value = ChangePasswordState(outcome = ChangePasswordOutcome.Invalid(check))
            return
        }
        _state.value = ChangePasswordState(busy = true)
        viewModelScope.launch {
            val outcome = change(password)
            _state.value = ChangePasswordState(outcome = outcome)
        }
    }

    private suspend fun change(password: String): ChangePasswordOutcome {
        val prepared = runMutation { session.api.prepareOwnPasswordChange(session.userId) }
        if (prepared != MutationResult.Done) return failed(PasswordStep.Prepare, prepared, password)
        val changed = runMutation { session.api.changePassword(session.userId, password) }
        if (changed != MutationResult.Done) return failed(PasswordStep.Change, changed, password)
        // A senha já mudou: sair da tela agora não pode deixar a conta com um cookie que o servidor já recusa.
        withContext(NonCancellable) {
            try {
                endSession()
            } catch (e: Exception) {
                // O próximo pedido com o cookie velho dá 401 e marca a conta como expirada do mesmo jeito.
            }
        }
        return ChangePasswordOutcome.Changed
    }

    /** O texto que o servidor mandou não leva a senha de volta, mesmo que ele a repita. */
    private fun failed(step: PasswordStep, result: MutationResult, password: String): ChangePasswordOutcome {
        val safe = if (result is MutationResult.Rejected) result.copy(detail = result.detail?.let { redactSecrets(it, password) }) else result
        return ChangePasswordOutcome.Failed(step, safe)
    }
}
