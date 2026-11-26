package chat.papo.mobile.chat

import chat.papo.mobile.account.ServerUrl

/** O que falta a uma senha nova para a política do servidor. O servidor pode exigir mais; a palavra final é dele. */
enum class PasswordProblem { TooShort, NoUppercase, NoSymbol }

/** Tamanho mínimo, em code points, como o servidor conta. */
const val MIN_PASSWORD_LENGTH = 8

/**
 * Confere [password] com a política que o servidor aplica (no mínimo 8 code points, uma letra maiúscula e um caractere
 * que não seja letra nem número). Vazio quando a senha serve. Um caractere fora do plano básico é tratado como símbolo,
 * como são os emojis; uma letra ou um número desses planos que o servidor contaria como tal faz esta conferência aceitar
 * a mais, e então o servidor recusa com o motivo.
 */
fun passwordProblems(password: String): List<PasswordProblem> {
    var points = 0
    var upper = false
    var symbol = false
    var i = 0
    while (i < password.length) {
        // Um par de substitutos é um só code point.
        val pair = password[i].isHighSurrogate() && i + 1 < password.length && password[i + 1].isLowSurrogate()
        when (if (pair) CharKind.Symbol else kindOf(password[i])) {
            CharKind.Upper -> upper = true
            CharKind.Symbol -> symbol = true
            CharKind.Plain -> Unit
        }
        i += if (pair) 2 else 1
        points++
    }
    return buildList {
        if (points < MIN_PASSWORD_LENGTH) add(PasswordProblem.TooShort)
        if (!upper) add(PasswordProblem.NoUppercase)
        if (!symbol) add(PasswordProblem.NoSymbol)
    }
}

private enum class CharKind { Upper, Plain, Symbol }

/** Maiúscula; letra ou número que não é maiúscula; ou qualquer outra coisa (símbolo, pontuação, espaço). */
private fun kindOf(c: Char): CharKind = when (c.category) {
    CharCategory.UPPERCASE_LETTER -> CharKind.Upper
    CharCategory.LOWERCASE_LETTER, CharCategory.TITLECASE_LETTER, CharCategory.MODIFIER_LETTER, CharCategory.OTHER_LETTER,
    CharCategory.DECIMAL_DIGIT_NUMBER, CharCategory.LETTER_NUMBER, CharCategory.OTHER_NUMBER,
    -> CharKind.Plain
    else -> CharKind.Symbol
}

/** O resultado de conferir a senha nova e a repetição dela antes de mandar qualquer coisa ao servidor. */
sealed interface PasswordCheck {
    data object Ok : PasswordCheck

    data class Weak(val problems: List<PasswordProblem>) : PasswordCheck

    data object Mismatch : PasswordCheck
}

fun checkNewPassword(password: String, repeat: String): PasswordCheck {
    val problems = passwordProblems(password)
    return when {
        problems.isNotEmpty() -> PasswordCheck.Weak(problems)
        password != repeat -> PasswordCheck.Mismatch
        else -> PasswordCheck.Ok
    }
}

/** O que se tirou de um link de redefinição. [otherHost] é o endereço do link quando ele não é o do servidor, senão null. */
data class ResetLink(val token: String, val otherHost: String?)

private const val RESET_SEGMENT = "passwordchange"
private const val MAX_RESET_TOKEN = 256

/**
 * Tira o token de [input]: o link completo (`https://.../passwordchange/<token>`, onde vale o último segmento depois
 * de `passwordchange`) ou o próprio token. O token tem até 256 caracteres do alfabeto base64 de URL; qualquer outra
 * coisa, e links que não sejam http ou https, dão null. O endereço do link costuma ser o do site, não o da API, então
 * ele só é devolvido em [ResetLink.otherHost] para a tela mostrar os dois e pedir uma confirmação; o token vai ao
 * servidor da conta, nunca ao endereço do link.
 */
fun parseResetLink(input: String, server: ServerUrl): ResetLink? {
    val text = input.trim()
    if (text.isEmpty()) return null
    // Um token cru não tem barra nem dois-pontos; qualquer um dos dois quer dizer que é um endereço.
    if ('/' !in text && ':' !in text) return text.takeIf(::isResetToken)?.let { ResetLink(it, otherHost = null) }
    return resetLinkFromUrl(text, server)
}

private fun resetLinkFromUrl(text: String, server: ServerUrl): ResetLink? {
    val schemeEnd = text.indexOf("://")
    if (schemeEnd < 0) return null
    val scheme = text.substring(0, schemeEnd).lowercase()
    if (scheme != "http" && scheme != "https") return null
    val rest = text.substring(schemeEnd + 3).substringBefore('#').substringBefore('?')
    val slash = rest.indexOf('/')
    if (slash < 0) return null
    val segments = rest.substring(slash).split('/').filter { it.isNotEmpty() }
    val marker = segments.indexOf(RESET_SEGMENT)
    if (marker < 0) return null
    val token = segments.drop(marker + 1).lastOrNull()?.takeIf(::isResetToken) ?: return null

    val link = (ServerUrl.parse("$scheme://${rest.substring(0, slash)}") as? ServerUrl.Parsed.Ok)?.url ?: return null
    return ResetLink(token, otherHost = hostName(link).takeUnless { it == hostName(server) })
}

private fun isResetToken(text: String): Boolean =
    text.length in 1..MAX_RESET_TOKEN && text.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }

/** O nome do servidor sem a porta, já em minúsculas (o [ServerUrl] normaliza). */
private fun hostName(url: ServerUrl): String {
    val host = url.host
    return if (host.startsWith("[")) host.substringBefore(']') + "]" else host.substringBefore(':')
}

/**
 * Tira de [text] cada um dos [secrets] (senha, token) que o servidor tenha repetido nele, para que uma mensagem de erro
 * nunca os leve até a tela ou um registro. Segredos com menos de 4 caracteres não entram: trocá-los apagaria o texto.
 */
fun redactSecrets(text: String, vararg secrets: String): String =
    secrets.filter { it.length >= 4 }.fold(text) { acc, secret -> acc.replace(secret, "…") }
