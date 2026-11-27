package chat.papo.mobile

import chat.papo.mobile.chat.isDmBlockedType
import chat.papo.mobile.data.api.ApiException
import chat.papo.mobile.ui.components.UiError
import chat.papo.mobile.ui.components.toUiError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DmBlockedErrorTest {
    @Test
    fun tipoTerminadoEmDmBlockedContaQualquerQueSejaOPrefixo() {
        assertTrue(isDmBlockedType("https://papo.test/problems/dm-blocked"))
        assertTrue(isDmBlockedType("/dm-blocked"))
    }

    @Test
    fun outrosTiposEAusenciaNaoContam() {
        assertFalse(isDmBlockedType(null))
        assertFalse(isDmBlockedType(""))
        assertFalse(isDmBlockedType("https://papo.test/problems/forbidden"))
        assertFalse(isDmBlockedType("https://papo.test/problems/dm-blocked/extra"))
        assertFalse(isDmBlockedType("dm-blocked")) // sem a barra, não é o último segmento
    }

    @Test
    fun forbiddenComODmBlockedVira_DmBlocked() {
        val error = ApiException(403, "Proibido", "detalhe", "https://papo.test/problems/dm-blocked")
        assertEquals(UiError.DmBlocked, error.toUiError())
    }

    @Test
    fun outroStatusComOMesmoTipoNaoViraDmBlocked() {
        val error = ApiException(400, "Erro", "detalhe", "https://papo.test/problems/dm-blocked")
        assertEquals(UiError.Server("detalhe"), error.toUiError())
    }

    @Test
    fun forbiddenSemTipoMostraOTextoDoServidor() {
        assertEquals(UiError.Server("detalhe"), ApiException(403, "Proibido", "detalhe", null).toUiError())
    }
}
