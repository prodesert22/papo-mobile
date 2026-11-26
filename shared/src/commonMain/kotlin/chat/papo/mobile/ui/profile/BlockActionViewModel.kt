package chat.papo.mobile.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.papo.mobile.chat.BlockList
import chat.papo.mobile.core.MutationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BlockActionState(
    /** O pedido está a caminho: o botão espera. */
    val busy: Boolean = false,
    /** O que deu errado no último pedido, ou null; nada é repetido sozinho. */
    val failure: MutationResult? = null,
)

/** Bloquear ou desbloquear [userId] pelo perfil dele. Um pedido por vez. */
class BlockActionViewModel(private val blocks: BlockList, val userId: String) : ViewModel() {
    private val _state = MutableStateFlow(BlockActionState())
    val state: StateFlow<BlockActionState> = _state.asStateFlow()

    fun block() = run { blocks.block(userId) }

    fun unblock() = run { blocks.unblock(userId) }

    private fun run(request: suspend () -> MutationResult) {
        if (_state.value.busy) return
        _state.update { BlockActionState(busy = true) }
        viewModelScope.launch {
            val result = request()
            _state.update { BlockActionState(failure = result.takeUnless { it == MutationResult.Done }) }
        }
    }
}
