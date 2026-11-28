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
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import chat.papo.mobile.shared.R
import chat.papo.mobile.voice.CallNotice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Serviço em primeiro plano que mantém a chamada viva com o app fora da tela.
 * O Android só deixa usar microfone, câmera e captura de tela em segundo plano
 * a partir de um serviço que declare cada um desses usos.
 *
 * A notificação é de chamada em andamento (cronômetro, Silenciar e Desligar). Os textos vêm de [CallNotice], montado no
 * código comum a partir de `strings.xml`: aqui não há texto de interface. Na tela de bloqueio ela aparece na versão pública,
 * sem nome de canal nem de servidor; tocar nela pede o desbloqueio do aparelho, pois o app não abre sobre a tela de bloqueio.
 */
class CallService : Service() {
    // O dono dos ouvintes do que a notificação mostra: nasce e morre com o serviço.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foreground = false
    private var startedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            // Troca de texto ou de estado do microfone: refaz a notificação sem mexer nos tipos do serviço. O fim da chamada
            // (aviso nulo) não a refaz: o serviço está parando.
            notices.collect { notice ->
                if (foreground && notice != null) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(notice))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HANGUP -> {
                hangupHandler?.invoke()
                if (!foreground) stopSelf()
                return START_NOT_STICKY
            }
            ACTION_MUTE -> {
                muteHandler?.invoke()
                if (!foreground) stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                foreground = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val types = intent?.getIntExtra(EXTRA_TYPES, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            ?: ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (startedAt == 0L) startedAt = System.currentTimeMillis()
        try {
            startForeground(NOTIFICATION_ID, notification(notices.value), types)
            foreground = true
            pending?.complete(true)
        } catch (e: Exception) {
            pending?.complete(false)
        }
        pending = null
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        foreground = false
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(notice: CallNotice?): Notification {
        val appName = applicationInfo.loadLabel(packageManager).toString()
        val manager = getSystemService(NotificationManager::class.java)
        // O canal antigo tinha importância baixa, e o Android esconde notificações silenciosas na tela de bloqueio, onde
        // ficam Silenciar e Desligar. Um app não sobe a importância de um canal que já existe: este é novo, com a importância
        // padrão (aparece na tela de bloqueio, sem surgir por cima) e sem som nem vibração.
        manager.deleteNotificationChannel(OLD_CHANNEL_ID)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, notice?.channelLabel ?: appName, NotificationManager.IMPORTANCE_DEFAULT).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            },
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val title = notice?.title ?: appName

        // Desligar e Silenciar valem sem desbloquear o aparelho: não mostram nada do app.
        val hangup = notice?.let { action(R.drawable.ic_call_end, it.hangupLabel, hangupIntent(this)) }
        val mute = notice?.takeIf { it.canMute }?.let {
            action(if (it.muted) R.drawable.ic_call_mic else R.drawable.ic_call_mic_off, it.muteLabel, muteIntent(this))
        }

        val lockScreen = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(appName)
            .setContentText(notice?.publicText)
            .setOngoing(true)
            .setUsesChronometer(true)
            .setWhen(startedAt)
            .apply {
                if (mute != null) addAction(mute)
                if (hangup != null) addAction(hangup)
            }
            .build()

        val person = Person.Builder().setName(title).setImportant(true).build()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(title)
            .setContentText(notice?.text)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, hangupIntent(this)))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(true)
            .setWhen(startedAt)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(lockScreen)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply { if (mute != null) addAction(mute) }
            .build()
    }

    private fun action(icon: Int, label: String, intent: PendingIntent) =
        NotificationCompat.Action.Builder(icon, label, intent).setAuthenticationRequired(false).build()

    companion object {
        private const val CHANNEL_ID = "papo.calls.ongoing"
        private const val OLD_CHANNEL_ID = "papo.calls"
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_HANGUP = "chat.papo.mobile.call.HANGUP"
        private const val ACTION_MUTE = "chat.papo.mobile.call.MUTE"
        private const val ACTION_STOP = "chat.papo.mobile.call.STOP"
        private const val EXTRA_TYPES = "types"

        /** Chamado quando o usuário toca em "Desligar" na notificação ou na janela flutuante. */
        @Volatile
        var hangupHandler: (() -> Unit)? = null

        /** Chamado quando o usuário toca em "Silenciar" ou "Ativar microfone" na notificação ou na janela flutuante. */
        @Volatile
        var muteHandler: (() -> Unit)? = null

        private val notices = MutableStateFlow<CallNotice?>(null)

        @Volatile
        private var pending: CompletableDeferred<Boolean>? = null

        /** O que a notificação mostra; o serviço em andamento a refaz sozinho. Nulo quando não há chamada. */
        fun show(notice: CallNotice?) {
            notices.value = notice
        }

        /** O toque em "Desligar": para a notificação e a janela flutuante. */
        fun hangupIntent(context: Context): PendingIntent = serviceIntent(context, 1, ACTION_HANGUP)

        /** O toque em "Silenciar" ou "Ativar microfone". */
        fun muteIntent(context: Context): PendingIntent = serviceIntent(context, 2, ACTION_MUTE)

        private fun serviceIntent(context: Context, code: Int, action: String): PendingIntent = PendingIntent.getService(
            context,
            code,
            Intent(context, CallService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /**
         * Sobe o serviço (ou troca os usos declarados) e espera o sistema aceitar.
         * A captura de tela só pode começar depois que o tipo `mediaProjection` estiver ativo.
         */
        suspend fun ensure(context: Context, types: Int): Boolean {
            val waiter = CompletableDeferred<Boolean>()
            pending = waiter
            return try {
                context.startForegroundService(Intent(context, CallService::class.java).putExtra(EXTRA_TYPES, types))
                waiter.await()
            } catch (e: CancellationException) {
                pending = null
                throw e
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
