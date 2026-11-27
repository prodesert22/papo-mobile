package chat.papo.mobile.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.papo.mobile.AppSession
import chat.papo.mobile.account.ServerTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private val NoTheme: StateFlow<ServerTheme?> = MutableStateFlow(null)

/**
 * O tema do que está aberto: o da conta ativa, se ela tem um próprio, senão o global ([global], que já envolve esta
 * chamada). [content] fica sempre no mesmo lugar da composição: trocar de tema (ou de conta) não pode recriar a navegação.
 * Enquanto a conta ainda não leu o tema dela, vale o global.
 */
@Composable
fun AccountTheme(session: AppSession, global: ThemeSpec, content: @Composable () -> Unit) {
    val activeId by session.accounts.activeId.collectAsStateWithLifecycle()
    val sessions by session.accounts.sessionMap.collectAsStateWithLifecycle()
    val chosen by (activeId?.let { sessions[it] }?.theme?.state ?: NoTheme).collectAsStateWithLifecycle()
    PapoTheme((chosen as? ServerTheme.Own)?.spec ?: global, content = content)
}
