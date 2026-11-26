package chat.papo.mobile.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.papo.mobile.account.Account
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.Presence
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.account_settings
import chat.papo.mobile.resources.presence_away
import chat.papo.mobile.resources.presence_busy
import chat.papo.mobile.resources.presence_offline
import chat.papo.mobile.resources.presence_online
import chat.papo.mobile.resources.profile_edit_action
import chat.papo.mobile.resources.search_title
import chat.papo.mobile.resources.switcher_title
import chat.papo.mobile.ui.components.UserAvatar
import chat.papo.mobile.ui.theme.LocalPapoColors
import org.jetbrains.compose.resources.stringResource

private val HeaderButton = 40.dp

/**
 * O topo da lista de canais: o nome do servidor (ou o apelido da conta), com a troca rápida, a busca e o menu da conta, e
 * embaixo a linha da própria pessoa, com avatar, a cor do status e o nome. Tocar na linha abre a edição do perfil.
 * [accountSession] é nulo enquanto a conta não tem sessão aberta: sem ela não há avatar nem perfil a editar, e a linha some.
 */
@Composable
fun AccountHeader(
    account: Account,
    accountSession: AccountSession?,
    onSearch: (() -> Unit)?,
    onSwitch: () -> Unit,
    onSettings: () -> Unit,
    onEditProfile: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Dois botões a menos de largura que antes e duas linhas de nome: um servidor de nome comprido não é mais cortado.
            Text(
                account.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onSwitch, modifier = Modifier.size(HeaderButton)) {
                Icon(Icons.Filled.SwapHoriz, contentDescription = stringResource(Res.string.switcher_title))
            }
            if (onSearch != null) {
                IconButton(onClick = onSearch, modifier = Modifier.size(HeaderButton)) {
                    Icon(Icons.Filled.Search, contentDescription = stringResource(Res.string.search_title))
                }
            }
            IconButton(onClick = onSettings, modifier = Modifier.size(HeaderButton)) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(Res.string.account_settings))
            }
        }
        if (accountSession != null) {
            OwnUserRow(account, accountSession, onEditProfile)
        }
    }
}

@Composable
private fun OwnUserRow(account: Account, session: AccountSession, onEditProfile: () -> Unit) {
    val users by session.chat.users.collectAsStateWithLifecycle()
    val presence by session.chat.presence.collectAsStateWithLifecycle()
    val me = users[account.userId]
    val status = presence[account.userId]
    val name = me?.displayName?.takeIf { it.isNotBlank() } ?: account.displayName
    Row(
        Modifier
            .padding(top = 4.dp, end = 12.dp)
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.profile_edit_action), onClick = onEditProfile)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UserAvatar(session, me, size = 32.dp, fallbackName = account.username)
        Spacer(Modifier.width(10.dp))
        StatusDisc(status)
        Spacer(Modifier.width(8.dp))
        Text(
            name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** O disco do status da conta: verde disponível, âmbar ausente, vermelho ocupado e cinza sem presença (offline). */
@Composable
private fun StatusDisc(presence: Presence?) {
    val colors = LocalPapoColors.current
    val (color: Color, label) = when (presence?.status) {
        "online" -> colors.online to stringResource(Res.string.presence_online)
        "away" -> colors.accent to stringResource(Res.string.presence_away)
        "busy" -> MaterialTheme.colorScheme.error to stringResource(Res.string.presence_busy)
        else -> MaterialTheme.colorScheme.outline to stringResource(Res.string.presence_offline)
    }
    Box(Modifier.size(12.dp).background(color, CircleShape).semantics { contentDescription = label })
}
