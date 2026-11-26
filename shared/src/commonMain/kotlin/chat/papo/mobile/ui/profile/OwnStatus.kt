package chat.papo.mobile.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.Presence
import chat.papo.mobile.chat.UserProfile
import chat.papo.mobile.core.MutationResult
import chat.papo.mobile.core.runMutation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** O que a própria pessoa escolhe ser. O contrato não tem "invisível": só estes três. */
enum class OwnStatus(val wire: String?) {
    Available(null),
    Away("away"),
    Busy("busy"),
}

/**
 * O status da própria conta pelo que o app sabe: a presença ao vivo, se a pessoa está online (`online` é disponível), e,
 * sem ela, o status guardado no perfil. Null enquanto nenhum dos dois diz (perfil ainda não lido e sem presença).
 */
fun ownStatusOf(presence: Presence?, profile: UserProfile?): OwnStatus? = when (presence?.status) {
    "online" -> OwnStatus.Available
    "away" -> OwnStatus.Away
    "busy" -> OwnStatus.Busy
    else -> profile?.let { stored -> OwnStatus.entries.first { it.wire == stored.status } }
}

data class OwnStatusState(
    /** O que a tela marca: a escolha em andamento, senão o que o servidor confirmou, senão o que a presença diz. Null se não se sabe. */
    val selected: OwnStatus? = null,
    /** Há um pedido em andamento: [selected] é a escolha ainda não confirmada e os botões esperam. */
    val saving: Boolean = false,
    /** Por que a última escolha não valeu (e a tela voltou à anterior); null se deu certo ou nada foi tentado. */
    val problem: MutationResult? = null,
)

/**
 * A escolha de Disponível, Ausente ou Ocupado da conta de [session]. Fica em "salvando" até chegar o `presence_update`
 * ou a resposta 200, o que vier primeiro; se o pedido falha, volta à escolha anterior e diz por quê. Nada se repete sozinho.
 */
class OwnStatusViewModel(private val session: AccountSession) : ViewModel() {
    private val me = session.userId
    private val ownPresence = session.chat.presence.map { it[me] }.distinctUntilChanged()

    private val profile = MutableStateFlow<UserProfile?>(null)
    private val pending = MutableStateFlow<OwnStatus?>(null)

    /** O que a resposta 200 confirmou; a próxima mudança da presença (de qualquer aparelho) passa a valer no lugar dele. */
    private val confirmed = MutableStateFlow<OwnStatus?>(null)
    private val problem = MutableStateFlow<MutationResult?>(null)

    val state: StateFlow<OwnStatusState> =
        combine(ownPresence, profile, pending, confirmed, problem) { presence, stored, choice, accepted, failed ->
            OwnStatusState(selected = choice ?: accepted ?: ownStatusOf(presence, stored), saving = choice != null, problem = failed)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, OwnStatusState())

    init {
        viewModelScope.launch {
            ownPresence.onEach { presence ->
                confirmed.value = null
                // O evento que traz a escolha encerra o "salvando", mesmo antes da resposta do pedido.
                if (pending.value != null && pending.value == ownStatusOf(presence, null)) pending.value = null
            }.collect {}
        }
        // Sem presença (o tempo real ainda não conectou), o perfil guarda o status. Uma leitura só, e só se faz falta.
        if (ownStatusOf(session.chat.presence.value[me], null) == null) {
            viewModelScope.launch {
                try {
                    profile.value = session.profiles.get(me)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Sem o perfil nada fica marcado; escolher continua possível.
                }
            }
        }
    }

    /** Escolhe [status]. Um pedido por vez, e escolher o que já está marcado não faz nada. */
    fun choose(status: OwnStatus) {
        if (pending.value != null || status == state.value.selected) return
        problem.value = null
        pending.value = status
        viewModelScope.launch {
            val result = runMutation { session.api.updateStatus(me, status.wire) }
            if (result is MutationResult.Done) confirmed.value = status else problem.value = result
            pending.value = null
        }
    }
}
