package chat.papo.mobile.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.papo.mobile.chat.BlockList
import chat.papo.mobile.core.MutationResult
import chat.papo.mobile.ui.components.UiError
import chat.papo.mobile.ui.components.toUiError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BlockedUsersState(
    /** A lista está sendo lida do servidor. */
    val loading: Boolean = true,
    /** Falha da leitura; a lista mostrada é a que já se tinha. */
    val loadError: UiError? = null,
    /** Quem está sendo desbloqueado agora; os botões esperam a resposta. */
    val unblocking: String? = null,
    /** O que deu errado ao desbloquear; nada é repetido sozinho. */
    val failure: MutationResult? = null,
)

/** A lista de bloqueados da conta: lê o servidor ao abrir e depois só quando o usuário pede ou desbloqueia. */
class BlockedUsersViewModel(private val blocks: BlockList) : ViewModel() {
    private val _state = MutableStateFlow(BlockedUsersState())
    val state: StateFlow<BlockedUsersState> = _state.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        _state.update { it.copy(loading = true, loadError = null) }
        viewModelScope.launch {
            val error = try {
                blocks.refresh()
                null
            } catch (e: Throwable) {
                e.toUiError() // relança o cancelamento
            }
            _state.update { it.copy(loading = false, loadError = error) }
        }
    }

    fun unblock(userId: String) {
        if (_state.value.unblocking != null) return
        _state.update { it.copy(unblocking = userId, failure = null) }
        viewModelScope.launch {
            val result = blocks.unblock(userId)
            _state.update { it.copy(unblocking = null, failure = result.takeUnless { r -> r == MutationResult.Done }) }
        }
    }
}
