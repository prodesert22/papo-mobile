package chat.papo.mobile

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import chat.papo.mobile.platform.AndroidPlatformUi
import chat.papo.mobile.ui.PapoApp
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private val app get() = application as PapoApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        app.activities.attach(this)

        val lock = app.graph.lock
        // Antes do primeiro quadro, para a prévia nos apps recentes já nascer protegida.
        applySecureFlag(lock.lockEnabled.value && lock.secureScreen.value)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                combine(lock.lockEnabled, lock.secureScreen) { enabled, secure -> enabled && secure }
                    .collect(::applySecureFlag)
            }
        }

        val platformUi = AndroidPlatformUi(this)
        setContent { PapoApp(app.graph, platformUi) }
    }

    override fun onDestroy() {
        app.activities.detach(this)
        super.onDestroy()
    }

    private fun applySecureFlag(secure: Boolean) {
        if (secure) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
