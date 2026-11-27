package chat.papo.mobile

import chat.papo.mobile.ui.about.LicenseInfo
import chat.papo.mobile.ui.about.libraryRows
import chat.papo.mobile.ui.about.parseLibraryRows
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.entity.License
import com.mikepenz.aboutlibraries.entity.Scm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** As linhas da tela de licenças: ordem, licenças, e o que falta no POM. */
class LibraryRowsTest {
    private fun library(
        id: String,
        name: String = id,
        version: String? = "1.0",
        website: String? = null,
        scmUrl: String? = null,
        licenses: Set<License> = emptySet(),
    ) = Library(
        uniqueId = id,
        artifactVersion = version,
        name = name,
        description = null,
        website = website,
        developers = emptyList(),
        organization = null,
        scm = scmUrl?.let { Scm(null, null, it) },
        licenses = licenses,
    )

    private fun license(name: String, url: String? = null, text: String? = null) =
        License(name = name, url = url, licenseContent = text, hash = name)

    private fun rows(vararg libraries: Library) = libraryRows(Libs(libraries.toList(), emptySet()))

    @Test
    fun semBibliotecasNaoHaLinhas() {
        assertEquals(emptyList(), rows())
    }

    @Test
    fun umaBibliotecaViraUmaLinhaComSeusDados() {
        val apache = license("Apache License 2.0", url = "https://www.apache.org/licenses/LICENSE-2.0.txt", text = "texto")
        val row = rows(library("io.ktor:ktor-client-core", name = "ktor-client-core", version = "3.6.1", website = "https://ktor.io", licenses = setOf(apache))).single()

        assertEquals("io.ktor:ktor-client-core", row.id)
        assertEquals("ktor-client-core", row.name)
        assertEquals("3.6.1", row.version)
        assertEquals("https://ktor.io", row.website)
        assertEquals(listOf(LicenseInfo("Apache License 2.0", "https://www.apache.org/licenses/LICENSE-2.0.txt", "texto")), row.licenses)
    }

    @Test
    fun ordemPeloNomeSemDiferenciarMaiusculas() {
        val names = rows(library("c", name = "charlie"), library("a", name = "Bravo"), library("b", name = "alpha")).map { it.name }

        assertEquals(listOf("alpha", "Bravo", "charlie"), names)
    }

    @Test
    fun nomesIguaisDesempatamPeloId() {
        val ids = rows(library("z:runtime", name = "Runtime"), library("a:runtime", name = "Runtime")).map { it.id }

        assertEquals(listOf("a:runtime", "z:runtime"), ids)
    }

    @Test
    fun variasLicencasVemEmOrdemDeNome() {
        val row = rows(library("x", licenses = setOf(license("MIT"), license("Apache License 2.0"), license("bsd")))).single()

        assertEquals(listOf("Apache License 2.0", "bsd", "MIT"), row.licenses.map { it.name })
    }

    @Test
    fun semLicencaAListaFicaVazia() {
        assertEquals(emptyList(), rows(library("x")).single().licenses)
    }

    @Test
    fun semVersaoOuComVersaoEmBrancoNaoHaVersao() {
        val versions = rows(library("a", version = null), library("b", version = "  ")).map { it.version }

        assertEquals(listOf(null, null), versions)
    }

    @Test
    fun nomeEmBrancoUsaOId() {
        assertEquals("org.exemplo:lib", rows(library("org.exemplo:lib", name = " ")).single().name)
    }

    @Test
    fun paginaVemDoPomEDoRepositorioQuandoFalta() {
        val websites = rows(
            library("a", website = "https://pagina.exemplo"),
            library("b", website = " ", scmUrl = "https://repo.exemplo/b"),
            library("c", website = null, scmUrl = "https://repo.exemplo/c"),
            library("d", website = null, scmUrl = " "),
            library("e"),
        ).associate { it.id to it.website }

        assertEquals(
            mapOf("a" to "https://pagina.exemplo", "b" to "https://repo.exemplo/b", "c" to "https://repo.exemplo/c", "d" to null, "e" to null),
            websites,
        )
    }

    @Test
    fun licencaSemNomeUsaOEnderecoEQuemNaoTemNenhumDosDoisSai() {
        val row = rows(
            library(
                "x",
                licenses = setOf(
                    license(name = " ", url = "https://licenca.exemplo"),
                    License(name = "", url = null, hash = "vazia"),
                    License(name = "Com texto em branco", url = " ", licenseContent = " ", hash = "branco"),
                ),
            ),
        ).single()

        assertEquals(
            listOf(LicenseInfo("Com texto em branco", null, null), LicenseInfo("https://licenca.exemplo", "https://licenca.exemplo", null)),
            row.licenses,
        )
    }

    @Test
    fun semJsonNaoHaLista() {
        assertNull(parseLibraryRows(null))
    }
}
