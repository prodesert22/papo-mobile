package chat.papo.mobile.platform

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import chat.papo.mobile.shared.R
import chat.papo.mobile.voice.CallNotice
import chat.papo.mobile.voice.CallPresentation
import chat.papo.mobile.voice.PipAspect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A chamada fora da tela cheia no Android: a janela flutuante (picture-in-picture) da `MainActivity` e a notificação do
 * [CallService]. A activity se liga por [attach] e [detach] e repassa [onUserLeaveHint]; tudo roda na thread principal.
 *
 * Do Android 12 em diante o sistema abre a janela sozinho quando a pessoa sai do app (`autoEnterEnabled`); no Android 11 é
 * o `onUserLeaveHint` quem a abre. Nada aqui mostra a activity sobre a tela de bloqueio: com o aparelho bloqueado, tocar na
 * notificação pede o desbloqueio do sistema.
 */
class AndroidCallPresentation(context: Context, private val activities: ActivityHolder) : CallPresentation {
    private val app = context.applicationContext
    private val supported = app.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private val _inPip = MutableStateFlow(false)
    override val inPip: StateFlow<Boolean> = _inPip.asStateFlow()

    private var activity: ComponentActivity? = null
    private var eligible = false
    private var aspect = PipAspect.Standard
    private var notice: CallNotice? = null

    private val modeListener = Consumer<PictureInPictureModeChangedInfo> { _inPip.value = it.isInPictureInPictureMode }

    fun attach(activity: ComponentActivity) {
        this.activity = activity
        activity.addOnPictureInPictureModeChangedListener(modeListener)
        _inPip.value = activity.isInPictureInPictureMode
        applyParams()
    }

    fun detach(activity: ComponentActivity) {
        if (this.activity !== activity) return
        activity.removeOnPictureInPictureModeChangedListener(modeListener)
        this.activity = null
        _inPip.value = false
    }

    /** A pessoa saiu do app (Início). No Android 12 ou mais o sistema já cuida; antes dele, abre a janela flutuante aqui. */
    fun onUserLeaveHint() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || !eligible) return
        // Um pedido de permissão ou de captura de tela abre uma tela do sistema e dispara este aviso sem a pessoa ter saído.
        if (activities.requestsInFlight.get() > 0) return
        val host = activity ?: return
        try {
            host.enterPictureInPictureMode(params())
        } catch (_: IllegalStateException) {
            // O sistema recusa a janela (já parada, ou sem o recurso): o app segue em segundo plano, como sem ela.
        }
    }

    override fun setPipEligible(eligible: Boolean, aspect: PipAspect) {
        this.eligible = eligible
        this.aspect = aspect
        applyParams()
    }

    override fun setNotice(notice: CallNotice?) {
        this.notice = notice
        CallService.show(notice)
        applyParams()
    }

    override fun leavePip() {
        val host = activity ?: return
        if (!host.isInPictureInPictureMode) return
        // Trazer a própria activity à frente tira a janela flutuante e volta à tela cheia (no topo da pilha: chega a onNewIntent).
        host.startActivity(Intent(host, host.javaClass).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun applyParams() {
        val host = activity ?: return
        if (!supported) return
        try {
            host.setPictureInPictureParams(params())
        } catch (_: IllegalStateException) {
            // A activity não pode ter janela flutuante agora (já destruída): o próximo attach aplica de novo.
        }
    }

    private fun params(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(aspect.width, aspect.height))
            .setActions(actions())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(eligible)
        return builder.build()
    }

    private fun actions(): List<RemoteAction> {
        val current = notice ?: return emptyList()
        return buildList {
            if (current.canMute) {
                val icon = if (current.muted) R.drawable.ic_call_mic else R.drawable.ic_call_mic_off
                add(remote(icon, current.muteLabel, CallService.muteIntent(app)))
            }
            add(remote(R.drawable.ic_call_end, current.hangupLabel, CallService.hangupIntent(app)))
        }
    }

    private fun remote(icon: Int, label: String, intent: PendingIntent) =
        RemoteAction(Icon.createWithResource(app, icon), label, label, intent)
}
