package chat.papo.mobile.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.data.ws.WsState
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.connection_offline
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.seconds

/** Quanto tempo sem conexão antes de avisar: abrir o app ou uma reconexão rápida não fazem o ícone piscar. */
private val OfflineGrace = 2.seconds

/**
 * Se o estado da conexão de tempo real conta como "sem conexão": desconectado ou tentando conectar. A sessão que
 * acabou ([WsState.Unauthorized]) fica de fora, porque já tem o próprio aviso (o ponto vermelho e "Entrar de novo").
 */
fun showsOffline(state: WsState): Boolean = state == WsState.Disconnected || state == WsState.Connecting

/**
 * Se a conta de [session] está sem conexão com o servidor há mais de [OfflineGrace]. Sem sessão (conta que saiu, ou
 * sessão encerrada) é falso: aí vale o aviso de sessão expirada.
 */
@Composable
fun rememberOffline(session: AccountSession?): Boolean {
    if (session == null) return false
    val state by session.chat.connection.collectAsStateWithLifecycle()
    val offline = showsOffline(state)
    val shown by produceState(false, offline) {
        value = false
        if (offline) {
            delay(OfflineGrace)
            value = true
        }
    }
    return shown
}

/** O ícone de servidor desconectado, com a descrição para o leitor de tela. */
@Composable
fun OfflineIcon(modifier: Modifier = Modifier, size: Dp = 18.dp) {
    Icon(
        Icons.Filled.CloudOff,
        contentDescription = stringResource(Res.string.connection_offline),
        tint = MaterialTheme.colorScheme.error,
        modifier = modifier.size(size),
    )
}
