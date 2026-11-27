package chat.papo.mobile.platform

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.annotation.DrawableRes
import androidx.core.content.pm.PackageInfoCompat
import chat.papo.mobile.AppGraph
import chat.papo.mobile.PlatformDeps
import chat.papo.mobile.core.AppDispatchers
import chat.papo.mobile.core.BuildInfo
import chat.papo.mobile.security.LockState
import chat.papo.mobile.security.NotificationPrivacy
import io.ktor.client.engine.okhttp.OkHttp
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Duas threads com prioridade de segundo plano (o escalonador dá preferência à tela).
 * Vivem o processo inteiro, como os dispatchers do kotlinx; por isso são daemon.
 */
private val backgroundDispatcher by lazy {
    Executors.newFixedThreadPool(2) { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "papo-background").apply { isDaemon = true }
    }.asCoroutineDispatcher()
}

/**
 * Monta o grafo do app com as implementações Android. [notificationIcon] é o ícone de uma cor das notificações, que mora
 * nos recursos do aplicativo. [pushTokens] é o mesmo objeto a que o serviço do FCM entrega as trocas de token.
 */
fun createAndroidGraph(
    context: Context,
    activities: ActivityHolder,
    @DrawableRes notificationIcon: Int,
    pushTokens: AndroidPushTokens,
): AppGraph {
    val app = context.applicationContext
    val crypto = JcaCrypto()
    val requests = ActivityRequests(app, activities)
    val prefs = AndroidPrefs(app)
    val notificationPrivacy = NotificationPrivacy(prefs)
    val notifier = AndroidSystemNotifier(app, notificationIcon) { notificationPrivacy.content.value }
    // O app ainda não criou nenhum WebView: o que um processo encerrado com o player do YouTube aberto deixou sai agora.
    YouTubeWebData.sweepLeftovers(app)
    val graph = AppGraph(
        PlatformDeps(
            crypto = crypto,
            keyStore = AndroidMasterKeyStore(app, crypto, activities),
            prefs = prefs,
            databases = AndroidDatabaseFactory(app),
            httpEngine = OkHttp.create(),
            dispatchers = AppDispatchers(Dispatchers.Main.immediate, Dispatchers.IO, Dispatchers.Default, backgroundDispatcher),
            mediaDirectory = java.io.File(app.cacheDir, "media").absolutePath,
            voice = WebRtcVoiceEngineFactory(app, requests),
            videoPosters = AndroidVideoPosterExtractor(),
            build = buildInfo(app),
            webPlayerData = YouTubeWebData,
            audio = androidAudioEngineFactory(app),
            notifier = notifier,
            notificationPermission = AndroidNotificationPermission(app, requests),
            pushTokens = pushTokens,
            notificationPrivacy = notificationPrivacy,
        ),
    )
    // O que ficou para outros aplicativos lerem (Compartilhar) não sobrevive ao bloqueio nem ao apagamento dos dados locais.
    graph.scope.launch { graph.lock.state.collect { if (it !is LockState.Unlocked) SharedMedia.store.clear() } }
    // O Android mostra os pushes sozinho, no canal que o manifesto aponta: ele precisa existir antes da primeira mensagem.
    if (pushTokens.available) graph.scope.launch { notifier.ensurePushChannel() }
    return graph
}

/** A compilação instalada, lida do pacote; sem o pacote (não acontece no aparelho), uma vazia. */
private fun buildInfo(app: Context): BuildInfo {
    val info = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull() ?: return BuildInfo()
    return BuildInfo(
        versionName = info.versionName.orEmpty(),
        versionCode = PackageInfoCompat.getLongVersionCode(info),
        debuggable = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
    )
}
