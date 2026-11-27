package chat.papo.mobile.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.NotificationItem
import chat.papo.mobile.core.MutationResult
import chat.papo.mobile.data.api.ApiException
import chat.papo.mobile.ui.chat.MessageTarget
import chat.papo.mobile.ui.components.UiError
import chat.papo.mobile.ui.components.toUiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** O que a tela da caixa mostra além da lista, que vem de `AccountSession.inbox`. */
data class InboxScreenState(
    /** O id da linha que está abrindo (procurando a conversa no servidor); as outras esperam. */
    val opening: String? = null,
    /** A conversa da linha tocada não existe mais ou está bloqueada: a tela avisa. */
    val unavailable: Boolean = false,
    /** Uma falha ao abrir que não diz que a conversa sumiu (sem conexão, erro do servidor). */
    val openError: UiError? = null,
    /** Marcando todas como lidas. */
    val markingAll: Boolean = false,
    /** O que deu errado em "Marcar todas como lidas"; nada é repetido sozinho. */
    val markFailure: MutationResult? = null,
)

/**
 * A caixa de notificações de uma conta. A lista, o estado de leitura e o erro da leitura são da própria caixa
 * (`AccountSession.inbox`); aqui ficam as ações da tela. Toda leitura ou marcação é uma ação de quem usa a tela: nada
 * se repete sozinho (regra 15).
 */
class InboxViewModel(private val session: AccountSession) : ViewModel() {
    private val inbox = session.inbox
    private val _state = MutableStateFlow(InboxScreenState())
    val state: StateFlow<InboxScreenState> = _state.asStateFlow()

    /** Lê uma vez as notificações mais novas, desde a primeira página ("Tentar de novo"). */
    fun retry() {
        viewModelScope.launch { inbox.refresh() }
    }

    fun loadMore() {
        viewModelScope.launch { inbox.loadMore() }
    }

    /**
     * Abre a conversa de [item] na mensagem dela, por [navigate] (canal e mensagem, com o instante aproximado da
     * notificação), e marca a linha como lida, uma vez. Uma conversa direta que o aparelho não tem (fechada) é lida
     * por id antes; se o servidor a nega (bloqueio, 403) ou não a tem (404), a tela avisa e [navigate] não é chamado.
     * Uma falha de rede não marca nem navega: o toque pode ser repetido.
     */
    fun open(item: NotificationItem, navigate: (channelId: String, target: MessageTarget) -> Unit) {
        if (_state.value.opening != null) return
        _state.update { it.copy(opening = item.id, unavailable = false, openError = null) }
        viewModelScope.launch {
            val outcome = try {
                if (!isKnown(item.channelId)) session.chat.adoptDm(item.channelId)
                Opened.Yes
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.isNotFound || e.isForbidden) Opened.Unavailable else Opened.Failed(e.toUiError())
            } catch (e: Exception) {
                Opened.Failed(e.toUiError())
            }
            _state.update { it.copy(opening = null) }
            when (outcome) {
                is Opened.Failed -> _state.update { it.copy(openError = outcome.error) }
                Opened.Unavailable -> {
                    markRead(item)
                    _state.update { it.copy(unavailable = true) }
                }
                Opened.Yes -> {
                    markRead(item)
                    navigate(item.channelId, MessageTarget(item.messageId, item.createdAt, approximate = true))
                }
            }
        }
    }

    /** Marca todas as linhas carregadas como lidas, em blocos de 1000. O que não foi carregado fica de fora. */
    fun markAllRead() {
        if (_state.value.markingAll) return
        val ids = inbox.state.value.items.filterNot { it.read }.map { it.id }
        if (ids.isEmpty()) return
        _state.update { it.copy(markingAll = true, markFailure = null) }
        viewModelScope.launch {
            val result = inbox.markRead(ids)
            _state.update { it.copy(markingAll = false, markFailure = result.takeUnless { r -> r == MutationResult.Done }) }
        }
    }

    fun dismissUnavailable() = _state.update { it.copy(unavailable = false) }

    fun dismissOpenError() = _state.update { it.copy(openError = null) }

    /** O canal ou a conversa direta está no aparelho? */
    private fun isKnown(id: String): Boolean = session.chat.channels.value.any { it.id == id } || session.chat.dms.value.any { it.id == id }

    /**
     * Marca a linha no escopo da sessão, não no da tela: abrir a conversa fecha a caixa, e o pedido não pode ser
     * cancelado com ela. Uma linha já lida não faz pedido.
     */
    private fun markRead(item: NotificationItem) {
        if (item.read) return
        session.scope.launch { inbox.markRead(listOf(item.id)) }
    }

    private sealed interface Opened {
        data object Yes : Opened
        data object Unavailable : Opened
        class Failed(val error: UiError) : Opened
    }
}
