package chat.papo.mobile

import chat.papo.mobile.security.secureWindow
import kotlin.test.Test
import kotlin.test.assertEquals

class SecureWindowTest {
    @Test
    fun semEscolhaComOBloqueioLigadoEscondeOConteudo() {
        assertEquals(true, secureWindow(lockEnabled = true, pref = null))
    }

    @Test
    fun semEscolhaSemOBloqueioLiberaAsCapturas() {
        assertEquals(false, secureWindow(lockEnabled = false, pref = null))
    }

    @Test
    fun escolhaDeBloquearVale_comOBloqueioLigado() {
        assertEquals(true, secureWindow(lockEnabled = true, pref = true))
    }

    @Test
    fun escolhaDeBloquearVale_tambemSemOBloqueio() {
        assertEquals(true, secureWindow(lockEnabled = false, pref = true))
    }

    @Test
    fun escolhaDePermitirVale_tambemComOBloqueioLigado() {
        assertEquals(false, secureWindow(lockEnabled = true, pref = false))
    }

    @Test
    fun escolhaDePermitirVale_semOBloqueio() {
        assertEquals(false, secureWindow(lockEnabled = false, pref = false))
    }
}
