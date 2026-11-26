package chat.papo.mobile.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.UserProfile
import chat.papo.mobile.data.api.ApiException
import chat.papo.mobile.ui.components.UiError
import chat.papo.mobile.ui.components.toUiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A leitura do perfil: o que a tela mostra no lugar do conteúdo. */
sealed interface ProfileLoad {
    data object Loading : ProfileLoad

    data class Loaded(val profile: UserProfile) : ProfileLoad

    /** O servidor não tem esse usuário (404): a conta pode ter sido removida. */
    data object NotFound : ProfileLoad

    data class Failed(val error: UiError) : ProfileLoad
}

data class ProfileUiState(
    val load: ProfileLoad = ProfileLoad.Loading,
    /** A conversa direta está sendo aberta: o botão fica parado até o servidor responder. */
    val opening: Boolean = false,
    val openError: UiError? = null,
)

/**
 * Perfil de um usuário da conta de [session]. O perfil vem do `ProfileCache` da sessão, então abrir o mesmo perfil de
 * novo logo depois não volta ao servidor. Nada se repete sozinho depois de um erro: quem decide é o usuário, em
 * [retry] e em [openChat].
 */
class ProfileViewModel(private val session: AccountSession, val userId: String) : ViewModel() {
    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        load(refresh = false)
    }

    /** Lê o perfil de novo do servidor depois de um erro. */
    fun retry() = load(refresh = true)

    /**
     * Mostra o que o `ProfileCache` da sessão tem agora, sem passar por "carregando": é para quando a tela volta a ficar
     * à vista depois de o próprio perfil ser editado, que deixa no cache a leitura nova. Dentro da validade não vai ao
     * servidor. Só vale com o perfil já na tela; uma falha aqui deixa o que estava.
     */
    fun showCached() {
        if (_state.value.load !is ProfileLoad.Loaded) return
        viewModelScope.launch {
            try {
                val profile = session.profiles.get(userId)
                _state.update { if (it.load is ProfileLoad.Loaded) it.copy(load = ProfileLoad.Loaded(profile)) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // O perfil que já está na tela segue valendo.
            }
        }
    }

    private fun load(refresh: Boolean) {
        job?.cancel()
        _state.update { it.copy(load = ProfileLoad.Loading) }
        job = viewModelScope.launch {
            val next = try {
                ProfileLoad.Loaded(session.profiles.get(userId, refresh))
            } catch (e: ApiException) {
                if (e.isNotFound) ProfileLoad.NotFound else ProfileLoad.Failed(e.toUiError())
            } catch (e: Throwable) {
                // toUiError relança o cancelamento: sair da tela ou tentar de novo não vira erro.
                ProfileLoad.Failed(e.toUiError())
            }
            _state.update { it.copy(load = next) }
        }
    }

    /**
     * Abre (ou cria) a conversa direta com o usuário e chama [onOpened] com o id dela. Um pedido por vez; se falhar,
     * o erro fica em [ProfileUiState.openError] e nada é repetido sozinho.
     */
    fun openChat(onOpened: (channelId: String) -> Unit) {
        if (_state.value.opening || userId == session.userId) return
        _state.update { it.copy(opening = true, openError = null) }
        viewModelScope.launch {
            val channelId = try {
                session.chat.openDm(userId)
            } catch (e: Throwable) {
                val error = e.toUiError()
                _state.update { it.copy(opening = false, openError = error) }
                return@launch
            }
            _state.update { it.copy(opening = false) }
            onOpened(channelId)
        }
    }
}
