package chat.papo.mobile.platform

import androidx.fragment.app.FragmentActivity
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger

/** Activity em primeiro plano, necessária para mostrar pedidos do sistema (biometria, permissões). */
class ActivityHolder {
    private var ref: WeakReference<FragmentActivity>? = null

    val current: FragmentActivity? get() = ref?.get()

    /**
     * Quantos pedidos ao sistema (permissão, captura de tela) abriram uma tela por cima do app e ainda não voltaram. No Android 11
     * essa tela dispara `onUserLeaveHint` como se a pessoa tivesse saído do app, e a janela flutuante não pode abrir por isso.
     */
    val requestsInFlight = AtomicInteger()

    fun attach(activity: FragmentActivity) {
        ref = WeakReference(activity)
    }

    fun detach(activity: FragmentActivity) {
        if (ref?.get() === activity) ref = null
    }
}
