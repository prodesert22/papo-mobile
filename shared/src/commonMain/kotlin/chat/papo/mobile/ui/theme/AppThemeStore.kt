package chat.papo.mobile.ui.theme

import chat.papo.mobile.security.AppPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * O tema global do app. Fica nas preferências do app e não no banco cifrado: não é dado do usuário e a tela de bloqueio,
 * que aparece antes de qualquer banco abrir, já usa este tema. A leitura e a gravação são imediatas (as preferências
 * ficam em memória), então não há carregamento a esperar. Quem o cria (o `AppGraph`) é o dono, e ele vive o processo todo.
 */
class AppThemeStore(private val prefs: AppPrefs) {
    private val _spec = MutableStateFlow(ThemeSpec.decode(prefs.getString(KEY)))
    val spec: StateFlow<ThemeSpec> = _spec.asStateFlow()

    fun set(spec: ThemeSpec) {
        _spec.value = spec
        prefs.putString(KEY, spec.encode())
    }

    companion object {
        const val KEY = "ui.theme"
    }
}
