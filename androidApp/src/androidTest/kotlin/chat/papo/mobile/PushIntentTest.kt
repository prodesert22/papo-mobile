package chat.papo.mobile

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import chat.papo.mobile.platform.NotificationIntents
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * O que o Android entrega ao app quando se toca num push que ele mostrou sozinho: o lançador com os dados da mensagem
 * como extras (`Intent` real, que os testes de unidade da JVM não têm).
 */
@RunWith(AndroidJUnit4::class)
class PushIntentTest {
    private val canal = "7a2e2c1b-2222-4222-8222-222222222222"

    private fun launcher() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    @Test
    fun oToqueNoPushLeSoOCanalSemContaNemMensagem() {
        val intent = launcher().putExtra("type", "message").putExtra("message_id", "m1").putExtra("channel_id", canal)

        val open = NotificationIntents.parsePush(intent)

        assertEquals(canal, open?.conversationId)
        assertNull(open?.accountId)
        assertNull(open?.target)
    }

    @Test
    fun semCanalOuComCanalForaDoFormatoNaoVale() {
        assertNull(NotificationIntents.parsePush(launcher()))
        assertNull(NotificationIntents.parsePush(launcher().putExtra("channel_id", "../../x")))
        assertNull(NotificationIntents.parsePush(null))
    }

    @Test
    fun extraDeOutroTipoNaoVale() {
        assertNull(NotificationIntents.parsePush(launcher().putExtra("channel_id", 7)))
    }

    @Test
    fun oIntentDaNotificacaoDoAppNaoEUmPush() {
        // Com a ação própria, quem lê é `parse`; mesmo que traga um `channel_id`, `parsePush` não o toma.
        val own = Intent("chat.papo.mobile.action.OPEN_CONVERSATION").putExtra("channel_id", canal)

        assertNull(NotificationIntents.parsePush(own))
    }
}
