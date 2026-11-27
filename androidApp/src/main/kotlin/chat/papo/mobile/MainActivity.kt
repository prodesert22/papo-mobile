package chat.papo.mobile

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import chat.papo.mobile.platform.AndroidPlatformUi
import chat.papo.mobile.platform.NotificationIntents
import chat.papo.mobile.security.secureWindow
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
        applySecureFlag(secureWindow(lock.lockEnabled.value, lock.secureScreen.value))
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                combine(lock.lockEnabled, lock.secureScreen, ::secureWindow)
                    .collect(::applySecureFlag)
            }
        }

        // Só na primeira criação: girar o aparelho recria a activity com o mesmo intent, e o toque não pode valer duas vezes.
        if (savedInstanceState == null) openFromNotification(intent)

        val platformUi = AndroidPlatformUi(this)
        setContent { PapoApp(app.graph, platformUi) }
    }

    /** Um toque numa notificação com o app já aberto chega aqui (a activity aceita o intent no topo da pilha). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFromNotification(intent)
    }

    /**
     * O pedido fica na fila do grafo até a tela principal atendê-lo, depois do bloqueio e do carregamento das contas. Vem de
     * uma notificação do app ou de um push que o Android mostrou sozinho (só com o canal; a conta se escolhe depois).
     */
    private fun openFromNotification(intent: Intent?) {
        (NotificationIntents.parse(intent) ?: NotificationIntents.parsePush(intent))?.let(app.graph.notificationOpens::submit)
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
