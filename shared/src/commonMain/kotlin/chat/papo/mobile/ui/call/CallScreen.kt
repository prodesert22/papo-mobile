package chat.papo.mobile.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.papo.mobile.AppSession
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.User
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.action_back
import chat.papo.mobile.resources.action_close
import chat.papo.mobile.resources.call_alone
import chat.papo.mobile.resources.call_bar
import chat.papo.mobile.resources.call_bar_open
import chat.papo.mobile.resources.call_camera_off
import chat.papo.mobile.resources.call_camera_on
import chat.papo.mobile.resources.call_connecting
import chat.papo.mobile.resources.call_end_connection_lost
import chat.papo.mobile.resources.call_end_denied
import chat.papo.mobile.resources.call_end_failed
import chat.papo.mobile.resources.call_end_full
import chat.papo.mobile.resources.call_end_no_microphone
import chat.papo.mobile.resources.call_end_room_closed
import chat.papo.mobile.resources.call_ended_title
import chat.papo.mobile.resources.call_hangup
import chat.papo.mobile.resources.call_joining
import chat.papo.mobile.resources.call_mute
import chat.papo.mobile.resources.call_muted
import chat.papo.mobile.resources.call_reconnecting
import chat.papo.mobile.resources.call_screen_of
import chat.papo.mobile.resources.call_screen_off
import chat.papo.mobile.resources.call_screen_on
import chat.papo.mobile.resources.call_switch_camera
import chat.papo.mobile.resources.call_unmute
import chat.papo.mobile.resources.call_you
import chat.papo.mobile.resources.call_your_screen
import chat.papo.mobile.resources.channels_open_chat
import chat.papo.mobile.ui.components.LocalPlatformUi
import chat.papo.mobile.ui.components.UserAvatar
import chat.papo.mobile.ui.theme.LocalPapoColors
import chat.papo.mobile.voice.CallController
import chat.papo.mobile.voice.CallEnd
import chat.papo.mobile.voice.CallPhase
import chat.papo.mobile.voice.CallState
import chat.papo.mobile.voice.VideoKey
import chat.papo.mobile.voice.VideoTrackHandle
import org.jetbrains.compose.resources.stringResource

/** Um quadro da grade: uma pessoa (com ou sem câmera) ou a tela que alguém transmite. */
private data class Tile(
    val key: String,
    val userId: String,
    val isScreen: Boolean,
    val video: VideoTrackHandle?,
)

/** [onOpenChat] abre o chat do canal de voz da chamada (conta e canal). */
@Composable
fun CallScreen(session: AppSession, onBack: () -> Unit, onOpenChat: (accountId: String, channelId: String) -> Unit) {
    val controller by session.calls.current.collectAsStateWithLifecycle()
    val current = controller
    if (current == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val sessions by session.accounts.sessionMap.collectAsStateWithLifecycle()
    val accountSession = sessions[current.accountId]
    if (accountSession == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val state by current.state.collectAsStateWithLifecycle()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Header(accountSession, state, onBack) { onOpenChat(state.accountId, state.channelId) }
            if (state.phase == CallPhase.Ended) {
                Ended(state) {
                    session.calls.dismissEnded()
                    onBack()
                }
            } else {
                Participants(accountSession, current, state, Modifier.weight(1f))
                Controls(current, state)
            }
        }
    }
}

@Composable
private fun Header(session: AccountSession, state: CallState, onBack: () -> Unit, onOpenChat: () -> Unit) {
    val channels by session.chat.channels.collectAsStateWithLifecycle()
    val name = channels.firstOrNull { it.id == state.channelId }?.name.orEmpty()
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val status = when (state.phase) {
                CallPhase.Joining -> stringResource(Res.string.call_joining)
                CallPhase.Connecting -> stringResource(Res.string.call_connecting)
                CallPhase.Reconnecting -> stringResource(Res.string.call_reconnecting)
                else -> state.errorDetail
            }
            status?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onOpenChat) {
            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = stringResource(Res.string.channels_open_chat))
        }
    }
}

