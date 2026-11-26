package chat.papo.mobile.media

import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.data.api.AttachmentDto
import chat.papo.mobile.data.api.EmbedDto

/**
 * Um vídeo que o player sabe abrir. A [url] é sempre do servidor da conta dona da mensagem,
 * nunca uma URL de fora: quem a abre usa o `PapoClient` dessa conta, que põe o cookie `Auth`
 * (regra 4). Quem a monta são [playableAttachment] e [relayVideo].
 */
data class PlayableMedia(
    /** Identifica a mídia entre recomposições e giros de tela: `attachment:<id>` ou `embed:<id>`. */
    val key: String,
    val url: String,
    val mimeType: String,
    /** Nome mostrado no visualizador; vazio quando a origem não dá um. */
    val title: String,
    val width: Int?,
    val height: Int?,
)

/**
 * O anexo toca no player? Vale para qualquer tipo de vídeo (MIME começando por `video/`): o
 * servidor fareja só o MIME e não diz quais codecs o aparelho decodifica, então um formato sem
 * suporte falha depois, como [PlaybackFailure.Unsupported]. Anexo `blocked` não toca (o
 * servidor o tira da conversa).
 */
fun playableAttachment(server: ServerUrl, attachment: AttachmentDto): PlayableMedia? {
    if (!attachment.isVideo) return null
    if (attachment.moderationStatus == MODERATION_BLOCKED) return null
    if (!PATH_TOKEN.matches(attachment.id)) return null
    return PlayableMedia(
        key = "attachment:${attachment.id}",
        url = server.endpoint("/attachments/${attachment.id}"),
        mimeType = attachment.mimeType,
        title = attachment.fileName,
        width = attachment.width,
        height = attachment.height,
    )
}

/**
 * O vídeo direto do embed toca pelo relay do servidor (`GET /embeds/{id}/video`)? Só quando o
 * embed traz um `video` com MIME de arquivo (`video/mp4|webm|ogg`) e URL HTTPS que não é do
 * YouTube. O YouTube é uma página de iframe, sem MIME, e o relay a recusa com 404. O cliente
 * nunca abre a URL de origem: ela só decide se há o que tocar, e o endereço devolvido é o do
 * relay na conta.
 */
fun relayVideo(server: ServerUrl, embed: EmbedDto): PlayableMedia? {
    val video = embed.video ?: return null
    val mimeType = video.mimeType?.takeIf { it in RELAY_MIME_TYPES } ?: return null
    val source = video.url ?: return null
    if (!source.startsWith("https://", ignoreCase = true)) return null
    if (hostOf(source) in YOUTUBE_HOSTS) return null
    if (!PATH_TOKEN.matches(embed.id)) return null
    return PlayableMedia(
        key = "embed:${embed.id}",
        url = server.endpoint("/embeds/${embed.id}/video"),
        mimeType = mimeType,
        title = embed.title.orEmpty(),
        width = video.width,
        height = video.height,
    )
}

/**
 * Proporção largura/altura da caixa do vídeo, entre 0,5 e 2,5 para que um vídeo muito alto ou
 * muito largo não ocupe a lista inteira. Sem dimensões válidas (o servidor só as dá para
 * alguns contêineres), 16:9.
 */
fun videoAspect(width: Int?, height: Int?): Float {
    if (width == null || height == null || width <= 0 || height <= 0) return DEFAULT_ASPECT
    return (width.toFloat() / height).coerceIn(MIN_ASPECT, MAX_ASPECT)
}

/**
 * A proporção da caixa do cartão de um anexo de vídeo: a do vídeo ([width] e [height]) e, se o servidor não as deu, a da
 * miniatura que ele gerou ([thumbnailWidth] e [thumbnailHeight]), que mostra o mesmo quadro. Sem nenhuma das duas, 16:9.
 */
fun videoCardAspect(width: Int?, height: Int?, thumbnailWidth: Int?, thumbnailHeight: Int?): Float {
    val hasSize = width != null && height != null && width > 0 && height > 0
    return if (hasSize) videoAspect(width, height) else videoAspect(thumbnailWidth, thumbnailHeight)
}

/** Por que o vídeo não tocou. A tela comum escolhe o texto de cada um; o `androidMain` só informa o motivo. */
enum class PlaybackFailure {
    /**
     * 401 depois da renovação única de sessão: a sessão da conta acabou. O app já trata isso (a conta passa a
     * "Sessão encerrada"); o player só diz o motivo, sem tentar entrar de novo.
     */
    SessionEnded,

