package chat.papo.mobile.platform

import chat.papo.mobile.chat.isYouTubeId

/**
 * A página do player: o HTML é constante do app, e só o id do vídeo e o botão de tela cheia a variam. É só um iframe do
 * `embed` do YouTube; o conteúdo não vem do servidor nem do site, e [videoId] só entra depois de validado.
 *
 * - `loadDataWithBaseURL` com o id do app como origem é o que dá ao pedido do iframe o `Referer` que o YouTube exige para
 *   identificar o app (sem ele o player responde com erro de configuração); a política de referência não o suprime.
 * - A política de conteúdo da página só deixa carregar um iframe do YouTube: ela não tem script, imagem nem nada mais.
 * - `autoplay`: o toque no ▶ do app já é o pedido de tocar. `fs` é o botão de tela cheia do YouTube.
 */
internal fun youTubePage(videoId: String, fullscreenButton: Boolean): String {
    check(isYouTubeId(videoId)) { "id de vídeo do YouTube inválido" }
    val fs = if (fullscreenButton) 1 else 0
    return """<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; frame-src https://www.youtube.com; style-src 'unsafe-inline'">
<meta name="referrer" content="strict-origin-when-cross-origin">
<style>html,body{margin:0;height:100%;background:#000}iframe{position:absolute;top:0;left:0;width:100%;height:100%;border:0}</style>
</head>
<body>
<iframe src="https://www.youtube.com/embed/$videoId?autoplay=1&amp;playsinline=1&amp;fs=$fs" title="YouTube" allow="autoplay; encrypted-media; picture-in-picture; fullscreen" allowfullscreen referrerpolicy="strict-origin-when-cross-origin"></iframe>
</body>
</html>
"""
}

/** O endereço que o WebView usa como origem da página do player: o id do app, como o YouTube pede. */
internal fun youTubeBaseUrl(applicationId: String): String = "https://$applicationId"

/** O que fazer com uma navegação que o WebView do player quer fazer. */
internal enum class WebNavigation {
    /** Deixa seguir. */
    Allow,

    /** Cancela, sem mais nada. */
    Block,

    /** Cancela e abre o vídeo no YouTube, fora do app. */
    OpenOutside,
}

/**
 * Nenhuma página nova abre no WebView: a do quadro principal é sempre a do app (a que o `loadDataWithBaseURL` pôs), então
 * qualquer navegação dele (um link do player, um anúncio, um redirecionamento) é uma saída do embed e vira
 * [WebNavigation.OpenOutside], que abre o vídeo no YouTube e não o endereço pedido, que não é de confiança. Os quadros de
 * dentro do player (o próprio iframe e o que o YouTube põe nele) só podem carregar `https`; o resto (`http`, `intent:`,
 * `file:`, `content:`, `javascript:`) é bloqueado sem avisar ninguém.
 */
internal fun youTubeNavigation(url: String, isMainFrame: Boolean): WebNavigation = when {
    isMainFrame -> WebNavigation.OpenOutside
    url.startsWith("https://", ignoreCase = true) || url == "about:blank" || url == "about:srcdoc" -> WebNavigation.Allow
    else -> WebNavigation.Block
}
