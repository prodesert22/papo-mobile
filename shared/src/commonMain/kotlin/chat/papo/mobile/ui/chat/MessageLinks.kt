package chat.papo.mobile.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import chat.papo.mobile.chat.safeLink
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.action_cancel
import chat.papo.mobile.resources.link_confirm_body
import chat.papo.mobile.resources.link_confirm_open
import chat.papo.mobile.resources.link_confirm_title
import chat.papo.mobile.resources.msg_link_copy
import chat.papo.mobile.resources.msg_link_open
import chat.papo.mobile.resources.msg_links
import chat.papo.mobile.ui.media.openInBrowser
import org.jetbrains.compose.resources.stringResource

/**
 * Pede a confirmação antes de abrir [url], o destino de um link cujo texto não o mostra. O endereço aparece inteiro, em
 * quantas linhas precisar. Só abre se for `http` ou `https` ([safeLink], regra 6); fecha com [onDismiss] ao abrir ou cancelar.
 */
@Composable
internal fun LinkConfirmDialog(url: String, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.link_confirm_title)) },
        text = {
            Column {
                Text(stringResource(Res.string.link_confirm_body))
                Spacer(Modifier.height(8.dp))
                Text(url, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    safeLink(url)?.let { openInBrowser(uriHandler, it) }
                    onDismiss()
                },
            ) { Text(stringResource(Res.string.link_confirm_open)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

/**
 * A seção **Links** da folha de ações da mensagem: o destino real de cada link, inteiro (quem o escreveu pode ter dado outro
 * texto a ele), com **Copiar** e **Abrir**. Com muitos links a lista rola dentro da folha. Só abre `http` e `https`
 * ([safeLink], regra 6). [onCopy] recebe o endereço a copiar; [onOpened] avisa que um foi aberto (a folha se fecha).
 */
@Composable
internal fun MessageLinksSection(links: List<String>, onCopy: (String) -> Unit, onOpened: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Text(
        stringResource(Res.string.msg_links),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp),
    )
    Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
        for (url in links) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(url, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                IconButton(onClick = { onCopy(url) }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(Res.string.msg_link_copy))
                }
                IconButton(
                    onClick = {
                        safeLink(url)?.let { openInBrowser(uriHandler, it) }
                        onOpened()
                    },
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = stringResource(Res.string.msg_link_open))
                }
            }
        }
    }
}
