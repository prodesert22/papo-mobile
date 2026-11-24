package chat.papo.mobile.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.resume

/** Pedidos ao sistema que precisam de uma Activity (permissões, captura de tela), como funções suspensas. */
class ActivityRequests(private val context: Context, private val activities: ActivityHolder) {
    fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Pede as permissões que faltam; devolve quais ficaram concedidas. */
    suspend fun request(permissions: List<String>): Map<String, Boolean> {
        val missing = permissions.filterNot(::isGranted)
        val result = permissions.associateWith { it !in missing }.toMutableMap()
        if (missing.isEmpty()) return result
        val answer = launch(ActivityResultContracts.RequestMultiplePermissions(), missing.toTypedArray()) ?: return result
        result.putAll(answer)
        return result
    }

    suspend fun startForResult(intent: Intent): ActivityResult? =
        launch(ActivityResultContracts.StartActivityForResult(), intent)

    private suspend fun <I, O> launch(contract: ActivityResultContract<I, O>, input: I): O? = withContext(Dispatchers.Main) {
        val activity = activities.current ?: return@withContext null
        suspendCancellableCoroutine { continuation ->
            val key = "papo-request-" + UUID.randomUUID()
            var launcher: androidx.activity.result.ActivityResultLauncher<I>? = null
            launcher = activity.activityResultRegistry.register(key, contract) { output ->
                launcher?.unregister()
                if (continuation.isActive) continuation.resume(output)
            }
            continuation.invokeOnCancellation { launcher.unregister() }
            launcher.launch(input)
        }
    }
}
