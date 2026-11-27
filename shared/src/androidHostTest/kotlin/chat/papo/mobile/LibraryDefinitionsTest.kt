package chat.papo.mobile

import chat.papo.mobile.ui.about.LibraryRow
import chat.papo.mobile.ui.about.LicenseInfo
import chat.papo.mobile.ui.about.parseLibraryRows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** O JSON no formato que o plugin do AboutLibraries gera, lido pelo `aboutlibraries-core` de Android (org.json). */
class LibraryDefinitionsTest {
    private val sample = """
        {
          "libraries": [
            {
              "uniqueId": "net.zetetic:sqlcipher-android",
              "artifactVersion": "4.20.0",
              "name": "sqlcipher-android",
              "website": "https://www.zetetic.net/sqlcipher",
              "licenses": ["sqlcipher"]
            },
            {
              "uniqueId": "io.ktor:ktor-client-core",
              "artifactVersion": "3.6.1",
              "name": "ktor-client-core",
              "scm": {"url": "https://github.com/ktorio/ktor.git"},
              "licenses": ["Apache-2.0", "MIT"]
            },
            {
              "uniqueId": "org.exemplo:sem-licenca",
              "name": "Alpha sem licença"
            }
          ],
          "licenses": {
            "sqlcipher": {
              "name": "SQLCipher Community Edition License",
              "url": "https://www.zetetic.net/sqlcipher/license/",
              "content": "Copyright (c) 2025, ZETETIC LLC"
            },
            "Apache-2.0": {
              "name": "Apache License 2.0",
              "url": "https://www.apache.org/licenses/LICENSE-2.0.txt",
              "spdxId": "Apache-2.0"
            },
            "MIT": {"name": "MIT", "url": "https://opensource.org/license/mit"}
          }
        }
    """.trimIndent()

    @Test
    fun oJsonDoPluginViraAsLinhasDaTela() {
        val rows = parseLibraryRows(sample)

        assertEquals(
            listOf(
                LibraryRow("org.exemplo:sem-licenca", "Alpha sem licença", null, null, emptyList()),
                LibraryRow(
                    id = "io.ktor:ktor-client-core",
                    name = "ktor-client-core",
                    version = "3.6.1",
                    website = "https://github.com/ktorio/ktor.git",
                    licenses = listOf(
                        LicenseInfo("Apache License 2.0", "https://www.apache.org/licenses/LICENSE-2.0.txt", null),
                        LicenseInfo("MIT", "https://opensource.org/license/mit", null),
                    ),
                ),
                LibraryRow(
                    id = "net.zetetic:sqlcipher-android",
                    name = "sqlcipher-android",
                    version = "4.20.0",
                    website = "https://www.zetetic.net/sqlcipher",
                    licenses = listOf(
                        LicenseInfo(
                            "SQLCipher Community Edition License",
                            "https://www.zetetic.net/sqlcipher/license/",
                            "Copyright (c) 2025, ZETETIC LLC",
                        ),
                    ),
                ),
            ),
            rows,
        )
    }

    @Test
    fun jsonSemBibliotecasNaoHaLista() {
        assertNull(parseLibraryRows("""{"libraries": [], "licenses": {}}"""))
    }

    @Test
    fun jsonQuebradoNaoHaLista() {
        assertNull(parseLibraryRows("isto não é json"))
    }
}
