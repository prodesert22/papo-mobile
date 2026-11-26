package chat.papo.mobile.platform

import android.content.Context
import chat.papo.mobile.AppGraph
import chat.papo.mobile.PlatformDeps
import chat.papo.mobile.core.AppDispatchers
import chat.papo.mobile.security.LockState
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

/** Monta o grafo do app com as implementações Android. */
fun createAndroidGraph(context: Context, activities: ActivityHolder): AppGraph {
    val app = context.applicationContext
    val crypto = JcaCrypto()
    // O app ainda não criou nenhum WebView: o que um processo encerrado com o player do YouTube aberto deixou sai agora.
    YouTubeWebData.sweepLeftovers(app)
    val graph = AppGraph(
        PlatformDeps(
            crypto = crypto,
            keyStore = AndroidMasterKeyStore(app, crypto, activities),
            prefs = AndroidPrefs(app),
            databases = AndroidDatabaseFactory(app),
            httpEngine = OkHttp.create(),
            dispatchers = AppDispatchers(Dispatchers.Main.immediate, Dispatchers.IO, Dispatchers.Default, backgroundDispatcher),
            mediaDirectory = java.io.File(app.cacheDir, "media").absolutePath,
            voice = WebRtcVoiceEngineFactory(app, ActivityRequests(app, activities)),
            videoPosters = AndroidVideoPosterExtractor(),
            appVersion = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty(),
            webPlayerData = YouTubeWebData,
        ),
    )
    // O que ficou para outros aplicativos lerem (Compartilhar) não sobrevive ao bloqueio nem ao apagamento dos dados locais.
    graph.scope.launch { graph.lock.state.collect { if (it !is LockState.Unlocked) SharedMedia.store.clear() } }
    return graph
}
