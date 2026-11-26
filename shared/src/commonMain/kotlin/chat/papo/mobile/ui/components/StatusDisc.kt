package chat.papo.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.presence_away
import chat.papo.mobile.resources.presence_busy
import chat.papo.mobile.resources.presence_offline
import chat.papo.mobile.resources.presence_online
import chat.papo.mobile.ui.theme.LocalPapoColors
import org.jetbrains.compose.resources.stringResource

/**
 * O disco do status: verde para `online`, âmbar para `away`, vermelho para `busy` e cinza para qualquer outro valor ou
 * nenhum (offline). Quem lê a tela ouve o nome do status.
 */
@Composable
fun StatusDisc(status: String?, modifier: Modifier = Modifier, size: Dp = 12.dp) {
    val colors = LocalPapoColors.current
    val (color, label) = when (status) {
        "online" -> colors.online to stringResource(Res.string.presence_online)
        "away" -> colors.accent to stringResource(Res.string.presence_away)
        "busy" -> MaterialTheme.colorScheme.error to stringResource(Res.string.presence_busy)
        else -> MaterialTheme.colorScheme.outline to stringResource(Res.string.presence_offline)
    }
    Box(modifier.size(size).background(color, CircleShape).semantics { contentDescription = label })
}
