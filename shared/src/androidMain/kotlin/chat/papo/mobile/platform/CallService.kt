package chat.papo.mobile.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred

/**
 * Serviço em primeiro plano que mantém a chamada viva com o app fora da tela.
 * O Android só deixa usar microfone, câmera e captura de tela em segundo plano
 * a partir de um serviço que declare cada um desses usos.
 */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HANGUP -> {
                hangupHandler?.invoke()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val types = intent?.getIntExtra(EXTRA_TYPES, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            ?: ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        try {
            startForeground(NOTIFICATION_ID, notification(title), types)
            pending?.complete(true)
        } catch (e: Exception) {
            pending?.complete(false)
        }
        pending = null
        return START_NOT_STICKY
    }

    private fun notification(title: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Chamadas", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
            )
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val hangup = PendingIntent.getService(
            this,
            1,
            Intent(this, CallService::class.java).setAction(ACTION_HANGUP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(title.ifBlank { "Chamada de voz" })
            .setContentText("Em chamada no Papo")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_CALL)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Desligar", hangup).build())
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "papo.calls"
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_HANGUP = "chat.papo.mobile.call.HANGUP"
        private const val ACTION_STOP = "chat.papo.mobile.call.STOP"
        private const val EXTRA_TYPES = "types"
        private const val EXTRA_TITLE = "title"

        /** Chamado quando o usuário toca em "Desligar" na notificação. */
        @Volatile
        var hangupHandler: (() -> Unit)? = null

        @Volatile
        private var pending: CompletableDeferred<Boolean>? = null

        /**
         * Sobe o serviço (ou troca os usos declarados) e espera o sistema aceitar.
         * A captura de tela só pode começar depois que o tipo `mediaProjection` estiver ativo.
         */
        suspend fun ensure(context: Context, types: Int, title: String): Boolean {
            val waiter = CompletableDeferred<Boolean>()
            pending = waiter
            return try {
                context.startForegroundService(
                    Intent(context, CallService::class.java).putExtra(EXTRA_TYPES, types).putExtra(EXTRA_TITLE, title),
                )
                waiter.await()
            } catch (e: Exception) {
                pending = null
                false
            }
        }

        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, CallService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
