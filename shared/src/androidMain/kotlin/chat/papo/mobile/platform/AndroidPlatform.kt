package chat.papo.mobile.platform

import android.content.Context
import chat.papo.mobile.AppGraph
import chat.papo.mobile.PlatformDeps
import chat.papo.mobile.core.AppDispatchers
import io.ktor.client.engine.okhttp.OkHttp
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
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
    return AppGraph(
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
        ),
    )
}
