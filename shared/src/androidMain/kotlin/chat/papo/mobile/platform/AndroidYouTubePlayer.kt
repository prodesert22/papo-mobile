package chat.papo.mobile.platform

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.http.SslError
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import chat.papo.mobile.chat.isYouTubeId
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.embed_youtube_player
import chat.papo.mobile.resources.embed_youtube_stopped
import chat.papo.mobile.security.WebPlayerData
import java.io.File
import org.jetbrains.compose.resources.stringResource

/** A tela cheia que o próprio player do YouTube pede (`onShowCustomView`): a view e o aviso de que ela saiu. */
internal class CustomView(val view: View, val callback: WebChromeClient.CustomViewCallback)

/**
 * O player do YouTube do Android: um `WebView` com uma página constante do app que só tem o iframe do vídeo
 * ([youTubePage]). O contrato está em `PlatformUi.YouTubePlayer`.
 *
 * O WebView é a única exceção à regra de que tudo o que o app guarda fica cifrado (o Chromium grava o perfil dele em
 * claro na pasta de dados), então ele só existe com estas condições, que o [YouTubeWebHolder] cumpre: JavaScript ligado só
 * porque o player o exige; sem acesso a arquivo nem a conteúdo; sem cache HTTP; sem cookies de terceiros; sem ponte de
 * JavaScript para o app; sem janelas novas nem permissões; nenhuma navegação para fora do embed ([youTubeNavigation]); e o
 * perfil apagado ao fechar e ao travar o app ([YouTubeWebData]). Não se usa o WebView para mais nada.
 */
@Composable
internal fun AndroidYouTubePlayer(videoId: String, modifier: Modifier, fullscreenButton: Boolean, onOpenExternally: () -> Unit) {
    if (!isYouTubeId(videoId)) return
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOpenExternally by rememberUpdatedState(onOpenExternally)
    // Um player novo (outro vídeo) começa sem a tela cheia do anterior e sem o aviso de que parou.
    var custom by remember(videoId, fullscreenButton) { mutableStateOf<CustomView?>(null) }
    var stopped by remember(videoId, fullscreenButton) { mutableStateOf(false) }
    val holder = remember(videoId, fullscreenButton) {
        YouTubeWebHolder(
            context = context,
            videoId = videoId,
            fullscreenButton = fullscreenButton,
            onOpenOutside = { currentOpenExternally() },
            onCustomView = { custom = it },
            onStopped = { stopped = true },
        )
    }

    // Fora da tela o player pausa e não retoma sozinho (como o de vídeo): quem retoma é o usuário.
    DisposableEffect(holder, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> holder.pause()
                Lifecycle.Event.ON_START -> holder.resume()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val view = holder.webView
    if (view == null || stopped) {
        Box(modifier.background(Color.Black).padding(12.dp), contentAlignment = Alignment.Center) {
            Text(
                stringResource(Res.string.embed_youtube_stopped),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    val label = stringResource(Res.string.embed_youtube_player)
    AndroidView(
        modifier = modifier,
        factory = { view },
        update = { it.contentDescription = label },
        // O dono fecha o WebView (e o tira da tela antes de destruí-lo); aqui não há nada a soltar.
        onRelease = { },
    )
    custom?.let { shown ->
        Dialog(
            onDismissRequest = holder::hideCustomView,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            HideSystemBarsInDialog()
            AndroidView(modifier = Modifier.fillMaxSize().background(Color.Black), factory = { shown.view })
        }
    }
}

/**
 * Dono do `WebView` de uma composição: o cria, o endurece, o carrega quando a composição é aplicada e o fecha ao sair dela,
 * inclusive se ela for abandonada antes de aplicada, ou quando o app trava ([YouTubeWebData.wipe]) (regra 7). [close] é
 * idempotente. Sem WebView no aparelho (o componente do sistema ausente ou desligado) não há view, e a tela mostra o aviso.
 */
internal class YouTubeWebHolder(
    private val context: Context,
    private val videoId: String,
    private val fullscreenButton: Boolean,
    private val onOpenOutside: () -> Unit,
    private val onCustomView: (CustomView?) -> Unit,
    private val onStopped: () -> Unit,
) : RememberObserver {
    private var closed = false
    private var custom: CustomView? = null

    val webView: WebView? = createWebView()

    override fun onRemembered() {
        val view = webView ?: return
        YouTubeWebData.register(this)
        // Só aqui o YouTube é contatado: a composição já foi aplicada, e ela só existe depois do toque no ▶.
        view.loadDataWithBaseURL(youTubeBaseUrl(context.packageName), youTubePage(videoId, fullscreenButton), "text/html", "utf-8", null)
    }

    override fun onForgotten() = close()

    override fun onAbandoned() = close()

    fun pause() {
        if (!closed) webView?.onPause()
    }

    fun resume() {
        if (!closed) webView?.onResume()
    }

    /** Sai da tela cheia do próprio player: o WebView volta ao cartão e segue de onde estava. */
    fun hideCustomView() {
        val shown = custom ?: return
        custom = null
        onCustomView(null)
        shown.callback.onCustomViewHidden()
    }

    /**
     * Destrói o WebView e apaga o que o Chromium guardou (cookies, armazenamento, cache e histórico). Idempotente; só na
     * thread principal.
     */
    fun close() {
        if (closed) return
        closed = true
        YouTubeWebData.forget(this)
        custom?.callback?.onCustomViewHidden()
        custom = null
        val view = webView ?: return
        view.stopLoading()
        (view.parent as? ViewGroup)?.removeView(view)
        view.clearCache(true)
        view.clearHistory()
        view.clearFormData()
        view.destroy()
        YouTubeWebData.clearStorage()
    }

    /** O WebView endurecido, ou null se o aparelho não o tem (o componente do sistema ausente ou desligado). */
    private fun createWebView(): WebView? {
        val view = try {
            WebView(context)
        } catch (_: Exception) {
            return null
        }
        // Do tamanho da caixa: sem isto o `AndroidView` o põe em WRAP_CONTENT, o WebView passa a medir a altura pelo
        // conteúdo e o `height:100%` da página (o iframe do player) vira zero — o player toca, mas nada aparece.
        view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        harden(view)
        return view
    }

    // JavaScript ligado: o player do YouTube não roda sem ele. A página é constante e o conteúdo é do YouTube.
    @SuppressLint("SetJavaScriptEnabled")
    private fun harden(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            // O que o Chromium poderia ler ou guardar além do player: nada. O padrão do cache é o do HTTP; aqui ele se ignora.
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            domStorageEnabled = false
            setGeolocationEnabled(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            // O toque no ▶ do app é quem pediu para tocar; sem isto o WebView exigiria outro toque no player.
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
            builtInZoomControls = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        // Nada de menu de link ou de texto (copiar, abrir, compartilhar) sobre o conteúdo do player: o toque longo se encerra aqui.
        view.setOnLongClickListener { true }
        view.isHapticFeedbackEnabled = false
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = false
        view.overScrollMode = View.OVER_SCROLL_NEVER
        view.setBackgroundColor(android.graphics.Color.BLACK)
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                when (youTubeNavigation(request.url.toString(), request.isForMainFrame)) {
                    WebNavigation.Allow -> false
                    WebNavigation.Block -> true
                    WebNavigation.OpenOutside -> {
                        onOpenOutside()
                        true
                    }
                }

            // A verificação do certificado fica ligada: um erro nunca é ignorado.
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) = handler.cancel()

            // O processo de renderização morreu (pouca memória, por exemplo): sem tratar isto o app inteiro cai.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                onStopped()
                view.post { close() }
                return true
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            // O player não pede câmera, microfone nem nada: tudo é negado (a mídia protegida por DRM fica de fora).
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) =
                callback.invoke(origin, false, false)

            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean = result.cancelAndHandled()

            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean = result.cancelAndHandled()

            override fun onJsBeforeUnload(view: WebView, url: String, message: String, result: JsResult): Boolean = result.cancelAndHandled()

            override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String?, result: JsPromptResult): Boolean =
                result.cancelAndHandled()

            // A mensagem do console da página (do YouTube) não vai para o log do app.
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean = true

            // O botão de tela cheia do próprio player: a mesma view vai para um diálogo e volta, sem recarregar.
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                val shown = CustomView(view, callback)
                custom = shown
                onCustomView(shown)
            }

            override fun onHideCustomView() {
                val shown = custom ?: return
                custom = null
                onCustomView(null)
                shown.callback.onCustomViewHidden()
            }
        }
    }

    private fun JsResult.cancelAndHandled(): Boolean {
        cancel()
        return true
    }
}

