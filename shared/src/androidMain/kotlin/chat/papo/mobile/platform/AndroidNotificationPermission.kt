package chat.papo.mobile.platform

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import chat.papo.mobile.chat.NotificationPermission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A permissão de notificar no Android. "Pode notificar" é o que o sistema diz ([NotificationManagerCompat.areNotificationsEnabled]):
 * vale a permissão do Android 13 e também as notificações do app desligadas nas configurações, em qualquer versão.
 */
class AndroidNotificationPermission(context: Context, private val requests: ActivityRequests) : NotificationPermission {
    private val app = context.applicationContext
    private val _granted = MutableStateFlow(enabled())

    override val granted: StateFlow<Boolean> = _granted.asStateFlow()

    /** O pedido do sistema existe a partir do Android 13, e só enquanto a permissão não foi concedida. */
    override val canRequest: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    override suspend fun request(): Boolean {
        if (canRequest) {
            // Depois de duas recusas o sistema nega sem mostrar o pedido: o resultado é o mesmo e a tela oferece as configurações.
            requests.request(listOf(Manifest.permission.POST_NOTIFICATIONS))
        }
        refresh()
        return _granted.value
    }

    override fun openSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            app.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Um aparelho sem a tela de configurações de notificação: não há para onde levar.
        }
    }

    override fun refresh() {
        _granted.value = enabled()
    }

    private fun enabled(): Boolean = NotificationManagerCompat.from(app).areNotificationsEnabled()
}
