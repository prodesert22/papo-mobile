package chat.papo.mobile

import chat.papo.mobile.chat.webhookAvatarId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebhookAuthorTest {
    private val id = "6f0c9a3e-1b2d-4c5e-8f70-123456789abc"

    @Test
    fun ocaminhoDoProprioWebhookDaMensagemVale() {
        assertEquals(id, webhookAvatarId(id, "/webhooks/$id/avatar"))
    }

    @Test
    fun caminhoDeOutroWebhookNaoVale() {
        assertNull(webhookAvatarId(id, "/webhooks/outro-id/avatar"))
    }

    @Test
    fun urlAbsolutaNaoVale() {
        assertNull(webhookAvatarId(id, "https://avatars.example.com/robo.png"))
        assertNull(webhookAvatarId(id, "http://papo.test/webhooks/$id/avatar"))
        assertNull(webhookAvatarId(id, "https://papo.test/webhooks/$id/avatar"))
    }

    @Test
    fun urlSemEsquemaNaoVale() {
        assertNull(webhookAvatarId(id, "//avatars.example.com/webhooks/$id/avatar"))
        assertNull(webhookAvatarId(id, "webhooks/$id/avatar"))
    }

    @Test
    fun consultaOuFragmentoNaoValem() {
        assertNull(webhookAvatarId(id, "/webhooks/$id/avatar?v=2"))
        assertNull(webhookAvatarId(id, "/webhooks/$id/avatar#topo"))
    }

    @Test
    fun sobraNoFimOuNoComecoNaoVale() {
        assertNull(webhookAvatarId(id, "/webhooks/$id/avatar/"))
        assertNull(webhookAvatarId(id, "/webhooks/$id/avatar.png"))
        assertNull(webhookAvatarId(id, " /webhooks/$id/avatar"))
        assertNull(webhookAvatarId(id, "/webhooks/$id/avatar "))
        assertNull(webhookAvatarId(id, "/WEBHOOKS/$id/avatar"))
    }

    @Test
    fun semWebhookOuSemUrlNaoHaAvatar() {
        assertNull(webhookAvatarId(null, "/webhooks/$id/avatar"))
        assertNull(webhookAvatarId(id, null))
        assertNull(webhookAvatarId(null, null))
        assertNull(webhookAvatarId("", ""))
        assertNull(webhookAvatarId("  ", "/webhooks/  /avatar"))
    }

    @Test
    fun idComCaractereQueMudariaOCaminhoNaoVale() {
        for (bad in listOf("a/b", "a?b", "a#b", "a%2Fb", "..", "a b", "a\nb")) {
            assertNull(webhookAvatarId(bad, "/webhooks/$bad/avatar"), bad)
        }
    }
}
