package chat.papo.mobile.ui.switcher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.papo.mobile.account.AccountManager
import chat.papo.mobile.chat.OpenConversation
import chat.papo.mobile.chat.SwitchEntry
import chat.papo.mobile.chat.SwitchSource
import chat.papo.mobile.chat.switchEntries
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Troca rápida de conversa entre as contas abertas. Só lê o que as sessões já têm guardado (canais, conversas
 * diretas e usuários); não faz nenhuma requisição, e cada entrada leva o id da conta de onde veio.
 *
 * [open] é a conversa de onde a troca foi aberta, se houve uma: ela não é oferecida.
 */
class QuickSwitcherViewModel(accounts: AccountManager, open: OpenConversation? = null) : ViewModel() {
    /** Conversas já ordenadas, de todas as contas; nulo até a primeira leitura. */
    val entries: StateFlow<List<SwitchEntry>?> = sources(accounts)
        .map { switchEntries(it, open) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/**
 * Uma fonte por conta com sessão aberta. Conta com a sessão expirada fica de fora: a tela principal também não
 * abre conversas dela até entrar de novo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private fun sources(accounts: AccountManager): Flow<List<SwitchSource>> =
    combine(accounts.accounts, accounts.sessionMap) { list, sessions ->
        list.filterNot { it.sessionExpired }.mapNotNull { account -> sessions[account.id]?.let { account to it } }
    }.flatMapLatest { open ->
        if (open.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(
                open.map { (account, session) ->
                    val chat = session.chat
                    combine(chat.channels, chat.dms, chat.users) { channels, dms, users ->
                        SwitchSource(account.id, account.title, session.userId, channels, dms, users)
                    }
                },
            ) { it.toList() }
        }
    }
