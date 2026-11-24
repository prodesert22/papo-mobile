package chat.papo.mobile

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import chat.papo.mobile.platform.ActivityHolder
import chat.papo.mobile.platform.CallService
import chat.papo.mobile.platform.createAndroidGraph

class PapoApplication : Application() {
    val activities = ActivityHolder()
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = createAndroidGraph(this, activities)
        CallService.hangupHandler = { graph.currentSession()?.calls?.leave() }

        // O bloqueio automático conta o tempo com o app inteiro fora da tela,
        // não a cada troca de Activity.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = graph.onForeground()
                override fun onStop(owner: LifecycleOwner) = graph.onBackground()
            },
        )
    }
}
