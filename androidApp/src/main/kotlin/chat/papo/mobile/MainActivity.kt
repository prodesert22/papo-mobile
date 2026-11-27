package chat.papo.mobile

import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import chat.papo.mobile.platform.AndroidPlatformUi
import chat.papo.mobile.platform.NotificationIntents
import chat.papo.mobile.security.secureWindow
import chat.papo.mobile.ui.PapoApp
import chat.papo.mobile.ui.theme.ThemeSpec
import chat.papo.mobile.ui.theme.windowStyleOf
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

        applyGlobalTheme(app.graph.theme.spec.value)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) { app.graph.theme.spec.collect(::applyGlobalTheme) }
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

    /**
     * O fundo da janela e os ícones das barras do sistema seguem o tema global, para não piscar a cor fixa de `themes.xml`
     * (nem ícones claros sobre fundo claro) antes e atrás da interface. Trocar o modo do sistema recria a activity.
     */
    private fun applyGlobalTheme(spec: ThemeSpec) {
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val style = windowStyleOf(spec, systemDark = night)
        window.setBackgroundDrawable(ColorDrawable(style.backgroundArgb))
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !style.dark
            isAppearanceLightNavigationBars = !style.dark
        }
    }

    private fun applySecureFlag(secure: Boolean) {
        if (secure) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
