package chat.papo.mobile

import chat.papo.mobile.media.ModerationView
import chat.papo.mobile.media.moderationView
import kotlin.test.Test
import kotlin.test.assertEquals

class ModerationTest {
    @Test
    fun bloqueadoViraOAvisoSemBuscarNada() {
        assertEquals(ModerationView.Blocked, moderationView("blocked"))
    }

    @Test
    fun sensivelFicaEscondidoAteOToque() {
        assertEquals(ModerationView.Sensitive, moderationView("sensitive"))
    }

    @Test
    fun oRestoApareceNormalInclusivePendingDeServidorSemModeracao() {
        // `pending` é também o estado fixo de um servidor sem moderação: esconder esconderia todas as imagens dele.
        listOf("pending", "processing", "clean", "failed", "estado-novo", "", "BLOCKED", null).forEach {
            assertEquals(ModerationView.Normal, moderationView(it), "$it")
        }
    }
}
