package chat.papo.mobile.chat

import chat.papo.mobile.security.AppPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Os pedidos para abrir uma conversa que vêm de tocar numa notificação. Há no máximo um pedido à espera: ele fica aqui
 * até a tela principal poder atendê-lo (o app destravado, as contas carregadas), então um toque com o app bloqueado
 * passa pelo bloqueio antes. Um pedido novo toma o lugar do que ainda não foi atendido.
 */
class NotificationOpenRequests {
    private val _pending = MutableStateFlow<NotificationOpen?>(null)
    val pending: StateFlow<NotificationOpen?> = _pending.asStateFlow()

    fun submit(request: NotificationOpen) {
        _pending.value = request
    }

    /** Dá [request] por atendido (ou descartado); se já chegou outro no lugar dele, o outro fica. */
    fun consume(request: NotificationOpen) {
        _pending.compareAndSet(request, null)
    }
}

/**
 * A explicação que se mostra uma vez, antes de pedir ao Android a permissão de notificar: na primeira abertura depois da
 * atualização, com alguma conta, e só onde o sistema tem um pedido a mostrar e o app ainda não tem a permissão. Depois de
 * vista (aceita ou não), não volta: o resto fica por conta do aviso na tela de notificações da conta.
 */
class NotificationPermissionPrompt(private val permission: NotificationPermission, private val prefs: AppPrefs) {
    private val _explaining = MutableStateFlow(prefs.getString(EXPLAINED) == null && permission.canRequest && !permission.granted.value)

    /** A explicação está à espera de ser mostrada. */
    val explaining: StateFlow<Boolean> = _explaining.asStateFlow()

    /** A pessoa aceitou ver o pedido do sistema. */
    suspend fun accept() {
        markExplained()
        permission.request()
    }

    /** A pessoa adiou: o pedido não volta sozinho. */
    fun decline() = markExplained()

    private fun markExplained() {
        prefs.putString(EXPLAINED, "1")
        _explaining.value = false
    }

    private companion object {
        const val EXPLAINED = "notifications.permissionExplained"
    }
}
