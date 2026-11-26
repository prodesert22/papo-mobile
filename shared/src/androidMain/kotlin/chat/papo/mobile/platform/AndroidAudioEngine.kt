package chat.papo.mobile.platform

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.media.AudioEngine
import chat.papo.mobile.media.AudioEngineFactory
import chat.papo.mobile.media.AudioEngineListener
import chat.papo.mobile.media.PlayableMedia

/** O motor de áudio do Android: o [AudioEngineFactory] que o [chat.papo.mobile.PlatformDeps] recebe. */
internal fun androidAudioEngineFactory(context: Context): AudioEngineFactory =
    AudioEngineFactory { listener -> AndroidAudioEngine(context.applicationContext, listener) }

/**
 * Um `ExoPlayer` só de áudio (as faixas de vídeo e de texto ficam desligadas), com a mesma fonte de dados e a mesma política
 * de erro do player de vídeo: os pedidos saem pelo `PapoClient` da conta dona do áudio, com o cookie `Auth`, sem cache em
 * disco (regra 8), e só rede e 5xx repetem, no máximo duas vezes ([PapoLoadErrorPolicy]).
 *
 * O player nasce no primeiro [open], na thread principal, e é o dono único dele: [release] o fecha. Como o `ExoPlayer` só
 * aceita a thread em que nasceu, [release] vindo de outra thread passa pela principal. Um único player serve a todas as
 * contas: cada [open] monta a fonte de mídia com o cliente da conta daquele áudio.
 */
@OptIn(UnstableApi::class)
internal class AndroidAudioEngine(
    private val context: Context,
    private val listener: AudioEngineListener,
) : AudioEngine {
    private var player: ExoPlayer? = null
    private var released = false

    /** Mp3 e outros formatos sem índice só buscam (e dão duração) se o motor puder estimar pela taxa constante. */
    private val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)

    override fun open(client: PapoClient, media: PlayableMedia, startAtMs: Long, speed: Float) {
        if (released) return
        val player = player ?: newPlayer().also { player = it }
        val source = DefaultMediaSourceFactory(papoDataSourceFactory(client), extractors)
            .setLoadErrorHandlingPolicy(PapoLoadErrorPolicy)
            .createMediaSource(MediaItem.fromUri(media.url))
        player.setPlaybackSpeed(speed)
        player.setMediaSource(source, startAtMs.coerceAtLeast(0L))
        player.prepare()
        // Quem pede o foco de áudio ao começar é o próprio player: um vídeo que toca no momento o perde, e vice-versa.
        player.playWhenReady = true
    }

    override fun play() {
        player?.play()
    }

    override fun pause() {
        player?.pause()
    }

    override fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
    }

    override fun setSpeed(speed: Float) {
        player?.setPlaybackSpeed(speed)
    }

    override fun positionMs(): Long = player?.currentPosition ?: 0L

    override fun stop() {
        // Parado, o player larga a conexão e abandona o foco de áudio; o próximo `open` reaproveita o mesmo player.
        player?.run {
            stop()
            clearMediaItems()
        }
    }

    override fun release() {
        if (released) return
        released = true
        val closing = player ?: return
        player = null
        if (Looper.myLooper() == closing.applicationLooper) {
            closing.release()
        } else {
            Handler(closing.applicationLooper).post { closing.release() }
        }
    }

    private fun newPlayer(): ExoPlayer {
        val created = ExoPlayer.Builder(context)
            // Áudio é mídia: pede o foco (pausa quando outro app, ou um vídeo, toma o som) e pausa sozinho se o fone sair.
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        created.trackSelectionParameters = created.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        created.addListener(PlayerEvents(created))
        return created
    }

    /** Traduz os avisos do `ExoPlayer` para o [AudioEngineListener]. */
    private inner class PlayerEvents(private val player: ExoPlayer) : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = listener.onPlayingChanged(playWhenReady)

        override fun onPlaybackStateChanged(playbackState: Int) {
            listener.onBufferingChanged(playbackState == Player.STATE_BUFFERING)
            when (playbackState) {
                Player.STATE_READY -> reportDuration()
                Player.STATE_ENDED -> listener.onEnded()
            }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) = reportDuration()

        override fun onPlayerError(error: PlaybackException) {
            // O player só chega aqui depois da política de repetição: o motivo é definitivo.
            listener.onFailure(failureOf(error.cause, error.errorCode))
        }

        private fun reportDuration() {
            val duration = player.duration
            if (duration != C.TIME_UNSET && duration >= 0L) listener.onDuration(duration)
        }
    }
}
