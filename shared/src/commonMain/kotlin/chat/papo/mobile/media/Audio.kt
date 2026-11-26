package chat.papo.mobile.media

import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.data.api.AttachmentDto

/** MIME de áudio como o web o reconhece: `audio/` e um subtipo de token (inclui `audio/mp4` do M4A). */
private val AUDIO_MIME = Regex("audio/[a-z0-9][a-z0-9!#\$&^_.+-]*", RegexOption.IGNORE_CASE)

/**
 * Nome que o web dá a uma gravação de voz: o servidor farejaria `video/webm` ou `video/ogg` num WebM ou Ogg só de áudio,
 * e só o nome diz o que é.
 */
private val RECORDED_AUDIO_NAME = Regex("audio_.*\\.(webm|ogg|wav|mp3|m4a|aac|opus)", RegexOption.IGNORE_CASE)

/** Extensões de áudio de envios antigos, que o servidor ainda não farejava e guardou como genérico. */
private val LEGACY_AUDIO_NAME = Regex(".*\\.(m4a|m4b|m4p|mp3|wav|ogg|opus|aac|flac|wma|aif|aiff)", RegexOption.IGNORE_CASE)

private const val GENERIC_MIME = "application/octet-stream"

/**
 * O anexo é um áudio que o player toca? A regra é a do web (`Attachment.svelte`): MIME de tipo `audio` (qualquer subtipo); ou o nome
 * de uma gravação (`audio_` e uma extensão webm, ogg, wav, mp3, m4a, aac ou opus), seja qual for o MIME; ou, num MIME genérico, uma extensão de áudio. O servidor
 * não diz quais codecs o aparelho decodifica: um formato sem suporte falha depois, como [PlaybackFailure.Unsupported].
 */
fun isAudioAttachment(mimeType: String, fileName: String): Boolean {
    val type = mimeType.trim().lowercase()
    return AUDIO_MIME.matches(type) ||
        RECORDED_AUDIO_NAME.matches(fileName) ||
        (type == GENERIC_MIME && LEGACY_AUDIO_NAME.matches(fileName))
}

/**
 * O áudio que o player toca para [attachment], ou null se o anexo não é um áudio, a moderação o bloqueou (o servidor o
 * tira da conversa) ou o id não cabe num caminho de URL. Como no vídeo, o endereço é sempre o do servidor da conta dona da
 * mensagem, e quem o abre usa o `PapoClient` dela (regra 4).
 */
fun playableAudio(server: ServerUrl, attachment: AttachmentDto): PlayableMedia? {
    if (!isAudioAttachment(attachment.mimeType, attachment.fileName)) return null
    if (moderationView(attachment.moderationStatus) == ModerationView.Blocked) return null
    if (!PATH_TOKEN.matches(attachment.id)) return null
    return PlayableMedia(
        key = "attachment:${attachment.id}",
        url = server.endpoint("/attachments/${attachment.id}"),
        mimeType = attachment.mimeType,
        title = attachment.fileName,
        width = null,
        height = null,
    )
}

/** As velocidades do player de áudio, na ordem em que o botão as alterna. [factor] é o que o motor recebe. */
enum class AudioSpeed(val factor: Float) {
    Normal(1f),
    Fast(1.5f),
    Faster(2f),
    ;

    /** A próxima velocidade; depois da última volta à normal. */
    fun next(): AudioSpeed = entries[(ordinal + 1) % entries.size]
}

/**
 * O tempo como `m:ss` (`h:mm:ss` a partir de uma hora), com os segundos truncados, como o web. Negativo vale 0.
 */
fun formatClock(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = (totalSeconds % 60L).toString().padStart(2, '0')
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$seconds" else "$minutes:$seconds"
}
