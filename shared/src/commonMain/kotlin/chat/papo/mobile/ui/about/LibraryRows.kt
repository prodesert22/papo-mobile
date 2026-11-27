package chat.papo.mobile.ui.about

import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.entity.License

/** Uma licença de uma biblioteca. [text] só existe quando o texto veio junto na compilação; [url] vem do POM (dado externo). */
data class LicenseInfo(val name: String, val url: String?, val text: String?)

/** Uma biblioteca de terceiros como a tela de licenças a mostra. [website] e as URLs das licenças vêm dos POMs. */
data class LibraryRow(
    val id: String,
    val name: String,
    val version: String?,
    val website: String?,
    /** Vazia quando o POM não informa licença: a tela mostra "Licença não informada". */
    val licenses: List<LicenseInfo>,
)

/** As linhas da lista, em ordem alfabética do nome (sem diferenciar maiúsculas); o id desempata nomes iguais. */
fun libraryRows(libs: Libs): List<LibraryRow> =
    libs.libraries.map(::libraryRow).sortedWith(compareBy({ it.name.lowercase() }, { it.id }))

/**
 * Lê o JSON gerado pelo plugin e monta as linhas. Devolve null quando não há JSON, quando ele não pôde ser lido ou quando
 * não lista nenhuma biblioteca: a tela mostra então que a lista não está disponível em vez de uma lista vazia.
 */
fun parseLibraryRows(json: String?): List<LibraryRow>? {
    if (json == null) return null
    // A leitura da biblioteca é síncrona e a falha dela pode ser de qualquer tipo; nada aqui suspende, então não há
    // cancelamento a repassar.
    val libs = try {
        Libs.Builder().withJson(json).build()
    } catch (_: Exception) {
        return null
    }
    return libraryRows(libs).ifEmpty { null }
}

private fun libraryRow(library: Library) = LibraryRow(
    id = library.uniqueId,
    name = library.name.ifBlank { library.uniqueId },
    version = library.artifactVersion?.takeIf { it.isNotBlank() },
    website = library.website?.takeIf { it.isNotBlank() } ?: library.scm?.url?.takeIf { it.isNotBlank() },
    licenses = library.licenses.mapNotNull(::licenseInfo).sortedBy { it.name.lowercase() },
)

/** Uma licença sem nome e sem endereço não diz nada ao usuário: a biblioteca passa a constar como sem licença informada. */
private fun licenseInfo(license: License): LicenseInfo? {
    val url = license.url?.takeIf { it.isNotBlank() }
    val name = license.name.ifBlank { url } ?: return null
    return LicenseInfo(name, url, license.licenseContent?.takeIf { it.isNotBlank() })
}
