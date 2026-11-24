package chat.papo.mobile.platform

import android.content.Context
import chat.papo.mobile.AppGraph
import chat.papo.mobile.PlatformDeps
import chat.papo.mobile.core.AppDispatchers
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers

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
            dispatchers = AppDispatchers(Dispatchers.Main.immediate, Dispatchers.IO, Dispatchers.Default),
            mediaDirectory = java.io.File(app.cacheDir, "media").absolutePath,
            voice = WebRtcVoiceEngineFactory(app, ActivityRequests(app, activities)),
            appVersion = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty(),
        ),
    )
}