@Composable
private fun Participants(session: AccountSession, controller: CallController, state: CallState, modifier: Modifier) {
    val users by session.chat.users.collectAsStateWithLifecycle()
    val remote by controller.remoteVideo.collectAsStateWithLifecycle()
    val localCamera by controller.localCamera.collectAsStateWithLifecycle()

    val slotOf = state.videoSlots.entries.associate { it.value to it.key }
    fun remoteVideo(userId: String, kind: String) = slotOf[VideoKey(userId, kind)]?.let { remote.getOrNull(it) }

    val tiles = buildList {
        // Telas transmitidas primeiro: são o que as pessoas entraram para ver.
        for (member in state.members.values) {
            if (member.userId != session.userId && member.screenSharing) {
                add(Tile("screen-" + member.userId, member.userId, true, remoteVideo(member.userId, VideoKey.SCREEN)))
            }
        }
        for (member in state.members.values.sortedBy { it.userId != session.userId }) {
            val mine = member.userId == session.userId
            val video = if (mine) localCamera else remoteVideo(member.userId, VideoKey.CAMERA).takeIf { member.cameraOn }
            add(Tile("user-" + member.userId, member.userId, false, video))
        }
    }

    // Tocar num vídeo o amplia para a área toda (inteiro, sem recorte); tocar de novo volta à grade.
    var focusedKey by remember { mutableStateOf<String?>(null) }
    val focused = tiles.firstOrNull { it.key == focusedKey && it.video != null }
    if (focused != null) {
        Box(
            modifier.fillMaxWidth().padding(8.dp).clip(RoundedCornerShape(18.dp)).background(Color.Black).clickable { focusedKey = null },
            contentAlignment = Alignment.Center,
        ) {
            LocalPlatformUi.current.VideoView(focused.video!!, Modifier.fillMaxSize(), fill = false)
        }
        return
    }

    Column(modifier) {
        if (state.screenOn) {
            Text(
                stringResource(Res.string.call_your_screen),
                style = MaterialTheme.typography.labelLarge,
                color = LocalPapoColors.current.accent,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(if (tiles.size <= 2) 1 else 2),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(tiles, key = { it.key }, span = { if (it.isScreen) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { tile ->
                ParticipantTile(session, tile, users[tile.userId], state, onFocus = { focusedKey = tile.key })
            }
        }
        if (state.phase == CallPhase.Connected && state.members.size <= 1) {
            Text(
                stringResource(Res.string.call_alone),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun ParticipantTile(session: AccountSession, tile: Tile, user: User?, state: CallState, onFocus: () -> Unit) {
    val member = state.members[tile.userId]
    val mine = tile.userId == session.userId
    val speaking = !tile.isScreen && tile.userId in state.speakers
    val name = if (mine) stringResource(Res.string.call_you) else user?.displayName ?: "?"
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(if (tile.isScreen) 16f / 9f else 4f / 3f)
            .clip(shape)
            .background(if (tile.isScreen && tile.video != null) Color.Black else MaterialTheme.colorScheme.surfaceContainer)
            .border(2.dp, if (speaking) LocalPapoColors.current.online else Color.Transparent, shape)
            .clickable(enabled = tile.video != null, onClick = onFocus),
        contentAlignment = Alignment.Center,
    ) {
        if (tile.video != null) {
            LocalPlatformUi.current.VideoView(tile.video, Modifier.fillMaxSize(), fill = !tile.isScreen)
        } else {
            UserAvatar(session, user, size = 72.dp, fallbackName = name)
        }
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val muted = if (mine) state.micMuted else member?.muted == true
            if (!tile.isScreen && muted) {
                Icon(
                    Icons.Filled.MicOff,
                    contentDescription = stringResource(Res.string.call_muted),
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                if (tile.isScreen) stringResource(Res.string.call_screen_of, name) else name,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Controls(controller: CallController, state: CallState) {
    val ready = state.phase == CallPhase.Connected || state.phase == CallPhase.Reconnecting
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ControlButton(
            icon = if (state.micMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
            label = stringResource(if (state.micMuted) Res.string.call_unmute else Res.string.call_mute),
            active = !state.micMuted,
            enabled = ready && !state.busy,
            onClick = controller::toggleMute,
        )
        ControlButton(
            icon = if (state.cameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
            label = stringResource(if (state.cameraOn) Res.string.call_camera_off else Res.string.call_camera_on),
            active = state.cameraOn,
            enabled = ready && !state.busy,
            onClick = controller::toggleCamera,
        )
        if (state.cameraOn) {
            ControlButton(Icons.Filled.Cameraswitch, stringResource(Res.string.call_switch_camera), false, ready, controller::switchCamera)
        }
        ControlButton(
            icon = if (state.screenOn) Icons.Filled.StopScreenShare else Icons.Filled.ScreenShare,
            label = stringResource(if (state.screenOn) Res.string.call_screen_off else Res.string.call_screen_on),
            active = state.screenOn,
            enabled = ready && !state.busy,
            onClick = controller::toggleScreen,
        )
        OutputButton(controller, state)
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error)
                .clickable(role = Role.Button, onClick = controller::leave),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.CallEnd, contentDescription = stringResource(Res.string.call_hangup), tint = MaterialTheme.colorScheme.onError)
        }
    }
}

@Composable
internal fun ControlButton(icon: ImageVector, label: String, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(if (active) scheme.primaryContainer else scheme.surfaceContainerHighest)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = (if (active) scheme.onPrimaryContainer else scheme.onSurface).copy(alpha = if (enabled) 1f else 0.4f),
        )
    }
}

@Composable
private fun Ended(state: CallState, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text(stringResource(Res.string.call_ended_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        val reason = stringResource(
            when (state.end) {
                CallEnd.Denied -> Res.string.call_end_denied
                CallEnd.Full -> Res.string.call_end_full
                CallEnd.NoMicrophone -> Res.string.call_end_no_microphone
                CallEnd.ConnectionLost -> Res.string.call_end_connection_lost
                CallEnd.RoomClosed -> Res.string.call_end_room_closed
                else -> Res.string.call_end_failed
            },
        )
        Text(reason, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.errorDetail?.takeIf { state.end == CallEnd.Failed }?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(Res.string.action_close)) }
    }
}

/** Faixa mostrada fora da tela de chamada enquanto há uma em andamento. */
@Composable
fun CallBar(session: AppSession, onOpen: () -> Unit) {
    val controller by session.calls.current.collectAsStateWithLifecycle()
    val current = controller ?: return
    val state by current.state.collectAsStateWithLifecycle()
    val sessions by session.accounts.sessionMap.collectAsStateWithLifecycle()
    val accountSession = sessions[current.accountId] ?: return
    val channels by accountSession.chat.channels.collectAsStateWithLifecycle()
    val name = channels.firstOrNull { it.id == state.channelId }?.name.orEmpty()
    val colors = LocalPapoColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (state.phase == CallPhase.Ended) MaterialTheme.colorScheme.errorContainer else colors.online.copy(alpha = 0.18f))
            .clickable(onClickLabel = stringResource(Res.string.call_bar_open), onClick = onOpen)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PhoneInTalk, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            if (state.phase == CallPhase.Ended) {
                stringResource(Res.string.call_ended_title)
            } else {
                stringResource(Res.string.call_bar, name)
            },
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f).padding(vertical = 14.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (state.phase != CallPhase.Ended) {
            IconButton(onClick = current::leave) {
                Icon(Icons.Filled.CallEnd, contentDescription = stringResource(Res.string.call_hangup), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
