package chat.papo.mobile.ui.components

import androidx.compose.ui.platform.UriHandler
import chat.papo.mobile.core.safeExternalUrl

/**
 * O `LocalUriHandler` do app: todo link que o Compose abre (`LinkAnnotation.Url`, `openUri`) passa por aqui e só chega a
 * [open] se for `http` ou `https` ([safeExternalUrl], regra 6); qualquer outro esquema não abre nada. Se [open] não
 * conseguir (sem navegador), também não há o que mostrar daqui: quem precisa avisar chama a plataforma direto.
 */
class ExternalUriHandler(private val open: (String) -> Boolean) : UriHandler {
    override fun openUri(uri: String) {
        safeExternalUrl(uri)?.let { open(it) }
    }
}
