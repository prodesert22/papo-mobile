package chat.papo.mobile

import chat.papo.mobile.chat.Channel
import chat.papo.mobile.chat.ChannelNotificationSettings
import chat.papo.mobile.chat.ChannelType
import chat.papo.mobile.chat.Dm
import chat.papo.mobile.chat.DmNotificationSettings
import chat.papo.mobile.chat.MessageKind
import chat.papo.mobile.chat.Mute
import chat.papo.mobile.chat.RowStyle
import chat.papo.mobile.chat.ServerNotificationSettings
import chat.papo.mobile.chat.TriggerKind
import chat.papo.mobile.chat.hasUnreadDot
import chat.papo.mobile.chat.rowStyle
import chat.papo.mobile.chat.toMessageKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class ConversationRowStyleTest {
    // A tabela "Visual da lista" do plano.

    @Test
    fun lidaFicaComum() {
        assertEquals(RowStyle(emphasized = false, dimmed = false, showDot = false, badge = null), rowStyle(unread = false, mentions = 0, capped = false, muted = false))
    }

    @Test
    fun mensagemNovaDestacaEPoeOCirculo() {
        assertEquals(RowStyle(emphasized = true, dimmed = false, showDot = true, badge = null), rowStyle(unread = true, mentions = 0, capped = false, muted = false))
    }

    @Test
    fun comMencaoOMesmoDestaqueEONumeroTomaOLugarDoCirculo() {
        assertEquals(RowStyle(emphasized = true, dimmed = false, showDot = false, badge = "3"), rowStyle(unread = true, mentions = 3, capped = false, muted = false))
    }

    @Test
    fun mencaoSemMensagemNovaConhecidaTambemDestaca() {
        assertEquals(RowStyle(emphasized = true, dimmed = false, showDot = false, badge = "1"), rowStyle(unread = false, mentions = 1, capped = false, muted = false))
    }

    @Test
    fun silenciadaEsmaeceSemDestaqueNemCirculo() {
        assertEquals(RowStyle(emphasized = false, dimmed = true, showDot = false, badge = null), rowStyle(unread = true, mentions = 0, capped = false, muted = true))
        assertEquals(RowStyle(emphasized = false, dimmed = true, showDot = false, badge = null), rowStyle(unread = false, mentions = 0, capped = false, muted = true))
    }

    @Test
    fun silenciadaMantemONumeroDeMencoes() {
        assertEquals(RowStyle(emphasized = false, dimmed = true, showDot = false, badge = "2"), rowStyle(unread = true, mentions = 2, capped = false, muted = true))
    }

    @Test
    fun oNumeroSegueOFormatoDoSelo() {
        assertEquals("99+", rowStyle(unread = true, mentions = 150, capped = false, muted = false).badge)
        assertEquals("5+", rowStyle(unread = true, mentions = 5, capped = true, muted = false).badge)
    }

    // hasUnreadDot

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000)

    private fun channel(id: String, unread: Boolean) = Channel(
        id, id, ChannelType.Text, 0, null, null,
        lastMessageId = "m2", lastMessageAt = null, lastMessageText = null, lastMessageAuthor = null,
        lastReadMessage = if (unread) "m1" else "m2", notification = null,
    )

    private fun dm(id: String, unread: Int) = Dm(id, "u2", null, null, null, unread)

    private fun dot(
        channels: List<Channel> = emptyList(),
        dms: List<Dm> = emptyList(),
        server: ServerNotificationSettings = ServerNotificationSettings(),
        channelSettings: Map<String, ChannelNotificationSettings> = emptyMap(),
        dmSettings: Map<String, DmNotificationSettings> = emptyMap(),
    ) = hasUnreadDot(channels, dms, server, channelSettings, dmSettings, now)

    @Test
    fun semNadaNaoLidoNaoHaPonto() {
        assertFalse(dot())
        assertFalse(dot(channels = listOf(channel("c1", unread = false)), dms = listOf(dm("d1", 0))))
    }

    @Test
    fun canalOuConversaNaoLidosAcendemOPonto() {
        assertTrue(dot(channels = listOf(channel("c1", unread = true))))
        assertTrue(dot(dms = listOf(dm("d1", 2))))
    }

    @Test
    fun canalSilenciadoNaoAcendeOPontoMasOutroNaoLidoSim() {
        val muted = mapOf("c1" to ChannelNotificationSettings(mute = Mute(null)))
        assertFalse(dot(channels = listOf(channel("c1", unread = true)), channelSettings = muted))
        assertTrue(dot(channels = listOf(channel("c1", unread = true), channel("c2", unread = true)), channelSettings = muted))
    }

    @Test
    fun servidorSilenciadoApagaOsCanaisMasNaoAsConversasDiretas() {
        val server = ServerNotificationSettings(mute = Mute(null))

        assertFalse(dot(channels = listOf(channel("c1", unread = true)), server = server))
        assertTrue(dot(dms = listOf(dm("d1", 1)), server = server))
    }

    @Test
    fun conversaDiretaSilenciadaNaoAcendeOPonto() {
        assertFalse(dot(dms = listOf(dm("d1", 4)), dmSettings = mapOf("d1" to DmNotificationSettings(mute = Mute(null)))))
    }

    @Test
    fun silencioQueJaAcabouNaoApaga() {
        val expired = mapOf("c1" to ChannelNotificationSettings(mute = Mute(now - 1.hours)))
        assertTrue(dot(channels = listOf(channel("c1", unread = true)), channelSettings = expired))
        val running = mapOf("c1" to ChannelNotificationSettings(mute = Mute(now + 1.hours)))
        assertFalse(dot(channels = listOf(channel("c1", unread = true)), channelSettings = running))
    }

    // TriggerKind -> MessageKind

    @Test
    fun mencaoERespostaValemComoMencaoEEveryoneTemOSeu() {
        assertEquals(MessageKind.Mention, TriggerKind.Mention.toMessageKind())
        assertEquals(MessageKind.Mention, TriggerKind.Reply.toMessageKind())
        assertEquals(MessageKind.Everyone, TriggerKind.Everyone.toMessageKind())
        assertEquals(MessageKind.Plain, TriggerKind.Dm.toMessageKind())
    }
}