/**
 * O que o `WebView` do player deixa no aparelho (exceção da regra 8) e como se apaga. O Chromium guarda o perfil dele em
 * `app_webview/` (cookies, armazenamento, estado de rede) e `cache/WebView/`, em claro. Os dados saem de três jeitos:
 * ao fechar o player ([YouTubeWebHolder.close]), ao travar o app ([wipe], ligado ao `AppLock`) e, se o processo morreu com o
 * player aberto, na próxima abertura do app ([sweepLeftovers]), antes de qualquer WebView existir.
 */
internal object YouTubeWebData : WebPlayerData {
    // Só na thread principal.
    private val live = mutableSetOf<YouTubeWebHolder>()

    fun register(holder: YouTubeWebHolder) {
        live += holder
    }

    fun forget(holder: YouTubeWebHolder) {
        live -= holder
    }

    /** Fecha os players abertos, que apagam o que guardaram. O app travou: a tela pode ainda não ter saído da composição. */
    override fun wipe() {
        val closeAll = { live.toList().forEach(YouTubeWebHolder::close) }
        if (Looper.myLooper() == Looper.getMainLooper()) closeAll() else Handler(Looper.getMainLooper()).post(closeAll)
    }

    /** Cookies e armazenamento do Chromium; o cache e o histórico são de cada WebView, apagados antes de ele ser destruído. */
    fun clearStorage() {
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
        WebStorage.getInstance().deleteAllData()
    }

    /**
     * Apaga a pasta do perfil e a do cache do Chromium, que um processo encerrado à força com o player aberto deixou. Só é
     * seguro antes de o primeiro WebView do processo ser criado, e o app não usa WebView para mais nada.
     */
    fun sweepLeftovers(context: Context) {
        listOf(File(context.dataDir, "app_webview"), File(context.cacheDir, "WebView")).forEach { dir ->
            if (dir.exists()) dir.deleteRecursively()
        }
    }
}
