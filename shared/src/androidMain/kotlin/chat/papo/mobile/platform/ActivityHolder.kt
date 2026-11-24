package chat.papo.mobile.platform

import androidx.fragment.app.FragmentActivity
import java.lang.ref.WeakReference

/** Activity em primeiro plano, necessária para mostrar pedidos do sistema (biometria, permissões). */
class ActivityHolder {
    private var ref: WeakReference<FragmentActivity>? = null

    val current: FragmentActivity? get() = ref?.get()

    fun attach(activity: FragmentActivity) {
        ref = WeakReference(activity)
    }

    fun detach(activity: FragmentActivity) {
        if (ref?.get() === activity) ref = null
    }
}
