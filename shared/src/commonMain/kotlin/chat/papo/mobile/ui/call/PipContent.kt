package chat.papo.mobile.ui.call

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.papo.mobile.AppSession
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.call_you
import chat.papo.mobile.ui.components.LocalPlatformUi
import chat.papo.mobile.ui.components.UserAvatar
import chat.papo.mobile.voice.CallController
import chat.papo.mobile.voice.CallState
import chat.papo.mobile.voice.PipContent
import chat.papo.mobile.voice.VideoKey
import chat.papo.mobile.voice.VideoTrackHandle
import chat.papo.mobile.voice.pipContent
import org.jetbrains.compose.resources.stringResource

/**
 * O que a janela flutuante mostra no lugar do app inteiro: só a imagem da chamada, sem botões (Silenciar e Desligar são ações
 * da própria janela, no sistema). Fica por cima da navegação, que continua montada por baixo para a pessoa voltar onde estava.
 */
@Composable
fun PipOverlay(session: AppSession) {
    val controller by session.calls.current.collectAsStateWithLifecycle()
    val sessions by session.accounts.sessionMap.collectAsStateWithLifecycle()
    val current = controller
    val accountSession = current?.let { sessions[it.accountId] }
    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        if (current != null && accountSession != null) PipCall(session, accountSession, current)
    }
}

@Composable
private fun PipCall(session: AppSession, account: AccountSession, controller: CallController) {
    val state by controller.state.collectAsStateWithLifecycle()
    val focus by session.calls.focus.collectAsStateWithLifecycle()
    val remote by controller.remoteVideo.collectAsStateWithLifecycle()
    val localCamera by controller.localCamera.collectAsStateWithLifecycle()
    val users by account.chat.users.collectAsStateWithLifecycle()

    val content = pipContent(state, account.userId, focus)
    val video: VideoTrackHandle? = when (content) {
        is PipContent.Screen -> remoteTrack(state, remote, content.userId, VideoKey.SCREEN)
        is PipContent.Camera -> if (content.mine) localCamera else remoteTrack(state, remote, content.userId, VideoKey.CAMERA)
        is PipContent.Avatar -> null
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (video != null) {
            // A tela transmitida aparece inteira; a câmera preenche a janela.
            LocalPlatformUi.current.VideoView(video, Modifier.fillMaxSize(), fill = content is PipContent.Camera)
        } else {
            // Sem vídeo (chamada só de voz, ou o quadro ainda não chegou): a foto de quem fala.
            val userId = when (content) {
                is PipContent.Screen -> content.userId
                is PipContent.Camera -> content.userId
                is PipContent.Avatar -> content.userId
            }
            val name = if (userId == account.userId) stringResource(Res.string.call_you) else users[userId]?.displayName ?: "?"
            UserAvatar(account, users[userId], size = 56.dp, fallbackName = name)
        }
    }
}

/** A faixa de vídeo que chega do servidor para [userId]; nula se ele não está assinado ou o quadro ainda não veio. */
private fun remoteTrack(state: CallState, remote: List<VideoTrackHandle?>, userId: String, kind: String): VideoTrackHandle? {
    val slot = state.videoSlots.entries.firstOrNull { it.value == VideoKey(userId, kind) }?.key ?: return null
    return remote.getOrNull(slot)
}
