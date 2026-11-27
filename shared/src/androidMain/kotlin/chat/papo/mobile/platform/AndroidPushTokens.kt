package chat.papo.mobile.platform

import android.content.Context
import android.os.Build
import chat.papo.mobile.push.PushTokens
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.tasks.await

/**
 * O token do Firebase Cloud Messaging. O app só tem push se foi compilado com o `google-services.json` do projeto
 * Firebase: sem ele o `FirebaseApp` nunca é criado, [available] é falso e nada do Firebase é chamado (as chamadas dele
 * lançariam `IllegalStateException`).
 *
 * O serviço do FCM (`PapoMessagingService`) entrega as trocas de token em [onNewToken]; [changes] as repete a quem
 * começa a ouvir depois, porque o serviço pode rodar antes de existir qualquer sessão de conta.
 */
class AndroidPushTokens(context: Context) : PushTokens {
    override val available: Boolean = try {
        FirebaseApp.getApps(context.applicationContext).isNotEmpty()
    } catch (e: Exception) {
        false
    }

    override val deviceName: String get() = Build.MODEL.orEmpty()

    private val published = MutableSharedFlow<String>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val changes: Flow<String> = published.asSharedFlow()

    override suspend fun current(): String? {
        if (!available) return null
        return try {
            FirebaseMessaging.getInstance().token.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // sem rede ou sem Play Services: a conta registra quando houver um token
        }
    }

    /** O FCM trocou o token do aparelho. */
    fun onNewToken(token: String) {
        published.tryEmit(token)
    }
}
