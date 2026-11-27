package chat.papo.mobile

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Os textos legais que vão no app são cópias dos da raiz do repositório: a licença do app (`LICENSE`) e as dos recursos
 * incluídos (`licenses/`). Uma mudança num original sem a cópia, ou o contrário, quebra este teste.
 */
class LegalTextsTest {
    private val bundled = File("src/commonMain/composeResources/files/legal")
    private val repository = File("..")

    @Test
    fun aLicencaDoAppEhIgualAoLicenseDaRaiz() {
        assertContentEquals(File(repository, "LICENSE").readBytes(), File(bundled, "LICENSE.txt").readBytes())
    }

    @Test
    fun cadaLicencaDeRecursoTemUmaCopiaIgual() {
        val originals = File(repository, "licenses").listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }
        val copies = bundled.listFiles().orEmpty().filter { it.isFile && it.name != "LICENSE.txt" }.sortedBy { it.name }
        assertEquals(originals.map { it.name }, copies.map { it.name })
        originals.zip(copies).forEach { (original, copy) -> assertContentEquals(original.readBytes(), copy.readBytes(), original.name) }
    }
}
