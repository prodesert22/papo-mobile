package chat.papo.mobile.ui.about

import chat.papo.mobile.core.BuildInfo

/** O que a tela Sobre mostra da versão: cada parte é null quando a compilação não a tem. */
data class VersionSummary(
    /** O `X.Y.Z` sem o sufixo de beta. */
    val release: String?,
    /** O N de `-beta.N`. */
    val beta: Int?,
    /** O `versionCode`, que o Android compara nas atualizações. */
    val build: Long?,
    val development: Boolean,
)

private val BetaVersion = Regex("""(.+)-beta\.(\d+)""")

/** Separa [build] nas partes da tela. Um `versionName` fora do formato `X.Y.Z[-beta.N]` aparece inteiro, sem beta. */
fun describeVersion(build: BuildInfo): VersionSummary {
    val name = build.versionName.trim()
    val match = BetaVersion.matchEntire(name)
    val beta = match?.groupValues?.get(2)?.toIntOrNull()
    val release = if (match != null && beta != null) match.groupValues[1] else name.ifEmpty { null }
    return VersionSummary(release, beta, build.versionCode.takeIf { it > 0 }, build.debuggable)
}

/** O texto de "Copiar versão", para colar num relatório: `Papo Mobile 0.9.0-beta.2 (90002)`. */
fun versionClipboardText(appName: String, build: BuildInfo): String {
    val name = build.versionName.trim()
    return buildList {
        add(appName)
        if (name.isNotEmpty()) add(name)
        if (build.versionCode > 0) add("(${build.versionCode})")
    }.joinToString(" ")
}

/** Os endereços que a tela Sobre abre, todos `https` (a regra 6 ainda os confere em quem abre). */
enum class AboutLink(val url: String) {
    Source("https://github.com/prodesert22/papo-mobile"),
    Releases("https://github.com/prodesert22/papo-mobile/releases"),
    Server("https://github.com/prodesert22/papo-backend"),
    Web("https://github.com/prodesert22/papo-frontend"),
    Terminal("https://github.com/prodesert22/papo-tui"),
    Developer("https://github.com/prodesert22"),
}

/** [url] sem o esquema, para a linha de resumo do link: `github.com/prodesert22`. */
fun displayUrl(url: String): String = url.substringAfter("://")

/**
 * O texto de [license] (como o GNU o distribui, com quebra de linha fixa em cerca de 80 colunas) com cada parágrafo numa
 * linha só, para a tela estreita refluir. Os parágrafos são separados por linha em branco; o recuo de cada linha cai.
 */
fun readableLicense(license: String): String =
    license.replace("\r\n", "\n")
        .split(Regex("\n[ \t]*\n"))
        .map { paragraph -> paragraph.lines().joinToString(" ") { it.trim() }.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n\n")
