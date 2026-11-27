package chat.papo.mobile

import chat.papo.mobile.security.AppPrefs
import chat.papo.mobile.security.NotificationContent
import chat.papo.mobile.security.NotificationPrivacy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationPrivacyTest {
    private class FakePrefs : AppPrefs {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String) = map[key]
        override fun putString(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    @Test
    fun semEscolhaGravadaMostraOConteudo() {
        assertEquals(NotificationContent.Show, NotificationPrivacy(FakePrefs()).content.value)
    }

    @Test
    fun valorDesconhecidoGravadoVoltaAMostrar() {
        val prefs = FakePrefs().apply { map["notifications.content"] = "de_uma_versao_futura" }
        assertEquals(NotificationContent.Show, NotificationPrivacy(prefs).content.value)
    }

    @Test
    fun cadaEscolhaFicaGravadaELidaNaProximaAbertura() {
        val prefs = FakePrefs()
        val privacy = NotificationPrivacy(prefs)
        for (option in NotificationContent.entries) {
            privacy.setContent(option)
            assertEquals(option, privacy.content.value)
            assertEquals(option.id, prefs.map["notifications.content"])
            assertEquals(option, NotificationPrivacy(prefs).content.value)
        }
    }

    @Test
    fun osIdsGravadosSaoDistintos() {
        assertEquals(NotificationContent.entries.size, NotificationContent.entries.map { it.id }.toSet().size)
    }

    @Test
    fun mostrarNaoTornaNadaGenerico() {
        assertFalse(NotificationContent.Show.genericOnLockScreen)
        assertFalse(NotificationContent.Show.genericAlways)
    }

    @Test
    fun ocultarNaTelaDeBloqueioSoTornaGenericaAVersaoDaTelaDeBloqueio() {
        assertTrue(NotificationContent.HideOnLockScreen.genericOnLockScreen)
        assertFalse(NotificationContent.HideOnLockScreen.genericAlways)
    }

    @Test
    fun ocultarSempreTornaTudoGenerico() {
        assertTrue(NotificationContent.HideAlways.genericOnLockScreen)
        assertTrue(NotificationContent.HideAlways.genericAlways)
    }
}
