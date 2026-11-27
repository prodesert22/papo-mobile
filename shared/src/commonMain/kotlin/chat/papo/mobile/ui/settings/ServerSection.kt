package chat.papo.mobile.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.papo.mobile.account.Account
import chat.papo.mobile.account.AccountManager
import chat.papo.mobile.core.formatDate
import chat.papo.mobile.core.parseInstant
import chat.papo.mobile.data.api.ServerDto
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.members_count
import chat.papo.mobile.resources.server_channels
import chat.papo.mobile.resources.server_closed
import chat.papo.mobile.resources.server_created
import chat.papo.mobile.resources.server_owner
import chat.papo.mobile.resources.server_public
import chat.papo.mobile.resources.server_roles
import chat.papo.mobile.resources.server_stale
import chat.papo.mobile.ui.components.UiError
import chat.papo.mobile.ui.components.accentColor
import chat.papo.mobile.ui.components.initials
import chat.papo.mobile.ui.components.rememberImage
import chat.papo.mobile.ui.components.text
import chat.papo.mobile.ui.components.toUiError
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Informações do servidor da conta: um `GET /server` ao abrir. Sem rede, mostra o nome e o ícone gravados no registro
 * e o erro, e não repete o pedido sozinho.
 */
@Composable
internal fun ServerSection(accounts: AccountManager, account: Account) {
    var info by remember(account.id) { mutableStateOf<ServerDto?>(null) }
    var error by remember(account.id) { mutableStateOf<UiError?>(null) }

    LaunchedEffect(account.id) {
        try {
            info = accounts.refreshServer(account.id)
            error = null
        } catch (e: Throwable) {
            error = e.toUiError() // relança o cancelamento
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        val icon = rememberImage(account.serverIcon)
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(account.accentColor().copy(alpha = 0.28f)),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Image(icon, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Text(initials(account.serverName), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(account.serverName, style = MaterialTheme.typography.titleMedium)
            Text(account.server.display, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Spacer(Modifier.height(8.dp))
    error?.let {
        Text(it.text(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(Res.string.server_stale), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    info?.let { server ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(if (server.public) Res.string.server_public else Res.string.server_closed), style = MaterialTheme.typography.bodyLarge)
            server.ownerUsername?.takeIf { it.isNotBlank() }?.let {
                Text(stringResource(Res.string.server_owner, it), style = MaterialTheme.typography.bodyLarge)
            }
            Text(pluralStringResource(Res.plurals.members_count, server.memberCount, server.memberCount), style = MaterialTheme.typography.bodyLarge)
            Text(pluralStringResource(Res.plurals.server_channels, server.channelCount, server.channelCount), style = MaterialTheme.typography.bodyLarge)
            Text(pluralStringResource(Res.plurals.server_roles, server.roleCount, server.roleCount), style = MaterialTheme.typography.bodyLarge)
            parseInstant(server.createdAt)?.let {
                Text(stringResource(Res.string.server_created, it.formatDate()), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
