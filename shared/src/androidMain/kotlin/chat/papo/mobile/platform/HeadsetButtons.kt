package chat.papo.mobile.platform

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent

/**
 * Recebe o botão do fone (gancho ou tocar/pausar) com uma [MediaSession] do sistema, sem biblioteca. Só existe
 * enquanto a chamada dura: [start] a ativa e [stop] a libera. Chama [onPress] uma vez por aperto, na thread principal.
 */
internal class HeadsetButtons(private val context: Context, private val onPress: () -> Unit) {
    private var session: MediaSession? = null

    fun start() {
        if (session != null) return
        val created = MediaSession(context, TAG)
        created.setCallback(
            object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val event = keyEvent(mediaButtonIntent) ?: return false
                    if (event.keyCode != KeyEvent.KEYCODE_HEADSETHOOK && event.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) return false
                    // Cada aperto chega como "desceu" e "subiu"; só o primeiro conta (e o que repete por segurar, não).
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) onPress()
                    return true
                }
            },
            // Sem um Handler a sessão usa o Looper da thread que chama, e o motor abre a chamada fora da principal.
            Handler(Looper.getMainLooper()),
        )
        // O sistema entrega o botão à sessão que parece estar tocando: a chamada é áudio em andamento.
        created.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE)
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build(),
        )
        created.isActive = true
        session = created
    }

    fun stop() {
        val current = session ?: return
        session = null
        runCatching {
            current.isActive = false
            current.release()
        }
    }

    private fun keyEvent(intent: Intent): KeyEvent? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }

    private companion object {
        const val TAG = "papo-call"
    }
}
