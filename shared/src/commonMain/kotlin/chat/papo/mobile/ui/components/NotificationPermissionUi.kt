package chat.papo.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.papo.mobile.chat.NotificationPermission
import chat.papo.mobile.chat.NotificationPermissionPrompt
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.notif_system_denied_body
import chat.papo.mobile.resources.notif_system_denied_title
import chat.papo.mobile.resources.notif_system_open_settings
import chat.papo.mobile.resources.notif_system_permission_allow
import chat.papo.mobile.resources.notif_system_permission_body
import chat.papo.mobile.resources.notif_system_permission_later
import chat.papo.mobile.resources.notif_system_permission_title
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** A permissão de notificar da plataforma; nula onde ela não tem notificações, e então nada aparece. */
val LocalNotificationPermission = staticCompositionLocalOf<NotificationPermission?> { null }

/**
 * O aviso de que o Android não deixa o app notificar, com os atalhos para pedir a permissão (onde o sistema ainda
 * mostra o pedido) e para as configurações de notificação do app. Some quando a permissão existe e onde não há o que pedir.
 */
@Composable
fun NotificationPermissionNotice(modifier: Modifier = Modifier) {
    val permission = LocalNotificationPermission.current ?: return
    val granted by permission.granted.collectAsStateWithLifecycle()
    if (granted) return
    val scope = rememberCoroutineScope()
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(Res.string.notif_system_denied_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(Res.string.notif_system_denied_body), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (permission.canRequest) {
                    TextButton(onClick = { scope.launch { permission.request() } }) {
                        Text(stringResource(Res.string.notif_system_permission_allow))
                    }
                }
                TextButton(onClick = permission::openSettings) { Text(stringResource(Res.string.notif_system_open_settings)) }
            }
        }
    }
}

/** A explicação única que vem antes do pedido do Android; só aparece enquanto [prompt] a quer mostrar. */
@Composable
fun NotificationPermissionExplainer(prompt: NotificationPermissionPrompt) {
    val explaining by prompt.explaining.collectAsStateWithLifecycle()
    if (!explaining) return
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = prompt::decline,
        title = { Text(stringResource(Res.string.notif_system_permission_title)) },
        text = { Text(stringResource(Res.string.notif_system_permission_body)) },
        confirmButton = {
            TextButton(onClick = { scope.launch { prompt.accept() } }) { Text(stringResource(Res.string.notif_system_permission_allow)) }
        },
        dismissButton = {
            TextButton(onClick = prompt::decline) { Text(stringResource(Res.string.notif_system_permission_later)) }
        },
    )
}
