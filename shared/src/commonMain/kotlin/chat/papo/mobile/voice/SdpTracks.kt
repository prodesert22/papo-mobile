package chat.papo.mobile.voice

/**
 * Liga cada m-line da resposta do servidor à faixa que ele colocou nela: `mid` → id da faixa
 * (`papo-audio-N`, `papo-screen-audio-N`), o mesmo id de `voice_audio_routes`. O id vem da linha
 * `a=msid:<stream> <faixa>` da m-line; o stream pode ser `-`.
 *
 * M-lines sem `mid` ou sem `msid` com id de faixa (as que o servidor deixa vazias) ficam de fora,
 * assim como linhas antes da primeira m-line. Aceita quebras de linha CRLF e LF. Pura: não guarda o
 * SDP nem o devolve em erro.
 */
fun answerTrackIds(sdp: String): Map<String, String> {
    val ids = LinkedHashMap<String, String>()
    var inSection = false
    var mid: String? = null
    var trackId: String? = null

    fun closeSection() {
        val m = mid
        val t = trackId
        if (inSection && m != null && t != null) ids[m] = t
        mid = null
        trackId = null
    }

    for (raw in sdp.split('\n')) {
        val line = raw.trimEnd('\r')
        when {
            line.startsWith("m=") -> {
                closeSection()
                inSection = true
            }
            line.startsWith("a=mid:") -> mid = line.removePrefix("a=mid:").trim().ifEmpty { null }
            // Só a primeira msid de cada m-line vale; o formato é "<stream> <faixa>".
            line.startsWith("a=msid:") && trackId == null ->
                trackId = line.removePrefix("a=msid:").trim().split(' ').filter { it.isNotEmpty() }.getOrNull(1)
        }
    }
    closeSection()
    return ids
}
