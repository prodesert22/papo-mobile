package chat.papo.mobile.account

/**
 * Endereço normalizado de um servidor Papo. Duas entradas que apontam para o
 * mesmo backend produzem o mesmo [value], que é o que identifica o servidor.
 */
data class ServerUrl(val value: String) {
    val isSecure: Boolean get() = value.startsWith("https://")

    /** `host[:porta][/prefixo]`, como mostrado ao usuário. */
    val display: String get() = value.substringAfter("://")

    val host: String get() = display.substringBefore('/')

    /** Endereço do WebSocket deste servidor. */
    val webSocketUrl: String
        get() = (if (isSecure) "wss://" else "ws://") + display + "/ws"

    fun endpoint(path: String): String = value + path

    enum class Problem { Empty, Scheme, Host, Port, Credentials, Path }

    sealed interface Parsed {
        data class Ok(val url: ServerUrl) : Parsed
        data class Invalid(val problem: Problem) : Parsed
    }

    companion object {
        fun parse(input: String): Parsed {
            val raw = input.trim()
            if (raw.isEmpty()) return Parsed.Invalid(Problem.Empty)

            val scheme: String
            val afterScheme: String
            val schemeEnd = raw.indexOf("://")
            if (schemeEnd >= 0) {
                scheme = raw.substring(0, schemeEnd).lowercase()
                afterScheme = raw.substring(schemeEnd + 3)
            } else {
                scheme = "https"
                afterScheme = raw
            }
            if (scheme != "http" && scheme != "https") return Parsed.Invalid(Problem.Scheme)

            val withoutQuery = afterScheme.substringBefore('?').substringBefore('#')
            val slash = withoutQuery.indexOf('/')
            val authority = if (slash >= 0) withoutQuery.substring(0, slash) else withoutQuery
            val rawPath = if (slash >= 0) withoutQuery.substring(slash) else ""

            if ('@' in authority) return Parsed.Invalid(Problem.Credentials)

            val host: String
            val portText: String?
            if (authority.startsWith("[")) {
                val close = authority.indexOf(']')
                if (close < 0) return Parsed.Invalid(Problem.Host)
                host = authority.substring(0, close + 1).lowercase()
                val rest = authority.substring(close + 1)
                portText = when {
                    rest.isEmpty() -> null
                    rest.startsWith(":") -> rest.substring(1)
                    else -> return Parsed.Invalid(Problem.Host)
                }
                if (host.length < 4 || !host.substring(1, host.length - 1).all { it.isHexDigit() || it == ':' || it == '.' }) {
                    return Parsed.Invalid(Problem.Host)
                }
            } else {
                val colon = authority.lastIndexOf(':')
                host = (if (colon >= 0) authority.substring(0, colon) else authority).lowercase()
                portText = if (colon >= 0) authority.substring(colon + 1) else null
                val validHost = host.isNotEmpty() &&
                    host.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' } &&
                    !host.startsWith('.') && !host.endsWith('.') && ".." !in host &&
                    !host.startsWith('-') && !host.endsWith('-')
                if (!validHost) return Parsed.Invalid(Problem.Host)
            }

            var port: Int? = null
            if (portText != null) {
                port = portText.takeIf { it.isNotEmpty() && it.length <= 5 && it.all(Char::isDigit) }?.toInt()
                if (port == null || port !in 1..65535) return Parsed.Invalid(Problem.Port)
                if ((scheme == "https" && port == 443) || (scheme == "http" && port == 80)) port = null
            }

            val path = rawPath.trimEnd('/')
            if (path.any { it.isWhitespace() } || "//" in path) return Parsed.Invalid(Problem.Path)

            val value = buildString {
                append(scheme).append("://").append(host)
                if (port != null) append(':').append(port)
                append(path)
            }
            return Parsed.Ok(ServerUrl(value))
        }

        private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f'
    }
}
