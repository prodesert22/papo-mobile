package chat.papo.mobile.core

/**
 * [raw] como um link que o app pode abrir fora dele, ou null. Só passa `http` e `https` (o esquema sem diferença de
 * maiúscula), com um host depois de `//` e sem espaço nem caractere de controle em lugar nenhum: é a regra 6, valendo para
 * todo link que sai do app (mensagens, embeds, telas do app). Devolve o texto como veio, sem espaços nas pontas; o resto
 * (porta, caminho, consulta) é de quem abre.
 */
fun safeExternalUrl(raw: String): String? {
    val url = raw.trim()
    if (url.any { it.isWhitespace() || it.isISOControl() }) return null
    val colon = url.indexOf(':')
    if (colon <= 0) return null
    val scheme = url.substring(0, colon).lowercase()
    if (scheme != "http" && scheme != "https") return null
    val rest = url.substring(colon + 1)
    if (!rest.startsWith("//")) return null
    // O host vai até o primeiro separador de caminho, consulta ou fragmento; sem as credenciais nem a porta.
    val authority = rest.substring(2).takeWhile { it != '/' && it != '?' && it != '#' }
    val host = authority.substringAfterLast('@').substringBefore(':')
    if (host.isEmpty()) return null
    return url
}