    /** 403: a sessão vale, mas a conta não tem permissão para ler o canal da mensagem. */
    Forbidden,

    /** 404, ou outro 4xx que o contrato não define: o arquivo não existe, ou o relay não serve este embed. */
    NotFound,

    /** 410 ou 416: o arquivo não está mais como era (removido, ou o trecho pedido já não existe). */
    Gone,

    /** 429: limite de pedidos do servidor. Nunca se repete sozinho; o usuário decide. */
    RateLimited,

    /** 5xx, incluindo o 502 do relay (falha na origem ou as vagas globais do relay cheias). Costuma passar. */
    Unavailable,

    /** O player não lê o contêiner ou não decodifica o codec: o aparelho não toca este formato. */
    Unsupported,

    /** Sem resposta HTTP: conexão caída, tempo esgotado ou fim inesperado do fluxo. */
    Network,
}

/** De onde veio um erro que não tem (ou não deixa) status HTTP para decidir. */
enum class PlaybackErrorKind {
    /** Falha ao ler a fonte: conexão, tempo esgotado, corpo interrompido. */
    Io,

    /** O player recusou o conteúdo: contêiner ou codec sem suporte, dados corrompidos. */
    Decoding,
}

/**
 * Traduz o erro do player em [PlaybackFailure]. Havendo status HTTP de erro (4xx/5xx), ele
 * decide; sem status, ou com um que não é erro, decide [kind].
 */
fun classifyPlayback(httpStatus: Int?, kind: PlaybackErrorKind): PlaybackFailure =
    httpStatus?.let(::failureForStatus) ?: when (kind) {
        PlaybackErrorKind.Io -> PlaybackFailure.Network
        PlaybackErrorKind.Decoding -> PlaybackFailure.Unsupported
    }

/**
 * Espera, em milissegundos, antes de repetir automaticamente depois de [failure], ou null se
 * não se repete. [attempt] é quantas repetições já foram feitas (0 na primeira falha). Só rede
 * e [PlaybackFailure.Unavailable] repetem, no máximo duas vezes (1 s e 3 s): o limite global
 * do servidor é de 10 pedidos por segundo por endereço e cada `Range` conta. Qualquer 4xx
 * nunca repete: o 429 por causa do limite e o 401 ([PlaybackFailure.SessionEnded]) porque o
 * cliente já gastou a única renovação de sessão.
 */
fun retryDelay(failure: PlaybackFailure, attempt: Int): Long? {
    if (failure !in RETRYABLE) return null
    return RETRY_DELAYS_MS.getOrNull(attempt)
}

private val STATUS_FAILURES = mapOf(
    401 to PlaybackFailure.SessionEnded,
    403 to PlaybackFailure.Forbidden,
    404 to PlaybackFailure.NotFound,
    410 to PlaybackFailure.Gone,
    416 to PlaybackFailure.Gone,
    429 to PlaybackFailure.RateLimited,
)

private fun failureForStatus(status: Int): PlaybackFailure? = STATUS_FAILURES[status] ?: when (status) {
    in 500..599 -> PlaybackFailure.Unavailable
    in 400..499 -> PlaybackFailure.NotFound
    else -> null
}

private val RETRYABLE = setOf(PlaybackFailure.Network, PlaybackFailure.Unavailable)
private val RETRY_DELAYS_MS = listOf(1_000L, 3_000L)

private const val MODERATION_BLOCKED = "blocked"

/** O servidor só serve estes tipos pelo relay; outro vira 404. */
private val RELAY_MIME_TYPES = setOf("video/mp4", "video/webm", "video/ogg")

/** O id vai no caminho da URL: só o que um UUID tem, para o endereço nunca sair do servidor da conta. */
private val PATH_TOKEN = Regex("[A-Za-z0-9_-]+")

/** Páginas de player do YouTube e o encurtador: não são arquivo de vídeo e o relay não as serve. */
private val YOUTUBE_HOSTS = setOf(
    "youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be",
    "youtube-nocookie.com", "www.youtube-nocookie.com",
)

/** Host em minúsculas de uma URL absoluta, sem credenciais, porta, caminho, consulta nem fragmento. */
private fun hostOf(url: String): String =
    url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        .substringAfterLast('@').substringBefore(':').lowercase()

private const val MIN_ASPECT = 0.5f
private const val MAX_ASPECT = 2.5f
private const val DEFAULT_ASPECT = 16f / 9f
