package chat.papo.mobile.ui.home

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.papo.mobile.AppSession
import chat.papo.mobile.account.Account
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.account_settings
import chat.papo.mobile.resources.rail_add
import chat.papo.mobile.resources.rail_settings
import chat.papo.mobile.resources.rail_unread
import chat.papo.mobile.resources.security_alert_body
import chat.papo.mobile.resources.security_alert_dismiss
import chat.papo.mobile.resources.security_alert_title
import chat.papo.mobile.resources.session_expired_action
import chat.papo.mobile.resources.session_expired_body
import chat.papo.mobile.resources.session_expired_title
import chat.papo.mobile.ui.components.AccountAvatar
import chat.papo.mobile.ui.theme.LocalPapoColors
import org.jetbrains.compose.resources.stringResource

/** Tela principal: trilho de contas à esquerda e o conteúdo da conta ativa à direita. */
@Composable
fun HomeScreen(
    session: AppSession,
    onAddServer: () -> Unit,
    onReauthenticate: (Account) -> Unit,
    onAccountSettings: (Account) -> Unit,
    onAppSettings: () -> Unit,
    content: @Composable (Account) -> Unit,
) {
    val accounts by session.accounts.accounts.collectAsStateWithLifecycle()
    val activeId by session.accounts.activeId.collectAsStateWithLifecycle()
    val alerts by session.accounts.securityAlerts.collectAsStateWithLifecycle()
    val active = accounts.firstOrNull { it.id == activeId } ?: accounts.firstOrNull()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      Row(Modifier.fillMaxSize()) {
        AccountRail(
            session = session,
            accounts = accounts,
            activeId = active?.id,
            onSelect = { session.accounts.select(it.id) },
            onAdd = onAddServer,
            onSettings = onAppSettings,
        )
        if (active != null) {
            Column(
                Modifier.fillMaxSize().windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End + WindowInsetsSides.Bottom),
                ),
            ) {
                AccountHeader(active, onSettings = { onAccountSettings(active) })
                if (active.sessionExpired) {
                    Notice(
                        title = stringResource(Res.string.session_expired_title),
                        body = stringResource(Res.string.session_expired_body),
                        action = stringResource(Res.string.session_expired_action),
                        onAction = { onReauthenticate(active) },
                        error = true,
                    )
                } else {
                    if (active.id in alerts) {
                        Notice(
                            title = stringResource(Res.string.security_alert_title),
                            body = stringResource(Res.string.security_alert_body),
                            action = stringResource(Res.string.security_alert_dismiss),
                            onAction = { session.accounts.dismissSecurityAlert(active.id) },
                            error = true,
                        )
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) { content(active) }
                }
            }
        }
      }
    }
}

@Composable
private fun AccountRail(
    session: AppSession,
    accounts: List<Account>,
    activeId: String?,
    onSelect: (Account) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
) {
    val colors = LocalPapoColors.current
    Column(
        Modifier
            .fillMaxHeight()
            .background(colors.rail)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start))
            .width(76.dp)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LazyColumn(
            Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(accounts, key = { it.id }) { account ->
                RailItem(account, selected = account.id == activeId, unread = hasUnread(session, account.id), onClick = { onSelect(account) })
            }
            item {
                RailAction(Icons.Filled.Add, stringResource(Res.string.rail_add), onAdd)
            }
        }
        RailAction(Icons.Filled.Settings, stringResource(Res.string.rail_settings), onSettings)
    }
}

/** Há canal ou conversa direta com mensagem não lida nesta conta. */
@Composable
private fun hasUnread(session: AppSession, accountId: String): Boolean {
    val sessions by session.accounts.sessionMap.collectAsStateWithLifecycle()
    val chat = sessions[accountId]?.chat ?: return false
    val channels by chat.channels.collectAsStateWithLifecycle()
    val dms by chat.dms.collectAsStateWithLifecycle()
    return channels.any { it.unread } || dms.any { it.unreadCount > 0 }
}

@Composable
private fun RailItem(account: Account, selected: Boolean, unread: Boolean, onClick: () -> Unit) {
    val colors = LocalPapoColors.current
    val indicator by animateDpAsState(if (selected) 32.dp else 0.dp)
    val label = account.handle + (account.label?.let { " ($it)" } ?: "") +
        if (unread) stringResource(Res.string.rail_unread) else ""
    Box(
        Modifier
            .fillMaxWidth()
            .height(66.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .semantics {
                contentDescription = label
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .width(4.dp)
                .height(indicator)
                .background(colors.accent, RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)),
        )
        AccountAvatar(account, selected = selected, ringBackground = colors.rail, unread = unread, unreadColor = colors.accent)
    }
}

@Composable
private fun RailAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    val colors = LocalPapoColors.current
    Box(Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)).clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = colors.onRail)
        }
    }
}

@Composable
private fun AccountHeader(account: Account, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(account.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                account.handle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Filled.Tune, contentDescription = stringResource(Res.string.account_settings))
        }
    }
}

@Composable
private fun Notice(title: String, body: String, action: String, onAction: () -> Unit, error: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = if (error) scheme.errorContainer else scheme.surfaceContainer,
        contentColor = if (error) scheme.onErrorContainer else scheme.onSurface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            if (error && action == stringResource(Res.string.session_expired_action)) {
                Button(onClick = onAction, modifier = Modifier.padding(bottom = 6.dp)) { Text(action) }
            } else {
                TextButton(onClick = onAction, modifier = Modifier.align(Alignment.End)) { Text(action) }
            }
        }
    }
}
