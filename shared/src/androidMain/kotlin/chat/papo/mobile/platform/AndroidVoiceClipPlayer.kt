package chat.papo.mobile.platform

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import chat.papo.mobile.media.VoiceClip
import chat.papo.mobile.media.VoiceClipPlayer
import chat.papo.mobile.media.VoiceClipPlayerFactory
import chat.papo.mobile.media.VoiceClipPlayerListener

/** O player da revisão de uma gravação: o [VoiceClipPlayerFactory] que o [chat.papo.mobile.PlatformDeps] recebe. */
internal fun androidVoiceClipPlayerFactory(context: Context): VoiceClipPlayerFactory =
    VoiceClipPlayerFactory { clip, listener -> AndroidVoiceClipPlayer(context.applicationContext, clip, listener) }

/**
 * Um `ExoPlayer` que toca os bytes da gravação direto da memória ([ByteArrayDataSource]): nem rede nem disco (regra 8). É o
 * dono do player e o fecha em [release]. Pede o foco de áudio ao tocar, como o player dos anexos: o que tocava sai.
 */
@OptIn(UnstableApi::class)
class AndroidVoiceClipPlayer(
    context: Context,
    clip: VoiceClip,
    private val listener: VoiceClipPlayerListener,
) : VoiceClipPlayer {
    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    init {
        player.addListener(Events())
        val source = ProgressiveMediaSource.Factory { ByteArrayDataSource(clip.bytes) }
            .createMediaSource(MediaItem.fromUri("memory:voice"))
        player.setMediaSource(source)
        player.prepare()
    }

    override fun play() = player.play()

    override fun pause() = player.pause()

    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    override fun positionMs(): Long = player.currentPosition

    override fun release() = player.release()

    private inner class Events : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = listener.onPlayingChanged(playWhenReady)

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_ENDED) return
            // Acabou com `playWhenReady` ainda ligado: sem desligar, voltar ao começo já tocaria de novo.
            player.pause()
            listener.onEnded()
        }

        override fun onPlayerError(error: PlaybackException) = listener.onFailure()
    }
}
