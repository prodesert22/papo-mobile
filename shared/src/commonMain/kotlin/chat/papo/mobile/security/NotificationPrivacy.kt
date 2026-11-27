package chat.papo.mobile.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * O que as notificações que o app monta mostram de uma mensagem. Os ids são o que fica gravado.
 *
 * Não vale para o push que o Android mostra sozinho: esse segue a prévia que o servidor manda.
 */
enum class NotificationContent(val id: String) {
    /** Autor, texto e foto, também na tela de bloqueio do aparelho. */
    Show("show"),

    /** Com o conteúdo na tela de bloqueio escondido (visibilidade privada): lá aparece só um aviso genérico. */
    HideOnLockScreen("lock_screen"),

    /** Um aviso genérico, sem autor, texto nem foto, em qualquer lugar. */
    HideAlways("always");

    /** A versão da notificação para a tela de bloqueio deve ser a genérica. */
    val genericOnLockScreen: Boolean get() = this != Show

    /** A notificação inteira é genérica, em qualquer lugar. */
    val genericAlways: Boolean get() = this == HideAlways

    companion object {
        /** Um valor ausente ou desconhecido (versão futura, lixo) é o comportamento de sempre: mostrar. */
        fun fromId(id: String?): NotificationContent = entries.firstOrNull { it.id == id } ?: Show
    }
}

/**
 * A escolha do conteúdo das notificações. Guarda em [AppPrefs] (não é dado do usuário nem de uma conta) e vale já na
 * próxima notificação, para todas as contas.
 */
class NotificationPrivacy(private val prefs: AppPrefs) {
    private val _content = MutableStateFlow(NotificationContent.fromId(prefs.getString(PREF_CONTENT)))
    val content: StateFlow<NotificationContent> = _content.asStateFlow()

    fun setContent(value: NotificationContent) {
        prefs.putString(PREF_CONTENT, value.id)
        _content.value = value
    }

    private companion object {
        const val PREF_CONTENT = "notifications.content"
    }
}
